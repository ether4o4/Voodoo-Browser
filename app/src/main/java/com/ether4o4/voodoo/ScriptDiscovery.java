package com.ether4o4.voodoo;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.webkit.WebView;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Native port of Magic Userscript+'s website-specific Greasy Fork discovery experience.
 * Reference: magicoflolis/Userscript-Plus (MIT); see assets/licenses/Userscript-Plus.txt.
 * Never injects a privileged discovery bridge into a webpage.
 */
final class ScriptDiscovery {
    private final MainActivity a;private Button launcher;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScriptNetwork net=new ScriptNetwork();
    private final Map<String,JSONArray> cache=new ConcurrentHashMap<>();
    private final Map<String,Long> fetched=new ConcurrentHashMap<>();
    private AlertDialog panel,choices;
    private volatile int generation;
    private volatile boolean closed;
    private volatile String activeFetch;
    ScriptDiscovery(MainActivity activity){a=activity;}
    void attach(WebView view){
        String url=a.tabUrl(view);if(a.current!=view||a.privacy!=null&&a.privacy.active||a.local(url)||!a.webUrl(url)){if(launcher!=null&&launcher.getParent()!=null)((android.view.ViewGroup)launcher.getParent()).removeView(launcher);return;}
        if(launcher==null){launcher=a.button("JS",()->{if(a.current!=null)show(a.current);});launcher.setContentDescription("Magic scripts: discover, save or activate a script");launcher.setTextColor(MainActivity.SEAFOAM);launcher.setBackground(a.rounded(0xEB25262B,MainActivity.SEAFOAM));}
        if(launcher.getParent()==a.content)return;if(launcher.getParent()!=null)((android.view.ViewGroup)launcher.getParent()).removeView(launcher);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(a.dp(48),a.dp(48),Gravity.BOTTOM|Gravity.END);p.setMargins(0,0,a.dp(10),a.dp(10));a.content.addView(launcher,p);
    }
    LinearLayout column(){LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(a.dp(16),a.dp(8),a.dp(16),a.dp(12));return box;}
    TextView text(String value){TextView t=new TextView(a);t.setText(value);t.setTextColor(MainActivity.INK);t.setTextSize(14);t.setPadding(0,a.dp(8),0,a.dp(8));return t;}
    void cancelFetch(){String id=activeFetch;if(id!=null)net.cancel(id);}
    JSONObject fetch(String url,ScriptNetwork.Policy policy,int limit)throws Exception{String id=UUID.randomUUID().toString();activeFetch=id;try{return net.request(id,new JSONObject().put("url",url),policy,limit);}finally{if(id.equals(activeFetch))activeFetch=null;}}
    void show(WebView view){
        if(closed||!a.alive||!a.tabs.contains(view))return;if(panel!=null)panel.dismiss();
        String url=view.getUrl();if(!a.webUrl(url)||a.local(url)){a.toast("Script discovery is available on ordinary websites.");return;}
        String host=Uri.parse(url).getHost(),catalogHost=DiscoverySite.catalogHost(url);LinearLayout box=column();ScrollView scroll=new ScrollView(a);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Magic scripts · "+catalogHost).setView(scroll).setNegativeButton("Back to website",(d,w)->{if(a.tabs.contains(view))a.showTab(view);}).setNeutralButton("Script editor",(d,w)->a.openLocal(MainActivity.STUDIO+"#scripts")).create();
        panel=dialog;dialog.setOnDismissListener(d->{if(panel==dialog){generation++;panel=null;cancelFetch();}});
        LinearLayout actions=new LinearLayout(a);actions.addView(a.button("Refresh",()->load(view,host,catalogHost,box,actions,true)),new LinearLayout.LayoutParams(0,a.dp(48),1));actions.addView(a.button("Saved scripts",()->{generation++;cancelFetch();box.removeAllViews();if(actions.getParent()!=null)((ViewGroup)actions.getParent()).removeView(actions);box.addView(actions);renderInstalled(null,box);}),new LinearLayout.LayoutParams(0,a.dp(48),1));actions.addView(a.button("Commands",()->a.scripts.menu(view)),new LinearLayout.LayoutParams(0,a.dp(48),1));
        dialog.show();load(view,host,catalogHost,box,actions,false);
    }
    void load(WebView view,String host,String catalogHost,LinearLayout box,LinearLayout actions,boolean refresh){
        if(closed||!a.alive)return;cancelFetch();int token=++generation;
        box.removeAllViews();if(actions.getParent()!=null)((android.view.ViewGroup)actions.getParent()).removeView(actions);box.addView(actions);box.addView(text("Searching Greasy Fork… No script is installed or executed by searching."));
        worker.execute(()->{
            if(closed||token!=generation)return;
            try{
                JSONArray records;
                records=refresh?null:cache.get(catalogHost);
                if(records==null||System.currentTimeMillis()-fetched.getOrDefault(catalogHost,0L)>=600000){JSONObject response=fetch(DiscoverySite.catalogUrlForHost(catalogHost),address->{if(!"api.greasyfork.org".equals(Uri.parse(address).getHost()))throw new Exception("Unexpected discovery redirect.");},4000000);if(response.getInt("status")!=200)throw new Exception("Greasy Fork HTTP "+response.getInt("status"));records=new JSONArray(response.getString("responseText"));cache.put(catalogHost,records);fetched.put(catalogHost,System.currentTimeMillis());if(cache.size()>8){String oldest=Collections.min(fetched.entrySet(),Map.Entry.comparingByValue()).getKey();cache.remove(oldest);fetched.remove(oldest);}}
                JSONArray result=records;a.runOnUiThread(()->{if(!a.alive||closed||token!=generation)return;box.removeAllViews();box.addView(actions);if(!a.tabs.contains(view)||!host.equals(Uri.parse(a.tabUrl(view)).getHost())){box.addView(text("Website changed. Close and reopen discovery for the current site."));return;}renderInstalled(a.tabUrl(view),box);box.addView(text(result.length()+" results for "+catalogHost+" · Source: Greasy Fork · Choose a script for source and activation options."));
                    if(result.length()==0)box.addView(text("No scripts found for "+catalogHost+". The website may still support custom scripts."));
                    appendResults(result,0,host,box,token);
                });
            }catch(Exception e){a.runOnUiThread(()->{if(!a.alive||closed||token!=generation)return;box.removeAllViews();box.addView(actions);box.addView(text("Discovery failed: "+e.getMessage()+"\nUse Refresh to retry. Installed scripts remain available."));renderInstalled(a.tabUrl(view),box);});}
        });
    }
    void appendResults(JSONArray records,int start,String host,LinearLayout box,int token){
        if(closed||token!=generation)return;int end=Math.min(start+20,Math.min(100,records.length()));
        for(int i=start;i<end;i++){JSONObject record=records.optJSONObject(i);if(record==null)continue;Button choose=a.button(record.optString("name")+" · "+record.optString("version"),()->preview(record,host,box));choose.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choose.setMinHeight(a.dp(48));box.addView(choose,new LinearLayout.LayoutParams(-1,-2));box.addView(text(record.optString("description")));}
        if(end<Math.min(100,records.length())){Button more=a.button("Show more scripts",()->{});more.setOnClickListener(v->{box.removeView(more);appendResults(records,end,host,box,token);});box.addView(more);}else box.addView(text("Discovery adapted from Magic Userscript+ by Magic (MIT)."));
    }
    void renderInstalled(String url,LinearLayout box){
        WebView target=a.site();String current=target==null?"":a.tabUrl(target),host=url==null?null:Uri.parse(url).getHost();int shown=0;
        JSONArray installed=a.userScripts();for(int i=0;i<installed.length();i++){JSONObject script=installed.optJSONObject(i);if(script==null)continue;try{
            ScriptRules rules=new ScriptRules(script.getString("code"));if(url!=null&&!rules.allows(url)&&!script.optString("sourceHost").equals(host))continue;shown++;
            String mode=ScriptChanges.mode(script),label=mode.equals("automatic")?"Automatic":mode.equals("manual")?"Manual · tap Activate":"Saved only";
            Button choose=a.button(script.optString("name")+"\n"+label,()->options(script,null,script.optString("sourceHost"),script.optString("code")));choose.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);choose.setMinHeight(a.dp(60));box.addView(choose,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout row=new LinearLayout(a);if(mode.equals("manual")){Button activate=a.button("Activate",()->activate(target,script));activate.setEnabled(target!=null&&rules.allows(current)&&a.setting("scripts",true));row.addView(activate,new LinearLayout.LayoutParams(0,a.dp(48),1));}
            row.addView(a.button("Remove",()->new AlertDialog.Builder(a).setTitle("Remove "+script.optString("name")+"?").setMessage("Reload affected pages to remove changes already made by the script.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->change(script,true)).show()),new LinearLayout.LayoutParams(0,a.dp(48),1));box.addView(row);
            if(url==null&&!rules.allows(current))box.addView(text("Open a matching website to activate this script."));
        }catch(Exception ignored){}}
        if(url==null&&shown==0)box.addView(text("No saved scripts yet. Choose a script from discovery or save one in the editor."));
    }
    void activate(WebView target,JSONObject script){if(target==null){a.toast("Open a matching website first.");return;}a.scripts.runOnce(target,script,()->{if(panel!=null)panel.dismiss();a.showTab(target);},a::toast);}
    void change(JSONObject selected,boolean remove){
        try{JSONObject studio=a.object(a.prefs.getString("studio","{}"));JSONArray all=studio.optJSONArray("scripts"),next=new JSONArray();if(all==null)return;
            for(int i=0;i<all.length();i++){JSONObject script=all.getJSONObject(i);if(script.optString("id").equals(selected.optString("id"))){if(remove)continue;script.put("enabled",!script.optBoolean("enabled"));}next.put(script);}
            int revision=studio.optInt("_revision");studio.put("scripts",next).put("_revision",revision+1);a.scripts.save(studio,revision,()->{a.toast("Script "+(remove?"removed":"updated")+". Reload the website to refresh execution.");WebView target=a.site();if(target!=null)show(target);},a::toast);
        }catch(Exception e){a.toast(e.getMessage());}
    }
    void preview(JSONObject record,String host,LinearLayout parent){
        if(closed||!a.alive)return;int token=generation;
        JSONArray installed=a.userScripts();for(int i=0;i<installed.length();i++){JSONObject script=installed.optJSONObject(i);if(script!=null&&record.optString("code_url").equals(script.optString("sourceURL"))){options(script,null,host,script.optString("code"));return;}}
        a.toast("Loading script…");worker.execute(()->{if(closed||token!=generation)return;try{
            String address=record.getString("code_url");JSONObject response=fetch(address,url->{String h=Uri.parse(url).getHost();if(!Arrays.asList("update.greasyfork.org","greasyfork.org").contains(h))throw new Exception("Untrusted code source host.");},200000);
            if(response.getInt("status")!=200)throw new Exception("Source HTTP "+response.getInt("status"));String code=response.getString("responseText");ScriptRules rules=new ScriptRules(code);
            a.runOnUiThread(()->{if(a.alive&&!closed&&token==generation)options(null,record,host,code);});
        }catch(Exception e){a.runOnUiThread(()->{if(a.alive&&!closed&&token==generation)a.toast("Source preview failed: "+e.getMessage());});}});
    }
    void options(JSONObject selected,JSONObject record,String host,String code){
        if(closed||!a.alive)return;
        try{
            ScriptRules rules=new ScriptRules(code);WebView target=a.site();LinearLayout box=column();
            box.addView(text(rules.first("name","Script")+"\nVersion: "+rules.first("version","unspecified")));
            box.addView(text("Choose one. All three choices save this script."));
            String[] modes={"saved","automatic","manual"},labels={"Save to scripts · keep it inactive","Automatic activation · on matching websites","Manual activation · only when you tap Activate"};
            CheckBox[] checks=new CheckBox[3];boolean[] selecting={false};String initial=selected==null?"saved":ScriptChanges.mode(selected);
            for(int i=0;i<3;i++){CheckBox check=new CheckBox(a);check.setText(labels[i]);check.setTextColor(MainActivity.INK);check.setMinHeight(a.dp(56));check.setChecked(modes[i].equals(initial));checks[i]=check;box.addView(check);}
            box.addView(text("Saving does not reload the page. Automatic starts on the next matching page load. Activate reloads the website once."));
            box.addView(text("Sites: "+rules.matches+rules.includes+"\nExcludes: "+rules.excludes+rules.excludeGlobs+"\nGrants: "+rules.values("grant")+"\nNetwork: "+rules.values("connect")+"\nDependencies: "+rules.values("require")+rules.values("resource")));
            if(!rules.problems().isEmpty())box.addView(text("Compatibility: "+String.join("; ",rules.problems())));
            Button source=a.button("Review source",()->{});box.addView(source);source.setOnClickListener(v->{if(source.getTag()!=null)return;source.setTag(true);EditText text=new EditText(a);text.setText(code);text.setTextColor(MainActivity.INK);text.setTextSize(11);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setKeyListener(null);text.setMinLines(8);text.setMaxLines(15);box.addView(text);source.setText("Source shown below");});
            ScrollView scroll=new ScrollView(a);scroll.addView(box);
            AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Script options").setView(scroll).setNegativeButton("Back",null).setPositiveButton("Save",null).setNeutralButton("Activate",null).create();
            if(choices!=null)choices.dismiss();choices=dialog;dialog.setOnDismissListener(d->{if(choices==dialog)choices=null;});
            dialog.setOnShowListener(d->{
                Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE),run=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                Runnable refresh=()->run.setEnabled(checks[2].isChecked()&&target!=null&&a.tabs.contains(target)&&rules.allows(a.tabUrl(target))&&rules.problems().isEmpty()&&a.setting("scripts",true));
                for(int i=0;i<3;i++){final int selectedMode=i;checks[i].setOnCheckedChangeListener((button,checked)->{if(selecting[0])return;selecting[0]=true;for(int j=0;j<3;j++)checks[j].setChecked(j==selectedMode);selecting[0]=false;refresh.run();});}
                java.util.function.Consumer<Boolean> apply=execute->{
                    String mode=checks[1].isChecked()?"automatic":checks[2].isChecked()?"manual":"saved";save.setEnabled(false);run.setEnabled(false);
                    saveChoice(selected,record,host,code,mode,saved->{dialog.dismiss();if(target!=null&&a.tabs.contains(target))show(target);if(execute)activate(target,saved);else a.toast(mode.equals("automatic")?"Saved for automatic activation. Reload the website to apply it here.":mode.equals("manual")?"Saved for manual activation. Tap Activate whenever you want to run it.":"Saved to scripts. Automatic activation is off.");},error->{if(dialog.isShowing()){save.setEnabled(true);refresh.run();}a.toast(error);});
                };
                save.setOnClickListener(v->apply.accept(false));run.setOnClickListener(v->apply.accept(true));refresh.run();
            });dialog.show();
        }catch(Exception e){a.toast(e.getMessage());}
    }
    void saveChoice(JSONObject selected,JSONObject record,String host,String code,String mode,java.util.function.Consumer<JSONObject> ok,java.util.function.Consumer<String> fail){
        try{
            JSONObject studio=a.object(a.prefs.getString("studio","{}"));JSONArray all=studio.optJSONArray("scripts");if(all==null)all=new JSONArray();JSONObject script;
            if(selected!=null){JSONObject latest=ScriptChanges.find(all,selected.optString("id"));if(latest==null||!latest.optString("code").equals(code))throw new Exception("The saved script changed. Reopen its options.");script=new JSONObject(latest.toString());}
            else{for(int i=0;i<all.length();i++)if(record.optString("code_url").equals(all.getJSONObject(i).optString("sourceURL")))throw new Exception("Already saved. Reopen it from Saved scripts.");script=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",new ScriptRules(code).first("name",record.optString("name"))).put("code",code).put("sourceURL",record.optString("code_url")).put("sourceHost",host).put("sourcePage",record.optString("url"));}
            ScriptChanges.mode(script,mode);JSONArray next=new JSONArray();boolean found=false;for(int i=0;i<all.length();i++){JSONObject item=all.getJSONObject(i);if(item.optString("id").equals(script.optString("id"))){next.put(script);found=true;}else next.put(item);}if(!found)next.put(script);
            int revision=studio.optInt("_revision");if(!studio.has("chat"))studio.put("chat",new JSONObject().put("provider","venice").put("messages",new JSONArray()));if(!studio.has("history"))studio.put("history",new JSONArray());studio.put("scripts",next).put("_revision",revision+1);JSONObject saved=script;
            a.scripts.save(studio,revision,()->ok.accept(saved),fail);
        }catch(Exception e){fail.accept(e.getMessage());}
    }
    void update(JSONObject script,java.util.function.Consumer<JSONObject> ok,java.util.function.Consumer<String> fail){
        if(closed||!a.alive)return;
        worker.execute(()->{try{
            ScriptRules rules=new ScriptRules(script.getString("code"));String url=rules.first("downloadURL",script.optString("sourceURL")),metadata=rules.first("updateURL","");
            if(url.isEmpty()||url.equals("none")||metadata.equals("none"))throw new Exception("No update source configured, or updates disabled by metadata.");
            if(!metadata.isEmpty()){
                JSONObject response=net.request(UUID.randomUUID().toString(),new JSONObject().put("url",metadata),ScriptNetwork::https,200000);
                if(response.getInt("status")!=200)throw new Exception("Update metadata HTTP "+response.getInt("status"));
                ScriptRules update=new ScriptRules(response.getString("responseText"));
                if(update.first("version","").equals(rules.first("version",""))){a.runOnUiThread(()->{try{ok.accept(new JSONObject().put("changed",false));}catch(Exception ignored){}});return;}
            }
            JSONObject response=net.request(UUID.randomUUID().toString(),new JSONObject().put("url",url),ScriptNetwork::https,200000);
            if(response.getInt("status")!=200)throw new Exception("Update HTTP "+response.getInt("status"));String code=response.getString("responseText");ScriptRules next=new ScriptRules(code);
            JSONObject result=new JSONObject().put("code",code).put("version",next.first("version","")).put("oldVersion",rules.first("version","")).put("changed",!code.equals(script.getString("code"))).put("url",url);
            a.runOnUiThread(()->ok.accept(result));
        }catch(Exception e){a.runOnUiThread(()->fail.accept(e.getMessage()));}});
    }
    void checkUpdates(){
        if(!a.setting("scriptUpdateChecks",false)||System.currentTimeMillis()-a.prefs.getLong("scriptUpdateCheckAt",0)<86400000L)return;
        a.prefs.edit().putLong("scriptUpdateCheckAt",System.currentTimeMillis()).apply();
        JSONArray all=a.userScripts();
        for(int i=0;i<all.length();i++){JSONObject script=all.optJSONObject(i);if(script==null)continue;
            String id=script.optString("id");
            update(script,result->{try{JSONObject receipt=new JSONObject().put("checkedAt",System.currentTimeMillis()).put("available",result.optBoolean("changed")).put("version",result.optString("version"));a.prefs.edit().putString("scriptUpdate."+id,receipt.toString()).apply();if(result.optBoolean("changed"))a.toast("Update available: "+script.optString("name")+". Review it in Script Hub.");}catch(Exception ignored){}},
                error->{try{a.prefs.edit().putString("scriptUpdate."+id,new JSONObject().put("checkedAt",System.currentTimeMillis()).put("error",error).toString()).apply();}catch(Exception ignored){}});
        }
    }
    void close(){closed=true;generation++;cancelFetch();if(choices!=null)choices.dismiss();if(panel!=null)panel.dismiss();net.close();worker.shutdownNow();cache.clear();fetched.clear();}
}
