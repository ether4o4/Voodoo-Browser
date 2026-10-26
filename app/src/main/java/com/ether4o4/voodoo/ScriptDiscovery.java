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
    private final Map<String,JSONArray> cache=new HashMap<>();
    private final Map<String,Long> fetched=new HashMap<>();
    ScriptDiscovery(MainActivity activity){a=activity;}
    void attach(WebView view){
        if(launcher!=null&&launcher.getParent()!=null)((android.view.ViewGroup)launcher.getParent()).removeView(launcher);
        String url=a.tabUrl(view);if(a.current!=view||a.local(url)||!a.webUrl(url))return;
        launcher=a.button("JS",()->show(view));launcher.setContentDescription("Discover and manage scripts for this website");launcher.setTextColor(MainActivity.SEAFOAM);launcher.setBackground(a.rounded(0xEB25262B,MainActivity.SEAFOAM));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(a.dp(48),a.dp(48),Gravity.BOTTOM|Gravity.END);p.setMargins(0,0,a.dp(10),a.dp(10));a.content.addView(launcher,p);
    }
    LinearLayout column(){LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(a.dp(16),a.dp(8),a.dp(16),a.dp(12));return box;}
    TextView text(String value){TextView t=new TextView(a);t.setText(value);t.setTextColor(MainActivity.INK);t.setTextSize(14);t.setPadding(0,a.dp(8),0,a.dp(8));return t;}
    void show(WebView view){
        String url=view.getUrl();if(!a.webUrl(url)||a.local(url)){a.toast("Script discovery is available on ordinary websites.");return;}
        String host=Uri.parse(url).getHost();LinearLayout box=column();ScrollView scroll=new ScrollView(a);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Magic scripts · "+host).setView(scroll).setNegativeButton("Close",null).setNeutralButton("Script Hub",(d,w)->a.openLocal(MainActivity.STUDIO+"#scripts")).create();
        LinearLayout actions=new LinearLayout(a);actions.addView(a.button("Refresh",()->{cache.remove(host);load(view,host,box,actions);}),new LinearLayout.LayoutParams(0,a.dp(48),1));actions.addView(a.button("Commands",()->a.scripts.menu(view)),new LinearLayout.LayoutParams(0,a.dp(48),1));
        load(view,host,box,actions);dialog.show();
    }
    void load(WebView view,String host,LinearLayout box,LinearLayout actions){
        box.removeAllViews();if(actions.getParent()!=null)((android.view.ViewGroup)actions.getParent()).removeView(actions);box.addView(actions);box.addView(text("Searching Greasy Fork… No script is installed or executed by searching."));
        worker.execute(()->{
            try{
                JSONArray records;
                if(cache.containsKey(host)&&System.currentTimeMillis()-fetched.getOrDefault(host,0L)<600000)records=cache.get(host);
                else {JSONObject response=net.request(UUID.randomUUID().toString(),new JSONObject().put("url","https://api.greasyfork.org/scripts/by-site/"+Uri.encode(host)+".json?language=all"),address->{if(!"api.greasyfork.org".equals(Uri.parse(address).getHost()))throw new Exception("Unexpected discovery redirect.");},4000000);if(response.getInt("status")!=200)throw new Exception("Greasy Fork HTTP "+response.getInt("status"));records=new JSONArray(response.getString("responseText"));cache.put(host,records);fetched.put(host,System.currentTimeMillis());}
                JSONArray result=records;a.runOnUiThread(()->{if(!a.alive)return;box.removeAllViews();box.addView(actions);if(!host.equals(Uri.parse(a.tabUrl(view)).getHost())){box.addView(text("Website changed. Close and reopen discovery for the current site."));return;}renderInstalled(a.tabUrl(view),box);box.addView(text(result.length()+" results · Source: Greasy Fork · Review source, matching rules and permissions before installation."));
                    if(result.length()==0)box.addView(text("No scripts found for this host. The website may still support custom scripts."));
                    for(int i=0;i<Math.min(100,result.length());i++){JSONObject record=result.optJSONObject(i);if(record==null)continue;box.addView(text(record.optString("name")+" · "+record.optString("version")+"\n"+record.optString("description")));box.addView(a.button("Preview source & permissions",()->preview(record,host,box)));}
                    box.addView(text("Discovery adapted from Magic Userscript+ by Magic (MIT). This is Voodoo's native engine, not a Tampermonkey extension."));
                });
            }catch(Exception e){a.runOnUiThread(()->{if(!a.alive)return;box.removeAllViews();box.addView(actions);box.addView(text("Discovery failed: "+e.getMessage()+"\nUse Refresh to retry. Installed scripts remain available."));renderInstalled(a.tabUrl(view),box);});}
        });
    }
    void renderInstalled(String url,LinearLayout box){
        String host=Uri.parse(url).getHost();
        JSONArray installed=a.userScripts();for(int i=0;i<installed.length();i++){JSONObject script=installed.optJSONObject(i);if(script==null)continue;try{ScriptRules rules=new ScriptRules(script.getString("code"));if(!rules.allows(url)&&!script.optString("sourceHost").equals(host))continue;box.addView(text("Installed · "+script.optString("name")+" · "+(script.optBoolean("enabled")?"enabled":"disabled")));LinearLayout row=new LinearLayout(a);row.addView(a.button(script.optBoolean("enabled")?"Disable":"Enable",()->change(script,false)),new LinearLayout.LayoutParams(0,a.dp(48),1));row.addView(a.button("Uninstall",()->new AlertDialog.Builder(a).setTitle("Uninstall "+script.optString("name")+"?").setMessage("Reload affected pages to remove running code. Version history and script values remain in backups.").setNegativeButton("Cancel",null).setPositiveButton("Uninstall",(d,w)->change(script,true)).show()),new LinearLayout.LayoutParams(0,a.dp(48),1));box.addView(row);}catch(Exception ignored){}}
    }
    void change(JSONObject selected,boolean remove){
        try{JSONObject studio=a.object(a.prefs.getString("studio","{}"));JSONArray all=studio.optJSONArray("scripts"),next=new JSONArray();if(all==null)return;
            for(int i=0;i<all.length();i++){JSONObject script=all.getJSONObject(i);if(script.optString("id").equals(selected.optString("id"))){if(remove)continue;script.put("enabled",!script.optBoolean("enabled"));}next.put(script);}
            int revision=studio.optInt("_revision");studio.put("scripts",next).put("_revision",revision+1);a.scripts.save(studio,revision,()->a.toast("Script "+(remove?"uninstalled":"updated")+". Reload the website to refresh execution."),a::toast);
        }catch(Exception e){a.toast(e.getMessage());}
    }
    void preview(JSONObject record,String host,LinearLayout parent){
        a.toast("Loading source for review…");worker.execute(()->{try{
            String address=record.getString("code_url");JSONObject response=net.request(UUID.randomUUID().toString(),new JSONObject().put("url",address),url->{String h=Uri.parse(url).getHost();if(!Arrays.asList("update.greasyfork.org","greasyfork.org").contains(h))throw new Exception("Untrusted code source host.");},200000);
            if(response.getInt("status")!=200)throw new Exception("Source HTTP "+response.getInt("status"));String code=response.getString("responseText");ScriptRules rules=new ScriptRules(code);
            a.runOnUiThread(()->{if(!a.alive)return;LinearLayout panel=column();panel.addView(text(rules.first("name",record.optString("name"))+"\nVersion: "+rules.first("version","unspecified")+"\nSource: "+address+"\nMatches: "+rules.matches+rules.includes+"\nExcludes: "+rules.excludes+rules.excludeGlobs+"\nGrants: "+rules.values("grant")+"\nConnect: "+rules.values("connect")+"\nRequires: "+rules.values("require")+"\nResources: "+rules.values("resource")+"\nCompatibility: "+(rules.problems().isEmpty()?"Supported API subset; runtime testing required.":String.join("; ",rules.problems()))));
                EditText source=new EditText(a);source.setText(code);source.setTextColor(MainActivity.INK);source.setTextSize(11);source.setTypeface(android.graphics.Typeface.MONOSPACE);source.setKeyListener(null);source.setMinLines(10);source.setMaxLines(20);panel.addView(source);
                ScrollView scroll=new ScrollView(a);scroll.addView(panel);
                new AlertDialog.Builder(a).setTitle("Review userscript").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Install disabled",(d,w)->install(record,host,code)).show();
            });
        }catch(Exception e){a.runOnUiThread(()->a.toast("Source preview failed: "+e.getMessage()));}});
    }
    void install(JSONObject record,String host,String code){
        try{JSONObject studio=a.object(a.prefs.getString("studio","{}"));JSONArray all=studio.optJSONArray("scripts");if(all==null)all=new JSONArray();for(int i=0;i<all.length();i++)if(record.optString("code_url").equals(all.getJSONObject(i).optString("sourceURL")))throw new Exception("Already installed. Use Script Hub to review updates.");
            JSONObject script=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",new ScriptRules(code).first("name",record.optString("name"))).put("code",code).put("enabled",false).put("sourceURL",record.optString("code_url")).put("sourceHost",host).put("sourcePage",record.optString("url"));
            all.put(script);int revision=studio.optInt("_revision");if(!studio.has("chat"))studio.put("chat",new JSONObject().put("provider","venice").put("messages",new JSONArray()));if(!studio.has("history"))studio.put("history",new JSONArray());studio.put("scripts",all).put("draft",script).put("_revision",revision+1);
            a.scripts.save(studio,revision,()->a.toast("Installed disabled. Review and enable in Script Hub."),a::toast);
        }catch(Exception e){a.toast(e.getMessage());}
    }
    void update(JSONObject script,java.util.function.Consumer<JSONObject> ok,java.util.function.Consumer<String> fail){
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
    void close(){net.close();worker.shutdownNow();}
}
