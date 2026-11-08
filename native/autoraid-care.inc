static bool ar_care_prepare(Enhancer *s,void *game,void *managed){
    AutoRaid *a=&s->route;SilphManagedRef bag={0};void *object=NULL;int r=silph_game_read_item_bag(game,&bag);
    if(!r)r=silph_managed_target(managed,bag,&object);
    const char *types[]={"Holoholo.Rpc.Item","System.Collections.Generic.List<Holoholo.Rpc.PokemonProto>"};
    const void *use=!r?method(s,s->base.object_get_class(object),"UseMedicineOnPokemons",NULL,2,types):NULL;
    void *list_class=use?s->api.class_from_type(s->api.parameter(use,1)):NULL;const char *member[]={"Holoholo.Rpc.PokemonProto"};
    unsigned contract=(use?1u:0u)|(list_class?2u:0u)|(list_class&&method(s,list_class,".ctor","System.Void",0,NULL)?4u:0u)|(list_class&&method(s,list_class,"Add","System.Void",1,member)?8u:0u);
    if(bag.serial)silph_managed_release(managed,bag);
    if(contract!=15){a->medicine_disabled=1;__android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine unavailable contract=%u error=%d; using individual care",contract,r);return false;}
    a->medicine_limit=6;
    __android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine ready batchLimit=%u",a->medicine_limit);return true;
}
static bool ar_care_health(Enhancer *s,void *read,void *game,void *managed,unsigned index,int32_t *hp,int32_t *maximum,SilphManagedRef *pokemon){
    SilphReadValue current={0},full={0};int r=silph_game_read_owned(game,s->care_team[index],pokemon);
    if(!r&&pokemon->serial)r=silph_read_field(read,*pokemon,"stamina_",SILPH_READ_I32,&current);else r=SILPH_MANAGED_NOT_READY;
    if(!r)r=silph_read_field(read,*pokemon,"maxStamina_",SILPH_READ_I32,&full);
    *hp=(int32_t)current.bits;*maximum=(int32_t)full.bits;
    if(r||*hp<0||*maximum<=0||*hp>*maximum){if(pokemon->serial)silph_managed_release(managed,*pokemon);*pokemon=(SilphManagedRef){0};return false;}return true;
}
static bool ar_care_tick(Enhancer *s,void *read,void *game,void *managed,const SilphSchedulePermit *permit,uint64_t now,bool safe){
    AutoRaid *a=&s->route;if(a->medicine_disabled)return false;
    if(!s->care_ready&&!s->care_pending.serial)return true;
    if(!a->medicine_active){
        if(!safe)return true;if(!a->medicine_limit&&!ar_care_prepare(s,game,managed))return false;
        a->medicine_active=1;a->medicine_mask=a->medicine_wait=a->medicine_rounds=0;a->medicine_scan=now;
    }
    if(s->care_pending.serial){
        void *promise=NULL;bool done=false,failed=false;bool valid=target(s,s->care_pending,&promise);
        void *k=valid?s->base.object_get_class(promise):NULL;const void *complete=valid?field(s,k,"completeCalled","System.Boolean"):NULL,*error=valid?field(s,k,"errorCalled","System.Boolean"):NULL;
        if(complete&&error){s->api.get(promise,complete,&done);s->api.get(promise,error,&failed);}else valid=false;
        if(valid&&!done&&!failed&&now-a->medicine_at<UINT64_C(15000000000))return true;
        void *result=NULL;const void *value=valid&&done&&!failed?field(s,k,"completedValue","System.Collections.Generic.HashSet<System.UInt64>"):NULL;
        if(value)s->api.get(promise,value,&result);int32_t count=0;
        const char *id_types[]={"System.UInt64"};const void *contains=result?method(s,s->base.object_get_class(result),"Contains","System.Boolean",1,id_types):NULL;
        valid=contains&&scalar(s,method(s,s->base.object_get_class(result),"get_Count","System.Int32",0,NULL),result,&count,4)&&count>=0&&count<=(int32_t)a->medicine_count;
        unsigned accepted=0;uint32_t confirmed=0;
        for(unsigned i=0;i<s->care_count&&valid;i++)if(a->medicine_wait&(1u<<i)){
            void *args[]={&s->care_team[i]},*boxed=NULL;bool yes=false;
            if(!invoke(s,contains,result,args,&boxed)||!boxed){valid=false;break;}void *flag=s->base.object_unbox(boxed);if(!flag){valid=false;break;}memcpy(&yes,flag,1);
            if(yes){confirmed|=1u<<i;accepted++;}
        }
        valid=valid&&accepted==(unsigned)count;
        if(!valid){a->medicine_mask|=a->medicine_wait;a->medicine_wait=0;__android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine unconfirmed; batch not resent");}
        else{a->medicine_mask|=a->medicine_wait&~confirmed;a->medicine_wait=confirmed;a->medicine_until=now+UINT64_C(8000000000);__android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine confirmed item=%u accepted=%u requested=%u elapsedMs=%llu",a->medicine_item,accepted,a->medicine_count,(unsigned long long)((now-a->medicine_at)/1000000));}
        drop(s,&s->care_pending);drop(s,&a->medicine_list);s->care_next=now+UINT64_C(50000000);return true;
    }
    if(!safe||now<s->care_next)return true;s->care_next=now+UINT64_C(100000000);
    for(unsigned i=0;i<s->care_count;i++)if(a->medicine_wait&(1u<<i)){
        SilphManagedRef pokemon={0};int32_t hp=0,maximum=0;bool ok=ar_care_health(s,read,game,managed,i,&hp,&maximum,&pokemon);if(pokemon.serial)silph_managed_release(managed,pokemon);
        if(ok&&hp>a->medicine_hp[i])a->medicine_wait&=~(1u<<i);
        else if(now>=a->medicine_until){a->medicine_mask|=1u<<i;a->medicine_wait&=~(1u<<i);}
    }
    if(a->medicine_wait)return true;
    SilphManagedRef bag={0};void *object=NULL;int r=silph_game_read_item_bag(game,&bag);if(!r)r=silph_managed_target(managed,bag,&object);
    const char *types[]={"Holoholo.Rpc.Item","System.Collections.Generic.List<Holoholo.Rpc.PokemonProto>"};
    const void *use=!r?method(s,s->base.object_get_class(object),"UseMedicineOnPokemons",NULL,2,types):NULL;
    void *list_class=use?s->api.class_from_type(s->api.parameter(use,1)):NULL,*list=NULL;
    bool prepared=false;unsigned count=0;uint32_t stock=0,item=0,mask=0;
    const uint32_t revives[]={202,201},potions[]={104,103,102,101};const char *pokemon_type[]={"Holoholo.Rpc.PokemonProto"};
    for(unsigned i=0;i<s->care_count&&!r;i++)if(!(a->medicine_mask&(1u<<i))){
        SilphManagedRef pokemon={0};int32_t hp=0,maximum=0;
        if(!ar_care_health(s,read,game,managed,i,&hp,&maximum,&pokemon)){a->medicine_mask|=1u<<i;continue;}
        if(hp>=maximum){a->medicine_mask|=1u<<i;silph_managed_release(managed,pokemon);continue;}
        if(!item){const uint32_t *order=hp?potions:revives;unsigned size=hp?4:2;
            for(unsigned j=0;j<size;j++){uint32_t n=0;if(!silph_game_read_item(game,(int32_t)order[j],&n)&&n){item=order[j];stock=n;break;}}
            if(!item){a->medicine_mask|=1u<<i;silph_managed_release(managed,pokemon);continue;}}
        if((hp==0)!=(item==201||item==202)){silph_managed_release(managed,pokemon);continue;}
        if(!prepared){
            list=list_class?s->api.object_new(list_class):NULL;
            prepared=list&&keep(s,list,&a->medicine_list,false)&&invoke(s,method(s,list_class,".ctor","System.Void",0,NULL),list,NULL,NULL);
            if(!prepared)r=SILPH_MANAGED_CONTRACT;
        }
        void *value=NULL;if(!r)r=silph_managed_target(managed,pokemon,&value);void *args[]={value};
        if(!r&&!invoke(s,method(s,list_class,"Add","System.Void",1,pokemon_type),list,args,NULL))r=SILPH_MANAGED_CONTRACT;
        if(!r){mask|=1u<<i;count++;a->medicine_hp[i]=hp;}
        silph_managed_release(managed,pokemon);if(count>=a->medicine_limit||count>=stock)break;
    }
    if(r){
        drop(s,&a->medicine_list);a->medicine_active=0;a->medicine_disabled=1;if(bag.serial)silph_managed_release(managed,bag);
        __android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine preparation unavailable error=%d; using individual care",r);return false;
    }
    if(count&&a->medicine_rounds<96){
        void *promise=NULL;void *args[]={&item,list};bool sent=silph_schedule_permitted(permit,true)&&invoke(s,use,object,args,&promise);
        if(sent&&promise&&keep(s,promise,&s->care_pending,false)){a->medicine_wait=mask;a->medicine_count=count;a->medicine_item=item;a->medicine_at=now;a->medicine_rounds++;__android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine requested item=%u members=%u",item,count);}
        else{a->medicine_mask|=mask;drop(s,&a->medicine_list);__android_log_print(ANDROID_LOG_INFO,"SilphRaid","group medicine request unconfirmed; batch not resent");}
    }else if(!count||a->medicine_rounds>=96){
        drop(s,&a->medicine_list);s->care_ready=s->care_count=s->care_index=0;a->medicine_active=0;
        __android_log_print(ANDROID_LOG_INFO,"SilphRaid","group team care finished rounds=%u elapsedMs=%llu error=%d",a->medicine_rounds,(unsigned long long)((now-a->medicine_scan)/1000000),r);
    }
    if(bag.serial)silph_managed_release(managed,bag);return true;
}
