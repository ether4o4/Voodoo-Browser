package com.ether4o4.voodoo;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Chrome extension runtime for Voodoo's WebView tabs.
 *
 * Extension files are served from https://{id}.ext.voodoo/ by request interception.
 * Each extension gets a persistent hidden background page (MV2 background page/scripts
 * or the MV3 service worker run as a page script), content scripts in its own AndroidX
 * isolated world, and chrome.* APIs bridged to native code. Native code identifies the
 * calling extension from the registered world/listener and the verified frame origin.
 */
final class ExtensionHost {
    static final String SUFFIX=".ext.voodoo";
    static final String STORE_CRX="https://clients2.google.com/service/update2/crx?response=redirect&prodversion=131.0.6778.0&acceptformat=crx2,crx3&x=id%3D";

    final MainActivity a;
    final File codeRoot,dataRoot;
    final SharedPreferences prefs;
    final String api;
    final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"voodoo-extensions");t.setDaemon(true);return t;});
    final ExecutorService net=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"voodoo-extension-net");t.setDaemon(true);return t;});
    final Map<String,Ext> exts=new LinkedHashMap<>();
    volatile Map<String,Ext> snapshot=Collections.emptyMap();
    final Map<WebView,Integer> tabIds=new HashMap<>();
    final Map<WebView,List<ScriptHandler>> tabScripts=new HashMap<>();
    final Map<WebView,Set<JavaScriptExecutionWorld>> tabWorlds=new HashMap<>();
    final Map<WebView,Boolean> pageListener=new HashMap<>();
    final Map<WebView,String> status=new HashMap<>();
    final Map<WebView,String> topUrls=new ConcurrentHashMap<>();
    final List<Endpoint> endpoints=new ArrayList<>();
    final Map<String,Pending> pending=new HashMap<>();
    final Map<String,Port> ports=new HashMap<>();
    final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    int nextTabId=1,nextFrameId=1;
    boolean closed;

    static final class Ext {
        String id;File dir;JSONObject manifest,messages;List<String> hosts,apis,war;volatile boolean enabled;String source;boolean isolatedWorld;
        String contentBundle,mainBundle,pageBundle;volatile ExtensionRules rules=ExtensionRules.EMPTY;
        JSONObject local,sync,session=new JSONObject(),state;
        WebView background,offscreen;AlertDialog popup;JSONObject lifecycle;
        String origin(){return "https://"+id+SUFFIX;}
        String base(){return origin()+"/";}
        String name(){return manifest.optString("name",id);}
        JSONObject action(){JSONObject x=manifest.optJSONObject("action");if(x==null)x=manifest.optJSONObject("browser_action");if(x==null)x=manifest.optJSONObject("page_action");return x==null?new JSONObject():x;}
        boolean has(String permission){return apis.contains(permission);}
    }
    static final class Endpoint {
        Ext ext;WebView view;JavaScriptReplyProxy proxy;String kind,url="";int frameId;long seen=System.nanoTime();
    }
    static final class Pending {
        JavaScriptReplyProxy proxy;String replyId,op;Set<Endpoint> waiting=new HashSet<>();JSONArray results=new JSONArray();boolean done;Endpoint single;String error;
    }
    static final class Port {Endpoint opener;Set<Endpoint> receivers=new HashSet<>();}

    ExtensionHost(MainActivity activity){
        a=activity;codeRoot=new File(a.getFilesDir(),"extensions");dataRoot=new File(a.getFilesDir(),"extension-data");codeRoot.mkdirs();dataRoot.mkdirs();
        prefs=a.getSharedPreferences("extensions_v1",Context.MODE_PRIVATE);api=a.asset("extensions/chrome-api.js");
        JSONObject installed=a.object(prefs.getString("installed","{}"));
        for(Iterator<String> it=installed.keys();it.hasNext();){String id=it.next();try{load(id,installed.getJSONObject(id));}catch(Exception e){a.toast("Extension "+id+" could not load: "+e.getMessage());}}
        publish();
    }
    boolean supported(){return WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)&&WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);}

    // ---------- loading ----------
    Ext load(String id,JSONObject info)throws Exception{
        if(!ExtensionPackage.validId(id))throw new Exception("Invalid extension ID.");
        Ext e=new Ext();e.id=id;e.dir=new File(codeRoot,id);e.enabled=info.optBoolean("enabled",true);e.source=info.optString("source","file");
        JSONObject raw=ExtensionPackage.manifest(ExtensionPackage.read(new File(e.dir,"manifest.json")));
        e.messages=ExtensionPackage.messages(e.dir,raw,Locale.getDefault());e.manifest=new JSONObject(ExtensionPackage.localize(raw.toString(),e.messages).replace("__MSG_@@extension_id__",id));
        e.hosts=ExtensionPackage.hostPermissions(e.manifest);e.apis=ExtensionPackage.apiPermissions(e.manifest);e.war=ExtensionPackage.webAccessible(e.manifest);
        e.state=a.object(prefs.getString("state."+id,"{}"));
        e.local=readJson(new File(dataRoot,id+"/local.json"));e.sync=readJson(new File(dataRoot,id+"/sync.json"));
        exts.put(id,e);buildBundles(e);rebuildRules(e);return e;
    }
    JSONObject readJson(File f){try{return f.isFile()?new JSONObject(ExtensionPackage.read(f)):new JSONObject();}catch(Exception ex){return new JSONObject();}}
    void publish(){snapshot=Collections.unmodifiableMap(new LinkedHashMap<>(exts));}
    void saveState(Ext e){prefs.edit().putString("state."+e.id,e.state.toString()).apply();}
    void saveIndex(){try{JSONObject all=new JSONObject();for(Ext e:exts.values())all.put(e.id,new JSONObject().put("enabled",e.enabled).put("source",e.source));prefs.edit().putString("installed",all.toString()).commit();}catch(Exception ignored){}}

    String file(Ext e,String path)throws IOException{
        File f=new File(e.dir,path.replaceFirst("^/+",""));if(!f.getCanonicalPath().startsWith(e.dir.getCanonicalPath()+File.separator)||!f.isFile())throw new FileNotFoundException("Missing extension file "+path);
        return ExtensionPackage.read(f);
    }
    /** Relative url() in extension CSS must resolve against the extension, not the website. */
    String css(Ext e,String path)throws IOException{
        String dir=path.contains("/")?path.substring(0,path.lastIndexOf('/')+1):"";
        return file(e,path).replace("chrome-extension://"+e.id+"/",e.base()).replaceAll("url\\(\\s*(['\"]?)(?![a-zA-Z][a-zA-Z0-9+.-]*:|//|#|/)([^'\")]+)\\1\\s*\\)","url($1"+java.util.regex.Matcher.quoteReplacement(e.base()+dir)+"$2$1)").replaceAll("url\\(\\s*(['\"]?)/(?!/)","url($1"+java.util.regex.Matcher.quoteReplacement(e.base()));
    }
    JSONObject config(Ext e,String context)throws Exception{
        Locale l=Locale.getDefault();
        return new JSONObject().put("id",e.id).put("base",e.base()).put("manifest",e.manifest).put("messages",e.messages).put("context",context).put("uiLang",l.getLanguage()+(l.getCountry().isEmpty()?"":"-"+l.getCountry()));
    }
    /** Static manifest content scripts plus chrome.scripting.registerContentScripts entries. */
    JSONArray scriptEntries(Ext e){
        JSONArray out=new JSONArray();JSONArray stat=e.manifest.optJSONArray("content_scripts");
        if(stat!=null)for(int i=0;i<stat.length();i++){JSONObject s=stat.optJSONObject(i);if(s!=null)out.put(s);}
        JSONArray dyn=e.state.optJSONArray("scripts");
        if(dyn!=null)for(int i=0;i<dyn.length();i++){JSONObject s=dyn.optJSONObject(i);if(s==null)continue;try{JSONObject c=new JSONObject(s.toString());
            if(c.has("allFrames"))c.put("all_frames",c.opt("allFrames"));if(c.has("runAt"))c.put("run_at",c.opt("runAt"));if(c.has("excludeMatches"))c.put("exclude_matches",c.opt("excludeMatches"));if(c.has("world"))c.put("world",c.optString("world"));out.put(c);}catch(Exception ignored){}}
        return out;
    }
    static JSONArray regexes(JSONArray patterns,boolean glob){JSONArray out=new JSONArray();if(patterns!=null)for(int i=0;i<patterns.length();i++)try{out.put(glob?ExtensionPackage.globRegex(patterns.getString(i)):ExtensionPackage.matchRegex(patterns.getString(i)));}catch(Exception ignored){}return out;}
    void buildBundles(Ext e){
        try{
            StringBuilder iso=new StringBuilder("(function(){\n").append(api).append("\nVoodooExtension(").append(config(e,"content")).append(",[");
            StringBuilder page=new StringBuilder();
            JSONArray entries=scriptEntries(e);
            for(int i=0;i<entries.length();i++){
                JSONObject s=entries.getJSONObject(i);
                String head="{matches:"+regexes(s.optJSONArray("matches"),false)+",excludeMatches:"+regexes(s.optJSONArray("exclude_matches"),false)+",includeGlobs:"+regexes(s.optJSONArray("include_globs"),true)+",excludeGlobs:"+regexes(s.optJSONArray("exclude_globs"),true)+",allFrames:"+s.optBoolean("all_frames")+",runAt:"+JSONObject.quote(s.optString("run_at","document_idle"));
                StringBuilder css=new StringBuilder();JSONArray cssFiles=s.optJSONArray("css");if(cssFiles!=null)for(int j=0;j<cssFiles.length();j++)try{css.append(css(e,cssFiles.getString(j))).append('\n');}catch(Exception ex){a.toast(e.name()+": "+ex.getMessage());}
                StringBuilder js=new StringBuilder();JSONArray jsFiles=s.optJSONArray("js");if(jsFiles!=null)for(int j=0;j<jsFiles.length();j++)try{js.append("\n").append(file(e,jsFiles.getString(j))).append("\n;");}catch(Exception ex){a.toast(e.name()+": "+ex.getMessage());}
                if("MAIN".equalsIgnoreCase(s.optString("world"))){
                    page.append(head).append(",css:").append(JSONObject.quote(css.toString())).append(",run:function(){").append(js).append("\n}},");
                }else iso.append(head).append(",css:").append(JSONObject.quote(css.toString())).append(",run:").append(js.length()==0?"null":"function(){"+js+"\n}").append("},");
            }
            e.contentBundle=iso.append("]);\n})();").toString();
            e.pageBundle="(function(){"+api+"\nVoodooExtension("+config(e,"page")+",[]);})();";
            // Only extensions that can run code in pages get a per-frame isolated world.
            e.isolatedWorld=entries.length()>0||e.has("scripting")||e.has("activeTab")||e.manifest.optInt("manifest_version")==2&&e.has("tabs")&&!e.hosts.isEmpty();
            e.mainBundle=page.length()==0?null:"(function(){\nconst entries=["+page+"];\n"+MAIN_RUNNER+"\n})();";
        }catch(Exception ex){e.contentBundle=null;e.mainBundle=null;a.toast(e.name()+": content scripts could not be prepared.");}
    }
    static final String MAIN_RUNNER="const top=window.top===window;let u;try{const l=new URL(location.href);u=l.protocol+'//'+l.hostname.toLowerCase()+(l.port?':'+l.port:'')+l.pathname+l.search;}catch(e){return;}"
        +"const t=a=>(a||[]).some(r=>new RegExp(r).test(u));const ready=(at,f)=>{if(at==='document_start')f();else if(at==='document_end'){if(document.readyState!=='loading')f();else document.addEventListener('DOMContentLoaded',f,{once:true});}else{if(document.readyState==='complete')f();else addEventListener('load',f,{once:true});}};"
        +"for(const e of entries){if(!top&&!e.allFrames)continue;if(!t(e.matches)||t(e.excludeMatches)||(e.includeGlobs.length&&!t(e.includeGlobs))||t(e.excludeGlobs))continue;"
        +"if(e.css)ready('document_start',()=>{const s=document.createElement('style');s.textContent=e.css;(document.head||document.documentElement).append(s);});ready(e.runAt,()=>{try{e.run.call(window);}catch(x){console.error(x);}});}";

    void rebuildRules(Ext e){
        JSONObject state;try{state=new JSONObject(e.state.toString());}catch(Exception ex){state=new JSONObject();}JSONArray session=e.session.optJSONArray("_dnr");
        JSONObject finalState=state;
        io.execute(()->{
            ExtensionRules rules=new ExtensionRules();
            JSONObject dnr=e.manifest.optJSONObject("declarative_net_request");JSONArray resources=dnr==null?null:dnr.optJSONArray("rule_resources");
            JSONArray enabled=finalState.optJSONArray("rulesets");Set<String> on=new HashSet<>();
            if(resources!=null)for(int i=0;i<resources.length();i++){JSONObject r=resources.optJSONObject(i);if(r==null)continue;if(enabled==null?r.optBoolean("enabled"):contains(enabled,r.optString("id")))on.add(r.optString("id"));}
            if(resources!=null)for(int i=0;i<resources.length();i++){JSONObject r=resources.optJSONObject(i);if(r==null||!on.contains(r.optString("id")))continue;try{rules.addAll(new JSONArray(ExtensionPackage.stripComments(file(e,r.getString("path")))));}catch(Exception ignored){}}
            rules.addAll(finalState.optJSONArray("dynamicRules"));rules.addAll(session);
            e.rules=rules;
        });
    }
    static boolean contains(JSONArray a,String v){for(int i=0;i<a.length();i++)if(v.equals(a.optString(i)))return true;return false;}

    // ---------- startup, tabs and lifecycle ----------
    void start(){
        if(!supported())return;
        for(Ext e:exts.values())if(e.enabled)startBackground(e,"startup",null);
    }
    void startBackground(Ext e,String reason,String previousVersion){
        stopBackground(e);
        JSONObject bg=e.manifest.optJSONObject("background");if(bg==null)return;
        WebView v=page(e,"background");e.background=v;
        View host=a.getWindow().getDecorView();if(host instanceof ViewGroup){v.setAlpha(0f);v.setFocusable(false);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);((ViewGroup)host).addView(v,0,new ViewGroup.LayoutParams(1,1));}
        v.loadUrl(e.base()+(bg.has("page")?bg.optString("page").replaceFirst("^/",""):"_generated_background_page.html"));
        try{e.lifecycle=reason.equals("startup")?new JSONObject().put("event","startup"):new JSONObject().put("event","installed").put("details",new JSONObject().put("reason",reason).put("previousVersion",previousVersion==null?JSONObject.NULL:previousVersion));}catch(Exception ignored){}
    }
    void stopBackground(Ext e){
        for(WebView v:new WebView[]{e.background,e.offscreen})if(v!=null){forget(v);try{((ViewGroup)v.getParent()).removeView(v);}catch(Exception ignored){}v.destroy();}
        e.background=null;e.offscreen=null;
    }
    String generatedBackground(Ext e){
        JSONObject bg=e.manifest.optJSONObject("background");StringBuilder html=new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(android.text.Html.escapeHtml(e.name())).append("</title></head><body>");
        if(bg!=null){
            String worker=bg.optString("service_worker","");
            if(!worker.isEmpty())html.append("<script").append("module".equals(bg.optString("type"))?" type=\"module\"":"").append(" src=\"/").append(android.text.Html.escapeHtml(worker.replaceFirst("^/",""))).append("\"></script>");
            JSONArray scripts=bg.optJSONArray("scripts");if(scripts!=null)for(int i=0;i<scripts.length();i++)html.append("<script src=\"/").append(android.text.Html.escapeHtml(scripts.optString(i).replaceFirst("^/",""))).append("\"></script>");
        }
        return html.append("</body></html>").toString();
    }
    /** WebView for an extension's own pages: background, popup, side panel and offscreen documents. */
    WebView page(Ext e,String context){
        WebView v=new WebView(a);WebSettings s=v.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(false);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        try{
            WebViewCompat.addWebMessageListener(v,"VoodooExtPage",Collections.singleton(e.origin()),(view,m,o,isMain,proxy)->receive(e,"background".equals(context)?"background":"page",view,m.getData(),o,isMain,proxy));
            WebViewCompat.addDocumentStartJavaScript(v,"(function(){"+api+"\nVoodooExtension("+config(e,context)+",[]);})();",Collections.singleton(e.origin()));
        }catch(Exception ex){a.toast(e.name()+": "+ex.getMessage());}
        v.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest r){return intercept(view,r,false);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){
                String u=r.getUrl().toString();if(e.id.equals(extId(r.getUrl().getHost())))return false;
                if(!"background".equals(context)&&a.webUrl(u)&&r.hasGesture()){a.newTab(u);if(e.popup!=null)e.popup.dismiss();}
                return true;
            }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail d){forget(view);if(view==e.background){e.background=null;try{((ViewGroup)view.getParent()).removeView(view);}catch(Exception ignored){}view.destroy();main.postDelayed(()->{if(!closed&&e.enabled&&e.background==null)startBackground(e,"startup",null);},3000);}return true;}
        });
        v.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb){cb.invoke(origin,false,false);}
            @Override public boolean onConsoleMessage(ConsoleMessage m){if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR)log(e,m.message()+" ("+m.sourceId()+":"+m.lineNumber()+")");return false;}
        });
        return v;
    }
    void log(Ext e,String text){try{JSONArray old=e.state.optJSONArray("errors");JSONArray next=new JSONArray().put(text.substring(0,Math.min(500,text.length())));if(old!=null)for(int i=0;i<Math.min(19,old.length());i++)next.put(old.get(i));e.state.put("errors",next);saveState(e);}catch(Exception ignored){}}

    int tabId(WebView v){Integer id=tabIds.get(v);if(id==null){id=nextTabId++;tabIds.put(v,id);}return id;}
    WebView tab(int id){for(WebView v:a.tabs)if(tabIds.get(v)!=null&&tabIds.get(v)==id)return v;return null;}
    String extId(String host){if(host==null)return null;String h=host.toLowerCase(Locale.ROOT);if(!h.endsWith(SUFFIX))return null;String id=h.substring(0,h.length()-SUFFIX.length());return ExtensionPackage.validId(id)?id:null;}
    boolean extensionUrl(String url){try{return extId(Uri.parse(url).getHost())!=null;}catch(Exception e){return false;}}

    /** Called for each browser tab after creation and when the set of extensions changes. */
    void install(WebView view){
        if(closed||!supported()||!a.tabs.contains(view))return;
        uninstall(view);tabId(view);
        List<ScriptHandler> handlers=new ArrayList<>();Set<JavaScriptExecutionWorld> worlds=new HashSet<>();Set<String> origins=new HashSet<>();
        for(Ext e:exts.values()){
            if(!e.enabled)continue;origins.add(e.origin());
            try{
                if(e.isolatedWorld){JavaScriptExecutionWorld world=WebViewCompat.getExecutionWorld(view,"voodoo-ext-"+e.id);worlds.add(world);
                WebViewCompat.addWebMessageListener(view,"VoodooExt",Collections.singleton("*"),world,(v,m,o,isMain,proxy)->receive(e,"content",v,m.getData(),o,isMain,proxy));
                if(e.contentBundle!=null)handlers.add(WebViewCompat.addJavaScriptOnEvent(view,e.contentBundle,WebViewCompat.INJECTION_EVENT_DOCUMENT_START,Collections.singleton("*"),world));}
                if(e.mainBundle!=null)handlers.add(WebViewCompat.addDocumentStartJavaScript(view,e.mainBundle,Collections.singleton("*")));
                if(e.pageBundle!=null)handlers.add(WebViewCompat.addDocumentStartJavaScript(view,e.pageBundle,Collections.singleton(e.origin())));
            }catch(Exception ex){log(e,"Tab injection failed: "+ex.getMessage());}
        }
        if(!origins.isEmpty())try{
            WebViewCompat.addWebMessageListener(view,"VoodooExtPage",origins,(v,m,o,isMain,proxy)->{Ext e=exts.get(extId(o.getHost()));if(e!=null&&e.enabled)receive(e,"page",v,m.getData(),o,isMain,proxy);});pageListener.put(view,true);
        }catch(Exception ex){a.toast("Extension pages unavailable: "+ex.getMessage());}
        tabScripts.put(view,handlers);tabWorlds.put(view,worlds);
    }
    void uninstall(WebView view){
        List<ScriptHandler> old=tabScripts.remove(view);if(old!=null)for(ScriptHandler h:old)try{h.remove();}catch(RuntimeException ignored){}
        Set<JavaScriptExecutionWorld> worlds=tabWorlds.remove(view);if(worlds!=null)for(JavaScriptExecutionWorld w:worlds)try{WebViewCompat.removeWebMessageListener(view,w,"VoodooExt");}catch(RuntimeException ignored){}
        if(pageListener.remove(view)!=null)try{WebViewCompat.removeWebMessageListener(view,"VoodooExtPage");}catch(RuntimeException ignored){}
    }
    void reinstallAll(){for(WebView v:new ArrayList<>(a.tabs))install(v);}

    void tabUpdated(WebView view,String url,String state){
        if(closed||!a.tabs.contains(view))return;
        topUrls.put(view,url==null?"":url);
        // Documents report pagehide themselves; this also clears frames that could not, without
        // dropping a new document whose hello raced ahead of onPageStarted.
        if("loading".equals(state)){long now=System.nanoTime();for(Endpoint p:new ArrayList<>(endpoints))if(p.view==view&&now-p.seen>1_500_000_000L)drop(p);}
        status.put(view,state);
        try{JSONObject change=new JSONObject().put("status",state);if("loading".equals(state)&&url!=null)change.put("url",url);broadcastTabs("updated",new JSONArray().put(tabId(view)).put(change).put(tabJson(view,null)));}catch(Exception ignored){}
    }
    void activated(WebView view){if(closed||!a.tabs.contains(view))return;try{broadcastTabs("activated",new JSONArray().put(new JSONObject().put("tabId",tabId(view)).put("windowId",1)));}catch(Exception ignored){}}
    void created(WebView view){try{broadcastTabs("created",new JSONArray().put(tabJson(view,null)));}catch(Exception ignored){}}
    void closed(WebView view){
        Integer id=tabIds.remove(view);uninstall(view);forget(view);status.remove(view);topUrls.remove(view);
        if(id!=null)try{broadcastTabs("removed",new JSONArray().put(id).put(new JSONObject().put("windowId",1).put("isWindowClosing",false)));}catch(Exception ignored){}
    }
    void forget(WebView view){for(Endpoint p:new ArrayList<>(endpoints))if(p.view==view)drop(p);}
    void drop(Endpoint p){
        endpoints.remove(p);
        for(Pending q:new ArrayList<>(pending.values()))if(q.waiting.remove(p))settle(q);
        for(Map.Entry<String,Port> en:new ArrayList<>(ports.entrySet())){Port port=en.getValue();if(port.opener==p){for(Endpoint r:port.receivers)send(r,"port-disconnect",new JSONObject(),en.getKey());ports.remove(en.getKey());}else if(port.receivers.remove(p)&&port.receivers.isEmpty()){send(port.opener,"port-disconnect",new JSONObject(),en.getKey());ports.remove(en.getKey());}}
    }
    void broadcastTabs(String type,JSONArray args){for(Endpoint p:new ArrayList<>(endpoints))if(!p.kind.equals("content"))try{p.proxy.postMessage(new JSONObject().put("event","tabs").put("type",type).put("args",args).toString());}catch(Exception ignored){}}
    void event(Ext e,String kind,JSONObject message){for(Endpoint p:new ArrayList<>(endpoints))if(p.ext==e&&(kind==null||p.kind.equals(kind)))try{p.proxy.postMessage(message.toString());}catch(Exception ignored){}}
    void send(Endpoint p,String event,JSONObject body,String portId){try{body.put("event",event);if(portId!=null)body.put("portId",portId);p.proxy.postMessage(body.toString());}catch(Exception ignored){}}

    boolean hostAllowed(Ext e,String url){
        if(url==null||!a.webUrl(url))return false;if(e.hosts.contains("<all_urls>"))return true;
        return ExtensionPackage.matches(e.hosts,url);
    }
    JSONObject tabJson(WebView v,Ext viewer)throws Exception{
        String url=a.tabUrl(v);boolean reveal=viewer==null||viewer.has("tabs")||hostAllowed(viewer,url)||viewer.has("activeTab")&&v==a.current||extId(Uri.parse(url).getHost())!=null&&viewer.id.equals(extId(Uri.parse(url).getHost()));
        JSONObject t=new JSONObject().put("id",tabId(v)).put("index",a.tabs.indexOf(v)).put("windowId",1).put("active",v==a.current).put("highlighted",v==a.current).put("selected",v==a.current).put("pinned",false).put("incognito",false).put("audible",false).put("discarded",false).put("autoDiscardable",true).put("groupId",-1).put("mutedInfo",new JSONObject().put("muted",false)).put("status",status.getOrDefault(v,"complete")).put("width",v.getWidth()).put("height",v.getHeight());
        if(reveal)t.put("url",url).put("title",a.tabTitle(v)).put("favIconUrl","");
        return t;
    }

    // ---------- native bridge ----------
    void reply(JavaScriptReplyProxy proxy,String id,Object data,String error){if(proxy!=null&&id!=null&&!id.isEmpty())a.reply(proxy,id,data,error);}
    Endpoint endpoint(JavaScriptReplyProxy proxy){for(Endpoint p:endpoints)if(p.proxy==proxy)return p;return null;}
    void receive(Ext e,String kind,WebView view,String raw,Uri origin,boolean isMain,JavaScriptReplyProxy proxy){
        String rid=null;
        try{
            if(closed||!e.enabled||raw==null||raw.length()>30_000_000)return;
            if(!kind.equals("content")&&!e.id.equals(extId(origin.getHost())))return;
            JSONObject req=new JSONObject(raw);rid=req.optString("id",null);String op=req.getString("op");
            Endpoint self=endpoint(proxy);
            if(self==null){
                self=new Endpoint();self.ext=e;self.view=view;self.proxy=proxy;self.kind=kind;self.frameId=isMain?0:nextFrameId++;endpoints.add(self);
                int same=0;for(Endpoint p:endpoints)if(p.view==view)same++;
                if(same>96)for(Endpoint p:new ArrayList<>(endpoints))if(p.view==view&&p!=self&&!p.kind.equals("background")){drop(p);break;}
            }
            self.url=req.optString("url",self.url);
            if(kind.equals("content")){
                Uri actual=Uri.parse(self.url);
                if(!Objects.equals(actual.getHost(),origin.getHost())&&!self.url.startsWith("about:")&&!self.url.startsWith("blob:")&&!self.url.startsWith("data:"))throw new Exception("Frame origin mismatch.");
                if(!Arrays.asList("hello","bye","storage-get","storage-bytes","storage-set","storage-remove","storage-clear","send-message","connect","port-post","port-disconnect","respond","exec-result").contains(op))throw new Exception("chrome."+op+" is not available to content scripts.");
                if(op.equals("send-message")&&!"runtime".equals(req.optString("target")))throw new Exception("Content scripts can only message their extension.");
            }
            handle(e,self,req,op,rid);
        }catch(Exception ex){reply(proxy,rid,null,ex.getMessage()==null?"Extension operation failed.":ex.getMessage());}
    }
    JSONObject sender(Endpoint p)throws Exception{
        JSONObject s=new JSONObject().put("id",p.ext.id).put("url",p.url).put("frameId",p.frameId);
        try{Uri u=Uri.parse(p.url);if(u.getScheme()!=null&&u.getHost()!=null)s.put("origin",u.getScheme()+"://"+u.getHost()+(u.getPort()<0?"":":"+u.getPort()));}catch(Exception ignored){}
        if(a.tabs.contains(p.view))s.put("tab",tabJson(p.view,p.ext));
        return s;
    }
    List<Endpoint> targets(Ext e,Endpoint from,JSONObject req)throws Exception{
        List<Endpoint> out=new ArrayList<>();
        if("tab".equals(req.optString("target"))){
            WebView v=tab(req.getInt("tabId"));if(v==null)throw new Exception("No tab with id: "+req.optInt("tabId")+".");
            Integer frame=req.has("frameId")&&!req.isNull("frameId")?req.getInt("frameId"):null;
            for(Endpoint p:endpoints)if(p.ext==e&&p.view==v&&p.kind.equals("content")&&(frame==null||frame==p.frameId))out.add(p);
        }else for(Endpoint p:endpoints)if(p.ext==e&&p!=from&&!p.kind.equals("content"))out.add(p);
        return out;
    }
    void settle(Pending q){
        if(q.done)return;
        if(q.op.equals("message")){if(q.waiting.isEmpty()){q.done=true;reply(q.proxy,q.replyId,null,null);}}
        else if(q.waiting.isEmpty()){q.done=true;if(q.results.length()==0&&q.error!=null)reply(q.proxy,q.replyId,null,q.error);else reply(q.proxy,q.replyId,q.results,null);}
        if(q.done)pending.values().remove(q);
    }
    Ext must(String id)throws Exception{Ext e=exts.get(id);if(e==null)throw new Exception("Extension removed.");return e;}

    void handle(Ext e,Endpoint self,JSONObject req,String op,String rid)throws Exception{
        JavaScriptReplyProxy proxy=self.proxy;
        switch(op){
            case "bg-ready":{if(self.kind.equals("background")&&e.lifecycle!=null){JSONObject l=e.lifecycle;e.lifecycle=null;try{self.proxy.postMessage(l.toString());}catch(Exception ignored){}}return;}
            case "bye":drop(self);return;
            case "hello":reply(proxy,rid,new JSONObject().put("tabId",a.tabs.contains(self.view)?tabId(self.view):-1).put("frameId",self.frameId),null);return;
            case "storage-get":reply(proxy,rid,storageGet(area(e,req.getString("area")),req.opt("keys")),null);return;
            case "storage-bytes":{JSONObject all=storageGet(area(e,req.getString("area")),req.opt("keys"));reply(proxy,rid,all.toString().getBytes(StandardCharsets.UTF_8).length,null);return;}
            case "storage-set":case "storage-remove":case "storage-clear":{
                String name=req.getString("area");if(name.equals("managed"))throw new Exception("Managed storage is read-only.");JSONObject store=area(e,name),changes=new JSONObject();
                if(op.equals("storage-set")){JSONObject items=req.getJSONObject("items");for(Iterator<String> it=items.keys();it.hasNext();){String k=it.next();JSONObject c=new JSONObject();if(store.has(k))c.put("oldValue",store.get(k));c.put("newValue",items.get(k));store.put(k,items.get(k));changes.put(k,c);}}
                else{JSONArray keys=op.equals("storage-clear")?store.names():req.getJSONArray("keys");if(keys!=null)for(int i=0;i<keys.length();i++){String k=keys.getString(i);if(store.has(k)){changes.put(k,new JSONObject().put("oldValue",store.get(k)));store.remove(k);}}}
                long limit=name.equals("sync")?102400:(e.has("unlimitedStorage")?200L*1024*1024:10L*1024*1024);
                String json=store.toString();if(json.length()>limit){reply(proxy,rid,null,"QUOTA_BYTES quota exceeded");return;}
                if(!name.equals("session")){File f=new File(dataRoot,e.id+"/"+name+".json");io.execute(()->{try{f.getParentFile().mkdirs();File tmp=new File(f.getPath()+".tmp");try(OutputStream o=new FileOutputStream(tmp)){o.write(json.getBytes(StandardCharsets.UTF_8));}if(!tmp.renameTo(f))throw new IOException();}catch(Exception ex){a.runOnUiThread(()->log(e,"Storage write failed."));}});}
                reply(proxy,rid,null,null);
                if(changes.length()>0)event(e,null,new JSONObject().put("event","storage").put("area",name).put("changes",changes));
                return;
            }
            case "send-message":{
                List<Endpoint> to=targets(e,self,req);if(to.isEmpty()){reply(proxy,rid,null,"Could not establish connection. Receiving end does not exist.");return;}
                Pending q=new Pending();q.proxy=proxy;q.replyId=rid;q.op="message";q.waiting.addAll(to);String mid=UUID.randomUUID().toString();pending.put(mid,q);
                JSONObject msg=new JSONObject().put("event","message").put("mid",mid).put("message",req.opt("message")==null?JSONObject.NULL:req.get("message")).put("sender",sender(self));
                for(Endpoint p:to)try{p.proxy.postMessage(msg.toString());}catch(Exception ex){q.waiting.remove(p);}
                settle(q);main.postDelayed(()->{if(!q.done){q.waiting.clear();settle(q);}},300000);return;
            }
            case "respond":{
                Pending q=pending.get(req.getString("mid"));if(q==null||q.done||!q.waiting.contains(self))return;
                if(req.optBoolean("none")){q.waiting.remove(self);settle(q);return;}
                q.done=true;pending.remove(req.getString("mid"));reply(q.proxy,q.replyId,req.opt("response")==null?JSONObject.NULL:req.get("response"),null);return;
            }
            case "connect":{
                String portId=req.getString("portId");if(!portId.matches("[a-z0-9]{4,40}")||ports.containsKey(portId))return;
                List<Endpoint> to=targets(e,self,req);Port port=new Port();port.opener=self;port.receivers.addAll(to);
                if(to.isEmpty()){send(self,"port-disconnect",new JSONObject(),portId);return;}ports.put(portId,port);
                for(Endpoint p:to)send(p,"connect",new JSONObject().put("name",req.optString("name")).put("sender",sender(self)),portId);return;
            }
            case "port-post":{Port port=ports.get(req.getString("portId"));if(port==null)return;JSONObject body=new JSONObject().put("message",req.opt("message")==null?JSONObject.NULL:req.get("message"));
                if(port.opener==self){for(Endpoint p:port.receivers)send(p,"port-message",new JSONObject(body.toString()),req.getString("portId"));}else if(port.receivers.contains(self))send(port.opener,"port-message",body,req.getString("portId"));return;}
            case "port-disconnect":{String portId=req.getString("portId");Port port=ports.get(portId);if(port==null)return;
                if(port.opener==self){for(Endpoint p:port.receivers)send(p,"port-disconnect",new JSONObject(),portId);ports.remove(portId);}
                else if(port.receivers.remove(self)){send(port.opener,"port-disconnect",new JSONObject(),portId);ports.remove(portId);}return;}
            case "exec-result":{
                Pending q=pending.get(req.getString("eid"));if(q==null||q.done||!q.waiting.remove(self))return;
                if(req.has("error")&&!req.isNull("error"))q.error=req.getString("error");else q.results.put(new JSONObject().put("frameId",self.frameId).put("result",req.opt("result")==null?JSONObject.NULL:req.get("result")).put("documentId",String.valueOf(System.identityHashCode(self))));
                settle(q);return;
            }
            case "tabs-query":{JSONObject q=req.optJSONObject("query");JSONArray out=new JSONArray();for(WebView v:a.tabs){JSONObject t=tabJson(v,e);if(matchesQuery(e,v,t,q))out.put(t);}reply(proxy,rid,out,null);return;}
            case "tabs-get":{WebView v=tab(req.getInt("tabId"));if(v==null)throw new Exception("No tab with id: "+req.optInt("tabId")+".");reply(proxy,rid,tabJson(v,e),null);return;}
            case "tabs-current":reply(proxy,rid,a.tabs.contains(self.view)?tabJson(self.view,e):null,null);return;
            case "tabs-create":{
                JSONObject p=req.getJSONObject("props");String url=resolve(e,p.optString("url",MainActivity.HOME));WebView v=a.newTab(url,p.optBoolean("active",true));
                if(v==null)throw new Exception("Tab limit reached (8 tabs).");reply(proxy,rid,tabJson(v,e),null);return;
            }
            case "tabs-update":{
                WebView v=req.has("tabId")&&!req.isNull("tabId")?tab(req.getInt("tabId")):a.current;if(v==null)throw new Exception("No tab with id: "+req.optInt("tabId")+".");
                JSONObject p=req.getJSONObject("props");if(p.has("url"))v.loadUrl(resolve(e,p.getString("url")));if(p.optBoolean("active")||p.optBoolean("highlighted"))a.showTab(v);
                reply(proxy,rid,tabJson(v,e),null);return;
            }
            case "tabs-remove":{JSONArray ids=req.getJSONArray("tabIds");for(int i=0;i<ids.length();i++){WebView v=tab(ids.getInt(i));if(v!=null)a.closeTab(v);}reply(proxy,rid,null,null);return;}
            case "tabs-reload":{WebView v=req.has("tabId")&&!req.isNull("tabId")?tab(req.getInt("tabId")):a.current;if(v!=null)v.reload();reply(proxy,rid,null,null);return;}
            case "tabs-nav":{WebView v=tab(req.getInt("tabId"));if(v!=null){if(req.getInt("dir")<0&&v.canGoBack())v.goBack();else if(req.getInt("dir")>0&&v.canGoForward())v.goForward();}reply(proxy,rid,null,null);return;}
            case "tabs-capture":{
                WebView v=a.current;if(v==null||v.getWidth()==0)throw new Exception("No visible tab.");if(!hostAllowed(e,a.tabUrl(v))&&!e.has("activeTab"))throw new Exception("Missing host permission for the tab.");
                Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,90,out);b.recycle();
                reply(proxy,rid,"data:image/jpeg;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP),null);return;
            }
            case "windows-get":{JSONObject w=new JSONObject().put("id",1).put("focused",true).put("top",0).put("left",0).put("width",a.content==null?0:a.content.getWidth()).put("height",a.content==null?0:a.content.getHeight()).put("incognito",false).put("type","normal").put("state","maximized").put("alwaysOnTop",false);
                if(req.optBoolean("populate")){JSONArray t=new JSONArray();for(WebView v:a.tabs)t.put(tabJson(v,e));w.put("tabs",t);}reply(proxy,rid,w,null);return;}
            case "execute":execute(e,self,req,rid);return;
            case "action-set":{JSONObject s=e.state.optJSONObject("action");if(s==null)s=new JSONObject();s.put(req.getString("key"),req.opt("value")==null?JSONObject.NULL:req.get("value"));e.state.put("action",s);saveState(e);reply(proxy,rid,null,null);return;}
            case "action-get":{JSONObject s=e.state.optJSONObject("action");reply(proxy,rid,s==null||!s.has(req.getString("key"))?null:s.get(req.getString("key")),null);return;}
            case "menu-create":case "menu-update":{
                JSONArray menus=e.state.optJSONArray("menus");if(menus==null)menus=new JSONArray();JSONObject props=req.getJSONObject("props");String menuId=op.equals("menu-create")?props.getString("id"):req.getString("menuId");
                JSONArray next=new JSONArray();boolean found=false;for(int i=0;i<menus.length();i++){JSONObject m=menus.getJSONObject(i);if(m.optString("id").equals(menuId)){found=true;if(op.equals("menu-update")){for(Iterator<String> it=props.keys();it.hasNext();){String k=it.next();m.put(k,props.get(k));}}else m=props;}next.put(m);}
                if(!found){if(op.equals("menu-update"))throw new Exception("Cannot find menu item with id "+menuId);if(next.length()>=200)throw new Exception("Too many menu items.");next.put(props);}
                e.state.put("menus",next);saveState(e);reply(proxy,rid,null,null);return;
            }
            case "menu-remove":{JSONArray menus=e.state.optJSONArray("menus"),next=new JSONArray();if(menus!=null)for(int i=0;i<menus.length();i++){JSONObject m=menus.getJSONObject(i);if(!m.optString("id").equals(req.getString("menuId"))&&!m.optString("parentId").equals(req.getString("menuId")))next.put(m);}e.state.put("menus",next);saveState(e);reply(proxy,rid,null,null);return;}
            case "menu-remove-all":e.state.put("menus",new JSONArray());saveState(e);reply(proxy,rid,null,null);return;
            case "open-options":{String path=optionsPage(e);if(path==null)throw new Exception("This extension has no options page.");openOrFocus(e.base()+path);reply(proxy,rid,null,null);return;}
            case "open-popup":{showPopup(e,popupPath(e));reply(proxy,rid,null,null);return;}
            case "open-panel":{String path=panelPath(e);if(path==null)throw new Exception("No side panel.");showPopup(e,path);reply(proxy,rid,null,null);return;}
            case "close-page":{if(e.popup!=null&&self.kind.equals("page")&&!a.tabs.contains(self.view))e.popup.dismiss();else if(a.tabs.contains(self.view))a.closeTab(self.view);return;}
            case "notify":Toast.makeText(a,req.optString("title")+(req.optString("message").isEmpty()?"":"\n"+req.optString("message")),Toast.LENGTH_LONG).show();reply(proxy,rid,null,null);return;
            case "download":{String u=resolve(e,req.getString("url"));a.download(u,WebSettings.getDefaultUserAgent(a),req.optString("filename").isEmpty()?null:"attachment; filename=\""+req.optString("filename").replaceAll("[\"/\\\\]","_")+"\"",null);reply(proxy,rid,(int)(System.currentTimeMillis()%100000),null);return;}
            case "cookies-get":case "cookies-all":{
                String url=req.optString("url");if(!hostAllowed(e,url))throw new Exception("No host permissions for cookies at url: \""+url+"\".");if(!e.has("cookies"))throw new Exception("Missing \"cookies\" permission.");
                JSONArray all=cookies(url,req.optString("name",""));reply(proxy,rid,op.equals("cookies-get")?(all.length()==0?null:all.get(0)):all,null);return;
            }
            case "cookies-set":case "cookies-remove":{
                JSONObject d=op.equals("cookies-set")?req.getJSONObject("details"):new JSONObject().put("url",req.getString("url")).put("name",req.getString("name"));String url=d.getString("url");
                if(!hostAllowed(e,url)||!e.has("cookies"))throw new Exception("No host permissions for cookies at url: \""+url+"\".");
                StringBuilder c=new StringBuilder(d.getString("name")).append('=').append(op.equals("cookies-set")?d.optString("value"):"");c.append("; Path=").append(d.optString("path","/"));
                if(d.has("domain"))c.append("; Domain=").append(d.getString("domain"));if(d.optBoolean("secure"))c.append("; Secure");if(d.optBoolean("httpOnly"))c.append("; HttpOnly");
                if(op.equals("cookies-remove"))c.append("; Max-Age=0");else if(d.has("expirationDate"))c.append("; Max-Age=").append(Math.max(0,(long)(d.getDouble("expirationDate")-System.currentTimeMillis()/1000.0)));
                CookieManager.getInstance().setCookie(url,c.toString());CookieManager.getInstance().flush();
                reply(proxy,rid,op.equals("cookies-set")?new JSONObject().put("name",d.getString("name")).put("value",d.optString("value")).put("domain",Uri.parse(url).getHost()).put("path",d.optString("path","/")).put("secure",d.optBoolean("secure")).put("httpOnly",d.optBoolean("httpOnly")).put("session",!d.has("expirationDate")).put("storeId","0"):new JSONObject().put("url",url).put("name",d.getString("name")).put("storeId","0"),null);return;
            }
            case "permissions-contains":{
                JSONArray p=req.optJSONArray("permissions"),o=req.optJSONArray("origins");boolean ok=true;
                if(p!=null)for(int i=0;i<p.length();i++)if(!e.has(p.getString(i)))ok=false;
                if(o!=null)for(int i=0;i<o.length();i++){String pat=o.getString(i);if(!e.hosts.contains(pat)&&!e.hosts.contains("<all_urls>")&&!e.hosts.contains("*://*/*")&&!ExtensionPackage.matches(e.hosts,pat.replace("*.","").replace("*://","https://").replace("*","x")))ok=false;}
                reply(proxy,rid,ok,null);return;
            }
            case "permissions-all":reply(proxy,rid,new JSONObject().put("permissions",new JSONArray(e.apis)).put("origins",new JSONArray(e.hosts)),null);return;
            case "dnr-update":{
                String scope=req.getString("scope");JSONArray current=scope.equals("session")?e.session.optJSONArray("_dnr"):e.state.optJSONArray("dynamicRules");if(current==null)current=new JSONArray();
                Set<Integer> remove=new HashSet<>();JSONArray r=req.getJSONArray("remove");for(int i=0;i<r.length();i++)remove.add(r.getInt(i));JSONArray add=req.getJSONArray("add");for(int i=0;i<add.length();i++)remove.add(add.getJSONObject(i).getInt("id"));
                JSONArray next=new JSONArray();for(int i=0;i<current.length();i++)if(!remove.contains(current.getJSONObject(i).optInt("id")))next.put(current.get(i));for(int i=0;i<add.length();i++)next.put(add.get(i));
                if(next.length()>(scope.equals("session")?5000:30000))throw new Exception("Rule count exceeded.");
                if(scope.equals("session"))e.session.put("_dnr",next);else{e.state.put("dynamicRules",next);saveState(e);}rebuildRules(e);reply(proxy,rid,null,null);return;
            }
            case "dnr-get":{JSONArray current=req.getString("scope").equals("session")?e.session.optJSONArray("_dnr"):e.state.optJSONArray("dynamicRules");JSONArray ids=req.optJSONArray("ids"),out=new JSONArray();
                if(current!=null)for(int i=0;i<current.length();i++){JSONObject rule=current.getJSONObject(i);if(ids==null||contains(ids,String.valueOf(rule.optInt("id"))))out.put(rule);}reply(proxy,rid,out,null);return;}
            case "dnr-rulesets":{
                Set<String> on=new LinkedHashSet<>(enabledRulesets(e));JSONArray en=req.getJSONArray("enable"),dis=req.getJSONArray("disable");for(int i=0;i<dis.length();i++)on.remove(dis.getString(i));for(int i=0;i<en.length();i++)on.add(en.getString(i));
                e.state.put("rulesets",new JSONArray(on));saveState(e);rebuildRules(e);reply(proxy,rid,null,null);return;
            }
            case "dnr-enabled":reply(proxy,rid,new JSONArray(enabledRulesets(e)),null);return;
            case "scripts-register":case "scripts-update":{
                JSONArray list=e.state.optJSONArray("scripts");if(list==null)list=new JSONArray();JSONArray add=req.getJSONArray("scripts");
                for(int i=0;i<add.length();i++){JSONObject s=add.getJSONObject(i);String sid=s.getString("id");int found=-1;for(int j=0;j<list.length();j++)if(sid.equals(list.getJSONObject(j).optString("id")))found=j;
                    if(op.equals("scripts-register")){if(found>=0)throw new Exception("Duplicate script ID '"+sid+"'.");list.put(s);}
                    else{if(found<0)throw new Exception("Script with ID '"+sid+"' does not exist.");JSONObject old=list.getJSONObject(found);for(Iterator<String> it=s.keys();it.hasNext();){String k=it.next();old.put(k,s.get(k));}}}
                e.state.put("scripts",list);saveState(e);buildBundles(e);reinstallAll();reply(proxy,rid,null,null);return;
            }
            case "scripts-unregister":{JSONArray list=e.state.optJSONArray("scripts"),ids=req.optJSONArray("ids"),next=new JSONArray();if(list!=null&&ids!=null)for(int i=0;i<list.length();i++)if(!contains(ids,list.getJSONObject(i).optString("id")))next.put(list.get(i));
                e.state.put("scripts",next);saveState(e);buildBundles(e);reinstallAll();reply(proxy,rid,null,null);return;}
            case "scripts-get":{JSONArray list=e.state.optJSONArray("scripts"),ids=req.optJSONArray("ids"),out=new JSONArray();if(list!=null)for(int i=0;i<list.length();i++)if(ids==null||contains(ids,list.getJSONObject(i).optString("id")))out.put(list.get(i));reply(proxy,rid,out,null);return;}
            case "offscreen-create":{if(e.offscreen!=null)throw new Exception("Only a single offscreen document may be created.");WebView v=page(e,"page");e.offscreen=v;v.loadUrl(resolve(e,req.getString("url")));reply(proxy,rid,null,null);return;}
            case "offscreen-close":{if(e.offscreen!=null){forget(e.offscreen);e.offscreen.destroy();e.offscreen=null;}reply(proxy,rid,null,null);return;}
            case "offscreen-has":reply(proxy,rid,e.offscreen!=null,null);return;
            case "fetch":fetch(e,req.getJSONObject("request"),proxy,rid);return;
            case "self":reply(proxy,rid,info(e),null);return;
            case "reload":{Ext fresh=reload(e.id);if(fresh!=null)startBackground(fresh,"startup",null);return;}
            default:throw new Exception("chrome API operation is not supported: "+op);
        }
    }
    List<String> enabledRulesets(Ext e){
        List<String> out=new ArrayList<>();JSONArray chosen=e.state.optJSONArray("rulesets");
        if(chosen!=null){for(int i=0;i<chosen.length();i++)out.add(chosen.optString(i));return out;}
        JSONObject dnr=e.manifest.optJSONObject("declarative_net_request");JSONArray res=dnr==null?null:dnr.optJSONArray("rule_resources");
        if(res!=null)for(int i=0;i<res.length();i++)if(res.optJSONObject(i)!=null&&res.optJSONObject(i).optBoolean("enabled"))out.add(res.optJSONObject(i).optString("id"));
        return out;
    }
    JSONObject info(Ext e)throws Exception{
        String opts=optionsPage(e);
        return new JSONObject().put("id",e.id).put("name",e.name()).put("shortName",e.manifest.optString("short_name",e.name())).put("version",e.manifest.optString("version")).put("description",e.manifest.optString("description")).put("enabled",e.enabled).put("installType",e.source.equals("store")?"normal":"development").put("type","extension").put("mayDisable",true).put("offlineEnabled",false).put("permissions",new JSONArray(e.apis)).put("hostPermissions",new JSONArray(e.hosts)).put("optionsUrl",opts==null?"":e.base()+opts).put("isApp",false);
    }
    boolean matchesQuery(Ext e,WebView v,JSONObject t,JSONObject q)throws Exception{
        if(q==null)return true;
        if(q.has("active")&&q.getBoolean("active")!=(v==a.current))return false;
        if(q.has("highlighted")&&q.getBoolean("highlighted")!=(v==a.current))return false;
        if(q.has("index")&&q.getInt("index")!=a.tabs.indexOf(v))return false;
        if(q.has("status")&&!q.getString("status").equals(t.optString("status")))return false;
        if(q.has("windowId")&&q.optInt("windowId")!=1&&q.optInt("windowId")!=-2)return false;
        if(q.has("pinned")&&q.optBoolean("pinned")||q.has("audible")&&q.optBoolean("audible")||q.has("discarded")&&q.optBoolean("discarded"))return false;
        if(q.has("url")){if(!t.has("url"))return false;List<String> pats=new ArrayList<>();Object u=q.get("url");if(u instanceof JSONArray)for(int i=0;i<((JSONArray)u).length();i++)pats.add(((JSONArray)u).getString(i));else pats.add(String.valueOf(u));if(!ExtensionPackage.matches(pats,t.getString("url")))return false;}
        if(q.has("title")){if(!t.has("title")||!java.util.regex.Pattern.compile(ExtensionPackage.globRegex(q.getString("title"))).matcher(t.getString("title")).matches())return false;}
        return true;
    }
    String resolve(Ext e,String url)throws Exception{
        if(url==null||url.isEmpty())return MainActivity.HOME;
        if(url.startsWith("chrome-extension://"+e.id+"/"))return e.base()+url.substring(("chrome-extension://"+e.id+"/").length());
        Uri u=Uri.parse(url);if(u.getScheme()==null)return e.base()+url.replaceFirst("^/+","");
        if(u.getScheme().equals("http")||u.getScheme().equals("https")){if(extId(u.getHost())!=null&&!e.id.equals(extId(u.getHost())))throw new Exception("Cannot open another extension's page.");return url.startsWith("http://")?"https://"+url.substring(7):url;}
        if(url.startsWith("chrome://newtab")||url.equals("about:blank"))return MainActivity.HOME;
        throw new Exception("Cannot navigate to "+u.getScheme()+": URLs.");
    }
    JSONArray cookies(String url,String name)throws Exception{
        JSONArray out=new JSONArray();String raw=CookieManager.getInstance().getCookie(url);if(raw==null)return out;Uri u=Uri.parse(url);
        for(String part:raw.split(";\\s*")){int eq=part.indexOf('=');String k=eq<0?part:part.substring(0,eq),v=eq<0?"":part.substring(eq+1);if(!name.isEmpty()&&!name.equals(k))continue;
            out.put(new JSONObject().put("name",k).put("value",v).put("domain",u.getHost()).put("hostOnly",true).put("path","/").put("secure","https".equals(u.getScheme())).put("httpOnly",false).put("session",true).put("sameSite","unspecified").put("storeId","0"));}
        return out;
    }
    JSONObject area(Ext e,String name)throws Exception{
        switch(name){case "local":return e.local;case "sync":return e.sync;case "session":{JSONObject s=e.session.optJSONObject("_storage");if(s==null){s=new JSONObject();e.session.put("_storage",s);}return s;}case "managed":return new JSONObject();default:throw new Exception("Unknown storage area.");}
    }
    static JSONObject storageGet(JSONObject store,Object keys)throws Exception{
        JSONObject out=new JSONObject();
        if(keys==null||keys==JSONObject.NULL){for(Iterator<String> it=store.keys();it.hasNext();){String k=it.next();out.put(k,store.get(k));}}
        else if(keys instanceof String){if(store.has((String)keys))out.put((String)keys,store.get((String)keys));}
        else if(keys instanceof JSONArray){JSONArray a=(JSONArray)keys;for(int i=0;i<a.length();i++){String k=a.getString(i);if(store.has(k))out.put(k,store.get(k));}}
        else if(keys instanceof JSONObject){JSONObject d=(JSONObject)keys;for(Iterator<String> it=d.keys();it.hasNext();){String k=it.next();out.put(k,store.has(k)?store.get(k):d.get(k));}}
        return out;
    }

    /** chrome.scripting.executeScript / tabs.executeScript / insertCSS. */
    void execute(Ext e,Endpoint self,JSONObject req,String rid)throws Exception{
        JSONObject target=req.optJSONObject("target");if(target==null)target=new JSONObject();
        WebView v=target.has("tabId")&&!target.isNull("tabId")?tab(target.getInt("tabId")):(a.tabs.contains(self.view)?self.view:a.current);
        if(v==null)throw new Exception("No tab with id: "+target.optInt("tabId")+".");
        String url=a.tabUrl(v);boolean own=e.id.equals(extId(Uri.parse(url).getHost()));
        if(!own&&!hostAllowed(e,url)&&!(e.has("activeTab")&&v==a.current))throw new Exception("Cannot access contents of the page. Extension manifest must request permission to access the respective host.");
        JSONArray frameIds=target.optJSONArray("frameIds");boolean all=target.optBoolean("allFrames");
        StringBuilder code=new StringBuilder();JSONArray files=req.optJSONArray("files");if(files!=null)for(int i=0;i<files.length();i++)code.append(file(e,files.getString(i))).append("\n;\n");
        StringBuilder style=new StringBuilder(req.optString("css",""));JSONArray cssFiles=req.optJSONArray("cssFiles");if(cssFiles!=null)for(int i=0;i<cssFiles.length();i++)style.append(css(e,cssFiles.getString(i))).append('\n');
        if(req.has("code")&&!req.isNull("code"))code.append(req.getString("code"));
        String func=req.optString("func",""),args=req.optJSONArray("args")==null?"[]":req.getJSONArray("args").toString();
        if("MAIN".equalsIgnoreCase(req.optString("world"))||own){
            String js=!func.isEmpty()?"("+func+").apply(window,"+args+")":code.toString();
            v.evaluateJavascript(js,value->{try{reply(self.proxy,rid,new JSONArray().put(new JSONObject().put("frameId",0).put("result",value==null||value.equals("null")?JSONObject.NULL:new JSONTokener(value).nextValue())),null);}catch(Exception ex){reply(self.proxy,rid,new JSONArray(),null);}});return;
        }
        List<Endpoint> frames=new ArrayList<>();for(Endpoint p:endpoints)if(p.ext==e&&p.view==v&&p.kind.equals("content")&&(all||frameIds!=null&&contains(frameIds,String.valueOf(p.frameId))||frameIds==null&&p.frameId==0))frames.add(p);
        if(frames.isEmpty())throw new Exception("The page is still loading or was opened before the extension was enabled. Reload the tab and try again.");
        Pending q=new Pending();q.proxy=self.proxy;q.replyId=rid;q.op="exec";q.waiting.addAll(frames);String eid=UUID.randomUUID().toString();pending.put(eid,q);
        JSONObject msg=new JSONObject().put("event","exec").put("eid",eid);
        if(style.length()>0)msg.put("css",style.toString()).put("remove",req.optBoolean("remove"));
        String q_eid=JSONObject.quote(eid);
        String source=!func.isEmpty()?"(function(){const done=globalThis.__voodooExecDone;try{Promise.resolve(("+func+"\n).apply(globalThis,"+args+")).then(v=>done("+q_eid+",v),e=>done("+q_eid+",null,String(e&&e.message||e)));}catch(e){done("+q_eid+",null,String(e&&e.message||e));}})();":code.toString();
        for(Endpoint p:frames){
            if(style.length()>0){try{p.proxy.postMessage(msg.toString());}catch(Exception ex){q.waiting.remove(p);}continue;}
            // Code runs in the extension's world at global scope without eval, so website CSP cannot block it.
            // A func reports its (possibly asynchronous) result through exec-result instead of the return value.
            Endpoint frame=p;boolean viaEvent=!func.isEmpty();p.proxy.executeJavaScript(source,new WebViewOutcomeReceiver<String,JavaScriptExecutionException>(){
                @Override public void onResult(String value){if(viaEvent)return;main.post(()->{if(q.done||!q.waiting.remove(frame))return;try{q.results.put(new JSONObject().put("frameId",frame.frameId).put("result",value==null||value.equals("null")?JSONObject.NULL:new JSONTokener(value).nextValue()));}catch(Exception ignored){}settle(q);});}
                @Override public void onError(JavaScriptExecutionException error){main.post(()->{if(q.done||!q.waiting.remove(frame))return;q.error=error.getMessage();settle(q);});}
            });
        }
        settle(q);main.postDelayed(()->{if(!q.done){q.waiting.clear();settle(q);}},30000);
    }

    /** Extension pages fetch granted hosts without CORS, like Chrome host permissions. */
    void fetch(Ext e,JSONObject r,JavaScriptReplyProxy proxy,String rid)throws Exception{
        String url=r.getString("url");if(!hostAllowed(e,url))throw new Exception("Host permission is required for "+Uri.parse(url).getHost());
        net.execute(()->{HttpURLConnection c=null;try{
            String method=r.optString("method","GET").toUpperCase(Locale.ROOT),address=url;JSONObject headers=r.optJSONObject("headers");byte[] body=r.optString("body").isEmpty()?null:Base64.getDecoder().decode(r.getString("body"));
            for(int hop=0;;hop++){
                if(hop>8)throw new IOException("Too many redirects.");if(!address.startsWith("https://")&&!address.startsWith("http://"))throw new IOException("Unsupported URL.");
                c=(HttpURLConnection)new URL(address).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(60000);c.setRequestMethod(method);
                if(headers!=null)for(Iterator<String> it=headers.keys();it.hasNext();){String k=it.next();if(!k.equalsIgnoreCase("host")&&!k.equalsIgnoreCase("content-length"))c.setRequestProperty(k,headers.getString(k));}
                if(!"omit".equals(r.optString("credentials"))){String cookie=CookieManager.getInstance().getCookie(address);if(cookie!=null)c.setRequestProperty("Cookie",cookie);}
                if(c.getRequestProperty("User-Agent")==null)c.setRequestProperty("User-Agent",WebSettings.getDefaultUserAgent(a));
                if(body!=null&&!method.equals("GET")&&!method.equals("HEAD")){c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body);}}
                int code=c.getResponseCode();
                List<String> setCookies=c.getHeaderFields().get("Set-Cookie");if(setCookies!=null)for(String sc:setCookies)CookieManager.getInstance().setCookie(address,sc);
                if(code>=300&&code<400&&c.getHeaderField("Location")!=null){address=new java.net.URI(address).resolve(c.getHeaderField("Location")).toString();if(code==303||(code==301||code==302)&&method.equals("POST")){method="GET";body=null;}c.disconnect();continue;}
                ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=code>=400?c.getErrorStream():c.getInputStream()){if(in!=null){byte[] b=new byte[16384];int n;while((n=in.read(b))>0){if(out.size()+n>25_000_000)throw new IOException("Response exceeds 25 MB.");out.write(b,0,n);}}}
                JSONObject h=new JSONObject();for(Map.Entry<String,List<String>> en:c.getHeaderFields().entrySet())if(en.getKey()!=null&&!en.getKey().equalsIgnoreCase("set-cookie"))h.put(en.getKey().toLowerCase(Locale.ROOT),String.join(", ",en.getValue()));
                JSONObject result=new JSONObject().put("status",code).put("statusText",c.getResponseMessage()==null?"":c.getResponseMessage()).put("headers",h).put("url",address).put("base64",Base64.getEncoder().encodeToString(out.toByteArray()));
                main.post(()->reply(proxy,rid,result,null));return;
            }
        }catch(Exception ex){String m=ex.getMessage()==null?"Network error":ex.getMessage();main.post(()->reply(proxy,rid,null,m));}finally{if(c!=null)c.disconnect();}});
    }

    // ---------- request interception (WebView IO threads) ----------
    static WebResourceResponse empty(int code,String reason){return new WebResourceResponse("text/plain","UTF-8",code,reason,Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    WebResourceResponse intercept(WebView view,WebResourceRequest r,boolean tab){
        Uri u=r.getUrl();String host=u.getHost();String id=extId(host);
        if(id!=null){
            Ext e=snapshot.get(id);if(e==null||!e.enabled)return empty(404,"Not Found");
            String path=u.getPath()==null||u.getPath().equals("/")?"/index.html":u.getPath();
            if(tab&&!r.isForMainFrame()){
                String ref=null;for(Map.Entry<String,String> h:r.getRequestHeaders().entrySet())if(h.getKey().equalsIgnoreCase("Referer"))ref=h.getValue();
                boolean fromSelf=ref!=null&&id.equals(extId(Uri.parse(ref).getHost()));
                if(!fromSelf&&!ExtensionPackage.webAccessible(e.war,path))return empty(404,"Not Found");
            }
            try{
                byte[] body;String mime;
                if(path.equals("/_generated_background_page.html")){body=generatedBackground(e).getBytes(StandardCharsets.UTF_8);mime="text/html";}
                else{
                    String decoded=Uri.decode(path).replaceFirst("^/+","");File f=new File(e.dir,decoded);
                    if(!f.getCanonicalPath().startsWith(e.dir.getCanonicalPath()+File.separator)||!f.isFile()||decoded.startsWith("_metadata/"))return empty(404,"Not Found");
                    body=java.nio.file.Files.readAllBytes(f.toPath());mime=ExtensionPackage.mime(decoded);
                }
                Map<String,String> headers=new HashMap<>();headers.put("Access-Control-Allow-Origin","*");headers.put("Cache-Control","no-cache");headers.put("X-Content-Type-Options","nosniff");
                return new WebResourceResponse(mime,mime.startsWith("text/")||mime.endsWith("javascript")||mime.endsWith("json")?"UTF-8":null,200,"OK",headers,new ByteArrayInputStream(body));
            }catch(Exception ex){return empty(404,"Not Found");}
        }
        if(!tab)return null;
        String url=u.toString();if(!url.startsWith("https://")&&!url.startsWith("http://"))return null;
        String accept=null,ref=null;for(Map.Entry<String,String> h:r.getRequestHeaders().entrySet()){if(h.getKey().equalsIgnoreCase("Accept"))accept=h.getValue();if(h.getKey().equalsIgnoreCase("Referer"))ref=h.getValue();}
        String initiator=null;try{initiator=r.isForMainFrame()?null:Uri.parse(ref!=null?ref:topUrls.getOrDefault(view,"")).getHost();}catch(Exception ignored){}
        ExtensionRules.Request q=new ExtensionRules.Request(url,initiator,ExtensionRules.type(r.isForMainFrame(),url,accept),r.getMethod());
        boolean blocked=false;
        for(Ext e:snapshot.values()){if(!e.enabled)continue;String d=e.rules.decide(q);if("block".equals(d))blocked=true;}
        if(!blocked)return null;
        if(r.isForMainFrame())return new WebResourceResponse("text/html","UTF-8",200,"OK",Collections.emptyMap(),new ByteArrayInputStream("<!doctype html><meta name=viewport content=\"width=device-width\"><body style=\"background:#000;color:#eee;font:16px sans-serif;padding:24px\"><h2>Blocked by an extension</h2><p>An installed extension blocked this page.</p>".getBytes(StandardCharsets.UTF_8)));
        return empty(403,"Blocked by extension");
    }

    // ---------- UI: Tools → Extensions ----------
    String popupPath(Ext e){JSONObject s=e.state.optJSONObject("action");String p=s==null?"":s.optString("popup","");if(p.isEmpty())p=e.action().optString("default_popup","");return p.isEmpty()?null:p.replaceFirst("^/","");}
    String optionsPage(Ext e){JSONObject ui=e.manifest.optJSONObject("options_ui");String p=ui!=null?ui.optString("page",""):e.manifest.optString("options_page","");return p.isEmpty()?null:p.replaceFirst("^/","");}
    String panelPath(Ext e){JSONObject s=e.state.optJSONObject("action");String p=s==null?"":s.optString("panel","");if(p.isEmpty()){JSONObject sp=e.manifest.optJSONObject("side_panel");p=sp==null?"":sp.optString("default_path","");}return p.isEmpty()?null:p.replaceFirst("^/","");}
    void openOrFocus(String url){for(WebView v:a.tabs)if(url.equals(a.tabUrl(v))){a.showTab(v);return;}WebView v=a.newTab(url);if(v==null)a.toast("Close a tab first (8-tab limit).");}
    void showPopup(Ext e,String path){
        if(path==null){a.toast(e.name()+" has no popup.");return;}
        if(e.popup!=null)e.popup.dismiss();
        WebView v=page(e,"page");v.setBackgroundColor(0xFFFFFFFF);v.loadUrl(e.base()+path);
        FrameLayout box=new FrameLayout(a);box.addView(v,new FrameLayout.LayoutParams(-1,a.dp(560)));
        AlertDialog d=new AlertDialog.Builder(a).setTitle(a.label(e.name(),MainActivity.SEAFOAM)).setView(box).setNegativeButton("Close",null).create();
        d.setOnDismissListener(x->{forget(v);v.destroy();if(e.popup==d)e.popup=null;});e.popup=d;d.show();
    }
    String badge(Ext e){JSONObject s=e.state.optJSONObject("action");String b=s==null?"":s.optString("badgeText","");return b.isEmpty()?"":" ["+b+"]";}
    void menu(){
        if(!supported()){a.toast("Update Android System WebView to use Chrome extensions.");return;}
        List<Ext> list=new ArrayList<>(exts.values());CharSequence[] items=new CharSequence[list.size()+2];
        for(int i=0;i<list.size();i++){Ext e=list.get(i);items[i]=a.label((e.enabled?"● ":"○ ")+e.name()+" "+e.manifest.optString("version")+badge(e),e.enabled?MainActivity.SEAFOAM:MainActivity.MUTED);}
        items[list.size()]=a.label("＋ Add from Chrome Web Store",MainActivity.GOLD);items[list.size()+1]=a.label("＋ Install .crx / .zip file",MainActivity.CYAN);
        new AlertDialog.Builder(a).setTitle(a.label("Extensions",MainActivity.PINK)).setItems(items,(d,w)->{if(w<list.size())extensionMenu(list.get(w));else if(w==list.size())storePrompt();else pickFile();}).setNegativeButton("Close",null).show();
    }
    void extensionMenu(Ext e){
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(e.enabled){
            String popup=popupPath(e);
            if(popup!=null){labels.add("Open popup");actions.add(()->showPopup(e,popup));}
            else if(e.manifest.has("action")||e.manifest.has("browser_action")||e.manifest.has("page_action")){labels.add("Run on this page");actions.add(()->{try{event(e,null,new JSONObject().put("event","action-clicked").put("tab",a.current==null?JSONObject.NULL:tabJson(a.current,e)));}catch(Exception ignored){}});}
            String panel=panelPath(e);if(panel!=null){labels.add("Open side panel");actions.add(()->showPopup(e,panel));}
            JSONArray menus=e.state.optJSONArray("menus");if(menus!=null)for(int i=0;i<menus.length();i++){JSONObject m=menus.optJSONObject(i);if(m==null||"separator".equals(m.optString("type"))||m.optBoolean("visible",true)==false||m.optString("title").isEmpty())continue;
                String title=m.optString("title").replace("%s","").replace("&&","&");labels.add("Menu: "+title);actions.add(()->{try{WebView v=a.current;JSONObject info=new JSONObject().put("menuItemId",m.opt("id")).put("editable",false).put("pageUrl",v==null?"":a.tabUrl(v));if(m.has("parentId"))info.put("parentMenuItemId",m.get("parentId"));event(e,null,new JSONObject().put("event","menu-clicked").put("info",info).put("tab",v==null?JSONObject.NULL:tabJson(v,e)));}catch(Exception ignored){}});}
            JSONObject commands=e.manifest.optJSONObject("commands");if(commands!=null)for(Iterator<String> it=commands.keys();it.hasNext();){String name=it.next();if(name.startsWith("_execute"))continue;String desc=commands.optJSONObject(name)==null?name:commands.optJSONObject(name).optString("description",name);labels.add("Command: "+desc);actions.add(()->{try{event(e,null,new JSONObject().put("event","command").put("command",name).put("tab",a.current==null?JSONObject.NULL:tabJson(a.current,e)));}catch(Exception ignored){}});}
            String opts=optionsPage(e);if(opts!=null){labels.add("Options");actions.add(()->openOrFocus(e.base()+opts));}
        }
        labels.add(e.enabled?"Disable":"Enable");actions.add(()->setEnabled(e,!e.enabled));
        labels.add("Details & permissions");actions.add(()->details(e));
        if(e.source.equals("store")){labels.add("Update from Chrome Web Store");actions.add(()->download(e.id));}
        labels.add("Remove");actions.add(()->new AlertDialog.Builder(a).setTitle("Remove "+e.name()+"?").setMessage("Its files, settings and stored data are deleted from this browser.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->remove(e)).show());
        new AlertDialog.Builder(a).setTitle(a.label(e.name()+badge(e),MainActivity.SEAFOAM)).setItems(labels.toArray(new CharSequence[0]),(d,w)->actions.get(w).run()).setNegativeButton("Back",(d,w)->menu()).show();
    }
    void details(Ext e){
        StringBuilder s=new StringBuilder();s.append("Version ").append(e.manifest.optString("version")).append(" · Manifest V").append(e.manifest.optInt("manifest_version")).append("\nID ").append(e.id).append("\nSource: ").append(e.source.equals("store")?"Chrome Web Store (signature verified)":e.source.equals("crx")?"CRX file (signature verified)":"unsigned ZIP");
        s.append("\n\n").append(e.manifest.optString("description")).append("\n\nPermissions: ").append(e.apis.isEmpty()?"none":String.join(", ",e.apis)).append("\nSite access: ").append(e.hosts.isEmpty()?"none":String.join(", ",e.hosts));
        ExtensionRules r=e.rules;if(r.count>0||r.unsupported>0)s.append("\n\nNetwork rules active: ").append(r.count).append(r.unsupported>0?" ("+r.unsupported+" redirect/header rules are not supported on Android)":"");
        JSONArray errors=e.state.optJSONArray("errors");if(errors!=null&&errors.length()>0){s.append("\n\nRecent errors:");for(int i=0;i<Math.min(5,errors.length());i++)s.append("\n• ").append(errors.optString(i));}
        new AlertDialog.Builder(a).setTitle(a.label(e.name(),MainActivity.SEAFOAM)).setMessage(s.toString()).setPositiveButton("Back",(d,w)->extensionMenu(e)).show();
    }
    void setEnabled(Ext e,boolean on){
        e.enabled=on;saveIndex();publish();
        if(on)startBackground(e,"startup",null);else{stopBackground(e);if(e.popup!=null)e.popup.dismiss();for(Endpoint p:new ArrayList<>(endpoints))if(p.ext==e)drop(p);}
        reinstallAll();a.toast(e.name()+(on?" enabled. Reload open pages to apply it.":" disabled."));
    }
    void remove(Ext e){
        stopBackground(e);if(e.popup!=null)e.popup.dismiss();for(Endpoint p:new ArrayList<>(endpoints))if(p.ext==e)drop(p);
        exts.remove(e.id);saveIndex();publish();prefs.edit().remove("state."+e.id).apply();reinstallAll();
        io.execute(()->{delete(e.dir);delete(new File(dataRoot,e.id));});a.toast(e.name()+" removed.");
    }
    static void delete(File f){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)delete(k);f.delete();}
    Ext reload(String id){Ext old=exts.get(id);if(old==null)return null;stopBackground(old);for(Endpoint p:new ArrayList<>(endpoints))if(p.ext==old)drop(p);
        try{JSONObject info=new JSONObject().put("enabled",old.enabled).put("source",old.source);exts.remove(id);Ext e=load(id,info);publish();reinstallAll();return e;}catch(Exception ex){a.toast("Reload failed: "+ex.getMessage());return null;}}

    void storePrompt(){
        EditText input=new EditText(a);input.setSingleLine(true);input.setHint("Web Store link or extension ID");
        WebView site=a.current;if(site!=null){String u=a.tabUrl(site);if(u.contains("chromewebstore.google.com")||u.contains("chrome.google.com/webstore"))input.setText(u);}
        new AlertDialog.Builder(a).setTitle(a.label("Add from Chrome Web Store",MainActivity.GOLD)).setMessage("Open an extension on chromewebstore.google.com, then paste its link (or 32-letter ID). The package is downloaded from Google and its signature is verified.").setView(input)
            .setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->{String id=ExtensionPackage.storeId(input.getText().toString());if(id==null)a.toast("That is not a Chrome Web Store extension link or ID.");else download(id);}).show();
    }
    void download(String id){
        a.toast("Downloading extension…");
        net.execute(()->{try{
            byte[] data=httpBytes(STORE_CRX+id+"%26installsource%3Dondemand%26uc",120_000_000);
            ExtensionPackage.Archive archive=ExtensionPackage.open(data);
            if(!archive.signed||!id.equals(archive.id))throw new SecurityException("The downloaded package is not signed for extension "+id+".");
            a.runOnUiThread(()->review(archive,"store"));
        }catch(Exception ex){String m=ex.getMessage();a.runOnUiThread(()->a.toast("Extension download failed: "+m));}});
    }
    static byte[] httpBytes(String address,int max)throws Exception{
        for(int hop=0;hop<8;hop++){
            HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            try{int code=c.getResponseCode();
                if(code>=300&&code<400){String next=new java.net.URI(address).resolve(c.getHeaderField("Location")).toString();if(!next.startsWith("https://"))throw new IOException("Insecure redirect.");address=next;continue;}
                if(code==204||code==404)throw new IOException("The Web Store did not return this extension (it may be unavailable or Chrome-only).");
                if(code!=200)throw new IOException("HTTP "+code);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>0){if(out.size()+n>max)throw new IOException("Extension is too large.");out.write(b,0,n);}return out.toByteArray();}
            }finally{c.disconnect();}
        }
        throw new IOException("Too many redirects.");
    }
    void pickFile(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/x-chrome-extension","application/zip","application/octet-stream","application/x-zip-compressed"});
        try{a.startActivityForResult(intent,12);}catch(Exception ex){a.toast("No file picker is available.");}
    }
    void picked(Uri uri){
        if(uri==null)return;
        net.execute(()->{try(InputStream in=a.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot read the file.");byte[] b=new byte[16384];int n;while((n=in.read(b))>0){if(out.size()+n>120_000_000)throw new IOException("Extension is too large.");out.write(b,0,n);}
            ExtensionPackage.Archive archive=ExtensionPackage.open(out.toByteArray());a.runOnUiThread(()->review(archive,archive.signed?"crx":"zip"));
        }catch(Exception ex){String m=ex.getMessage();a.runOnUiThread(()->a.toast("Extension could not be opened: "+m));}});
    }
    /** Unpack to staging, show the manifest's permissions, install only after approval. */
    void review(ExtensionPackage.Archive archive,String source){
        File staging=new File(codeRoot,".staging-"+UUID.randomUUID());
        io.execute(()->{try{
            staging.mkdirs();String prefix=ExtensionPackage.unpack(archive.zip,staging);File root=prefix.isEmpty()?staging:new File(staging,prefix);
            JSONObject raw=ExtensionPackage.manifest(ExtensionPackage.read(new File(root,"manifest.json")));JSONObject messages=ExtensionPackage.messages(root,raw,Locale.getDefault());JSONObject m=new JSONObject(ExtensionPackage.localize(raw.toString(),messages));
            String id=archive.id!=null?archive.id:ExtensionPackage.unpackedId(raw);
            a.runOnUiThread(()->{
                Ext old=exts.get(id);List<String> apis=ExtensionPackage.apiPermissions(m),hosts=ExtensionPackage.hostPermissions(m);
                StringBuilder s=new StringBuilder();s.append(m.optString("name")).append(" ").append(m.optString("version")).append(old!=null?" (update from "+old.manifest.optString("version")+")":"").append("\n\n");
                s.append(source.equals("zip")?"⚠ Unsigned ZIP: its origin cannot be verified.\n\n":"Signature verified.\n\n");
                s.append("It can:\n• Access ").append(hosts.contains("<all_urls>")||hosts.contains("*://*/*")?"all websites you visit":hosts.isEmpty()?"no websites":String.join(", ",hosts)).append("\n• Use: ").append(apis.isEmpty()?"no extra browser APIs":String.join(", ",apis));
                s.append("\n\nExtensions can read and change matching pages, including what you type. Install only extensions you trust. Voodoo has not audited this extension.");
                new AlertDialog.Builder(a).setTitle(a.label(old!=null?"Update extension?":"Add extension?",MainActivity.GOLD)).setMessage(s.toString())
                    .setNegativeButton("Cancel",(d,w)->io.execute(()->delete(staging))).setOnCancelListener(d->io.execute(()->delete(staging)))
                    .setPositiveButton(old!=null?"Update":"Add extension",(d,w)->commit(id,root,staging,source,old==null?null:old.manifest.optString("version"))).show();
            });
        }catch(Exception ex){delete(staging);String msg=ex.getMessage();a.runOnUiThread(()->a.toast("Extension could not be installed: "+msg));}});
    }
    void commit(String id,File root,File staging,String source,String previous){
        Ext old=exts.get(id);if(old!=null){stopBackground(old);if(old.popup!=null)old.popup.dismiss();for(Endpoint p:new ArrayList<>(endpoints))if(p.ext==old)drop(p);exts.remove(id);}
        io.execute(()->{try{
            File target=new File(codeRoot,id),trash=new File(codeRoot,".old-"+UUID.randomUUID());
            if(target.exists()&&!target.renameTo(trash))throw new IOException("Cannot replace the old version.");
            if(!root.renameTo(target)){if(trash.exists())trash.renameTo(target);throw new IOException("Cannot install files.");}
            delete(trash);delete(staging);
            a.runOnUiThread(()->{try{
                Ext e=load(id,new JSONObject().put("enabled",true).put("source",source.equals("store")||old!=null&&old.source.equals("store")&&!source.equals("zip")?"store":source));
                saveIndex();publish();reinstallAll();startBackground(e,previous==null?"install":"update",previous);
                a.toast(e.name()+(previous==null?" added. Reload open pages to use it.":" updated."));
            }catch(Exception ex){a.toast("Extension could not load: "+ex.getMessage());}});
        }catch(Exception ex){delete(staging);String m=ex.getMessage();a.runOnUiThread(()->a.toast("Extension could not be installed: "+m));}});
    }

    void close(){
        closed=true;for(Ext e:exts.values()){stopBackground(e);if(e.popup!=null)e.popup.dismiss();}
        endpoints.clear();pending.clear();ports.clear();io.shutdown();net.shutdownNow();
    }
}
