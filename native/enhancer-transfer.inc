static uint32_t encounter_source(int32_t type){
    if(type==25)return SILPH_SOURCE_GO_PASS;
    if(silph_research_encounter_type((uint32_t)type)||type==12||type==13||type==18||type==26||type==30)return SILPH_SOURCE_RESEARCH;
    return type>=0&&type<=30?SILPH_SOURCE_WILD:SILPH_SOURCE_UNKNOWN;
}
static void capture_abandon(Enhancer *s){
    if(s->capture_scope&&s->capture_token)silph_core_abandon(s->capture_scope,s->capture_token,s->capture_encounter,s->capture_sent);
    s->capture_token=0;
}
static void capture_begin(Enhancer *s,uint64_t encounter,uint32_t source,uint64_t now){
    if(!s->capture_scope||s->capture_token||!encounter)return;
    s->capture_sent=now/UINT64_C(1000000);s->capture_encounter=encounter;s->capture_source=source;
    s->capture_token=silph_core_issue(s->capture_scope,encounter,s->capture_sent,source==SILPH_SOURCE_RESEARCH||source==SILPH_SOURCE_GO_PASS?2:1);
}
static void capture_settle(Enhancer *s,uint64_t encounter,uint64_t id,bool confirmed,uint64_t now){
    if(!s->capture_token||s->capture_encounter!=encounter)return;
    uint64_t proof[]={s->capture_token,encounter,s->capture_sent,now/UINT64_C(1000000),id,confirmed?15u:0u};
    int accepted=silph_core_settle(s->capture_scope,proof,6);s->capture_token=0;
    if(accepted!=1)return;
    if(s->transfer_count==64){silph_core_finish(s->capture_scope,id,0);__android_log_print(ANDROID_LOG_INFO,"SilphEnhancer","transfer queue full; capture kept");return;}
    unsigned index=(s->transfer_head+s->transfer_count++)%64;
    s->transfers[index]=(EnhTransfer){.id=id,.encounter=encounter,.at=now,.source=s->capture_source};
}
void silph_enhancer_shadow_sent(uint64_t encounter,uint64_t now){Enhancer *s=owned();if(s)capture_begin(s,encounter,SILPH_SOURCE_WILD,now);}
void silph_enhancer_shadow_result(uint64_t encounter,uint64_t id,bool confirmed,uint64_t now){Enhancer *s=owned();if(s)capture_settle(s,encounter,id,confirmed,now);}
static void transfer_finish(Enhancer *s,bool confirmed,unsigned reason){
    EnhTransfer *entry=&s->transfers[s->transfer_head];silph_core_finish(s->capture_scope,entry->id,confirmed?1:0);
    if(confirmed)s->metrics.transferred++;
    __android_log_print(ANDROID_LOG_INFO,"SilphEnhancer","session capture transfer confirmed=%u reason=%u",confirmed,reason);
    drop(s,&s->transfer_pending);s->transfer_head=(s->transfer_head+1)%64;s->transfer_count--;s->transfer_next=0;
}
static void transfer_tick(Enhancer *s,void *read,void *game,void *managed,const SilphSchedulePermit *permit,uint64_t now,bool safe){
    if(!s->capture_scope||!s->transfer_count||now<s->transfer_next)return;
    EnhTransfer *entry=&s->transfers[s->transfer_head];s->transfer_next=now+UINT64_C(400000000);
    if(entry->phase==1){
        void *value=NULL;bool pending=false;bool ok=promise_value(s,&s->transfer_pending,s->release_class,&value,&pending);
        if(pending&&now-entry->at<UINT64_C(15000000000))return;
        int32_t result=0;if(!pending&&ok&&value&&read_i32(s,value,"Status",&result,true)&&result==1){entry->phase=2;entry->at=now;drop(s,&s->transfer_pending);}
        else transfer_finish(s,false,1);return;
    }
    if(entry->phase==2){
        SilphManagedRef pokemon={0};int r=silph_game_read_owned(game,entry->id,&pokemon);bool absent=!r&&!pokemon.serial;if(pokemon.serial)silph_managed_release(managed,pokemon);
        if(absent)transfer_finish(s,true,0);else if(now-entry->at>=UINT64_C(15000000000))transfer_finish(s,false,2);return;
    }
    if(!safe)return;
    SilphTransferInput input={0};SilphManagedRef bag={0},pokemon={0};int r=silph_game_read_transfer_data(game,entry->id,&input,&bag,&pokemon);
    if(r==SILPH_MANAGED_NOT_READY&&now-entry->at<UINT64_C(15000000000)){if(bag.serial)silph_managed_release(managed,bag);if(pokemon.serial)silph_managed_release(managed,pokemon);return;}
    SilphTransferVerdict verdict={0};input.options=s->config.keep;input.source=entry->source;
    if(!r)r=silph_transfer_evaluate(&input,sizeof(input),&verdict);
    if(!r&&verdict.decision==SILPH_VERDICT_CANDIDATE){uint32_t form=0;bool regional=silph_species_regional((uint32_t)input.species);if((regional&&silph_game_read_pokemon_form(game,pokemon,false,&form))||silph_selection_has(s->config.keep_species,sizeof(s->config.keep_species),(uint32_t)input.species,form))verdict.decision=SILPH_VERDICT_RETAIN;}
    if(!r&&verdict.decision==SILPH_VERDICT_CANDIDATE){
        SilphReadValue promise={0};r=silph_action_release(read,permit,s->capture_scope,entry->encounter,&input,bag,pokemon,&promise);
        void *object=NULL;if(!r)r=silph_managed_target(managed,promise.reference,&object);
        if(!r&&object&&keep(s,object,&s->transfer_pending,false)){entry->phase=1;entry->at=now;}else r=SILPH_MANAGED_NOT_READY;
        if(promise.reference.serial)silph_managed_release(managed,promise.reference);
        if(r)transfer_finish(s,false,3);
    }else transfer_finish(s,false,r?4:verdict.reasons);
    if(bag.serial)silph_managed_release(managed,bag);if(pokemon.serial)silph_managed_release(managed,pokemon);
}
