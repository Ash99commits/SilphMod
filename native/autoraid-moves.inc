static const void *ar_move_method(Enhancer *s,void *object,const char *name,const char *result,unsigned count,const char **args){
    void *k=s->base.object_get_class(object),*iterator=NULL;const void *match=NULL;
    for(unsigned n=0;k&&n<2048;n++){
        const void *m=s->base.class_get_methods(k,&iterator);if(!m)break;const char *actual=s->base.method_get_name(m);uint32_t impl=0;
        if(!actual||strcmp(actual,name)||s->base.method_get_param_count(m)!=count||(s->base.method_get_flags(m,&impl)&0x10u)||s->base.method_is_generic(m)||!type(s,s->base.method_get_return_type(m),result))continue;
        bool same=true;for(unsigned i=0;i<count;i++)same=same&&type(s,s->api.parameter(m,i),args[i]);if(!same)continue;if(match)return NULL;match=m;
    }return match;
}
static bool ar_move_scores(Enhancer *s,void *master,void *player,void *boss,SilphRaidMove *choices,size_t count,const int32_t *types){
    AutoRaid *a=&s->route;uint64_t own=0,enemy=a->site.seed;const void *own_id=field(s,s->base.object_get_class(player),"Id","System.UInt64");
    if(!own_id||!enemy)return false;s->api.get(player,own_id,&own);if(!own)return false;
    bool same=a->score_ready&&a->score_actor==own&&a->score_boss==enemy;
    for(size_t i=0;i<count;i++)same=same&&a->score_moves[choices[i].slot-1]==(int32_t)choices[i].move&&a->score_types[choices[i].slot-1]==types[i];
    if(same&&a->score_ready==2)return false;
    if(!same){
        a->score_ready=2;a->score_actor=own;a->score_boss=enemy;
        for(size_t i=0;i<count;i++){unsigned slot=choices[i].slot-1;a->score_moves[slot]=(int32_t)choices[i].move;a->score_types[slot]=types[i];}
        const char *pokemon_type[]={"Holoholo.Rpc.BattlePokemonProto"};void *settings=NULL,*settings_args[]={boss};
        if(!invoke(s,ar_move_method(s,master,"Get_PokemonSettingsWithOverrides","Holoholo.Rpc.IPokemonSettings",1,pokemon_type),master,settings_args,&settings)||!settings)return false;
        const char *effect_type[]={"Holoholo.Rpc.HoloPokemonType","Holoholo.Rpc.IPokemonSettings"};const void *effect=ar_move_method(s,master,"Get_TypeEffectiveness","System.Single",2,effect_type);
        void *table=typed_dictionary(s,master,"MoveSettingsProto");if(!effect||!table)return false;const char *key_type[]={"System.Object"};const void *get=ar_move_method(s,table,"get_Item","System.Object",1,key_type);
        bool typed=false;if(!get){const char *enum_type[]={"Holoholo.Rpc.HoloPokemonMove"};get=ar_move_method(s,table,"get_Item","Holoholo.Rpc.MoveSettingsProto",1,enum_type);typed=true;}
        void *move_class=klass(s,"holo-protos.dll","Holoholo.Rpc","HoloPokemonMove");if(!get||!move_class)return false;
        for(size_t i=0;i<count;i++){
            int32_t id=(int32_t)choices[i].move,attack=types[i];if(attack<1||attack>18)return false;
            void *key=typed?(void *)&id:s->api.value_box(move_class,&id),*value=NULL,*args[]={key},*boxed=NULL;if(!key||!invoke(s,get,table,args,&value)||!value)return false;
            const void *power=field(s,s->base.object_get_class(value),"Power","System.Single");float p=0,e=0;if(!power)return false;s->api.get(value,power,&p);
            void *effect_args[]={&attack,settings};if(!invoke(s,effect,master,effect_args,&boxed)||!boxed)return false;void *raw=s->base.object_unbox(boxed);if(!raw)return false;memcpy(&e,raw,4);
            if(!isfinite(p)||p<=0||p>100000||!isfinite(e)||e<=0||e>100)return false;unsigned slot=choices[i].slot-1;
            a->score_moves[slot]=id;a->score_types[slot]=attack;a->score_power[slot]=p;a->score_effect[slot]=e;
        }
        a->score_actor=(uint64_t)own;a->score_boss=(uint64_t)enemy;a->score_ready=1;
        __android_log_print(ANDROID_LOG_INFO,"SilphRaid","charged move priorities ready count=%zu",count);
    }
    for(size_t i=0;i<count;i++){unsigned slot=choices[i].slot-1;choices[i].power=a->score_power[slot];choices[i].effectiveness=a->score_effect[slot];choices[i].damage_scale=1;}
    return true;
}
static bool ar_choose_move(Enhancer *s,void *master,void *player,void *boss,void *moves,int32_t energy,int64_t turn,int64_t ms,void **selected,int32_t *slot,int32_t *duration){
    if(!boss)return false;const char *index_type[]={"System.Int32"};const void *get=method(s,s->base.object_get_class(moves),"get_Item",NULL,1,index_type);
    SilphRaidMove choices[3]={0};void *objects[3]={0};int32_t types[3]={0};size_t count=0;
    for(int32_t index=1;index<=3;index++){
        void *args[]={&index},*value=NULL;if(!invoke(s,get,moves,args,&value)||!value)continue;
        int32_t id=0,delta=0,turns=0,kind=0;if(!read_i32(s,value,"Move",&id,true)||!read_i32(s,value,"EnergyDelta",&delta,false)||!read_i32(s,value,"DurationTurns",&turns,false)||!read_i32(s,value,"Type",&kind,true))return false;
        for(unsigned j=0;(turns<1||!delta)&&j<s->raid_move_count;j++)if(s->raid_moves[j].id==id){if(turns<1&&s->raid_moves[j].duration>0)turns=(s->raid_moves[j].duration+(int32_t)ms-1)/(int32_t)ms;if(!delta)delta=s->raid_moves[j].delta;break;}
        if(id<=0||turns<1||turns>500||turns*ms>60000)return false;
        choices[count]=(SilphRaidMove){.slot=(uint32_t)index,.move=(uint32_t)id,.duration_ms=(uint32_t)(turns*ms),.energy_delta=delta};types[count]=kind;objects[count++]=value;
    }
    if(!count||!ar_move_scores(s,master,player,boss,choices,count,types))return false;
    uint32_t available=60000;if(s->raid_dodge_for!=s->raid_charge_turn&&s->dodge_start>turn){int64_t ticks=s->dodge_start-turn-2;available=ticks>0?(uint32_t)(ticks*ms>60000?60000:ticks*ms):0;}
    SilphRaidMoveChoice choice={0};if(silph_raid_choose_move(choices,count,energy,false,available,&choice))return false;
    *selected=NULL;for(size_t i=0;i<count;i++)if(choices[i].slot==choice.slot){*selected=objects[i];*slot=(int32_t)choice.slot;*duration=(int32_t)(choice.duration_ms/(uint32_t)ms);break;}return true;
}
