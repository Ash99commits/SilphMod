package dev.silphmod.nativecheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import dev.rootcatch.nativeengine.*;
import dev.rootcatch.root.RootShell;
import dev.rootcatch.ugphone.KecleonMovement;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class NativeRaidHost implements AutoCloseable {
    private final NativeSessionController controller;private final CheckLog log;private final BooleanSupplier active;
    private final SharedPreferences settings,history;private final RootShell joystick=new RootShell();
    private final RaidFeedClient feeds=new RaidFeedClient(SystemClock::elapsedRealtime);
    private final ScheduledExecutorService network=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"silph-raid-feed"));
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"silph-raid-route"));
    private final Set<String> completed=new LinkedHashSet<>();private final Map<String,Long> retry=new HashMap<>();
    private final Map<String,Integer> wins=new HashMap<>();private final RaidTargetPlan plan;
    private RaidPending pending;
    private volatile NativeKecleonWire.Event event;private volatile RaidFeed.Candidate candidate;private volatile String account="";
    private volatile boolean closed;private volatile long candidateRequest;private boolean choosing;private int country;private long commandSequence,retryAt,throwsSeen,berriesSeen;
    private final RaidSearchArea[] areas=new RaidSearchArea[3];private double latitude,longitude;private volatile double lastLatitude,lastLongitude;private volatile long lastAction;
    NativeRaidHost(Context context,NativeSessionController controller,CheckLog log,BooleanSupplier active){
        this.controller=controller;this.log=log;this.active=active;settings=new NativeSettings(context).prefs;history=context.getSharedPreferences("raid-history",0);plan=RaidTargetPlan.parse(settings.getString("raid.targets",""));
        for(int country=1;country<=2;country++)areas[country]=new RaidSearchArea(settings.getString("raid.reference"+country,RaidSearchArea.defaultPoint(country)),settings.getInt("raid.radiusKm",5));
        network.scheduleWithFixedDelay(this::refresh,0,60,TimeUnit.SECONDS);
    }
    static void clear(Context context){SharedPreferences p=context.getSharedPreferences("raid-history",0);SharedPreferences.Editor edit=p.edit();for(String key:p.getAll().keySet())if(key.startsWith("done.")||key.startsWith("wins.")||key.startsWith("pending."))edit.remove(key);edit.apply();}
    private void refresh(){
        int mask=settings.getInt("raid.countries",1);for(int c=1;c<=2&&!closed;c++)if((mask&(1<<(c-1)))!=0)try{
            RaidFeedClient.Result result=feeds.refresh(c,RaidSources.url(c));
            if(result.fetched)log.write("raid_feed","country="+c+" HTTP="+result.status+" rows="+result.snapshot.rows+" candidates="+result.snapshot.candidates.size());
        }catch(Exception error){if(!closed)log.write("raid_feed_error","country="+c+" "+error.getMessage());}
    }
    void snapshot(NativeSessionWire.Snapshot value){
        if(closed||account.isEmpty())return;
        if(value.throwsCount>throwsSeen||value.berries>berriesSeen){throwsSeen=value.throwsCount;berriesSeen=value.berries;RaidFeed.Candidate current=candidate;if(current==null||candidateRequest!=value.candidateRequest)return;lastLatitude=current.latitude;lastLongitude=current.longitude;lastAction=System.currentTimeMillis();
            history.edit().putLong("time."+account,lastAction).putString("location."+account,lastLatitude+","+lastLongitude).apply();log.write("raid_cooldown","capture action recorded request="+value.candidateRequest);}
        if(value.candidateState%100==7&&controller.totals().spun>=settings.getInt("raid.maximum",100))controller.requestStop("Maximum raids reached");
    }
    synchronized void accept(NativeKecleonWire.Event value){
        if(closed)return;String key=Long.toUnsignedString(value.accountHi,16)+":"+Long.toUnsignedString(value.accountLo,16);
        if(!account.isEmpty()&&!account.equals(key)){controller.requestStop("Raid account changed");return;}
        if(account.isEmpty()){
            account=key;completed.addAll(history.getStringSet("done."+account,Collections.emptySet()));lastAction=history.getLong("time."+account,0);
            String prefix="wins."+account+".";for(Map.Entry<String,?> row:history.getAll().entrySet())if(row.getKey().startsWith(prefix)&&row.getValue() instanceof Integer)wins.put(row.getKey().substring(prefix.length()),(Integer)row.getValue());
            try{pending=RaidPending.decode(history.getString("pending."+account,""),System.currentTimeMillis());if(pending==null)history.edit().remove("pending."+account).apply();}catch(Exception error){history.edit().remove("pending."+account).apply();log.write("raid_pending_error",error.getMessage());}
            String point=history.getString("location."+account,"");
            long supplied=settings.getLong("raid.lastActionAt",0);if(supplied>lastAction){lastAction=supplied;point=settings.getString("raid.lastActionCoordinates","");}
            if(!point.isBlank()){double[] p=dev.rootcatch.ugphone.KecleonFeed.parseCoordinates(point);lastLatitude=p[0];lastLongitude=p[1];}
        }
        event=value;if(value.phase==1){latitude=value.latitude;longitude=value.longitude;}submit(()->process(value));
    }
    private interface Job {void run()throws Exception;}
    private void submit(Job job){if(closed)return;try{worker.execute(()->{try{if(!closed)job.run();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){if(!closed){log.write("raid_host_error",e.toString());controller.requestStop("Raid controller: "+e.getMessage());}}});}catch(RejectedExecutionException ignored){} }
    synchronized void tick(){if(!closed&&event!=null&&event.phase==1&&!choosing&&(retryAt==0||SystemClock.elapsedRealtime()>=retryAt)){choosing=true;NativeKecleonWire.Event e=event;submit(()->{try{process(e);}finally{synchronized(this){choosing=false;}}});}}
    private synchronized void send(NativeKecleonWire.Event e,int kind,boolean accepted,double lat,double lon,String source){
        if(closed||event==null||event.request!=e.request)return;controller.enqueue(NativeKecleonWire.command(e,++commandSequence,kind,0,accepted,lat,lon,source));
        log.write("raid_command","request="+e.request+" kind="+kind+" sequence="+commandSequence);
    }
    private void process(NativeKecleonWire.Event e)throws Exception{
        if(closed||event==null||event.request!=e.request)return;
        RaidFeed.Candidate current=candidate;boolean victory=e.verified==1&&(e.phase==5||e.phase==7)&&current!=null&&candidateRequest==e.request&&!e.fort.isEmpty();
        if(victory&&(pending==null||!pending.candidate.key.equals(current.key))){pending=new RaidPending(current,e.fort);history.edit().putString("pending."+account,pending.encode()).apply();}
        if(victory&&!completed.contains(current.key)){
            completed.add(current.key);while(completed.size()>5000){Iterator<String> old=completed.iterator();old.next();old.remove();}
            String target=RaidTargetPlan.key(current);int count=wins.getOrDefault(target,0)+1;wins.put(target,count);
            history.edit().putStringSet("done."+account,new HashSet<>(completed)).putInt("wins."+account+"."+target,count).apply();
            log.write("raid_target_progress","target="+target+" victories="+count+" goal="+plan.limits.getOrDefault(target,0));
        }
        if(e.phase==1){
            synchronized(this){if(candidate!=null)return;}
            RaidFeed.Candidate best=null;double score=Double.POSITIVE_INFINITY;long elapsed=SystemClock.elapsedRealtime();int mask=settings.getInt("raid.countries",1);
            RaidPending resume=pending;if(resume!=null&&(mask&(1<<(resume.candidate.country-1)))!=0&&resume.candidate.expiresMs>System.currentTimeMillis())best=resume.candidate;
            boolean recovering=best!=null;
            if(best==null&&plan.reached(wins)){controller.requestStop("Selected raid targets completed");return;}
            double radius=lastAction==0?Double.POSITIVE_INFINITY:RaidCooldown.allowedMeters(System.currentTimeMillis()-lastAction);
            for(int c=1;!recovering&&c<=2;c++){
                if((mask&(1<<(c-1)))==0||country!=0&&country!=c)continue;RaidFeed.Snapshot snapshot=feeds.snapshot(c);if(snapshot==null)continue;long now=snapshot.nowMs(elapsed);if(now<0)continue;
                for(RaidFeed.Candidate item:snapshot.candidates){
                    if(item.expiresMs-now<180000||completed.contains(item.key)||retry.getOrDefault(item.key,0L)>elapsed||!plan.allows(item,wins))continue;
                    if(lastAction!=0&&RaidFeed.distanceMeters(lastLatitude,lastLongitude,item.latitude,item.longitude)>radius)continue;
                    if(!areas[c].includes(item))continue;double distance=areas[c].distance(item);
                    if(distance<score){best=item;score=distance;}
                }
            }
            if(best==null){if(retryAt==0||elapsed>=retryAt+25000)log.write("raid_wait","target="+plan.current(wins)+"; waiting for a matching raid in reference area and cooldown range");retryAt=elapsed+5000;return;}synchronized(this){if(candidate!=null)return;candidateRequest=e.request;candidate=best;country=best.country;}
            int filters=settings.getBoolean("raid.catchAll",true)?0:settings.getInt("raid.filters",0),players=settings.getInt("raid.minPlayers",2);
            String descriptor=best.species+":"+best.level+":"+best.form+":"+best.costume+":"+best.expiresMs+":"+filters+":"+players+":"+settings.getInt("raid.exitRemainingSeconds",20)+":"+best.temporaryEvolution;
            if(resume!=null&&best.key.equals(resume.candidate.key)){descriptor+=":"+resume.gym;if(descriptor.length()>96){controller.requestStop("Pending raid encounter needs manual recovery");return;}log.write("raid_resume","Recovering won encounter without another pass");}
            send(e,1,false,best.latitude,best.longitude,descriptor);log.write("raid_candidate","country="+country+" species="+best.species+" level="+best.level+" remainingMs="+(best.expiresMs-System.currentTimeMillis()));
        }else if(e.phase==2||e.phase==6){
            RaidFeed.Candidate target=candidate;if(target==null)return;
            while(!closed&&!active.getAsBoolean())Thread.sleep(100);if(closed)return;
            boolean accepted=true;double[][] points=e.phase==6?KecleonMovement.points(target.latitude,target.longitude):new double[][]{{target.latitude,target.longitude}};
            for(int i=0;i<points.length&&!closed;i++){
                while(!closed&&!active.getAsBoolean())Thread.sleep(100);if(closed)return;
                RootShell.Result result=joystick.execute(KecleonMovement.command(points[i][0],points[i][1]),3000);String lower=result.output.toLowerCase(Locale.ROOT);
                accepted=result.code==0&&!lower.contains("error")&&!lower.contains("exception")&&!lower.contains("not found");if(!accepted)break;if(i+1<points.length)Thread.sleep(KecleonMovement.STEP_MS);
            }
            synchronized(this){if(!closed&&event!=null&&event.request==e.request)controller.enqueue(NativeKecleonWire.command(e,++commandSequence,2,e.move,accepted,0,0,""));}
            log.write("raid_move","accepted="+accepted+" request="+e.request+" radius="+(e.phase==6?8:0));
        }else if(e.phase==7){
            if(victory&&pending!=null&&pending.candidate.key.equals(candidate.key)){pending=null;history.edit().remove("pending."+account).apply();}
            synchronized(this){if(candidate==null)return;
                if(e.reason==5){completed.add(candidate.key);while(completed.size()>5000){Iterator<String> old=completed.iterator();old.next();old.remove();}history.edit().putStringSet("done."+account,new HashSet<>(completed)).apply();log.write("raid_skipped","already completed; request="+e.request);}
                else if(e.verified!=1)retry.put(candidate.key,SystemClock.elapsedRealtime()+(e.reason==7?180000:60000));
                retry.entrySet().removeIf(row->row.getValue()<=SystemClock.elapsedRealtime());candidate=null;candidateRequest=0;retryAt=0;}
            if(plan.reached(wins)){controller.requestStop(pending==null?"Selected raid targets completed":"Pending raid encounter could not be reopened");return;}
            send(e,4,false,0,0,"");
        }
    }
    @Override public void close(){closed=true;feeds.close();network.shutdownNow();worker.shutdownNow();joystick.close();}
}
