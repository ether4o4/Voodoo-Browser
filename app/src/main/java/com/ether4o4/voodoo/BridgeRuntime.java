package com.ether4o4.voodoo;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.webkit.WebView;
import androidx.webkit.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** All browser state and command dispatch are confined to the main thread. */
final class BridgeRuntime {
    private final Context context;
    private final SharedPreferences prefs;
    private final SecureKeys keys;
    private final Handler main=new Handler(Looper.getMainLooper());
    private MainActivity activity;
    private boolean visible;
    private BridgeClient client;
    private final Map<WebView,Page> pages=new IdentityHashMap<>();
    private final ArrayDeque<JSONObject> queue=new ArrayDeque<>();
    private JSONObject active;
    private WebView activeView;
    private Runnable timeout;
    private long waitDeadline;
    private static final class Page {
        final String id="tab-"+UUID.randomUUID();long version=1;String documentId="";String origin="";
        JavaScriptReplyProxy proxy;JavaScriptExecutionWorld world;ScriptHandler script;
    }
    BridgeRuntime(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("voodoo_bridge_v1",Context.MODE_PRIVATE);keys=new SecureKeys(context);
        try{JSONObject journal=new JSONObject(prefs.getString("commands","{}"));for(Iterator<String> i=journal.keys();i.hasNext();){JSONObject e=journal.getJSONObject(i.next());if(Arrays.asList("queued","running").contains(e.optString("status")))e.put("status","uncertain");}prefs.edit().putString("commands",journal.toString()).putBoolean("paused",true).commit();}catch(Exception ignored){}
    }
    void attach(MainActivity a){activity=a;}
    void detach(MainActivity a){if(activity==a){suspend();activity=null;pages.clear();}}
    void foreground(boolean value){visible=value;if(!value)suspend();}
    boolean paired(){return prefs.contains("deviceId")&&keys.has("bridge.deviceToken");}
    String relayUrl(){return prefs.getString("relayUrl","");}
    String deviceId(){return prefs.getString("deviceId","");}
    String accountId(){return prefs.getString("accountId","");}
    String allowedSites(){return prefs.getString("allowedSites","");}
    Set<String> origins()throws Exception{return BridgePolicy.sites(allowedSites());}
    void saveSites(String input)throws Exception{Set<String> sites=BridgePolicy.sites(input);setPaused(true);if(!prefs.edit().putString("allowedSites",String.join("\n",sites)).commit())throw new Exception("Could not save websites.");}
    String state(){try{return new JSONObject().put("paired",paired()).put("connected",client!=null&&client.connected()).put("paused",prefs.getBoolean("paused",true)).put("visible",visible).put("relayUrl",relayUrl()).put("deviceId",deviceId()).put("accountId",accountId()).put("allowedSites",allowedSites()).put("task",new JSONObject(prefs.getString("task","{}"))).toString();}catch(Exception e){return "{}";}}
    void configure(String relay,String device,String account,String token)throws Exception{
        String origin=BridgePolicy.origin(relay);if(!relay.replaceAll("/+$","").equals(origin))throw new Exception("Relay must be an HTTPS origin, without a path.");
        if(!device.matches("dev-[A-Za-z0-9_-]{16,100}")||token.length()<32)throw new Exception("Invalid pairing receipt.");
        disconnect();keys.put("bridge.deviceToken",token,origin);
        if(!prefs.edit().putString("relayUrl",origin).putString("deviceId",device).putString("accountId",account).putBoolean("paused",true).commit())throw new Exception("Could not save pairing.");connect();
    }
    void connect()throws Exception{if(!paired())throw new Exception("Pair this device first.");disconnect();client=new BridgeClient(this,relayUrl(),deviceId(),keys.get("bridge.deviceToken",relayUrl()));client.connect();}
    void disconnect(){suspend();if(client!=null)client.close();client=null;}
    void setPaused(boolean value){if(value)suspend();prefs.edit().putBoolean("paused",value).commit();}
    void revoke(java.util.function.Consumer<String> callback){
        disconnect();String device=deviceId();
        try{
            okhttp3.OkHttpClient http=new okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build();
            okhttp3.Request req=new okhttp3.Request.Builder().url(relayUrl()+"/device/revoke").header("X-Device-Id",device).header("Authorization","Bearer "+keys.get("bridge.deviceToken",relayUrl())).post(okhttp3.RequestBody.create("{}",okhttp3.MediaType.parse("application/json"))).build();
            http.newCall(req).enqueue(new okhttp3.Callback(){
                public void onFailure(okhttp3.Call c,java.io.IOException e){http.dispatcher().executorService().shutdown();main.post(()->callback.accept("Bridge disabled locally. Relay could not be reached; retry Revoke pairing to revoke its credential."));}
                public void onResponse(okhttp3.Call c,okhttp3.Response r){boolean ok=r.isSuccessful()||r.code()==401; r.close();http.dispatcher().executorService().shutdown();main.post(()->{
                    if(ok&&device.equals(deviceId())){try{keys.put("bridge.deviceToken","");}catch(Exception ignored){}prefs.edit().remove("relayUrl").remove("deviceId").remove("accountId").putBoolean("paused",true).commit();}
                    callback.accept(ok?"Pairing revoked.":"Bridge disabled locally. Relay revocation failed; retry Revoke pairing.");
                });}
            });
        }catch(Exception e){callback.accept("Bridge disabled locally. Could not revoke at the relay: "+e.getMessage());}
    }

    void transportLost(BridgeClient source){main.post(()->{if(client==source)suspend();});}
    private void send(JSONObject result){if(client!=null)client.send(result);}
    private void suspend(){prefs.edit().putBoolean("paused",true).commit();while(!queue.isEmpty())finishQueued(queue.remove(),"Cancelled before execution.");if(active!=null)finish(false,null,"Execution interrupted; inspect the page before retrying.",true);}
    private Page page(WebView view){Page p=pages.get(view);if(p==null){p=new Page();pages.put(view,p);}return p;}
    void install(WebView view){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD))return;
        Page p=page(view);p.world=WebViewCompat.getExecutionWorld(view,"voodoo-browser-controller");
        WebViewCompat.addWebMessageListener(view,"VoodooBrowserHost",Collections.singleton("*"),p.world,(v,m,o,isMain,reply)->{
            if(!isMain||activity==null||m.getData()==null||m.getData().length()>250000)return;
            try{String origin=BridgePolicy.origin(o.toString());if(!origin.equals(BridgePolicy.origin(activity.tabUrl(v))))return;
                JSONObject data=new JSONObject(m.getData());if(data.optString("type").equals("ready")){p.proxy=reply;p.documentId=data.getString("documentId");p.origin=origin;return;}
                if(active==null||activeView!=v||reply!=p.proxy||!data.optString("id").equals(active.optString("id"))||!data.optString("documentId").equals(p.documentId)||!origins().contains(origin))return;
                if(!data.optBoolean("ok")){finish(false,null,data.optString("error","Page action failed."),false);return;}
                JSONObject result=data.getJSONObject("data");
                if(active.optString("op").equals("wait_for")&&!result.optBoolean("matched")){
                    if(System.currentTimeMillis()<waitDeadline){String id=active.getString("id");main.postDelayed(()->{if(active!=null&&id.equals(active.optString("id")))dispatchPage();},150);return;}
                    finish(false,result,"Wait condition was not observed before the timeout.",false);return;
                }
                if(active.optString("op").equals("read_page"))result.put("tabId",p.id).put("documentVersion",p.version).put("url",v.getUrl()).put("title",v.getTitle());
                finish(true,result,null,false);
            }catch(Exception e){if(activeView==v&&active!=null)finish(false,null,"Page response was invalid.",true);}
        });
        p.script=WebViewCompat.addJavaScriptOnEvent(view,activity.asset("browser-bridge/controller.js"),WebViewCompat.INJECTION_EVENT_DOCUMENT_START,Collections.singleton("*"),p.world);
    }
    void onNavigation(WebView v){Page p=page(v);p.version++;p.proxy=null;p.documentId="";if(activeView==v&&active!=null&&!Arrays.asList("navigate","reload","open_tab").contains(active.optString("op")))finish(false,null,"Page navigated during the action; inspect its result.",true);}
    void pageFinished(WebView v){if(activeView!=v||active==null||!Arrays.asList("navigate","reload","open_tab").contains(active.optString("op")))return;try{allowed(v);finish(true,info(v).put("loaded",true),null,false);}catch(Exception e){finish(false,null,e.getMessage(),true);}}
    void pageFailed(WebView v){if(activeView==v&&active!=null)finish(false,null,"Page loading failed.",true);}
    void closed(WebView v){if(active!=null&&activeView==v)finish(false,null,"Tab closed during execution; inspect the result.",true);Page p=pages.remove(v);if(p!=null&&p.world!=null){if(p.script!=null)p.script.remove();WebViewCompat.removeWebMessageListener(v,p.world,"VoodooBrowserHost");}}
    void command(BridgeClient source,JSONObject c){main.post(()->{
        if(client!=source||!source.connected())return;
        try{BridgePolicy.validate(c,System.currentTimeMillis());String id=c.getString("id"),op=c.getString("op");
            JSONObject journal=new JSONObject(prefs.getString("commands","{}")),old=journal.optJSONObject(id);String hash=ScriptNetwork.digest(c.toString());
            if(old!=null){if(!hash.equals(old.optString("hash")))throw new Exception("Command ID reused with different contents.");if(Arrays.asList("queued","running").contains(old.optString("status")))return;send(new JSONObject().put("type","result").put("id",id).put("ok",false).put("replayed",true).put("previousStatus",old.optString("status")).put("error","Command was already handled; read task status and the current page. It was not repeated."));return;}
            if(op.equals("task_status")){send(new JSONObject().put("type","result").put("id",id).put("ok",true).put("data",new JSONObject(state())));return;}
            if(op.equals("cancel_task")){setPaused(true);send(new JSONObject().put("type","result").put("id",id).put("ok",true).put("data",new JSONObject().put("cancelled",true)));return;}
            if(prefs.getBoolean("paused",true))throw new Exception("Bridge is paused. Resume it on the phone.");
            if(!visible||activity==null)throw new Exception("Keep Voodoo visible to run browser actions.");
            if(queue.size()>=20)throw new Exception("Bridge queue is full.");
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD))throw new Exception("Update Android System WebView; isolated browser controls are unavailable.");
            record(c,"queued",hash);queue.add(new JSONObject(c.toString()));drain();
        }catch(Exception e){try{send(new JSONObject().put("type","result").put("id",c.optString("id")).put("ok",false).put("error",e.getMessage()));}catch(Exception ignored){}}
    });}
    private void record(JSONObject c,String status,String suppliedHash)throws Exception{
        JSONObject journal=new JSONObject(prefs.getString("commands","{}"));long now=System.currentTimeMillis();List<String> remove=new ArrayList<>();for(Iterator<String> i=journal.keys();i.hasNext();){String id=i.next();if(now-journal.getJSONObject(id).optLong("at")>86400000)remove.add(id);}for(String id:remove)journal.remove(id);
        String id=c.getString("id");if(journal.length()>=2000&&!journal.has(id))throw new Exception("Command history is full; retry later.");
        JSONObject old=journal.optJSONObject(id);String hash=suppliedHash!=null?suppliedHash:old==null?ScriptNetwork.digest(c.toString()):old.optString("hash");
        JSONObject entry=new JSONObject().put("id",id).put("op",c.getString("op")).put("status",status).put("at",now).put("hash",hash);journal.put(id,entry);
        if(!prefs.edit().putString("commands",journal.toString()).putString("task",entry.toString()).commit())throw new Exception("Could not save command status; execution stopped.");
    }
    private void finishQueued(JSONObject c,String reason){try{record(c,"cancelled",null);send(new JSONObject().put("type","result").put("id",c.getString("id")).put("ok",false).put("error",reason));}catch(Exception ignored){}}
    private void finish(boolean ok,JSONObject data,String error,boolean uncertain){
        if(active==null)return;JSONObject c=active;active=null;activeView=null;if(timeout!=null)main.removeCallbacks(timeout);timeout=null;
        try{record(c,uncertain?"uncertain":ok?"completed":"failed",null);}catch(Exception e){ok=false;uncertain=true;error="Could not persist the outcome. Inspect the page before retrying.";prefs.edit().putBoolean("paused",true).commit();}
        try{JSONObject result=new JSONObject().put("type","result").put("id",c.getString("id")).put("ok",ok).put("uncertain",uncertain);if(data!=null)result.put("data",data);if(error!=null)result.put("error",error);send(result);}catch(Exception ignored){}
        main.post(this::drain);
    }
    private void drain(){
        if(active!=null||queue.isEmpty())return;
        if(prefs.getBoolean("paused",true)||!visible||activity==null){while(!queue.isEmpty())finishQueued(queue.remove(),"Bridge is paused or Voodoo is not visible.");return;}
        active=queue.remove();try{
            BridgePolicy.validate(active,System.currentTimeMillis());record(active,"running",null);
            String currentId=active.getString("id");timeout=()->{if(active!=null&&currentId.equals(active.optString("id")))finish(false,null,"Command timed out; inspect the page before retrying.",true);};main.postDelayed(timeout,Math.max(1,active.getLong("deadline")-System.currentTimeMillis()));execute();
        }catch(Exception e){finish(false,null,e.getMessage(),false);}
    }
    private void allowed(WebView v)throws Exception{if(!origins().contains(BridgePolicy.origin(activity.tabUrl(v))))throw new Exception("Website is not allowed. Add it in Bridge settings on the phone.");}
    private WebView tab(String id)throws Exception{for(WebView v:activity.tabs)if(page(v).id.equals(id)){allowed(v);return v;}throw new Exception("Unknown tab; list tabs again.");}
    private JSONObject info(WebView v)throws Exception{return new JSONObject().put("tabId",page(v).id).put("url",activity.tabUrl(v)).put("title",activity.tabTitle(v)).put("selected",activity.current==v).put("documentVersion",page(v).version);}
    private void execute()throws Exception{
        String op=active.getString("op");MainActivity a=activity;
        if(op.equals("list_tabs")){JSONArray all=new JSONArray();for(WebView v:a.tabs)try{allowed(v);all.put(info(v));}catch(Exception ignored){}finish(true,new JSONObject().put("tabs",all).put("onlyAllowedSites",true),null,false);return;}
        if(op.equals("open_tab")){
            String url=active.getString("url");if(!origins().contains(BridgePolicy.origin(url)))throw new Exception("Destination website is not allowed.");
            if(a.tabs.size()>=8)throw new Exception("Close a tab first; the eight-tab limit has been reached.");activeView=a.newTab(url);if(activeView==null)throw new Exception("Could not open tab.");return;
        }
        activeView=tab(active.getString("tabId"));WebView v=activeView;Page p=page(v);
        if(active.has("documentVersion")&&active.getLong("documentVersion")!=p.version)throw new Exception("Document changed; read the page again.");
        if(op.equals("close_tab")){String id=p.id;activeView=null;a.closeTab(v);finish(true,new JSONObject().put("closed",id),null,false);return;}
        a.showTab(v);
        if(op.equals("select_tab")){finish(true,info(v),null,false);return;}
        if(op.equals("navigate")){String url=active.getString("url");if(!origins().contains(BridgePolicy.origin(url)))throw new Exception("Destination website is not allowed.");v.loadUrl(url);return;}
        if(op.equals("reload")){v.reload();return;}
        if(op.equals("screenshot")){
            int w=v.getWidth(),h=v.getHeight();if(w<1||h<1)throw new Exception("The tab is not visible.");float scale=Math.min(1f,1280f/Math.max(w,h));Bitmap b=Bitmap.createBitmap(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.scale(scale,scale);v.draw(canvas);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();if(out.size()>3000000)throw new Exception("Screenshot exceeds the size limit.");finish(true,new JSONObject().put("mimeType","image/png").put("base64",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)),null,false);return;
        }
        if(op.equals("wait_for"))waitDeadline=Math.min(active.getLong("deadline"),System.currentTimeMillis()+active.optInt("timeoutMs",5000));
        dispatchPage();
    }
    private void dispatchPage(){if(active==null)return;try{allowed(activeView);Page p=page(activeView);if(p.proxy==null||p.documentId.isEmpty())throw new Exception("Page controller is not ready. Reload the tab and read it again.");JSONObject c=new JSONObject(active.toString()).put("documentId",p.documentId).put("allowedOrigins",new JSONArray(origins()));if(c.optString("op").equals("scroll")&&!c.has("pixels"))c.put("pixels",600);p.proxy.postMessage(c.toString());}catch(Exception e){finish(false,null,e.getMessage(),false);}}
}
