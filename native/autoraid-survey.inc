static void ar_survey_cancel(Enhancer *s){
    AutoRaid *a=&s->route;if(a->surveying)silph_raid_read_end(a->reader);
    a->surveying=a->survey_row=a->survey_done=a->survey_count=a->survey_error=0;
}
static void ar_survey_finish(Enhancer *s,unsigned error){
    AutoRaid *a=&s->route;silph_raid_read_end(a->reader);a->surveying=a->survey_row=0;a->survey_done=1;a->survey_error=error;a->dirty=1;
    silph_game_read_position(a->game,&a->latitude,&a->longitude);
    __android_log_print(ANDROID_LOG_INFO,"SilphRaid","local survey completed rows=%u error=%u",a->survey_count,error);
}
static void ar_survey_request(Enhancer *s,uint64_t now){
    AutoRaid *a=&s->route;if(a->surveying){a->survey_row=0;return;}
    ar_survey_cancel(s);a->survey_until=now+UINT64_C(15000000000);
    if(!ar_map_restored(s)||silph_raid_read_begin(a->reader)){ar_survey_finish(s,1);return;}
    a->surveying=1;
}
static void ar_survey_tick(Enhancer *s,uint64_t now){
    AutoRaid *a=&s->route;if(!a->surveying)return;
    if(now>=a->survey_until){ar_survey_finish(s,1);return;}if(a->survey_row)return;
    uint64_t start=clock_ns();for(unsigned i=0;i<3&&clock_ns()-start<UINT64_C(1000000);i++){
        SilphRaidSite site={0};bool finished=false;int r=silph_raid_read_next(a->reader,&site,&finished);
        if(r||finished||++a->survey_count>256){ar_survey_finish(s,r?1:0);return;}
        if(!site.verified||!site.active||site.complete||!site.display_known||!site.gym[0])continue;
        a->survey_site=site;a->survey_row=1;a->dirty=1;return;
    }
}
