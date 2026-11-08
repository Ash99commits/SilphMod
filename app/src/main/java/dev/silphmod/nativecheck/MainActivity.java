package dev.silphmod.nativecheck;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import dev.rootcatch.automation.ThrowQuality;
import dev.rootcatch.nativeengine.*;
import java.util.*;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private NativeSettings settings;
    private ModeUi ui;
    private int mode,pendingStart;
    private TextView status,details,elapsed;
    private final TextView[] metrics=new TextView[6];
    private Button start,export,clear;
    private String inputError="";
    private final ArrayList<View> edits=new ArrayList<>();
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,750);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);settings=new NativeSettings(this);pendingStart=state==null?0:state.getInt("pendingStart",0);
        getWindow().setStatusBarColor(ModeUi.BG);getWindow().setNavigationBarColor(ModeUi.BG);
        mode=state==null?getIntent().getIntExtra("mode",0):state.getInt("mode",0);show();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);mode=intent.getIntExtra("mode",mode);show();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("mode",mode);state.putInt("pendingStart",pendingStart);super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void show(){
        edits.clear();inputError="";ui=new ModeUi(this);status=null;start=null;
        if(mode!=6&&!NativeSessionWire.validMode(mode)){
            ui.title("SilphMod",BuildConfig.VERSION_NAME);
            LinearLayout enhancer=ui.card("ENHANCER MODE");enhancer.addView(ui.text("Manual play & quick encounters",23,ModeUi.TEXT));
            enhancer.addView(ui.text("Throw assistance · Scene skips · Buddy care",14,ModeUi.MUTED));
            ui.button(enhancer,"Open Enhancer Mode",true).setOnClickListener(v->open(5));
            LinearLayout farmer=ui.card("FARMER");farmer.addView(ui.text("Autocatch & autospin",23,ModeUi.TEXT));
            farmer.addView(ui.text("Wild catches · PokéStops · Inventory",14,ModeUi.MUTED));
            ui.button(farmer,"Open Farmer",true).setOnClickListener(v->open(1));
            LinearLayout kec=ui.card("KECLEON (BETA)");kec.addView(ui.text("Automatic Kecleon search & catch",23,ModeUi.TEXT));
            kec.addView(ui.text("Candidate queue · Automatic jumps · IV & shiny filters",14,ModeUi.MUTED));
            ui.button(kec,"Open Kecleon (BETA)",false).setOnClickListener(v->open(2));
            LinearLayout rocket=ui.card("AUTOROCKET (BETA)");rocket.addView(ui.text("Automatic grunt battles & shadow catches",23,ModeUi.TEXT));
            rocket.addView(ui.text("Rocket queue · Team healing · IV & shiny filters",14,ModeUi.MUTED));ui.button(rocket,"Open AutoRocket (BETA)",false).setOnClickListener(v->open(3));
            LinearLayout raid=ui.card("AUTORAID (BETA)");raid.addView(ui.text("Raid battles & automatic catches",23,ModeUi.TEXT));raid.addView(ui.text("Country queues · Free & premium passes · Team care",14,ModeUi.MUTED));ui.button(raid,"Open AutoRaid (BETA)",false).setOnClickListener(v->open(6));
            ui.show();return;
        }
        ui.button(ui.page,"‹  Modes",false).setOnClickListener(v->{mode=0;show();});
        ui.title(mode==6?"AutoRaid (BETA)":mode==5?"Enhancer Mode":mode==1?"Farmer":mode==2?"Kecleon (BETA)":"AutoRocket (BETA)","SilphMod");
        status=ui.text(CheckService.view.status,17,ModeUi.ACCENT);ui.page.addView(status);
        if(mode==5){elapsed=details=clear=null;enhancerSettings(new SettingsDialogs(this));serviceControls();ui.show();render();return;}
        if(mode==6){raidSettings();serviceControls();ui.show();render();return;}
        elapsed=ui.text("00:00:00",16,ModeUi.MUTED);ui.page.addView(elapsed);
        details=ui.text("",13,ModeUi.MUTED);ui.page.addView(details);
        String[] labels=mode==5?new String[]{"Encounters","Catches","Rockets","Shinies","XP","Stardust"}:mode==1?new String[]{"Catches","PokéStops","Transferred","Items deleted","XP","Stardust"}:new String[]{mode==3?"Rockets":"Encounters","Catches","Shinies","Items deleted","XP","Stardust"};
        for(int row=0;row<3;row++){LinearLayout line=ui.row();ui.page.addView(line);for(int col=0;col<2;col++)metrics[row*2+col]=ui.metric(line,labels[row*2+col]);}
        clear=ui.button(ui.page,"Clear statistics",false);clear.setOnClickListener(v->{if(CheckService.view.running)return;
            inputError="";settings.save(mode,new NativeSessionState.Totals());settings.prefs.edit().putLong("stats"+mode+".elapsed",0).apply();if(mode==2)dev.rootcatch.ugphone.KecleonHost.clearHistory(this);if(mode==3)NativeRocketHost.clear(this);render();});ui.space(ui.page,16);
        SettingsDialogs dialogs=new SettingsDialogs(this);
        LinearLayout features=ui.card("Features");
        check(features,"Fix error 12","fixLocation",false);
        if(mode==1){
            toggle(features,"Catches","catches",true);
            toggle(features,"Automatic event actions","automaticEventActions",true);
            setting(features,"Only catch",speciesSummary()).setOnClickListener(v->new PokemonPickerDialog(this,settings.prefs.getString("species","")).show(value->{settings.prefs.edit().putString("species",value).apply();show();}));
        }else{
            LinearLayout filters=ui.column();
            if(mode==3){CompoundButton all=toggle(features,"Catch all","rocketCatchAll",true);features.addView(filters);filters.setVisibility(all.isChecked()?View.GONE:View.VISIBLE);
                all.setOnCheckedChangeListener((v,on)->{settings.prefs.edit().putBoolean("rocketCatchAll",on).apply();filters.setVisibility(on?View.GONE:View.VISIBLE);});
            }else features.addView(filters);
            String[] names=mode==3?new String[]{"Only catch 15/15/15","Only catch 0/0/0","Only catch shiny","Only catch shiny + 15/15/15","Only catch 13/13/13 or above"}:new String[]{"Only catch 15/15/15","Only catch 0/0/0","Only catch shiny","Only catch shiny + 15/15/15"};
            for(int i=0;i<names.length;i++)bit(filters,names[i],mode==3?"rocketFilters":"filters",1<<i,0);
        }
        if(mode==3){
            setting(features,getString(R.string.rocket_types),rocketTypesSummary()).setOnClickListener(v->dialogs.rocketTypesDialog(settings.prefs.getInt("rocketTypes",RocketTypes.ALL),value->{settings.prefs.edit().putInt("rocketTypes",value).apply();show();}));
            check(features,"Use recommended team","rocketRecommendedTeam",true);
        }
        int q=Math.max(1,Math.min(4,settings.prefs.getInt("quality",4)));
        setting(features,"Throw quality",ThrowQuality.values()[q-1].label).setOnClickListener(v->dialogs.throwsDialog(ThrowQuality.values()[q-1],value->{settings.prefs.edit().putInt("quality",value.ordinal()+1).apply();show();}));
        toggle(features,"Curve ball","curve",true);
        CompoundButton berries=toggle(features,"Use berries","berries",true);
        LinearLayout berryOptions=ui.column();features.addView(berryOptions);
        setting(berryOptions,"Berry priority",settings.berryNames().replace(","," · ")).setOnClickListener(v->dialogs.berriesDialog(settings.berryNames(),value->{settings.saveBerries(value);show();}));
        berries.setOnCheckedChangeListener((v,on)->{settings.prefs.edit().putBoolean("berries",on).apply();berryOptions.setVisibility(on?View.VISIBLE:View.GONE);});berryOptions.setVisibility(berries.isChecked()?View.VISIBLE:View.GONE);
        check(features,"Remove buddy","removeBuddy",false);
        if(mode==1){
            toggle(features,"Spin PokéStops","spins",true);
            CompoundButton transfer=toggle(features,"Transfer new catches","transfer",false);
            LinearLayout options=ui.column();features.addView(options);options.setPadding(ui.dp(8),0,0,ui.dp(12));
            String[] names={"Keep shiny","Keep 15/15/15","Keep Dynamax","Keep hatched","Keep background","Keep shiny + background","Keep field research encounters"};
            for(int i=0;i<names.length;i++)bit(options,names[i],"keep",1<<i,95);
            check(options,"Keep GO Pass encounters","keepGoPass",true);
            String kept=settings.prefs.getString("keepSpecies",dev.rootcatch.automation.PokemonSelection.KEEP_DEFAULT);
            int keptCount=dev.rootcatch.automation.PokemonSelection.parse(kept).size();
            setting(options,"Select Pokémon to keep",keptCount==0?"None selected":keptCount+" selected").setOnClickListener(v->new PokemonPickerDialog(this,kept,true).show(value->{settings.prefs.edit().putString("keepSpecies",value).apply();show();}));
            transfer.setOnCheckedChangeListener((v,on)->{settings.prefs.edit().putBoolean("transfer",on).apply();options.setVisibility(on?View.VISIBLE:View.GONE);});options.setVisibility(transfer.isChecked()?View.VISIBLE:View.GONE);
        }
        CompoundButton clean=toggle(features,"Clean item bag","clean",false);
        LinearLayout itemOptions=ui.column();features.addView(itemOptions);
        setting(itemOptions,"Item limits",itemSummary()).setOnClickListener(v->dialogs.itemsDialog(settings.prefs.getString("limits",""),value->{settings.prefs.edit().putString("limits",value).apply();show();}));
        clean.setOnCheckedChangeListener((v,on)->{settings.prefs.edit().putBoolean("clean",on).apply();itemOptions.setVisibility(on?View.VISIBLE:View.GONE);});itemOptions.setVisibility(clean.isChecked()?View.VISIBLE:View.GONE);
        if(mode==1){
            LinearLayout movement=ui.card("Map");check(movement,"Double tap to teleport","farmer.tapTeleport",false);
            LinearLayout buddy=ui.card("Buddy");
            check(buddy,"Feed buddy","feedBuddy",false);
            setting(buddy,"Berry priority",settings.buddyBerryNames().replace(","," · ")).setOnClickListener(v->dialogs.berriesDialog(settings.buddyBerryNames(),value->{settings.saveBuddyBerries(value);show();}));
            check(buddy,"Mega Evolve buddy","megaBuddy",false);
            buddy.addView(ui.text(settings.prefs.getBoolean("removeBuddy",false)?"Disabled while Remove buddy is enabled":"Uses the available Mega Evolution with the lowest energy cost",13,ModeUi.MUTED));
        }
        if(mode==3){
            LinearLayout care=ui.card("Team care");
            setting(care,"Potion priority",medicineSummary(false)).setOnClickListener(v->dialogs.medicineDialog("Potion priority",settings.prefs.getString("rocketPotionOrder",""),false,value->{settings.prefs.edit().putString("rocketPotionOrder",value).apply();show();}));
            setting(care,"Revive priority",medicineSummary(true)).setOnClickListener(v->dialogs.medicineDialog("Revive priority",settings.prefs.getString("rocketReviveOrder",""),true,value->{settings.prefs.edit().putString("rocketReviveOrder",value).apply();show();}));
        }
        LinearLayout inventory=ui.card("Inventory tools");
        toggle(inventory,"Show IV names","showIvNames",true);
        toggle(inventory,"Extended manual transfer","extendedManualTransfer",false);
        LinearLayout game=ui.card("Game tools");
        toggle(game,"Fast gift opening","fastGiftOpening",true);
        if(mode!=3){toggle(game,"Auto-open gifts","autoOpenGifts",false);toggle(game,"Auto-send gifts","autoSendGifts",false);}
        toggle(game,"Hide trainer name","hideTrainerName",true);
        Button engine=ui.button(ui.page,"Engine settings",false);edits.add(engine);engine.setOnClickListener(v->engineDialog());
        serviceControls();ui.show();render();
    }
    private void serviceControls(){
        start=ui.button(ui.controls,"Start service",true);start.setOnClickListener(v->{if(CheckService.view.running){startService(new Intent(this,CheckService.class).setAction(CheckService.STOP));return;}
            try{inputError="";settings.config(mode,false);
                PowerManager power=getSystemService(PowerManager.class);
                if(mode==5&&power!=null&&!power.isIgnoringBatteryOptimizations(getPackageName())&&!settings.prefs.getBoolean("enh.backgroundPrompted",false)){
                    settings.prefs.edit().putBoolean("enh.backgroundPrompted",true).apply();pendingStart=mode;
                    try{startActivityForResult(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:"+getPackageName())),41);return;}
                    catch(ActivityNotFoundException|SecurityException unavailable){pendingStart=0;}
                }
                startMode(mode);
            }catch(Exception error){serviceError(error);}
        });
        export=ui.button(ui.controls,"Export logs",false);export.setOnClickListener(v->export());
    }
    private void startMode(int selected){settings.config(selected,false);getPreferences(0).edit().putInt("mode",selected).apply();startForegroundService(new Intent(this,CheckService.class).setAction(CheckService.START).putExtra("mode",selected));}
    private void serviceError(Exception error){inputError=error.getMessage();if(status!=null)status.setText(inputError);Toast.makeText(this,inputError,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41&&pendingStart==5){int selected=pendingStart;pendingStart=0;if(!CheckService.view.running)try{startMode(selected);}catch(Exception error){serviceError(error);}}}
    private void open(int selected){mode=selected;getPreferences(0).edit().putInt("mode",mode).apply();show();}
    private void render(){
        if(status==null)return;CheckService.View value=CheckService.view;var t=settings.totals(mode);
        set(status,value.running||inputError.isEmpty()?value.status:inputError);if(mode!=5)set(details,value.running?value.details:"");
        long[] values=mode==5?new long[]{t.encounters,t.caught,t.spun,t.shinies,t.xp,t.stardust}:mode==1?new long[]{t.caught,t.spun,t.transferred,t.discarded,t.xp,t.stardust}:new long[]{mode==3||mode==6?t.spun:t.encounters,t.caught,t.shinies,t.discarded,t.xp,t.stardust};
        if(mode!=5){long seconds=settings.elapsed(mode)/1000;set(elapsed,String.format(Locale.US,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60));
        for(int i=0;i<6;i++)set(metrics[i],String.format(Locale.US,"%,d",values[i]));}
        set(start,value.running?"Stop service":"Start service");if(clear!=null)clear.setEnabled(!value.running);for(View edit:edits)edit.setEnabled(!value.running);
    }
    private static void set(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
    private CheckBox check(LinearLayout parent,String title,String key,boolean fallback){CheckBox b=ui.check(parent,title,settings.prefs.getBoolean(key,fallback));edits.add(b);b.setOnCheckedChangeListener((v,on)->settings.prefs.edit().putBoolean(key,on).apply());return b;}
    private CompoundButton toggle(LinearLayout parent,String title,String key,boolean fallback){
        Switch b=ui.toggle(parent,title,settings.prefs.getBoolean(key,fallback));edits.add(b);b.setOnCheckedChangeListener((v,on)->settings.prefs.edit().putBoolean(key,on).apply());return b;
    }
    private CheckBox bit(LinearLayout parent,String title,String key,int bit,int fallback){CheckBox b=ui.check(parent,title,(settings.prefs.getInt(key,fallback)&bit)!=0);edits.add(b);b.setOnCheckedChangeListener((v,on)->{int n=settings.prefs.getInt(key,fallback);settings.prefs.edit().putInt(key,on?n|bit:n&~bit).apply();});return b;}
    private Button setting(LinearLayout parent,String label,String value){Button b=ui.button(parent,label+"\n"+value,false);edits.add(b);return b;}
    private String speciesSummary(){int n=dev.rootcatch.automation.PokemonSelection.parse(settings.prefs.getString("species","")).size();return n==0?"All Pokémon":n+" selected";}
    private String rocketTypesSummary(){int mask=settings.prefs.getInt("rocketTypes",RocketTypes.ALL),n=Integer.bitCount(mask&RocketTypes.ALL);return mask==RocketTypes.ALL?getString(R.string.all_regular_grunts):getResources().getQuantityString(R.plurals.rocket_types_selected,n,n);}
    private String medicineSummary(boolean revives){int[] ids=RocketCareOrder.parse(settings.prefs.getString(revives?"rocketReviveOrder":"rocketPotionOrder",""),revives);if(ids.length==0)return "Automatic";java.util.ArrayList<String> names=new java.util.ArrayList<>();for(int id:ids)for(ItemCatalog.Entry item:ItemCatalog.ENTRIES)if(item.id()==id)names.add(item.label());return String.join(" · ",names);}
    private String itemSummary(){String text=settings.prefs.getString("limits","");return text.isBlank()?"Not configured":text.split(",").length+" item types";}
    private void export(){export.setEnabled(false);export.setText("Exporting…");new Thread(()->{String result;try{CheckLog.export(this);result="Saved to Downloads/SilphMod";}catch(Exception error){result=error.getMessage();}String message=result;runOnUiThread(()->{export.setEnabled(true);export.setText("Export logs");Toast.makeText(this,message==null?"Export failed":message,Toast.LENGTH_LONG).show();});},"silph-export").start();}
    private void engineDialog(){
        Dialog dialog=new Dialog(this,R.style.SettingsDialogTheme);LinearLayout page=ui.column();page.setPadding(ui.dp(22),ui.dp(20),ui.dp(22),ui.dp(20));page.setBackgroundColor(ModeUi.BG);
        page.addView(ui.text("Engine settings",23,ModeUi.TEXT));
        LinkedHashMap<String,EditText> inputs=new LinkedHashMap<>();input(page,inputs,"delay","Action interval (ms)",100);
        if(mode==1){input(page,inputs,"catchesMax","Maximum catches",4800);input(page,inputs,"spinsMax","Maximum spins",2400);}else if(mode==3)input(page,inputs,"rocketsMax","Maximum Rockets",700);else input(page,inputs,"encountersMax","Maximum encounters",1000);
        EditText location=new EditText(this);if(mode==3){page.addView(ui.text("Last cooldown location",14,ModeUi.MUTED));location.setSingleLine(true);location.setTextColor(ModeUi.TEXT);location.setInputType(InputType.TYPE_CLASS_TEXT);
            location.setHint("Latitude,longitude (optional)");location.setText(settings.prefs.getString("rocketCooldownLocation",""));page.addView(location);}
        ui.button(page,"Save",true).setOnClickListener(v->{try{var editor=settings.prefs.edit();for(var pair:inputs.entrySet()){int n=Integer.parseInt(pair.getValue().getText().toString());if(pair.getKey().equals("delay")?(n<100||n>3000):(n<1||n>50000))throw new IllegalArgumentException("Invalid value");editor.putInt(pair.getKey(),n);}if(mode==3){String value=location.getText().toString().trim();if(!value.isEmpty())dev.rootcatch.ugphone.KecleonFeed.parseCoordinates(value);editor.putString("rocketCooldownLocation",value);}editor.apply();inputError="";dialog.dismiss();}catch(Exception error){Toast.makeText(this,"Invalid value",Toast.LENGTH_SHORT).show();}});
        ui.button(page,"Cancel",false).setOnClickListener(v->dialog.dismiss());dialog.setContentView(page);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-ui.dp(32),ui.dp(420)),-2);
    }
    private void input(LinearLayout page,Map<String,EditText> inputs,String key,String title,int fallback){page.addView(ui.text(title,14,ModeUi.MUTED));EditText edit=new EditText(this);edit.setSingleLine(true);edit.setInputType(InputType.TYPE_CLASS_NUMBER);edit.setTextColor(ModeUi.TEXT);edit.setText(Integer.toString(settings.prefs.getInt(key,fallback)));edit.setContentDescription(title);page.addView(edit);inputs.put(key,edit);}
    private void raidSettings(){
        SettingsDialogs dialogs=new SettingsDialogs(this);
        LinearLayout features=ui.card("Raid settings");check(features,"Fix error 12","raid.fixLocation",settings.prefs.getBoolean("fixLocation",false));
        elapsed=ui.text("00:00:00",16,ModeUi.MUTED);ui.page.addView(elapsed);details=ui.text("",13,ModeUi.MUTED);ui.page.addView(details);
        String[] labels={"Raids","Catches","Shinies","Items deleted","XP","Stardust"};for(int row=0;row<3;row++){LinearLayout line=ui.row();ui.page.addView(line);for(int col=0;col<2;col++)metrics[row*2+col]=ui.metric(line,labels[row*2+col]);}
        clear=ui.button(ui.page,"Clear statistics",false);clear.setOnClickListener(v->{if(CheckService.view.running)return;settings.save(6,new NativeSessionState.Totals());settings.prefs.edit().putLong("stats6.elapsed",0).apply();NativeRaidHost.clear(this);render();});
        LinearLayout countries=ui.card("Countries");bit(countries,"Mexico · Mexico City","raid.countries",1,1);bit(countries,"Japan · Sendai","raid.countries",2,1);
        int targets=RaidTargetPlan.parse(settings.prefs.getString("raid.targets","")).limits.size();
        setting(countries,"Select raid targets",targets==0?"None selected":targets+" selected").setOnClickListener(v->new RaidTargetsDialog(this,settings.prefs).show(this::show));
        ui.button(countries,"Lobby & limits",false).setOnClickListener(v->raidLimits());
        LinearLayout capture=ui.card("Catch filters");CompoundButton all=check(capture,"Catch all","raid.catchAll",true);String[] filters={"Only catch 15/15/15","Only catch background","Only catch shiny","Only catch shiny + 15/15/15","Only catch shiny + background"};
        CompoundButton[] choices=new CompoundButton[filters.length];boolean[] changing={false};
        for(int i=0;i<filters.length;i++){int bit=1<<i;choices[i]=bit(capture,filters[i],"raid.filters",bit,0);choices[i].setChecked(!all.isChecked()&&(settings.prefs.getInt("raid.filters",0)&bit)!=0);choices[i].setOnCheckedChangeListener((v,on)->{
            if(changing[0])return;int mask=settings.prefs.getInt("raid.filters",0);mask=on?mask|bit:mask&~bit;changing[0]=true;all.setChecked(mask==0);changing[0]=false;settings.prefs.edit().putInt("raid.filters",mask).putBoolean("raid.catchAll",mask==0).apply();});}
        all.setOnCheckedChangeListener((v,on)->{if(changing[0])return;changing[0]=true;if(on){for(CompoundButton choice:choices)choice.setChecked(false);settings.prefs.edit().putInt("raid.filters",0).putBoolean("raid.catchAll",true).apply();}else if(settings.prefs.getInt("raid.filters",0)==0)all.setChecked(true);else settings.prefs.edit().putBoolean("raid.catchAll",false).apply();changing[0]=false;});
        int quality=Math.max(1,Math.min(4,settings.prefs.getInt("raid.quality",4)));setting(capture,"Throw quality",ThrowQuality.values()[quality-1].label).setOnClickListener(v->dialogs.throwsDialog(ThrowQuality.values()[quality-1],value->{settings.prefs.edit().putInt("raid.quality",value.ordinal()+1).apply();show();}));
        check(capture,"Curve ball","raid.curve",true);check(capture,"Use berries","raid.berries",true);String order=settings.berryNames("raid.berriesOrder","706,708,701,705,703");
        setting(capture,"Berry priority",order.replace(","," · ")).setOnClickListener(v->dialogs.berriesDialog(order,value->{settings.saveRaidBerries(value);show();}));
        LinearLayout transfers=ui.card("Transfers");check(transfers,"Transfer new catches","raid.transfer",false);bit(transfers,"Keep shiny","raid.keep",1,settings.prefs.getInt("keep",95));bit(transfers,"Keep 15/15/15","raid.keep",2,settings.prefs.getInt("keep",95));bit(transfers,"Keep background","raid.keep",16,settings.prefs.getInt("keep",95));bit(transfers,"Keep shiny + background","raid.keep",32,settings.prefs.getInt("keep",95));
    }
    private void raidLimits(){
        Dialog dialog=new Dialog(this);LinearLayout page=ui.column();page.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(16));page.setBackgroundColor(ModeUi.BG);ScrollView scroll=new ScrollView(this);scroll.addView(page);
        page.addView(ui.text("Lobby & limits",21,ModeUi.TEXT));
        String[] keys={"raid.minPlayers","raid.maximum","raid.exitRemainingSeconds","raid.reference1","raid.reference2","raid.radiusKm","raid.lastActionCoordinates","raid.minutesAgo"};
        String[] labels={"Minimum trainers","Maximum raids","Leave if trainers are missing at (seconds remaining)","Mexico City reference coordinates","Sendai reference coordinates","Search radius (km)","Last cooldown action coordinates (optional)","Minutes since that action"};
        long originalAt=settings.prefs.getLong("raid.lastActionAt",0);String originalPoint=settings.prefs.getString("raid.lastActionCoordinates","");
        String originalMinutes=Long.toString(originalAt==0?120:Math.min(120,Math.max(0,(System.currentTimeMillis()-originalAt)/60000)));
        String[] values={Integer.toString(settings.prefs.getInt(keys[0],2)),Integer.toString(settings.prefs.getInt(keys[1],100)),Integer.toString(settings.prefs.getInt(keys[2],20)),settings.prefs.getString(keys[3],RaidSearchArea.defaultPoint(1)),settings.prefs.getString(keys[4],RaidSearchArea.defaultPoint(2)),Integer.toString(settings.prefs.getInt(keys[5],5)),originalPoint,originalMinutes};
        EditText[] inputs=new EditText[keys.length];for(int i=0;i<inputs.length;i++){page.addView(ui.text(labels[i],14,ModeUi.MUTED));EditText value=inputs[i]=new EditText(this);value.setSingleLine(true);value.setTextColor(ModeUi.TEXT);value.setInputType(i==3||i==4||i==6?InputType.TYPE_CLASS_TEXT:InputType.TYPE_CLASS_NUMBER);value.setText(values[i]);page.addView(value);}
        ui.button(page,"Save",true).setOnClickListener(v->{try{
            int players=Integer.parseInt(inputs[0].getText().toString()),maximum=Integer.parseInt(inputs[1].getText().toString()),remaining=Integer.parseInt(inputs[2].getText().toString()),radius=Integer.parseInt(inputs[5].getText().toString()),minutes=Integer.parseInt(inputs[7].getText().toString());
            if(players<1||players>20||maximum<1||maximum>50000||minutes<0||minutes>120||remaining<5||remaining>110)throw new IllegalArgumentException();
            String mexico=inputs[3].getText().toString().trim(),sendai=inputs[4].getText().toString().trim(),point=inputs[6].getText().toString().trim();new RaidSearchArea(mexico,radius);new RaidSearchArea(sendai,radius);if(!point.isEmpty())dev.rootcatch.ugphone.KecleonFeed.parseCoordinates(point);
            long at=originalAt;if(!point.equals(originalPoint)||!inputs[7].getText().toString().equals(originalMinutes))at=point.isEmpty()?0:System.currentTimeMillis()-minutes*60000L;
            settings.prefs.edit().putInt(keys[0],players).putInt(keys[1],maximum).putInt(keys[2],remaining).putString(keys[3],mexico).putString(keys[4],sendai).putInt(keys[5],radius).putString(keys[6],point).putLong("raid.lastActionAt",at).apply();dialog.dismiss();
        }catch(Exception error){Toast.makeText(this,"Check the lobby and limits",Toast.LENGTH_SHORT).show();}});
        ui.button(page,"Cancel",false).setOnClickListener(v->dialog.dismiss());dialog.setContentView(scroll);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-ui.dp(32),ui.dp(440)),-2);
    }
    private void enhancerSettings(SettingsDialogs dialogs){
        LinearLayout features=ui.card("Features");check(features,"Fix error 12","enh.fixLocation",settings.prefs.getBoolean("fixLocation",false));
        LinearLayout capture=ui.card("Capture assistance");
        capture.addView(ui.text("Open a Pokémon and throw normally to use your selected assistance",14,ModeUi.MUTED));
        toggle(capture,"Encounter IVs & level","enh.encounterInfo",true);
        toggle(capture,"Fast catch","enh.fastCatch",true);toggle(capture,"Short encounter intro","enh.skipIntro",true);
        toggle(capture,"Assist aim","enh.aimAssist",true);toggle(capture,"Throw assistance","enh.throwAssist",true);
        int q=Math.max(1,Math.min(4,settings.prefs.getInt("enh.quality",4)));
        setting(capture,"Throw quality",ThrowQuality.values()[q-1].label).setOnClickListener(v->dialogs.throwsDialog(ThrowQuality.values()[q-1],value->{settings.prefs.edit().putInt("enh.quality",value.ordinal()+1).apply();show();}));
        toggle(capture,"Curve ball","enh.curve",true);toggle(capture,"Use berries","enh.berries",false);
        setting(capture,"Berry priority",settings.berryNames("enh.berriesOrder","701,705,703").replace(","," · ")).setOnClickListener(v->dialogs.berriesDialog(settings.berryNames("enh.berriesOrder","701,705,703"),value->{settings.saveEnhancerBerries(value,false);show();}));
        LinearLayout battle=ui.card("Rocket assistance");toggle(battle,"AutoRocket","enh.autoRocket",false);
        battle.addView(ui.text("Open a regular grunt to battle, catch its shadow Pokémon and heal the team",14,ModeUi.MUTED));
        check(battle,"Use recommended team","enh.recommendedTeam",true);
        LinearLayout raid=ui.card("Raid assistance");toggle(raid,"AutoRaid","enh.autoRaid",false);
        raid.addView(ui.text("Choose your team and start a normal raid. Attacks and charged-attack dodges are automatic.",14,ModeUi.MUTED));
        check(raid,"Heal raid team after battle","enh.healRaidTeam",false);
        LinearLayout transfers=ui.card("Transfers");
        CompoundButton transfer=toggle(transfers,"Transfer new catches","enh.transfer",false);
        LinearLayout options=ui.column();transfers.addView(options);
        String[] names={"Keep shiny","Keep 15/15/15","Keep Dynamax","Keep hatched","Keep background","Keep shiny + background","Keep field research encounters"};
        for(int i=0;i<names.length;i++)bit(options,names[i],"enh.keep",1<<i,95);
        check(options,"Keep GO Pass encounters","enh.keepGoPass",true);
        String kept=settings.prefs.getString("enh.keepSpecies",dev.rootcatch.automation.PokemonSelection.KEEP_DEFAULT);
        int count=dev.rootcatch.automation.PokemonSelection.parse(kept).size();
        setting(options,"Select Pokémon to keep",count==0?"None selected":count+" selected").setOnClickListener(v->new PokemonPickerDialog(this,kept,true).show(value->{settings.prefs.edit().putString("enh.keepSpecies",value).apply();show();}));
        options.addView(ui.text("Legendary, Mythical and Ultra Beast Pokémon are always kept",13,ModeUi.MUTED));
        transfer.setOnCheckedChangeListener((v,on)->{settings.prefs.edit().putBoolean("enh.transfer",on).apply();options.setVisibility(on?View.VISIBLE:View.GONE);});options.setVisibility(transfer.isChecked()?View.VISIBLE:View.GONE);
        LinearLayout buddy=ui.card("Buddy");check(buddy,"Feed buddy","enh.feedBuddy",false);
        setting(buddy,"Berry priority",settings.berryNames("enh.buddyBerriesOrder","703,701,705").replace(","," · ")).setOnClickListener(v->dialogs.berriesDialog(settings.berryNames("enh.buddyBerriesOrder","703,701,705"),value->{settings.saveEnhancerBerries(value,true);show();}));
        check(buddy,"Mega Evolve buddy","enh.megaBuddy",false);
        LinearLayout movement=ui.card("Map");toggle(movement,"Double tap to teleport","enh.tapTeleport",false);
        movement.addView(ui.text("Double tap the map ground to move with your configured joystick",14,ModeUi.MUTED));
        LinearLayout inventory=ui.card("Inventory tools");toggle(inventory,"Show IV names","enh.showIvNames",true);toggle(inventory,"Extended manual transfer","enh.extendedManualTransfer",false);
        LinearLayout game=ui.card("Game tools");toggle(game,"Skip scenes","enh.skipScenes",true);toggle(game,"Hide trainer name","enh.hideTrainerName",true);toggle(game,"Fast gift opening","enh.fastGiftOpening",true);
        toggle(game,"Auto-open gifts","enh.autoOpenGifts",false);toggle(game,"Auto-send gifts","enh.autoSendGifts",false);

    }
}
