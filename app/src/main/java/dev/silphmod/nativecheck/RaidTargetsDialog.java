package dev.silphmod.nativecheck;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import dev.rootcatch.automation.PokemonCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class RaidTargetsDialog {
    private final Activity activity;private final SharedPreferences settings;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"silph-raid-targets"));
    private final RaidFeedClient client=new RaidFeedClient(android.os.SystemClock::elapsedRealtime);
    private final Map<String,Integer> selected=new LinkedHashMap<>();private final Map<String,String> draft=new HashMap<>(),variants=new HashMap<>();
    private final Map<Integer,PokemonCatalog.Entry> catalog=new HashMap<>();private final Map<Integer,Bitmap> icons=new HashMap<>();
    private final List<String> rows=new ArrayList<>(),visible=new ArrayList<>();private volatile boolean closed;private String query="";private Dialog dialog;private ModeUi ui;private TextView status;private TargetsAdapter adapter;
    private final Runnable refresh=()->{if(!closed)try{worker.execute(this::load);}catch(RejectedExecutionException ignored){}};
    RaidTargetsDialog(Activity activity,SharedPreferences settings){this.activity=activity;this.settings=settings;selected.putAll(RaidTargetPlan.parse(settings.getString("raid.targets","")).limits);for(Map.Entry<String,Integer> e:selected.entrySet())draft.put(e.getKey(),e.getValue().toString());}
    void show(Runnable saved){
        dialog=new Dialog(activity);ui=new ModeUi(activity);LinearLayout page=ui.column();page.setPadding(ui.dp(18),ui.dp(18),ui.dp(18),ui.dp(14));page.setBackgroundColor(ModeUi.BG);
        page.addView(ui.text("Select raid targets",22,ModeUi.TEXT));page.addView(ui.text("Choose bosses, quantities and order. Wait for each target before the next.",14,ModeUi.MUTED));status=ui.text("Loading available raids…",13,ModeUi.MUTED);page.addView(status);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("Search Pokémon");search.setTextColor(ModeUi.TEXT);search.setHintTextColor(ModeUi.MUTED);page.addView(search);
        ListView list=new ListView(activity);list.setDividerHeight(ui.dp(6));adapter=new TargetsAdapter();list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=ui.row();Button cancel=ui.button(footer,"Cancel",false),save=ui.button(footer,"Save",true);for(int i=0;i<2;i++)footer.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0,ui.dp(52),1));page.addView(footer);
        cancel.setOnClickListener(v->dialog.dismiss());save.setOnClickListener(v->{try{
            LinkedHashMap<String,Integer> values=new LinkedHashMap<>();for(String key:selected.keySet()){String text=draft.getOrDefault(key,"1");if(!text.matches("[0-9]{1,5}"))throw new IllegalArgumentException();values.put(key,Integer.parseInt(text));}
            settings.edit().putString("raid.targets",RaidTargetPlan.encode(values)).apply();dialog.dismiss();saved.run();
        }catch(IllegalArgumentException error){Toast.makeText(activity,"Enter 1–50000 victories for each selected boss",Toast.LENGTH_LONG).show();}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=PokemonCatalog.normalize(s.toString());filter();}public void afterTextChanged(Editable s){}});
        dialog.setOnDismissListener(v->{closed=true;main.removeCallbacks(refresh);client.close();worker.shutdownNow();});dialog.setContentView(page);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(activity.getResources().getDisplayMetrics().widthPixels-ui.dp(28),(int)(activity.getResources().getDisplayMetrics().heightPixels*.86));
        worker.execute(this::load);
    }
    private void load(){
        LinkedHashSet<String> entries=new LinkedHashSet<>();String result="";
        try{
            if(catalog.isEmpty()){for(PokemonCatalog.Entry e:PokemonCatalog.load(activity))if(e.form()==0)catalog.put(e.dex(),e);
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(activity.getAssets().open("raids/variants.tsv"),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){String[] p=line.split("\t",3);if(p.length==3)variants.put(p[0]+p[1],p[2]);}}}
            int mask=settings.getInt("raid.countries",1);for(int country=1;country<=2&&!closed;country++)if((mask&(1<<(country-1)))!=0){
                try{RaidFeedClient.Result response=client.refresh(country,RaidSources.url(country));for(RaidFeed.Candidate c:response.snapshot.candidates)if(c.expiresMs-response.snapshot.nowMs(android.os.SystemClock.elapsedRealtime())>=180000)entries.add(RaidTargetPlan.key(c));}
                catch(IOException error){result="Some raid lists are unavailable; your selection is preserved";}
            }
        }catch(Exception error){result="Raid list unavailable; your selection is preserved";}
        List<String> loaded=new ArrayList<>(entries);loaded.sort(Comparator.comparingInt((String key)->RaidTargetPlan.parts(key)[0]).thenComparing(key->key));
        String message=result;main.post(()->{if(closed||activity.isFinishing())return;rows.clear();rows.addAll(loaded);status.setText(message.isEmpty()?selected.size()+" selected · "+rows.size()+" raid variants":message);filter();main.postDelayed(refresh,60000);});
    }
    private String title(String key){int[] p=RaidTargetPlan.parts(key);PokemonCatalog.Entry e=catalog.get(p[0]);return e==null?"Pokémon #"+p[0]:e.name();}
    private String detail(String key){int[] p=RaidTargetPlan.parts(key);ArrayList<String> labels=new ArrayList<>();labels.add(RaidTargetPlan.levelLabel(p[1]));String name=title(key);
        if(p[2]>0){String text=variants.getOrDefault("f"+p[2],"Alternate form");if(text.startsWith(name+" "))text=text.substring(name.length()+1);if(!text.equalsIgnoreCase("Normal"))labels.add(text);}
        if(p[3]>0)labels.add(variants.getOrDefault("c"+p[3],"Costume"));if(p[4]>0)labels.add(variants.getOrDefault("e"+p[4],"Mega Evolution"));return String.join(" · ",labels);
    }
    private void move(String key,int delta){List<String> order=new ArrayList<>(selected.keySet());int old=order.indexOf(key),next=old+delta;if(old<0||next<0||next>=order.size())return;Collections.swap(order,old,next);Map<String,Integer> values=new LinkedHashMap<>(selected);selected.clear();for(String entry:order)selected.put(entry,values.get(entry));filter();}
    private void filter(){visible.clear();Set<String> available=new HashSet<>(rows);List<String> ordered=new ArrayList<>();for(String key:selected.keySet())if(available.contains(key))ordered.add(key);for(String row:rows)if(!selected.containsKey(row))ordered.add(row);for(String key:ordered)if(query.isEmpty()||PokemonCatalog.normalize(title(key)+detail(key)).contains(query))visible.add(key);adapter.notifyDataSetChanged();}
    private final class TargetsAdapter extends BaseAdapter {
        public int getCount(){return visible.size();}public Object getItem(int position){return visible.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            String key=visible.get(position);int[] p=RaidTargetPlan.parts(key);LinearLayout row=ui.row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,ui.dp(6),0,ui.dp(6));row.setBackgroundColor(ModeUi.PANEL);
            CheckBox check=new CheckBox(activity);check.setButtonTintList(ColorStateList.valueOf(ModeUi.ACCENT));check.setChecked(selected.containsKey(key));row.addView(check);
            ImageView icon=new ImageView(activity);icon.setContentDescription(title(key));row.addView(icon,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));Bitmap cached=icons.get(p[0]);if(cached!=null)icon.setImageBitmap(cached);else if(!closed)try{worker.execute(()->{
                Bitmap image=null;try(InputStream stream=activity.getAssets().open(String.format(Locale.ROOT,"pokemon/icons/%04d.png",p[0]))){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;image=BitmapFactory.decodeStream(stream,null,options);}catch(IOException ignored){}Bitmap loaded=image;
                main.post(()->{if(!closed&&loaded!=null){icons.put(p[0],loaded);icon.setImageBitmap(loaded);}});
            });}catch(RejectedExecutionException ignored){}
            LinearLayout labels=ui.column();labels.setPadding(ui.dp(8),0,ui.dp(6),0);labels.addView(ui.text((selected.containsKey(key)?(new ArrayList<>(selected.keySet()).indexOf(key)+1)+". ":"")+title(key),16,ModeUi.TEXT));labels.addView(ui.text(detail(key),12,ModeUi.MUTED));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            EditText count=new EditText(activity);count.setSingleLine(true);count.setInputType(InputType.TYPE_CLASS_NUMBER);count.setTextColor(ModeUi.TEXT);count.setContentDescription("Victories for "+title(key)+" "+detail(key));count.setText(draft.getOrDefault(key,"1"));count.setEnabled(check.isChecked());row.addView(count,new LinearLayout.LayoutParams(ui.dp(64),ui.dp(54)));
            check.setOnCheckedChangeListener((button,on)->{if(on){selected.put(key,1);draft.putIfAbsent(key,"1");}else selected.remove(key);count.setEnabled(on);filter();status.setText(selected.size()+" selected · "+rows.size()+" raid variants");});
            if(selected.containsKey(key)){LinearLayout arrows=ui.column();Button up=ui.button(arrows,"↑",false),down=ui.button(arrows,"↓",false);up.setContentDescription("Move "+title(key)+" earlier");down.setContentDescription("Move "+title(key)+" later");up.setOnClickListener(v->move(key,-1));down.setOnClickListener(v->move(key,1));row.addView(arrows,new LinearLayout.LayoutParams(ui.dp(48),-2));}
            count.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){draft.put(key,s.toString());}public void afterTextChanged(Editable s){}});return row;
        }
    }
}
