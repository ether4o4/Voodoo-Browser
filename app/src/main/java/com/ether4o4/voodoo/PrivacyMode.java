package com.ether4o4.voodoo;

import android.app.AlertDialog;
import android.content.*;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Private mode: private tabs use a separate throwaway WebView profile that is deleted
 * on exit and on the next launch. Optionally, all WebView traffic is routed through Tor.
 * Normal tabs are parked untouched and restored afterwards. Private tabs never write
 * tab lists, recent sites or page-failure records, and extensions and userscripts are
 * not injected into them.
 */
final class PrivacyMode {
    static final String PROFILE="voodoo-private";
    /** Nothing listens here, so traffic fails closed until Tor is ready. */
    static final String DEAD_PROXY="http://127.0.0.1:9";

    final MainActivity a;
    final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"voodoo-private");t.setDaemon(true);return t;});
    final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    final List<WebView> parked=new ArrayList<>();
    WebView parkedCurrent;
    volatile boolean active,usingTor;boolean torReady,closed;
    int session,headersSession=-1;
    long seed;
    ServiceConnection torConnection;
    /** org.torproject.jni.TorService, used reflectively: tor-android ships Java 21 class files and CI compiles with JDK 17. */
    volatile Object tor;
    static final String TOR_SERVICE="org.torproject.jni.TorService";
    Intent torIntent(){return new Intent().setClassName(a,TOR_SERVICE);}
    String shield;

    PrivacyMode(MainActivity activity){a=activity;}

    boolean supported(){
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)&&WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }
    boolean torSupported(){return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);}

    /** A crash or a killed process can leave the profile behind; delete it at launch. */
    void wipeLeftovers(){
        if(!supported())return;
        try{if(ProfileStore.getInstance().getAllProfileNames().contains(PROFILE))ProfileStore.getInstance().deleteProfile(PROFILE);}catch(RuntimeException ignored){}
    }

    // ---------- tab setup ----------
    /** Must run immediately after constructing a private tab, before any other WebView use. */
    void attach(WebView v){
        Profile p=ProfileStore.getInstance().getOrCreateProfile(PROFILE);
        WebViewCompat.setProfile(v,PROFILE);
        if(headersSession!=session&&WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS))try{
            headersSession=session;Set<String> all=Collections.singleton("*");
            p.addCustomHeader(new CustomHeader("Sec-GPC","1",all));p.addCustomHeader(new CustomHeader("DNT","1",all));
        }catch(RuntimeException ignored){}
    }
    void configure(WebView v){
        // Keep password managers and other autofill services from capturing private-tab input.
        v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s=v.getSettings();
        s.setUserAgentString(userAgent());
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        if(WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))try{
            WebSettingsCompat.setUserAgentMetadata(s,new UserAgentMetadata.Builder(WebSettingsCompat.getUserAgentMetadata(s)).setModel("").setPlatform("Android").setPlatformVersion("10.0.0").setArchitecture("").setBitness(0).setFullVersion(chromeMajor()+".0.0.0").build());
        }catch(RuntimeException ignored){}
        Profile p=WebViewCompat.getProfile(v);
        p.getCookieManager().setAcceptCookie(true);
        p.getCookieManager().setAcceptThirdPartyCookies(v,false);
        WebViewCompat.addDocumentStartJavaScript(v,shield(),Collections.singleton("*"));
    }
    String shield(){if(shield==null)shield=a.asset("privacy/shield.js").replace("__SEED__",String.valueOf(seed&0x7fffffffL));return shield;}
    String chromeMajor(){java.util.regex.Matcher m=java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(a));return m.find()?m.group(1):"131";}
    /** Chrome's reduced mobile user agent: no device model, Android version or WebView marker. */
    String userAgent(){return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"+chromeMajor()+".0.0.0 Mobile Safari/537.36";}
    String cookies(String url){
        if(!active||a.current==null)return CookieManager.getInstance().getCookie(url);
        try{return WebViewCompat.getProfile(a.current).getCookieManager().getCookie(url);}catch(RuntimeException e){return null;}
    }

    // ---------- entering and leaving ----------
    void toggle(){if(active)confirmExit();else choose();}
    void choose(){
        if(!supported()){a.toast("Private mode needs a newer Android System WebView. Update it from the Play Store.");return;}
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(a.dp(22),a.dp(8),a.dp(22),0);
        TextView info=new TextView(a);info.setTextColor(MainActivity.INK);info.setTextSize(14);
        info.setText("Private tabs keep nothing: cookies, site data, cache, history and tabs are deleted when you leave private mode or close the app. Location, camera, microphone, WebRTC, sensors and common fingerprinting surfaces are blocked or masked.\n\nYour normal tabs are kept and come back when you leave.");
        box.addView(info);
        CheckBox tor=new CheckBox(a);tor.setText("Hide my IP address with Tor (slower; some sites block Tor)");tor.setTextColor(MainActivity.SEAFOAM);tor.setChecked(a.prefs.getBoolean("privateTor",true));tor.setEnabled(torSupported());
        box.addView(tor);
        new AlertDialog.Builder(a).setTitle(a.label("Private mode",MainActivity.PINK)).setView(box).setNegativeButton("Cancel",null)
            .setPositiveButton("Start",(d,w)->{a.prefs.edit().putBoolean("privateTor",tor.isChecked()).apply();enter(tor.isChecked()&&torSupported());}).show();
    }
    void enter(boolean withTor){
        if(active||closed)return;
        a.saveTabs();
        session++;seed=new java.security.SecureRandom().nextLong();shield=null;
        parked.clear();parked.addAll(a.tabs);parkedCurrent=a.current;
        for(WebView v:parked){v.onPause();a.voice.leaving(v);}
        a.content.removeAllViews();a.tabs.clear();a.current=null;a.lastSite=null;
        active=true;usingTor=withTor;torReady=false;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        a.address.setImeOptions(a.address.getImeOptions()|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        indicate();
        if(!withTor){if(torSupported())ProxyController.getInstance().clearProxyOverride(a.getMainExecutor(),()->{});a.newTab(MainActivity.HOME);a.toast("Private mode on. Nothing from these tabs is kept.");return;}
        int mySession=session;
        // Fail closed first: no WebView request can leave the device until Tor is ready.
        proxy(DEAD_PROXY,()->{
            if(!active||session!=mySession)return;
            a.newTab(MainActivity.HOME);a.toast("Connecting to Tor… Websites load once the connection is ready.");
            startTor(mySession);
        });
    }
    void proxy(String rule,Runnable done){
        ProxyConfig config=new ProxyConfig.Builder().addProxyRule(rule).removeImplicitRules().build();
        ProxyController.getInstance().setProxyOverride(config,a.getMainExecutor(),done);
    }
    void startTor(int mySession){
        torConnection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){try{tor=binder.getClass().getMethod("getService").invoke(binder);}catch(Exception e){torFailed(mySession,"Tor could not start on this device.");return;}long deadline=System.currentTimeMillis()+120_000;poll(mySession,deadline);}
            @Override public void onServiceDisconnected(ComponentName name){tor=null;if(active&&usingTor&&session==mySession){torReady=false;indicate();a.toast("Tor stopped. Websites stay blocked until you leave and re-enter private mode.");}}
        };
        try{if(!a.bindService(torIntent(),torConnection,Context.BIND_AUTO_CREATE))throw new IllegalStateException();}
        catch(RuntimeException e){torConnection=null;torFailed(mySession,"Tor could not start on this device.");}
    }
    void poll(int mySession,long deadline){
        worker.execute(()->{
            Object t=tor;int port=-1;String established=null,progress=null;
            try{if(t!=null){port=(Integer)t.getClass().getMethod("getHttpTunnelPort").invoke(t);java.lang.reflect.Method info=t.getClass().getMethod("getInfo",String.class);established=(String)info.invoke(t,"status/circuit-established");progress=(String)info.invoke(t,"status/bootstrap-phase");}}catch(Exception ignored){}
            int p=port;String est=established,phase=progress;
            main.post(()->{
                if(!active||session!=mySession||closed)return;
                if(p>0&&"1".equals(est==null?null:est.trim())){
                    proxy("http://127.0.0.1:"+p,()->{if(!active||session!=mySession)return;torReady=true;indicate();a.toast("Tor connected. Your IP address is hidden from websites.");for(WebView v:a.tabs)if(!a.local(a.tabUrl(v)))v.reload();});
                    return;
                }
                if(System.currentTimeMillis()>deadline){torFailed(mySession,"Tor did not connect within 2 minutes"+(phase==null?".":" ("+phase.replaceAll(".*SUMMARY=\"([^\"]*)\".*","$1")+")."));return;}
                main.postDelayed(()->poll(mySession,deadline),700);
            });
        });
    }
    void torFailed(int mySession,String message){
        if(!active||session!=mySession)return;
        new AlertDialog.Builder(a).setTitle(a.label("Tor unavailable",MainActivity.GOLD)).setMessage(message+"\n\nWebsites stay blocked so your IP address is not exposed. You can retry, continue privately without Tor, or leave private mode.")
            .setPositiveButton("Retry",(d,w)->{stopTor();if(active&&session==mySession)startTor(mySession);})
            .setNeutralButton("Continue without Tor",(d,w)->{stopTor();usingTor=false;ProxyController.getInstance().clearProxyOverride(a.getMainExecutor(),()->{indicate();for(WebView v:a.tabs)v.reload();});})
            .setNegativeButton("Leave private mode",(d,w)->exit()).setCancelable(false).show();
    }
    void stopTor(){
        tor=null;torReady=false;
        if(torConnection!=null){try{a.unbindService(torConnection);}catch(RuntimeException ignored){}torConnection=null;}
        try{a.stopService(torIntent());}catch(RuntimeException ignored){}
    }
    void confirmExit(){
        new AlertDialog.Builder(a).setTitle(a.label("Leave private mode?",MainActivity.PINK)).setMessage("Private tabs close and their cookies, site data, cache and history are deleted. Your normal tabs come back.")
            .setNegativeButton("Stay",null).setPositiveButton("Leave and delete",(d,w)->exit()).show();
    }
    void exit(){
        if(!active)return;
        active=false;session++;
        for(WebView v:new ArrayList<>(a.tabs))a.discardTab(v,false);
        stopTor();
        if(torSupported())ProxyController.getInstance().clearProxyOverride(a.getMainExecutor(),()->{});
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        a.address.setImeOptions(a.address.getImeOptions()&~android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        a.tabs.addAll(parked);WebView back=parkedCurrent;parked.clear();parkedCurrent=null;indicate();
        if(a.tabs.isEmpty())a.newTab(MainActivity.HOME);else a.showTab(back!=null&&a.tabs.contains(back)?back:a.tabs.get(a.tabs.size()-1));
        a.tabButton.setContentDescription("Tabs ("+a.tabs.size()+")");
        deleteProfile(0);
        a.toast("Private data deleted.");
    }
    /** Destroyed WebViews release the profile asynchronously; retry briefly, then on next launch. */
    void deleteProfile(int attempt){
        if(active)return;
        try{if(!ProfileStore.getInstance().getAllProfileNames().contains(PROFILE)||ProfileStore.getInstance().deleteProfile(PROFILE))return;}catch(RuntimeException ignored){}
        if(attempt<10)main.postDelayed(()->deleteProfile(attempt+1),500);
    }
    void indicate(){
        if(a.address==null)return;
        a.address.setHint(!active?"Search or enter address":usingTor?(torReady?"Private · Tor":"Private · connecting to Tor…"):"Private search");
        a.address.setHintTextColor(active?MainActivity.PINK:MainActivity.MUTED);
        a.address.setBackground(active?a.rounded(0xFF1A0F1A,MainActivity.PINK):a.addressSurface());
    }
    /** Activity is going away: private data must not outlive it. */
    void close(){
        closed=true;
        if(active){active=false;stopTor();if(torSupported())try{ProxyController.getInstance().clearProxyOverride(a.getMainExecutor(),()->{});}catch(RuntimeException ignored){}}
        for(WebView v:parked)try{v.destroy();}catch(RuntimeException ignored){}
        parked.clear();worker.shutdownNow();
    }
    /** Called from onDestroy after private tabs are destroyed. */
    void wipeAfterClose(){try{ProfileStore.getInstance().deleteProfile(PROFILE);}catch(RuntimeException ignored){}}
}
