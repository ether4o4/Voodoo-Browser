package com.ether4o4.voodoo;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.webkit.WebView;
import androidx.webkit.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Execution/permission owner. Installed source remains in the existing Studio store. */
final class ScriptCoordinator {
    private final MainActivity a;
    private final SharedPreferences data;
    private final ScriptNetwork network=new ScriptNetwork();
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final Map<WebView,List<Registration>> registrations=new HashMap<>();
    private final List<Session> sessions=new ArrayList<>();
    private final Map<String,WebView> openedTabs=new HashMap<>();
    private final ManualScriptRuns<WebView,JSONObject> manual=new ManualScriptRuns<>();
    static final String[] API_NAMES={"GM","GM_addStyle","GM_addElement","GM_getValue","GM_setValue","GM_deleteValue","GM_listValues","GM_getValues","GM_setValues","GM_deleteValues","GM_addValueChangeListener","GM_removeValueChangeListener","GM_xmlhttpRequest","GM_getResourceText","GM_getResourceURL","GM_download","GM_setClipboard","GM_registerMenuCommand","GM_unregisterMenuCommand","GM_notification","GM_info","GM_log","GM_openInTab","console"};
    static final class Registration { JavaScriptExecutionWorld world;ScriptHandler script;boolean manual; }
    static final class Session {
        final WebView view;final String scriptId,hash,url;final JavaScriptReplyProxy proxy;
        final String token=UUID.randomUUID().toString();
        final Map<String,String> menus=new LinkedHashMap<>();
        final Set<String> requests=new HashSet<>();
        Session(WebView view,String id,String hash,String url,JavaScriptReplyProxy proxy){this.view=view;scriptId=id;this.hash=hash;this.url=url;this.proxy=proxy;}
    }
    ScriptCoordinator(MainActivity activity){a=activity;data=a.getSharedPreferences("script_runtime_v1",Context.MODE_PRIVATE);}
    boolean isolated(){return WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD);}
    JSONObject find(String id)throws Exception{JSONArray all=a.userScripts();for(int i=0;i<all.length();i++){JSONObject s=all.getJSONObject(i);if(id.equals(s.optString("id")))return s;}throw new Exception("Script is no longer installed.");}
    static String hash(JSONObject script)throws Exception{return ScriptNetwork.digest(script.getString("code"));}
    JSONObject values(String id){return a.object(data.getString("values."+id,"{}"));}
    String approved(String id){return data.getString("approval."+id,"");}
    void state(String id,String status,String reason){try{JSONObject s=new JSONObject().put("state",status).put("reason",reason).put("at",System.currentTimeMillis());data.edit().putString("state."+id,s.toString()).apply();}catch(Exception ignored){}}
    void log(String id,String url,String level,String text){
        try{JSONArray old=a.array(data.getString("logs","[]")),next=new JSONArray();next.put(new JSONObject().put("scriptId",id).put("url",url).put("level",level).put("text",text.substring(0,Math.min(4000,text.length()))).put("at",System.currentTimeMillis()));for(int i=0;i<Math.min(199,old.length());i++)next.put(old.get(i));data.edit().putString("logs",next.toString()).apply();if(level.equals("error"))state(id,"failed",text);}catch(Exception ignored){}
    }
    JSONObject status()throws Exception{
        JSONObject result=new JSONObject().put("isolatedWorlds",isolated()).put("logs",a.array(data.getString("logs","[]"))),states=new JSONObject();
        for(int i=0;i<a.userScripts().length();i++){JSONObject s=a.userScripts().getJSONObject(i);String id=s.optString("id");JSONObject info=a.object(data.getString("state."+id,"{}"));if(!s.optBoolean("enabled"))info.put("state","disabled");info.put("versions",a.array(data.getString("history."+id,"[]"))).put("blockedSites",a.array(data.getString("blocked."+id,"[]"))).put("health",a.object(data.getString("health."+id,"{}"))).put("update",a.object(a.prefs.getString("scriptUpdate."+id,"{}")));states.put(id,info);}
        return result.put("scripts",states);
    }
    void validateStore(JSONObject studio)throws Exception{
        if(studio.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>5*1024*1024)throw new Exception("Studio storage exceeds 5 MB. Export a backup and remove unused content.");
        JSONArray all=studio.getJSONArray("scripts");if(all.length()>100)throw new Exception("At most 100 scripts are supported.");Set<String> ids=new HashSet<>();
        for(int i=0;i<all.length();i++){JSONObject s=all.getJSONObject(i);String id=s.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id))throw new Exception("Invalid or duplicate script ID.");ScriptRules r=new ScriptRules(s.getString("code"));if(s.optBoolean("enabled")&&!r.problems().isEmpty())throw new Exception(String.join("\n",r.problems()));}
    }
    /** Called only through the internal asset-origin bridge; never by a website. */
    void save(JSONObject studio,Integer expected,Runnable success,java.util.function.Consumer<String> error){
        try{
            validateStore(studio);JSONObject previous=a.object(a.prefs.getString("studio","{}"));
            if(expected!=null&&previous.optInt("_revision")!=expected)throw new Exception("Workspace changed in another tab. Reload before saving.");
            JSONArray scripts=studio.getJSONArray("scripts");List<JSONObject> approvals=new ArrayList<>();
            for(int i=0;i<scripts.length();i++){JSONObject s=scripts.getJSONObject(i);if(s.optBoolean("enabled")&&!approved(s.getString("id")).equals(hash(s)))approvals.add(s);}
            Runnable commit=()->workers.execute(()->{
                try{
                    for(JSONObject s:approvals)prepare(s);
                    a.runOnUiThread(()->validateBatch(approvals,0,()->{try{
                        if(expected!=null&&a.object(a.prefs.getString("studio","{}")).optInt("_revision")!=expected)throw new Exception("Workspace changed while reviewing. Reload and retry.");
                        SharedPreferences.Editor edit=data.edit();
                        JSONArray before=previous.optJSONArray("scripts");
                        if(before!=null)for(int i=0;i<before.length();i++){JSONObject old=before.getJSONObject(i);for(int j=0;j<scripts.length();j++){JSONObject next=scripts.getJSONObject(j);if(old.optString("id").equals(next.optString("id"))&&!old.optString("code").equals(next.optString("code"))){String key="history."+old.getString("id");JSONArray versions=a.array(data.getString(key,"[]")),history=new JSONArray();history.put(new JSONObject(old.toString()).put("savedAt",System.currentTimeMillis()));for(int n=0;n<Math.min(19,versions.length());n++)history.put(versions.get(n));edit.putString(key,history.toString());}}}
                        for(JSONObject s:approvals)edit.putString("approval."+s.getString("id"),hash(s));
                        if(!edit.commit())throw new Exception("Could not save script permissions.");
                        if(!a.prefs.edit().putString("studio",studio.toString()).commit())throw new Exception("Could not save scripts.");
                        if(!String.valueOf(previous.optJSONArray("scripts")).equals(scripts.toString()))for(WebView v:a.tabs)install(v);success.run();
                    }catch(Exception e){error.accept(e.getMessage());}},error));
                }catch(Exception e){a.runOnUiThread(()->error.accept("Script preparation failed: "+e.getMessage()));}
            });
            if(approvals.isEmpty()){commit.run();return;}
            StringBuilder review=new StringBuilder("These scripts can read and change matching webpages. Review the source before approving.\n");
            for(JSONObject s:approvals){ScriptRules r=new ScriptRules(s.getString("code"));review.append("\n").append(r.first("name","Script")).append("\nSites: ").append(r.matches).append(r.includes).append("\nGrants: ").append(r.values("grant")).append("\nNetwork: ").append(r.values("connect")).append("\nDependencies: ").append(r.values("require")).append(r.values("resource")).append("\n");}
            new AlertDialog.Builder(a).setTitle("Approve script execution?").setMessage(review.toString()).setNegativeButton("Cancel",(d,w)->error.accept("Script approval cancelled.")).setPositiveButton("Approve",(d,w)->commit.run()).setOnCancelListener(d->error.accept("Script approval cancelled.")).show();
        }catch(Exception e){error.accept(e.getMessage());}
    }
    /** Compile only: offscreen blank WebView has no bridges, network, storage or userscript execution. */
    void validateBatch(List<JSONObject> scripts,int index,Runnable ok,java.util.function.Consumer<String> fail){
        if(index>=scripts.size()){ok.run();return;}
        try{
            String source=bundle(scripts.get(index));WebView validator=new WebView(a);validator.getSettings().setJavaScriptEnabled(true);validator.getSettings().setBlockNetworkLoads(true);validator.getSettings().setAllowFileAccess(false);validator.getSettings().setAllowContentAccess(false);
            boolean[] done={false};android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable timeout=()->{if(done[0])return;done[0]=true;validator.destroy();fail.accept("Syntax validation timed out; nothing was enabled.");};handler.postDelayed(timeout,10000);
            validator.setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(WebView v,String url){
                if(done[0])return;v.evaluateJavascript("(function(){try{new Function("+JSONObject.quote(source)+");return 'ok';}catch(e){return e.name+': '+e.message;}})()",value->{if(done[0])return;done[0]=true;handler.removeCallbacks(timeout);validator.destroy();String result=a.decode(value);if(!"ok".equals(result)){fail.accept("Script syntax validation: "+result);return;}validateBatch(scripts,index+1,ok,fail);});
            }});validator.loadDataWithBaseURL("https://validation.invalid/","<!doctype html><title>Syntax validation</title>","text/html","UTF-8",null);
        }catch(Exception e){fail.accept("Syntax validation failed: "+e.getMessage());}
    }
    void prepare(JSONObject script)throws Exception{
        ScriptRules rules=new ScriptRules(script.getString("code"));JSONArray cache=new JSONArray();int total=0;
        for(String type:Arrays.asList("require","resource"))for(String item:rules.values(type)){
            String name="",url=item;if(type.equals("resource")){String[] p=item.split("\\s+",2);if(p.length!=2)throw new Exception("Invalid @resource.");name=p[0];url=p[1];}
            String digestFragment=new java.net.URI(url).getFragment();String address=url.split("#",2)[0];String requestId=UUID.randomUUID().toString();
            JSONObject r=network.request(requestId,new JSONObject().put("url",address),ScriptNetwork::https,1000000);if(r.getInt("status")!=200)throw new Exception("Dependency returned HTTP "+r.getInt("status"));
            byte[] bytes=Base64.getDecoder().decode(r.getString("base64"));total+=bytes.length;if(total>3000000)throw new Exception("Dependencies exceed 3 MB.");
            String digest=ScriptNetwork.digest(bytes);
            if(digestFragment!=null){String expected=digestFragment.replaceFirst("^sha256[=-]","");String base64=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));if(!digestFragment.startsWith("sha256")||(!digest.equalsIgnoreCase(expected)&&!base64.equals(expected)))throw new Exception("Dependency integrity mismatch or unsupported hash: "+address);}
            cache.put(r.put("type",type).put("name",name).put("source",url).put("sha256",digest));
        }
        String key="deps."+hash(script);if(!data.edit().putString(key,cache.toString()).commit())throw new Exception("Could not cache dependencies.");
    }
    String bundle(JSONObject script)throws Exception{
        JSONObject config=new JSONObject().put("code",script.getString("code")).put("apiNames",new JSONArray(Arrays.asList(API_NAMES)));
        JSONArray cached=a.array(data.getString("deps."+hash(script),"[]"));StringBuilder requires=new StringBuilder();
        for(int i=0;i<cached.length();i++){JSONObject r=cached.getJSONObject(i);if(r.getString("type").equals("require"))requires.append(r.getString("responseText")).append("\n;\n");}
        return "(function(){\n"+a.engine+"\n"+a.asset("tools/runtime.js")+"\nVoodooRuntime("+config+",function("+String.join(",",API_NAMES)+"){\n"+requires+script.getString("code")+"\n});})();";
    }
    void install(WebView view){
        remove(view,false);List<Registration> list=new ArrayList<>();registrations.put(view,list);
        if(!a.setting("scripts",true))return;
        JSONArray scripts=a.userScripts();JSONObject one=manual.get(view);if(one!=null){JSONArray combined=new JSONArray();for(int n=0;n<scripts.length();n++){JSONObject s=scripts.optJSONObject(n);if(s!=null&&!s.optString("id").equals(one.optString("id")))combined.put(s);}combined.put(one);scripts=combined;}
        for(int i=0;i<scripts.length();i++){JSONObject script=scripts.optJSONObject(i);if(script==null)continue;String id=script.optString("id");
            if(!script.optBoolean("enabled")){state(id,"disabled","");continue;}
            try{
                ScriptRules rules=new ScriptRules(script.getString("code"));String sourceHash=hash(script);
                if(!rules.problems().isEmpty())throw new Exception(String.join("; ",rules.problems()));
                if(!isolated()){state(id,"incompatible","Update Android System WebView: isolated execution worlds are unavailable. No privileged page-world fallback.");continue;}
                boolean temporary=one!=null&&id.equals(one.optString("id"));
                if(!temporary&&!sourceHash.equals(approved(id))){state(id,"blocked","Review and save this script to approve execution.");continue;}
                JavaScriptExecutionWorld world=WebViewCompat.getExecutionWorld(view,"voodoo-script-"+id);
                WebViewCompat.addWebMessageListener(view,"VoodooGM",rules.origins(),world,(v,message,origin,main,proxy)->receive(v,id,sourceHash,message.getData(),origin,main,proxy));
                Registration reg=new Registration();reg.world=world;reg.manual=temporary;reg.script=WebViewCompat.addJavaScriptOnEvent(view,bundle(script),WebViewCompat.INJECTION_EVENT_DOCUMENT_START,rules.origins(),world);list.add(reg);state(id,"enabled","Waiting for a matching document.");
            }catch(Exception e){state(id,"incompatible",e.getMessage());log(id,"","error",e.getMessage());}
        }
    }
    void remove(WebView view,boolean closing){
        List<Registration> old=registrations.remove(view);if(old!=null)for(Registration r:old){if(r.script!=null)r.script.remove();WebViewCompat.removeWebMessageListener(view,r.world,"VoodooGM");}
        for(Session s:new ArrayList<>(sessions))if(s.view==view){send(s,new JSONObject(), "stop");for(String request:s.requests)network.cancel(request);sessions.remove(s);}
        if(closing){manual.remove(view);openedTabs.values().removeIf(v->v==view);}
    }
    void navigating(WebView view){
        if(manual.navigating(view))install(view);
        for(Session s:new ArrayList<>(sessions))if(s.view==view){for(String request:s.requests)network.cancel(request);sessions.remove(s);}
    }
    void finished(WebView view){if(manual.finished(view)){List<Registration> list=registrations.get(view);if(list!=null)for(Registration r:list)if(r.manual&&r.script!=null){r.script.remove();r.script=null;}}}
    void runOnce(WebView view,JSONObject script,Runnable ok,java.util.function.Consumer<String> fail){
        try{if(!isolated())throw new Exception("Update Android System WebView for isolated script execution.");ScriptRules rules=new ScriptRules(script.getString("code"));if(!rules.problems().isEmpty())throw new Exception(String.join("; ",rules.problems()));JSONObject temporary=new JSONObject(script.toString()).put("enabled",true);
            new AlertDialog.Builder(a).setTitle("Run once after reloading?").setMessage("This reloads the target page and runs the reviewed script once. Unsaved website edits may be lost. Network and other declared permissions will be available.").setNegativeButton("Cancel",(d,w)->fail.accept("Cancelled.")).setPositiveButton("Run once",(d,w)->workers.execute(()->{try{prepare(temporary);a.runOnUiThread(()->validateBatch(Collections.singletonList(temporary),0,()->{if(!a.alive||!a.tabs.contains(view)){fail.accept("Target tab was closed.");return;}manual.schedule(view,temporary);install(view);view.reload();ok.run();},fail));}catch(Exception e){a.runOnUiThread(()->fail.accept(e.getMessage()));}})).show();
        }catch(Exception e){fail.accept(e.getMessage());}
    }
    Session session(WebView v,String id,String hash,String url,JavaScriptReplyProxy proxy){
        for(Session s:sessions)if(s.proxy==proxy&&s.scriptId.equals(id))return s;
        Session s=new Session(v,id,hash,url,proxy);sessions.add(s);return s;
    }
    boolean siteBlocked(String id,String host){JSONArray sites=a.array(data.getString("blocked."+id,"[]"));for(int i=0;i<sites.length();i++)if(ScriptRules.canonicalHost(host).equals(ScriptRules.canonicalHost(sites.optString(i))))return true;return false;}
    void receive(WebView view,String scriptId,String sourceHash,String raw,Uri origin,boolean main,JavaScriptReplyProxy proxy){
        String requestId="";try{
            if(raw==null||raw.length()>1200000)throw new Exception("Invalid script request.");JSONObject req=new JSONObject(raw);requestId=req.optString("id");String url=req.getString("url"),op=req.getString("op");
            JSONObject script=manual.get(view);if(script==null||!scriptId.equals(script.optString("id")))script=find(scriptId);
            if(!a.setting("scripts",true)||!script.optBoolean("enabled")||!sourceHash.equals(hash(script)))throw new Exception("Script disabled, changed or removed. Reload the page.");
            ScriptRules rules=new ScriptRules(script.getString("code"));Uri actual=Uri.parse(url);
            if(!Objects.equals(actual.getScheme(),origin.getScheme())||!Objects.equals(actual.getHost(),origin.getHost())||actual.getPort()!=origin.getPort()||!rules.allows(url)||(rules.fields.containsKey("noframes")&&!main)||siteBlocked(scriptId,actual.getHost()))throw new Exception("Script blocked for this frame or website.");
            Session s=session(view,scriptId,sourceHash,url,proxy);String grant=op;
            if(op.equals("ready")||op.equals("health")||op.equals("bye")||op.equals("log"))grant="info";
            if(op.equals("abort"))grant="xmlhttpRequest";if(op.equals("menu"))grant="registerMenuCommand";if(op.equals("unmenu"))grant="unregisterMenuCommand";if(op.equals("closeTab"))grant="openInTab";
            boolean bulk=op.equals("setValue")&&rules.grant("setValues")||op.equals("deleteValue")&&rules.grant("deleteValues")||op.equals("getValue")&&rules.grant("getValues")||op.equals("abort")&&rules.grant("download");
            if(!rules.grant(grant)&&!bulk)throw new Exception("Missing @grant GM_"+grant);
            Object result=true;
            switch(op){
                case "ready":{JSONObject resources=new JSONObject();JSONArray cached=a.array(data.getString("deps."+sourceHash,"[]"));for(int i=0;i<cached.length();i++){JSONObject r=cached.getJSONObject(i);if(r.getString("type").equals("resource"))resources.put(r.getString("name"),r);}result=new JSONObject().put("values",values(scriptId)).put("resources",resources);break;}
                case "getValue":{JSONObject vals=values(scriptId);String key=req.getString("key");result=new JSONObject().put("found",vals.has(key)).put("value",vals.opt(key));break;}
                case "listValues":result=values(scriptId);break;
                case "setValue":case "deleteValue":{
                    String key=req.getString("key");if(key.length()>512)throw new Exception("Storage key too long.");JSONObject vals=values(scriptId);Object old=vals.opt(key);boolean deleted=op.equals("deleteValue");if(deleted)vals.remove(key);else vals.put(key,req.get("value"));if(vals.toString().length()>1000000)throw new Exception("Script storage exceeds 1 MB.");if(!data.edit().putString("values."+scriptId,vals.toString()).commit())throw new Exception("Script storage write failed.");
                    for(Session other:sessions)if(other.scriptId.equals(scriptId))send(other,new JSONObject().put("key",key).put("oldValue",old).put("value",vals.opt(key)).put("deleted",deleted).put("remote",other!=s),"value");break;
                }
                case "xmlhttpRequest":{
                    JSONObject details=req.getJSONObject("details");String token=s.token+":"+requestId,replyId=requestId;s.requests.add(token);network.reserve(token);
                    workers.execute(()->{try{JSONObject response=network.request(token,details,address->{if(!rules.connects(address,url))throw new Exception("Blocked by @connect: "+Uri.parse(address).getHost());},4000000);a.runOnUiThread(()->{if(sessions.contains(s))a.reply(proxy,replyId,response,null);});}catch(Exception e){a.runOnUiThread(()->a.reply(proxy,replyId,null,e.getMessage()));}finally{a.runOnUiThread(()->s.requests.remove(token));}});return;
                }
                case "abort":{String token=s.token+":"+req.getString("requestId");if(s.requests.contains(token))network.cancel(token);break;}
                case "log":log(scriptId,url,req.optString("level","info"),req.optString("text"));break;
                case "health":{
                    String status=req.optString("state","enabled");double duration=Math.max(0,Math.min(86400000,req.optDouble("duration",0)));
                    JSONObject health=a.object(data.getString("health."+scriptId,"{}"));health.put("runs",health.optInt("runs")+1).put("failedRuns",health.optInt("failedRuns")+(status.equals("failed")?1:0)).put("lastSynchronousDurationMs",duration);
                    JSONArray sites=health.optJSONArray("sites");if(sites==null)sites=new JSONArray();boolean known=false;for(int i=0;i<sites.length();i++)if(actual.getHost().equals(sites.optString(i)))known=true;if(!known&&sites.length()<20)sites.put(actual.getHost());health.put("sites",sites);data.edit().putString("health."+scriptId,health.toString()).apply();
                    state(scriptId,status,"Last synchronous execution: "+duration+" ms · "+actual.getHost());break;
                }
                case "menu":if(s.menus.size()>=30)throw new Exception("Menu limit reached.");s.menus.put(req.getString("command"),req.getString("name"));break;
                case "unmenu":s.menus.remove(req.getString("command"));break;
                case "setClipboard":{String text=req.getString("text"),replyId=requestId;new AlertDialog.Builder(a).setTitle("Script clipboard request").setMessage(rules.first("name","Script")+" wants to copy "+text.length()+" characters.").setNegativeButton("Cancel",(d,w)->a.reply(proxy,replyId,null,"Clipboard request declined.")).setPositiveButton("Copy",(d,w)->{if(!sessions.contains(s))return;((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Userscript",text));a.reply(proxy,replyId,true,null);}).show();return;}
                case "notification":new AlertDialog.Builder(a).setTitle("Script: "+req.optString("title")).setMessage(req.optString("text")).setPositiveButton("Dismiss",null).show();break;
                case "download":{JSONObject d=req.getJSONObject("details");String target=d.getString("url");if(!rules.connects(target,url))throw new Exception("Download blocked by @connect.");String name=d.optString("name","download").replaceAll("[/\\\\]","_");String replyId=requestId;new AlertDialog.Builder(a).setTitle("Script download (4 MB maximum)").setMessage(name+"\n"+target).setNegativeButton("Cancel",(dialog,w)->a.reply(proxy,replyId,null,"Download declined.")).setPositiveButton("Download",(dialog,w)->{if(sessions.contains(s))download(s,replyId,name,target,rules,url);}).show();return;}
                case "openInTab":{String target=req.getString("target"),replyId=requestId;if(!a.webUrl(target)||a.local(target))throw new Exception("Only ordinary websites can be opened.");new AlertDialog.Builder(a).setTitle("Script wants to open a tab").setMessage(target).setNegativeButton("Cancel",(d,w)->a.reply(proxy,replyId,null,"Tab declined.")).setPositiveButton("Open",(d,w)->{if(!sessions.contains(s))return;WebView tab=a.newTab(a.normalize(target),req.optBoolean("active",true));if(tab==null){a.reply(proxy,replyId,null,"Tab limit reached.");return;}String tabId=s.token+":"+UUID.randomUUID();openedTabs.put(tabId,tab);a.reply(proxy,replyId,tabId,null);}).show();return;}
                case "closeTab":{String tabId=req.getString("tabId");if(!tabId.startsWith(s.token+":"))throw new Exception("This script instance does not own that tab.");WebView tab=openedTabs.remove(tabId);if(tab!=null)a.closeTab(tab);break;}
                case "bye":for(String request:s.requests)network.cancel(request);s.requests.clear();sessions.remove(s);break;
                default:throw new Exception("Unsupported script operation: "+op);
            }
            a.reply(proxy,requestId,result,null);
        }catch(Exception e){log(scriptId,"","error",e.getMessage()==null?"Script request failed.":e.getMessage());a.reply(proxy,requestId,null,e.getMessage());}
    }
    void send(Session s,JSONObject value,String event){try{s.proxy.postMessage(value.put("event",event).toString());}catch(Exception ignored){}}
    void download(Session session,String replyId,String name,String target,ScriptRules rules,String page){
        String token=session.token+":"+replyId;session.requests.add(token);network.reserve(token);
        workers.execute(()->{Uri file=null;try{
            JSONObject response=network.request(token,new JSONObject().put("url",target),address->{if(!rules.connects(address,page))throw new Exception("Download redirect blocked by @connect.");},4000000);
            if(response.getInt("status")>=400)throw new Exception("Download HTTP "+response.getInt("status"));
            ContentValues metadata=new ContentValues();metadata.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);metadata.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,response.getString("contentType"));metadata.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);metadata.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
            file=a.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,metadata);if(file==null)throw new Exception("Cannot create download.");
            try(java.io.OutputStream out=a.getContentResolver().openOutputStream(file)){if(out==null)throw new Exception("Cannot write download.");out.write(Base64.getDecoder().decode(response.getString("base64")));}
            ContentValues ready=new ContentValues();ready.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0);a.getContentResolver().update(file,ready,null,null);
            a.runOnUiThread(()->a.reply(session.proxy,replyId,true,null));
        }catch(Exception e){if(file!=null)a.getContentResolver().delete(file,null,null);a.runOnUiThread(()->a.reply(session.proxy,replyId,null,e.getMessage()));}finally{a.runOnUiThread(()->session.requests.remove(token));}});
    }
    void menu(WebView view){List<Session> owners=new ArrayList<>();List<String> ids=new ArrayList<>(),labels=new ArrayList<>();for(Session s:sessions)if(s.view==view)for(Map.Entry<String,String> m:s.menus.entrySet()){owners.add(s);ids.add(m.getKey());labels.add(m.getValue());}if(labels.isEmpty()){a.toast("No userscript menu commands on this page.");return;}new AlertDialog.Builder(a).setTitle("Userscript commands").setItems(labels.toArray(new String[0]),(d,i)->{try{send(owners.get(i),new JSONObject().put("command",ids.get(i)),"menu");}catch(Exception ignored){}}).show();}
    void blockSite(String id,String host,boolean blocked)throws Exception{find(id);if(host==null||!host.matches("[A-Za-z0-9.-]+"))throw new Exception("Invalid host.");host=ScriptRules.canonicalHost(host);JSONArray before=a.array(data.getString("blocked."+id,"[]")),after=new JSONArray();for(int i=0;i<before.length();i++)if(!host.equals(ScriptRules.canonicalHost(before.optString(i))))after.put(ScriptRules.canonicalHost(before.optString(i)));if(blocked)after.put(host);if(!data.edit().putString("blocked."+id,after.toString()).commit())throw new Exception("Could not save site policy.");for(WebView view:a.tabs)install(view);}
    JSONObject backup()throws Exception{JSONObject all=new JSONObject();for(int i=0;i<a.userScripts().length();i++){String id=a.userScripts().getJSONObject(i).getString("id");all.put(id,new JSONObject().put("values",values(id)).put("history",a.array(data.getString("history."+id,"[]"))).put("blocked",a.array(data.getString("blocked."+id,"[]"))));}return all;}
    void rollback(String id,int index,Runnable ok,java.util.function.Consumer<String> fail){
        try{JSONArray versions=a.array(data.getString("history."+id,"[]"));JSONObject old=new JSONObject(versions.getJSONObject(index).toString()).put("enabled",false);
            JSONObject studio=a.object(a.prefs.getString("studio","{}"));JSONArray all=studio.getJSONArray("scripts");boolean found=false;
            for(int i=0;i<all.length();i++)if(all.getJSONObject(i).optString("id").equals(id)){all.put(i,old);found=true;}
            if(!found)throw new Exception("Script no longer installed.");int revision=studio.optInt("_revision");studio.put("_revision",revision+1);save(studio,revision,ok,fail);
        }catch(Exception e){fail.accept(e.getMessage());}
    }
    void restoreData(JSONObject runtime)throws Exception{
        if(runtime==null)return;SharedPreferences.Editor edit=data.edit();
        for(Iterator<String> it=runtime.keys();it.hasNext();){String id=it.next();find(id);JSONObject entry=runtime.getJSONObject(id),values=entry.getJSONObject("values");if(values.toString().length()>1000000)throw new Exception("Script values too large.");JSONArray history=entry.optJSONArray("history");if(history==null)history=new JSONArray();if(history.length()>20)throw new Exception("Too many script versions.");for(int i=0;i<history.length();i++)new ScriptRules(history.getJSONObject(i).getString("code"));edit.putString("values."+id,values.toString()).putString("history."+id,history.toString()).putString("blocked."+id,entry.optJSONArray("blocked")==null?"[]":entry.getJSONArray("blocked").toString()).remove("approval."+id);}
        if(!edit.commit())throw new Exception("Could not restore script values.");
    }
    void close(){for(WebView v:new ArrayList<>(registrations.keySet()))remove(v,true);network.close();workers.shutdownNow();}
}
