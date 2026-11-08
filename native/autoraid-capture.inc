static bool ar_drop_capture_model(Enhancer *s){
    AutoRaid *a=&s->route;void *model=NULL;if(target(s,a->model,&model)&&unity_alive(s,model)&&!ar_void(s,model,"Destroy"))return false;
    drop(s,&a->model);a->headless=0;return true;
}
static int ar_capture_finished(Enhancer *s,unsigned reason){
    if(!ar_drop_capture_model(s))return SILPH_MANAGED_EXCEPTION;drop(s,&s->route.reward);
    SilphManagedMetrics refs={0};if(!silph_managed_metrics(s->managed,&refs))__android_log_print(ANDROID_LOG_INFO,"SilphRaid","capture scope released request=%llu liveRefs=%u created=%llu released=%llu",(unsigned long long)s->route.request,refs.live_refs,(unsigned long long)refs.refs_created,(unsigned long long)refs.refs_released);
    ar_exit(s);ar_phase(s,5,7,reason,s->route.deadline);return 0;
}
static bool ar_single_reward(Enhancer *s,void *results,void **reward){
    void *list=NULL;const void *f=field(s,s->base.object_get_class(results),"PostRaidEncounter","Google.Protobuf.Collections.RepeatedField<Holoholo.Rpc.RaidEncounterProto>");if(!f)return false;s->api.get(results,f,&list);
    int32_t count=0;if(!list||!scalar(s,method(s,s->base.object_get_class(list),"get_Count","System.Int32",0,NULL),list,&count,4)||count!=1)return false;
    int32_t index=0;void *args[]={&index};const char *types[]={"System.Int32"};return invoke(s,method(s,s->base.object_get_class(list),"get_Item","Holoholo.Rpc.RaidEncounterProto",1,types),list,args,reward)&&*reward;
}
static int ar_prepare_capture(Enhancer *s,void *gui,void *info,uint64_t now){
    AutoRaid *a=&s->route;void *reward=NULL,*results=NULL,*fort=NULL,*pokemon=NULL,*factory=NULL,*model=NULL;char gym[161]={0};int64_t id=0;int32_t balls=0,ball=0;
    if(!object_field(s,gui,"elcc","Holoholo.Rpc.RaidEncounterProto",&reward)){
        if(!read_ref(s,info,"get_BattleResults",&results)||!ar_single_reward(s,results,&reward)){ar_resolve_gate(s,2);return 0;}
    }
    if(!object_field(s,reward,"FortId","System.String",&fort)||!label_string(s,fort,gym,sizeof(gym))||strcmp(gym,a->site.gym)||!read_i64(s,reward,"EncounterId",&id)||!id){ar_resolve_gate(s,3);return 0;}
    if(!object_field(s,reward,"Pokemon","Holoholo.Rpc.PokemonProto",&pokemon)||!read_i32(s,reward,"ThrowsRemaining",&balls,false)||balls<1||balls>200||!read_i32(s,reward,"RaidBall",&ball,true)||!silph_raid_reward_ball(a->level,(uint32_t)ball)){if(a->resolve_gate!=26)__android_log_print(ANDROID_LOG_INFO,"SilphRaid","reward not ready pokemon=%u balls=%d item=%d",pokemon!=NULL,balls,ball);ar_resolve_gate(s,26);return 0;}
    uint32_t selected_ball=silph_raid_reward_ball(a->level,(uint32_t)ball);if(!ball)__android_log_print(ANDROID_LOG_INFO,"SilphRaid","standard raid reward uses Premier Ball default");ball=(int32_t)selected_ball;
    if(!target(s,a->factory,&factory))return SILPH_MANAGED_STALE;void *args[]={reward};
    if(!invoke(s,a->create_model,factory,args,&model)||!exact(s,model,a->map_pokemon_class))return SILPH_MANAGED_EXCEPTION;
    uint64_t model_id=0;if(!scalar(s,method(s,a->map_pokemon_class,"get_EncounterId","System.UInt64",0,NULL),model,&model_id,8)||model_id!=(uint64_t)id){ar_void(s,model,"Destroy");return SILPH_MANAGED_STALE;}
    if(!keep(s,reward,&a->reward,false)||!keep(s,model,&a->model,false)){ar_void(s,model,"Destroy");return SILPH_MANAGED_CAPACITY;}
    bool shiny=false;void *display=NULL;if(read_ref(s,pokemon,"get_PokemonDisplay",&display)){const void *f=field(s,s->base.object_get_class(display),"Shiny","System.Boolean");if(f)s->api.get(display,f,&shiny);}
    a->expected=(uint64_t)id;a->balls=a->initial_balls=(uint32_t)balls;a->ball=(uint32_t)ball;a->headless=1;a->won=1;
    s->last_id=(uint64_t)id;s->shiny=shiny;s->berry_tried=0;s->berry_at=now;s->metrics.encounters++;s->metrics.encounter=(uint64_t)id;s->metrics.captured=0;
    ar_exit(s);ar_resolve_gate(s,30);ar_phase(s,5,6,0,now+UINT64_C(180000000000));a->ui_at=now+UINT64_C(100000000);
    __android_log_print(ANDROID_LOG_INFO,"SilphRaid","raid reward ready encounter=%llu balls=%d item=%d; capture camera not opened",(unsigned long long)a->expected,balls,ball);return 0;
}
static bool ar_headless_response(Enhancer *s,void *reply,int32_t status){
    AutoRaid *a=&s->route;if(!(s->config.flags&SILPH_ENH_AUTORAID)||!a->headless||s->promise_id!=a->expected)return true;
    int32_t balls=0;uint64_t captured=0;const void *f=field(s,s->catch_class,"CapturedPokemonId","System.UInt64");if(f)s->api.get(reply,f,&captured);
    bool valid=(status==1||status==2||status==3||status==4)&&read_i32(s,reply,"ThrowsRemaining",&balls,false)&&balls>=0&&(uint32_t)balls<=a->initial_balls&&(status!=1||captured);
    if(!valid){ar_phase(s,8,0,11,0);__android_log_print(ANDROID_LOG_ERROR,"SilphRaid","raid capture response unconfirmed status=%d; no resend",status);return false;}
    a->balls=(uint32_t)balls;return true;
}
static int ar_hidden_berry(Enhancer *s,void *model,uint64_t now){
    AutoRaid *a=&s->route;if(!(s->metrics.available&SILPH_CFG_BERRIES)||s->berry_tried)return 0;
    if(now<s->berry_at)return 1;s->berry_tried=1;uint32_t item=0;
    for(unsigned i=0;i<s->config.berry_count;i++){uint32_t count=0;if(!silph_game_read_item(a->game,(int32_t)s->config.berries[i],&count)&&count){item=s->config.berries[i];break;}}
    if(!item){__android_log_print(ANDROID_LOG_INFO,"SilphRaid","selected berries unavailable; capture continues");return 0;}
    SilphManagedRef bag_ref={0};void *bag=NULL,*promise=NULL;int r=silph_game_read_item_bag(a->game,&bag_ref);
    if(!r)r=silph_managed_target(a->managed,bag_ref,&bag);
    const char *types[]={"Holoholo.Rpc.Item","Niantic.Holoholo.Map.IItemEncounterContext"};int32_t selected=(int32_t)item;void *args[]={&selected,model};
    bool sent=!r&&invoke(s,method(s,s->base.object_get_class(bag),"UseItemForEncounter",NULL,2,types),bag,args,&promise)&&promise&&keep(s,promise,&s->berry_promise,false);
    if(bag_ref.serial)silph_managed_release(a->managed,bag_ref);
    if(!sent){__android_log_print(ANDROID_LOG_WARN,"SilphRaid","raid berry request unavailable item=%u; capture continues",item);return 0;}
    s->pending_berry=item;s->berry_id=a->expected;s->berry_at=now;return 1;
}
static int ar_hidden_capture(Enhancer *s,uint64_t now){
    AutoRaid *a=&s->route;if(now<a->ui_at||s->promise.serial||s->berry_promise.serial)return 0;
    void *reward=NULL,*model=NULL;if(!target(s,a->reward,&reward)||!target(s,a->model,&model)||!unity_alive(s,model))return SILPH_MANAGED_STALE;
    int accepted=ar_accept_reward(s,reward);if(accepted<0){ar_resolve_gate(s,4);return 0;}if(!accepted){__android_log_print(ANDROID_LOG_INFO,"SilphRaid","raid reward excluded by catch filters");return ar_capture_finished(s,4);}
    if(!a->balls)return ar_capture_finished(s,2);
    if(ar_hidden_berry(s,model,now))return 0;
    const float sizes[]={0,1.1f,.85f,.5f,.05f};EnhThrow data={.ball=(int32_t)a->ball,.reticle=sizes[s->config.quality],.bullseye=1,.spinning=!!(s->config.flags&SILPH_CFG_CURVE)};
    void *args[]={&data,NULL},*promise=NULL;capture_begin(s,a->expected,SILPH_SOURCE_WILD,now);
    if(!invoke(s,a->capture,model,args,&promise)||!promise){capture_abandon(s);ar_phase(s,8,0,11,0);return 0;}
    if(!keep(s,promise,&s->promise,false)){capture_abandon(s);return SILPH_MANAGED_CAPACITY;}
    a->balls--;s->metrics.throws++;s->promise_at=now;s->promise_id=a->expected;s->promise_shiny=s->shiny;a->ui_at=now+UINT64_C(150000000);
    ar_resolve_gate(s,31);__android_log_print(ANDROID_LOG_INFO,"SilphRaid","raid throw sent encounter=%llu ballsLeft=%u",(unsigned long long)a->expected,a->balls);return 0;
}
