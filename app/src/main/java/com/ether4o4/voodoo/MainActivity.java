package com.ether4o4.voodoo;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String ORIGIN="https://appassets.androidplatform.net",HOME=ORIGIN+"/assets/home.html",STUDIO=ORIGIN+"/assets/studio/index.html",WORKBENCH=STUDIO+"#workbench";
    static final String WB="fourgauteau.workbench.v1";
    static final int PINK=0xFFFF77B7,SEAFOAM=0xFF62E8C1,CYAN=0xFF62D9FF,GOLD=0xFFFFD166,INK=0xFFEEE7EF,MUTED=0xFFB0ACB7;
    static final String[] TOOL_LABELS={"AI chat","Workbench","Reload page","Find in page","Reader view","Copy clean link","Pin / save bookmark","Bookmarks","Page note","Text size","Desktop mode","Share page","Print / save PDF","Downloads","Settings"};
    final ArrayList<WebView> tabs=new ArrayList<>();final Map<WebView,String> deferredTabs=new HashMap<>(),initialUrls=new HashMap<>();final Map<WebView,List<ScriptHandler>> injection=new HashMap<>();final Map<WebView,RecentSites.Visit> recentVisits=new HashMap<>();
    final ExecutorService io=Executors.newFixedThreadPool(2);final AiClient ai=new AiClient();
    android.content.SharedPreferences prefs;PinStore pins;RecentSites recentSites;SecureKeys keys;WebViewAssetLoader assets;ScriptCoordinator scripts;ScriptDiscovery discovery;LocalScriptAi localAi;VoiceController voice;
    LinearLayout layout,pinBar,pinStrip;Button workspaceButton;FrameLayout content;EditText address;ProgressBar progress;Button tabButton;
    WebView current,lastSite;ValueCallback<Uri[]> chooser;String exportText;ValueCallback<String> exportReply;
    View fullScreen;WebChromeClient.CustomViewCallback fullScreenCallback;
    String engine;boolean alive=true;
    static BridgeRuntime bridgeRuntime;
    static synchronized BridgeRuntime bridge(Context c){if(bridgeRuntime==null)bridgeRuntime=new BridgeRuntime(c);return bridgeRuntime;}

    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        bridge(this).attach(this);
        prefs=getSharedPreferences("voodoo_data_v1",MODE_PRIVATE);keys=new SecureKeys(this);pins=new PinStore(prefs);recentSites=new RecentSites(prefs);
        assets=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        engine=asset("studio/engine.js");scripts=new ScriptCoordinator(this);discovery=new ScriptDiscovery(this);localAi=new LocalScriptAi(this);voice=new VoiceController(this);
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(Color.BLACK);
        layout.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout top=new LinearLayout(this);top.setPadding(dp(8),dp(3),dp(8),dp(3));
        address=new EditText(this);address.setSingleLine();address.setTextSize(15);address.setHint("Search or enter address");address.setSelectAllOnFocus(false);address.setOnFocusChangeListener((v,focused)->{if(focused)address.setText("");else if(current!=null)showAddress(tabUrl(current));});address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setTextColor(CYAN);address.setHintTextColor(MUTED);address.setPadding(dp(14),0,dp(14),0);address.setBackground(addressSurface());address.setBackgroundTintList(null);
        address.setOnEditorActionListener((v,a,e)->{navigate(address.getText().toString());return true;});
        top.addView(address,new LinearLayout.LayoutParams(0,dp(48),1));top.addView(button("Go",()->navigate(address.getText().toString())),new LinearLayout.LayoutParams(dp(56),dp(48)));layout.addView(top);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);layout.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        pinBar=new LinearLayout(this);pinBar.setGravity(Gravity.CENTER_VERTICAL);pinBar.setPadding(dp(5),0,dp(5),0);
        workspaceButton=button("All ▾",this::workspaceMenu);workspaceButton.setTextSize(12);workspaceButton.setSingleLine(true);workspaceButton.setEllipsize(android.text.TextUtils.TruncateAt.END);pinBar.addView(workspaceButton,new LinearLayout.LayoutParams(dp(96),dp(48)));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);pinStrip=new LinearLayout(this);pinStrip.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(pinStrip);pinBar.addView(scroll,new LinearLayout.LayoutParams(0,dp(48),1));Button addPin=button("＋",this::choosePin);addPin.setContentDescription("Pin current website");addPin.setTextColor(SEAFOAM);pinBar.addView(addPin,new LinearLayout.LayoutParams(dp(48),dp(48)));layout.addView(pinBar);refreshPinBar();
        content=new FrameLayout(this);layout.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=new LinearLayout(this);
        bar.addView(nav("‹",()->{if(current.canGoBack())current.goBack();},"Back"));bar.addView(nav("›",()->{if(current.canGoForward())current.goForward();},"Forward"));
        tabButton=nav("▣",this::tabsMenu,"Tabs");bar.addView(tabButton);bar.addView(nav("⌂",()->current.loadUrl(HOME),"Home"));bar.addView(nav("⚒",this::toolsMenu,"Browser tools"));bar.addView(nav("☆",this::savedMenu,"Saved"));layout.addView(bar);setContentView(layout);
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){new AlertDialog.Builder(this).setTitle(label("Update Android System WebView",GOLD)).setMessage("Voodoo needs a newer Android System WebView for its built-in tools. Update it through Play Store, then reopen this app.").setPositiveButton("Close",(d,w)->finish()).setCancelable(false).show();return;}
        String incoming=getIntent().getDataString();
        if(incoming!=null&&webUrl(incoming))newTab(normalize(incoming));else{
            int selected=prefs.getInt("activeTab",0);JSONArray urls=array(prefs.getString("tabs","[]"));for(int i=0;i<Math.min(urls.length(),8);i++){String u=urls.optString(i);if(webUrl(u))newTab(u,false);}
            if(tabs.isEmpty())newTab(HOME);else showTab(tabs.get(Math.max(0,Math.min(selected,tabs.size()-1))));
        }
        applySettings();
        if (bridge(this).paired()) try { bridge(this).connect(); } catch (Exception ignored) {}
    }
    Set<String> origins(String... values){return new HashSet<>(Arrays.asList(values));}
    int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    android.graphics.drawable.GradientDrawable rounded(int fill,int stroke){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    android.graphics.drawable.Drawable touchSurface(){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x309BBEB4),rounded(Color.TRANSPARENT,0),rounded(Color.WHITE,0));}
    android.graphics.drawable.Drawable addressSurface(){android.graphics.drawable.StateListDrawable d=new android.graphics.drawable.StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},rounded(0xFF101014,SEAFOAM));d.addState(new int[]{},rounded(0xFF101014,0xFF4B4954));return d;}
    CharSequence label(String text,int color){android.text.SpannableString s=new android.text.SpannableString(text==null?"":text);s.setSpan(new android.text.style.ForegroundColorSpan(color),0,s.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return s;}
    Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(text.equals("Go")?SEAFOAM:INK);b.setBackground(touchSurface());b.setBackgroundTintList(null);b.setStateListAnimator(null);b.setElevation(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),0,dp(4),0);b.setOnClickListener(v->action.run());return b;}
    Button nav(String text,Runnable action,String label){Button b=button(text,action);b.setTextColor(label.equals("Home")?PINK:label.equals("Browser tools")?GOLD:label.equals("Forward")||label.equals("Shortcuts")?SEAFOAM:CYAN);b.setTextSize(label.equals("Browser tools")||label.equals("Tabs")?13:22);b.setContentDescription(label);b.setTooltipText(label);b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(50),1));return b;}
    void showAddress(String url){if(address.hasFocus())return;address.setTextColor(local(url)?PINK:CYAN);address.setText(local(url)?"Voodoo Browser":url);}
    String asset(String path){try(InputStream in=getAssets().open(path)){return AiClient.read(in,1000000);}catch(Exception e){throw new IllegalStateException("Missing asset "+path,e);}}
    JSONObject object(String raw){try{return new JSONObject(raw);}catch(Exception e){return new JSONObject();}}
    JSONArray array(String raw){try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}}
    boolean setting(String name,boolean fallback){return object(prefs.getString("options","{}")).optBoolean(name,fallback);}
    boolean local(String u){return u!=null&&u.startsWith(ORIGIN+"/");}
    boolean webUrl(String u){try{Uri uri=Uri.parse(u);return ("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;}catch(Exception e){return false;}}
    String normalize(String text){String s=text.trim();if(s.isEmpty())return HOME;if(s.startsWith("http://"))return "https://"+s.substring(7);if(s.startsWith("https://"))return webUrl(s)?s:HOME;if(!s.contains(" ")&&s.matches("[A-Za-z0-9.-]+\\.[A-Za-z]{2,}([/:?#].*)?"))return "https://"+s;return "https://www.google.com/search?q="+Uri.encode(s);}
    void navigate(String text){if(current==null)return;current.loadUrl(normalize(text));address.clearFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);}
    WebView newTab(String url){return newTab(url,true);}
    WebView newTab(String url,boolean activate){if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){toast("Update Android System WebView to open Voodoo.");return null;}if(tabs.size()>=8){toast("Close a tab first (8-tab limit).");return null;}
        WebView v=new WebView(this);recentVisits.put(v,new RecentSites.Visit());v.setBackgroundColor(Color.BLACK);WebSettings s=v.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSafeBrowsingEnabled(true);s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportMultipleWindows(true);s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(v,setting("thirdPartyCookies",true));
        WebViewCompat.addWebMessageListener(v,"VoodooHost",origins(ORIGIN),(view,message,origin,isMain,reply)->{if(!isMain||!ORIGIN.equals(origin.toString()))return;try{JSONObject req=new JSONObject(message.getData());handle(view,req,reply);}catch(Exception e){toast("Invalid workspace request.");}});
        v.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest r){if(ORIGIN.equals(r.getUrl().getScheme()+"://"+r.getUrl().getAuthority())){WebResourceResponse response=assets.shouldInterceptRequest(r.getUrl());return response!=null?response:new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}return null;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){String u=r.getUrl().toString();if(webUrl(u)){if(u.startsWith("http://")){view.loadUrl(normalize(u));return true;}return false;}if(r.hasGesture()&&("mailto".equals(r.getUrl().getScheme())||"tel".equals(r.getUrl().getScheme()))){new AlertDialog.Builder(MainActivity.this).setTitle(label("Open another app?",GOLD)).setMessage(u).setNegativeButton("Cancel",null).setPositiveButton("Open",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,r.getUrl()));}catch(Exception e){toast("No app can open this link.");}}).show();}return true;}
            @Override public void onPageStarted(WebView view,String u,android.graphics.Bitmap icon){voice.leaving(view);scripts.navigating(view);bridge(MainActivity.this).onNavigation(view,u);ai.cancelPrefix(System.identityHashCode(view)+":");RecentSites.Visit visit=recentVisits.get(view);if(visit!=null)visit.start();initialUrls.put(view,u);if(view==current){showAddress(u);progress.setVisibility(View.VISIBLE);discovery.attach(view);}if(!local(u))lastSite=view;}
            @Override public void onPageFinished(WebView view,String u){if(!alive)return;scripts.finished(view);RecentSites.Visit visit=recentVisits.get(view);if(visit!=null&&visit.finish(u,view.getUrl())&&view==current)recordRecentSite(view);initialUrls.put(view,u);if(view==current){showAddress(u);progress.setVisibility(View.GONE);discovery.attach(view);}homeActive(view,view==current);saveTabs();bridge(MainActivity.this).pageFinished(view,u);}
            @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError error){RecentSites.Visit visit=recentVisits.get(view);if(visit!=null)visit.sslFailure(error.getUrl(),view.getUrl(),initialUrls.get(view));handler.cancel();if(Objects.equals(error.getUrl(),view.getUrl())||Objects.equals(error.getUrl(),initialUrls.get(view)))bridge(MainActivity.this).pageFailed(view,error.getUrl());toast("The site's secure connection could not be verified.");}
            @Override public void onReceivedError(WebView view,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame()){bridge(MainActivity.this).pageFailed(view,r.getUrl().toString());RecentSites.Visit visit=recentVisits.get(view);if(visit!=null)visit.fail();if(view==current){progress.setVisibility(View.GONE);toast("Page failed to load: "+e.getDescription());}}}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest r,WebResourceResponse response){if(r.isForMainFrame()){bridge(MainActivity.this).pageFailed(view,r.getUrl().toString());RecentSites.Visit visit=recentVisits.get(view);if(visit!=null)visit.fail();}}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){closeTab(view);toast("The page process stopped. Open the page again.");return true;}
        });
        v.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int p){if(view==current)progress.setProgress(p);}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){if(chooser!=null)chooser.onReceiveValue(null);chooser=callback;try{Intent intent=params.createIntent();intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,10);}catch(Exception e){chooser.onReceiveValue(null);chooser=null;toast("No file picker is available.");}return true;}
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean user,Message msg){if(!user)return false;WebView next=newTab(null);if(next==null)return false;((WebView.WebViewTransport)msg.obj).setWebView(next);msg.sendToTarget();return true;}
            @Override public void onCloseWindow(WebView window){closeTab(window);}
            @Override public void onPermissionRequest(PermissionRequest request){request.deny();toast("Camera and microphone capture are not enabled in this first build.");}
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb){cb.invoke(origin,false,false);toast("Location access is off in this build.");}
            @Override public void onShowCustomView(View view,CustomViewCallback callback){if(fullScreen!=null){callback.onCustomViewHidden();return;}fullScreen=view;fullScreenCallback=callback;((FrameLayout)getWindow().getDecorView()).addView(view,new FrameLayout.LayoutParams(-1,-1));}
            @Override public void onHideCustomView(){hideFullscreen();}
            @Override public boolean onConsoleMessage(ConsoleMessage message){if(message.message().startsWith("[Voodoo script]")){String old=prefs.getString("scriptLog","");String updated=message.message()+"\n"+old;prefs.edit().putString("scriptLog",updated.substring(0,Math.min(12000,updated.length()))).apply();}return false;}
        });
        v.setDownloadListener((u,ua,disposition,mime,length)->download(u,ua,disposition,mime));
        bridge(this).install(v);tabs.add(v);if(url!=null){deferredTabs.put(v,url);initialUrls.put(v,url);}installScripts(v);if(activate)showTab(v);else v.onPause();return v;
    }
    String tabUrl(WebView view){String url=view.getUrl();return url==null?initialUrls.getOrDefault(view,HOME):url;}
    String tabTitle(WebView view){String title=view.getTitle();if(title!=null&&!title.trim().isEmpty())return title;String url=tabUrl(view);return local(url)?"Voodoo Browser":Uri.parse(url).getHost();}
    void homeActive(WebView view,boolean active){if(view!=null&&HOME.equals(tabUrl(view).split("#",2)[0]))view.evaluateJavascript("globalThis.VoodooHome?.setActive("+active+")",null);}
    void recordRecentSite(WebView view){RecentSites.Visit visit=recentVisits.get(view);if(visit!=null&&visit.ready(view.getUrl())&&recentSites.record(view.getUrl(),view.getTitle(),favicon(view)))for(WebView home:tabs)if(HOME.equals(tabUrl(home).split("#",2)[0]))home.evaluateJavascript("globalThis.VoodooHome?.refreshRecentSites()",null);}
    void notifyHome(String pinId){String arg=pinId==null?"null":JSONObject.quote(pinId);for(WebView view:tabs)if(HOME.equals(tabUrl(view).split("#",2)[0]))view.evaluateJavascript("globalThis.VoodooHome?.refreshPins("+arg+")",null);}
    JSONObject pinState()throws Exception{JSONObject names=new JSONObject();for(String group:PinStore.GROUPS)names.put(group,pins.name(group));return new JSONObject().put("pins",pins.all()).put("names",names).put("active",prefs.getString("activeWorkspace","all"));}
    boolean openPinned(String url){for(WebView view:tabs)if(url.equals(tabUrl(view))){showTab(view);return true;}if(tabs.size()>=8)return false;return newTab(url)!=null;}
    void workspaceMenu(){CharSequence[] names=new CharSequence[PinStore.GROUPS.length+1];names[0]=label("All workspaces",SEAFOAM);for(int i=0;i<PinStore.GROUPS.length;i++)names[i+1]=label(pins.name(PinStore.GROUPS[i]),Color.parseColor(PinStore.COLORS[i]));new AlertDialog.Builder(this).setTitle(label("Workspace pins",PINK)).setItems(names,(d,w)->{prefs.edit().putString("activeWorkspace",w==0?"all":PinStore.GROUPS[w-1]).apply();refreshPinBar();notifyHome(null);}).show();}
    void refreshPinBar(){if(pinStrip==null)return;pinStrip.removeAllViews();String active=prefs.getString("activeWorkspace","all");workspaceButton.setText((active.equals("all")?"All":pins.name(active))+" ▾");workspaceButton.setTextColor(active.equals("all")?SEAFOAM:Color.parseColor(PinStore.COLORS[PinStore.groupIndex(active)]));JSONArray all=pins.all();pinBar.setVisibility(all.length()==0?View.GONE:View.VISIBLE);for(int i=0;i<all.length();i++){JSONObject pin=all.optJSONObject(i);if(pin==null||(!active.equals("all")&&!active.equals(pin.optString("group"))))continue;
        LinearLayout chip=new LinearLayout(this);chip.setBackground(touchSurface());chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(10),0,dp(10),0);TextView head=new TextView(this);head.setText("●");head.setTextColor(Color.parseColor(PinStore.COLORS[PinStore.groupIndex(pin.optString("group"))]));head.setPadding(0,0,dp(5),0);chip.addView(head);
        String icon=pin.optString("icon");boolean added=false;try{if(icon.startsWith("data:image/png;base64,")){byte[] raw=android.util.Base64.decode(icon.substring(22),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(raw,0,raw.length);if(bitmap!=null){ImageView img=new ImageView(this);img.setImageBitmap(bitmap);chip.addView(img,new LinearLayout.LayoutParams(dp(18),dp(18)));added=true;}}}catch(Exception ignored){}
        TextView text=new TextView(this);String title=pin.optString("title","Site");text.setText((added?"  ":"◈ ")+title);text.setTextSize(12);text.setTextColor(Color.parseColor(PinStore.COLORS[PinStore.groupIndex(pin.optString("group"))]));text.setMaxWidth(dp(115));text.setSingleLine();text.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.addView(text);chip.setContentDescription(title+", "+pins.name(pin.optString("group")));chip.setOnClickListener(v->{if(!openPinned(pin.optString("url")))toast("Close a tab first (8-tab limit).");});chip.setOnLongClickListener(v->{pinMenu(pin);return true;});pinStrip.addView(chip,new LinearLayout.LayoutParams(-2,dp(48)));
        }if(pinStrip.getChildCount()==0){TextView empty=new TextView(this);empty.setText("Nothing pinned here yet");empty.setTextColor(MUTED);empty.setTextSize(11);empty.setPadding(dp(9),0,0,0);pinStrip.addView(empty);}}
    String favicon(WebView view){try{android.graphics.Bitmap b=view.getFavicon();if(b==null)return null;android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(b,32,32,true);ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);return "data:image/png;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);}catch(Exception e){return null;}}
    JSONObject savePin(String url,String title,String group,String icon)throws Exception{JSONObject pin=pins.save(url,title,group,icon);JSONArray bookmarks=array(prefs.getString("bookmarks","[]"));boolean exists=false;for(int i=0;i<bookmarks.length();i++)if(url.equals(bookmarks.getJSONObject(i).optString("url")))exists=true;if(!exists&&bookmarks.length()<1000){bookmarks.put(new JSONObject().put("title",pin.optString("title")).put("url",url));prefs.edit().putString("bookmarks",bookmarks.toString()).apply();}refreshPinBar();notifyHome(pin.optString("id"));return pin;}
    void choosePin(){WebView target=site();if(target==null){toast("Open a website to pin it, or use Pin a site on the homepage.");return;}String url=target.getUrl(),title=target.getTitle(),icon=favicon(target);CharSequence[] names=new CharSequence[PinStore.GROUPS.length];for(int i=0;i<PinStore.GROUPS.length;i++)names[i]=label(pins.name(PinStore.GROUPS[i]),Color.parseColor(PinStore.COLORS[i]));new AlertDialog.Builder(this).setTitle(label("Pin to workspace",PINK)).setItems(names,(d,w)->{try{savePin(url,title,PinStore.GROUPS[w],icon);toast("Pinned to "+names[w]+".");}catch(Exception e){toast(e.getMessage());}}).show();}
    void pinMenu(JSONObject pin){CharSequence[] actions=new CharSequence[PinStore.GROUPS.length+1];for(int i=0;i<PinStore.GROUPS.length;i++)actions[i]=label("Move to "+pins.name(PinStore.GROUPS[i]),Color.parseColor(PinStore.COLORS[i]));actions[PinStore.GROUPS.length]="Unpin (keep bookmark)";new AlertDialog.Builder(this).setTitle(label(pin.optString("title"),SEAFOAM)).setItems(actions,(d,w)->{try{if(w==PinStore.GROUPS.length)pins.remove(pin.getString("id"));else pins.update(new JSONObject().put("id",pin.getString("id")).put("group",PinStore.GROUPS[w]));refreshPinBar();notifyHome(null);}catch(Exception e){toast(e.getMessage());}}).show();}
    void hideFullscreen(){if(fullScreen!=null){((FrameLayout)getWindow().getDecorView()).removeView(fullScreen);fullScreen=null;if(fullScreenCallback!=null)fullScreenCallback.onCustomViewHidden();fullScreenCallback=null;}}
    void showTab(WebView view){if(current!=null&&current!=view){voice.leaving(current);homeActive(current,false);current.onPause();}current=view;content.removeAllViews();content.addView(view,new FrameLayout.LayoutParams(-1,-1));view.onResume();String deferred=deferredTabs.remove(view);if(deferred!=null)view.loadUrl(deferred);discovery.attach(view);homeActive(view,true);recordRecentSite(view);if(!local(tabUrl(view)))lastSite=view;showAddress(tabUrl(view));tabButton.setContentDescription("Tabs ("+tabs.size()+")");saveTabs();}
    void closeTab(WebView view){if(!tabs.contains(view))return;bridge(this).closed(view);voice.leaving(view);scripts.remove(view,true);ai.cancelPrefix(System.identityHashCode(view)+":");tabs.remove(view);recentVisits.remove(view);deferredTabs.remove(view);initialUrls.remove(view);if(lastSite==view)lastSite=null;if(current==view){content.removeView(view);current=null;}view.destroy();if(tabs.isEmpty())newTab(HOME);else if(current==null)showTab(tabs.get(tabs.size()-1));tabButton.setContentDescription("Tabs ("+tabs.size()+")");saveTabs();}
    void saveTabs(){JSONArray urls=new JSONArray();for(WebView v:tabs){String u=tabUrl(v);urls.put(webUrl(u)?u:HOME);}prefs.edit().putString("tabs",urls.toString()).putInt("activeTab",Math.max(0,tabs.indexOf(current))).apply();}
    void openLocal(String url){String base=url.split("#",2)[0];for(WebView v:tabs)if(base.equals(tabUrl(v).split("#",2)[0])){if(url.contains("#")&&!url.equals(tabUrl(v))){if(deferredTabs.containsKey(v)){deferredTabs.put(v,url);initialUrls.put(v,url);}else v.loadUrl(url);}showTab(v);return;}newTab(url);}
    JSONArray userScripts(){JSONArray scripts=object(prefs.getString("studio","{}")).optJSONArray("scripts");return scripts==null?new JSONArray():scripts;}
    void installScripts(WebView v){scripts.install(v);}
    void applySettings(){refreshPinBar();notifyHome(null);getWindow().getDecorView().setKeepScreenOn(setting("keepAwake",false));for(WebView v:tabs){CookieManager.getInstance().setAcceptThirdPartyCookies(v,setting("thirdPartyCookies",true));installScripts(v);}}
    void reply(JavaScriptReplyProxy proxy,String id,Object data,String error){if(!alive)return;try{JSONObject result=new JSONObject().put("id",id).put("ok",error==null);if(error==null)result.put("data",data==null?JSONObject.NULL:data);else result.put("error",error);proxy.postMessage(result.toString());}catch(Exception ignored){}}
    void handle(WebView source,JSONObject req,JavaScriptReplyProxy reply){String id=req.optString("id"),op=req.optString("op");try{
        switch(op){
        case "get-store":{String k=req.getString("key");allowedStore(k);String raw=prefs.getString(k,null);reply(reply,id,raw==null?null:new JSONTokener(raw).nextValue(),null);break;}
        case "restore-backup":{
            JSONObject backup=req.getJSONObject("backup");android.content.SharedPreferences.Editor edit=prefs.edit();
            for(String key:Arrays.asList("studio","options","notes","bookmarks","pins","workbench")){
                Object value=backup.opt(key);if(value==null||value==JSONObject.NULL)continue;String storageKey=key.equals("workbench")?WB:key;if(value.toString().length()>4500000)throw new Exception("Backup section is too large.");
                if(key.equals("studio")){JSONObject studio=(JSONObject)value;JSONArray installed=studio.getJSONArray("scripts");if(installed.length()>100)throw new Exception("Too many scripts.");for(int i=0;i<installed.length();i++){JSONObject script=installed.getJSONObject(i);new ScriptRules(script.getString("code"));script.put("enabled",false);}scripts.validateStore(studio);studio.put("_revision",object(prefs.getString("studio","{}")).optInt("_revision")+1);}
                if(key.equals("options")){JSONObject options=(JSONObject)value;if(options.has("workspaceNames"))options.put("workspaceNames",PinStore.workspaceNames(options.getJSONObject("workspaceNames")));}if(key.equals("pins"))PinStore.validate((JSONArray)value);if(key.equals("workbench"))((JSONObject)value).put("_revision",object(prefs.getString(WB,"{}")).optInt("_revision")+1);edit.putString(storageKey,value.toString());
            }
            if(!edit.commit())throw new Exception("Backup could not be restored.");scripts.restoreData(backup.optJSONObject("scriptRuntime"));applySettings();reply(reply,id,true,null);break;
        }
        case "put-store":{String k=req.getString("key");allowedStore(k);Object value=req.get("value");if(k.equals("pins"))throw new Exception("Use pin controls to change saved sites.");if(k.equals("options")&&((JSONObject)value).has("workspaceNames"))((JSONObject)value).put("workspaceNames",PinStore.workspaceNames(((JSONObject)value).getJSONObject("workspaceNames")));String raw=value.toString();if(raw.length()>5000000)throw new Exception("This storage area is full. Export a backup first.");if(req.has("expectedRevision")&&object(prefs.getString(k,"{}")).optInt("_revision")!=req.getInt("expectedRevision"))throw new Exception("This workspace changed in another tab. Export your draft, then reload this tab.");if(k.equals("studio")){scripts.save((JSONObject)value,req.has("expectedRevision")?req.getInt("expectedRevision"):null,()->reply(reply,id,true,null),error->reply(reply,id,null,error));break;}if(!prefs.edit().putString(k,raw).commit())throw new Exception("Could not save changes.");if(k.equals("options"))applySettings();reply(reply,id,true,null);break;}
        case "speech-listen":voice.listen(source,reply,id);return;
        case "speech-speak":voice.speak(source,reply,id,req.getString("text"));return;
        case "speech-stop":voice.stop();reply(reply,id,true,null);break;
        case "recent-sites":reply(reply,id,recentSites.latest(),null);break;
        case "pins":reply(reply,id,pinState(),null);break;
        case "pin-site":{JSONObject pin=savePin(req.getString("url"),req.optString("title"),req.optString("group"),null);reply(reply,id,pin,null);break;}
        case "update-pin":{JSONObject pin=pins.update(req.getJSONObject("pin"));refreshPinBar();notifyHome(pin.optString("id"));reply(reply,id,pin,null);break;}
        case "delete-pin":pins.remove(req.getString("pinId"));refreshPinBar();notifyHome(null);reply(reply,id,true,null);break;
        case "set-workspace":{String group=req.optString("group");prefs.edit().putString("activeWorkspace",group.equals("all")?"all":PinStore.group(group)).apply();refreshPinBar();notifyHome(null);reply(reply,id,true,null);break;}
        case "rename-workspaces":{JSONObject safe=PinStore.workspaceNames(req.getJSONObject("names"));JSONObject options=object(prefs.getString("options","{}"));options.put("workspaceNames",safe);if(!prefs.edit().putString("options",options.toString()).commit())throw new Exception("Names could not be saved.");refreshPinBar();notifyHome(null);reply(reply,id,true,null);break;}
        case "open-pin":{JSONObject pin=pins.find(req.getString("pinId"));if(!openPinned(pin.optString("url")))throw new Exception("Close a tab first (8-tab limit).");reply(reply,id,true,null);break;}
        case "open-workspace":{String group=req.optString("group","all");JSONArray all=pins.all();int opened=0,remaining=0;for(int i=0;i<all.length();i++){JSONObject pin=all.getJSONObject(i);if(!group.equals("all")&&!pin.optString("group").equals(group))continue;if(openPinned(pin.optString("url")))opened++;else remaining++;}reply(reply,id,new JSONObject().put("opened",opened).put("remaining",remaining),null);break;}
        case "open-url":{String u=req.getString("url");if(!webUrl(u))throw new Exception("Use an HTTPS address.");newTab(normalize(u));reply(reply,id,true,null);break;}
        case "navigate":navigate(req.getString("text"));reply(reply,id,true,null);break;
        case "open-workbench":openLocal(WORKBENCH);reply(reply,id,true,null);break;
        case "open-studio":openLocal(STUDIO);reply(reply,id,true,null);break;
        case "open-script-hub":openLocal(STUDIO+"#scripts");reply(reply,id,true,null);break;
        case "context":{WebView target=site();if(target==null)throw new Exception("Open a website first, then return to AI + Scripts.");JSONObject result=new JSONObject().put("url",target.getUrl()).put("title",target.getTitle());if(!req.optBoolean("includePage")){reply(reply,id,result,null);break;}target.evaluateJavascript("JSON.stringify({text:(document.body?.innerText||'').slice(0,12000),elements:[...document.querySelectorAll('button,input,textarea,select,[role=button]')].slice(0,100).map(e=>({tag:e.tagName,id:e.id,name:e.getAttribute('name'),type:e.getAttribute('type'),label:e.getAttribute('aria-label'),testid:e.getAttribute('data-testid'),text:e.matches('input,textarea,select')?'':e.textContent.slice(0,100)}))})",value->{try{result.put("page",new JSONObject(decode(value)));reply(reply,id,result,null);}catch(Exception e){reply(reply,id,null,"Could not read this page.");}});break;}
        case "run-script":{WebView target=site();if(target==null)throw new Exception("Open the script's website first.");if(!setting("scripts",true))throw new Exception("Enable userscripts in Settings first.");JSONObject script=req.getJSONObject("script");if(!new ScriptRules(script.optString("code")).allows(target.getUrl()))throw new Exception("This URL does not match the script.");scripts.runOnce(target,script,()->{reply(reply,id,"Script scheduled for this reload.",null);showTab(target);},error->reply(reply,id,null,error));break;}
        case "script-status":reply(reply,id,scripts.status(),null);break;
        case "local-ai-status":reply(reply,id,localAi.status(),null);break;
        case "local-ai-select":localAi.select(req.getString("modelId"));reply(reply,id,true,null);break;
        case "local-ai-pause":localAi.pause();reply(reply,id,true,null);break;
        case "local-ai-unload":localAi.unload();reply(reply,id,true,null);break;
        case "local-ai-delete":localAi.delete(req.getString("modelId"));reply(reply,id,true,null);break;
        case "local-ai-download":{
            String modelId=req.getString("modelId");JSONObject m=localAi.model(modelId);
            new AlertDialog.Builder(this).setTitle("Download optional coding model?").setMessage(m.getString("name")+"\n"+m.getLong("bytes")+" bytes\nLicense: "+m.getString("license")+"\n"+m.getString("licenseURL")+"\nUse Wi-Fi if appropriate. Weights stay in this app's private storage; browsing does not require them.")
                .setNegativeButton("Cancel",(d,w)->reply(reply,id,null,"Download cancelled.")).setPositiveButton("Download / resume",(d,w)->{try{localAi.startDownload(modelId);reply(reply,id,true,null);}catch(Exception e){reply(reply,id,null,e.getMessage());}}).setOnCancelListener(d->reply(reply,id,null,"Download cancelled.")).show();break;
        }
        case "local-ai-chat":localAi.generate(req,text->runOnUiThread(()->{if(!alive)return;try{reply.postMessage(new JSONObject().put("id",id).put("event","delta").put("text",text).toString());}catch(Exception ignored){}}),result->runOnUiThread(()->reply(reply,id,result,null)),error->runOnUiThread(()->reply(reply,id,null,error)));break;
        case "script-update":discovery.update(scripts.find(req.getString("scriptId")),result->reply(reply,id,result,null),error->reply(reply,id,null,error));break;
        case "script-rollback":scripts.rollback(req.getString("scriptId"),req.getInt("index"),()->reply(reply,id,true,null),error->reply(reply,id,null,error));break;
        case "script-backup":reply(reply,id,scripts.backup(),null);break;
        case "script-site-policy":scripts.blockSite(req.getString("scriptId"),req.getString("host"),req.getBoolean("blocked"));reply(reply,id,true,null);break;
        case "script-discovery":{WebView target=site();if(target==null)throw new Exception("Open an ordinary website first.");discovery.show(target);reply(reply,id,true,null);break;}
        case "profiles":{reply(reply,id,publicProfiles(),null);break;}
        case "save-profile":{
            JSONObject p=req.getJSONObject("profile");String profileId=p.getString("id");if(!profileId.matches("[a-z0-9_-]{1,40}"))throw new Exception("Invalid profile ID.");
            String base=p.getString("base").trim().replaceAll("/+$","");AiClient.endpoint(base);String kind=p.optString("kind","openai");if(!Arrays.asList("openai","anthropic","gemini").contains(kind))throw new Exception("Unsupported protocol.");
            JSONObject profiles=object(prefs.getString("profiles","{}")),previous=profiles.optJSONObject(profileId);String supplied=req.optString("key").trim();
            if(supplied.isEmpty()&&!req.optBoolean("clearKey")&&previous!=null&&!previous.optString("base").equals(base)&&keys.has(profileId))throw new Exception("Re-enter the key when changing its API base URL.");
            if(req.optBoolean("clearKey"))keys.put(profileId,"",base);else if(!supplied.isEmpty())keys.put(profileId,supplied,base);
            JSONObject safe=new JSONObject().put("id",profileId).put("base",base).put("kind",kind).put("model",p.optString("model").trim()).put("name",p.optString("name",profileId));profiles.put(profileId,safe);
            if(!prefs.edit().putString("profiles",profiles.toString()).commit())throw new Exception("Could not save provider.");reply(reply,id,true,null);break;
        }
        case "cancel-chat":ai.cancel(System.identityHashCode(source)+":"+req.getString("requestId"));reply(reply,id,true,null);break;
        case "models":case "chat":{
            String profileId=req.getString("profileId");JSONObject profile=object(prefs.getString("profiles","{}")).optJSONObject(profileId);if(profile==null)throw new Exception("Save a provider in Providers first.");
            String key;try{key=keys.get(profileId,profile.getString("base"));}catch(Exception e){throw new Exception("The saved key cannot be used with this endpoint. Re-enter it in API keys.");}
            String requestKey=System.identityHashCode(source)+":"+id;ai.reserve(requestKey);io.execute(()->{try{
                JSONObject result=ai.request(requestKey,profile,key,req,op.equals("models"),text->runOnUiThread(()->{if(!alive)return;try{reply.postMessage(new JSONObject().put("id",id).put("event","delta").put("text",text).toString());}catch(Exception ignored){}}));
                runOnUiThread(()->reply(reply,id,result,null));
            }catch(Exception e){String error=e.getMessage()==null?"Network request failed.":e.getMessage();if(!key.isEmpty())error=error.replace(key,"[redacted]");String safe=error;runOnUiThread(()->reply(reply,id,null,safe));}});break;
        }
        case "export-file":{if(exportText!=null)throw new Exception("Finish the current export first.");exportText=req.getString("text");if(exportText.length()>12000000){exportText=null;throw new Exception("Backup too large.");}Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(req.optString("mime","application/json").split(";")[0]).putExtra(Intent.EXTRA_TITLE,req.optString("filename","Voodoo-backup.json").replaceAll("[/\\\\]","-"));exportReply=value->reply(reply,id,value,"cancelled".equals(value)?"Export cancelled.":null);startActivityForResult(intent,11);break;}
        case "script-log":reply(reply,id,scripts.status().getJSONArray("logs").toString(2),null);break;
        default:throw new Exception("Unknown workspace action.");
        }
    }catch(Exception e){reply(reply,id,null,e.getMessage()==null?"Request failed.":e.getMessage());}}
    String decode(String s){try{Object value=new JSONTokener(s).nextValue();return value==JSONObject.NULL?"":String.valueOf(value);}catch(Exception e){return s;}}
    void allowedStore(String key)throws Exception{if(!Arrays.asList(WB,"studio","options","notes","bookmarks","pins").contains(key))throw new Exception("Unknown storage area.");}
    JSONArray publicProfiles()throws Exception{JSONObject profiles=object(prefs.getString("profiles","{}"));JSONArray result=new JSONArray();Iterator<String> it=profiles.keys();while(it.hasNext()){String id=it.next();JSONObject p=new JSONObject(profiles.getJSONObject(id).toString());p.put("hasKey",keys.has(id));result.put(p);}return result;}
    WebView site(){if(current!=null&&!local(current.getUrl())&&webUrl(current.getUrl()))return current;if(lastSite!=null&&tabs.contains(lastSite)&&webUrl(lastSite.getUrl())&&!local(lastSite.getUrl()))return lastSite;return null;}
    void tabsMenu(){
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(this);scroll.addView(rows);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(label("Tabs",CYAN)).setView(scroll).setPositiveButton("New tab",(d,w)->newTab(HOME)).setNegativeButton("Done",null).create();
        for(WebView tab:new ArrayList<>(tabs)){
            LinearLayout row=new LinearLayout(this);Button select=button((tab==current?"● ":"")+tabTitle(tab),()->{if(tabs.contains(tab)){showTab(tab);dialog.dismiss();}});
            select.setTextColor(tab==current?PINK:CYAN);select.setSingleLine(true);select.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(select,new LinearLayout.LayoutParams(0,dp(52),1));
            Button close=button("×",()->{closeTab(tab);rows.removeView(row);});close.setTextSize(24);close.setContentDescription("Close "+tabTitle(tab));row.addView(close,new LinearLayout.LayoutParams(dp(52),dp(52)));rows.addView(row);
        }dialog.show();
    }
    void savedMenu(){
        CharSequence[] choices={label("Favorites",SEAFOAM),label("Saved sites",GOLD),"All bookmarks","Pin current site","Rename pin groups"};
        new AlertDialog.Builder(this).setTitle(label("Saved",GOLD)).setItems(choices,(d,w)->{switch(w){
            case 0:prefs.edit().putString("activeWorkspace","favorites").apply();openLocal(HOME+"#favorites");refreshPinBar();break;
            case 1:prefs.edit().putString("activeWorkspace","gold").apply();openLocal(HOME+"#saved");refreshPinBar();break;
            case 2:bookmarks();break;case 3:choosePin();break;case 4:renameGroups();break;
        }}).show();
    }
    void renameGroups(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);Map<String,EditText> fields=new LinkedHashMap<>();
        for(String group:PinStore.GROUPS){if(group.equals("favorites"))continue;EditText field=new EditText(this);field.setSingleLine(true);field.setText(pins.name(group));field.setHint(group);field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)});fields.put(group,field);box.addView(field);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename pin groups").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(v->{try{JSONObject input=new JSONObject();for(Map.Entry<String,EditText> e:fields.entrySet())input.put(e.getKey(),e.getValue().getText().toString());JSONObject options=object(prefs.getString("options","{}"));options.put("workspaceNames",PinStore.workspaceNames(input));if(!prefs.edit().putString("options",options.toString()).commit())throw new Exception("Names could not be saved.");refreshPinBar();notifyHome(null);dialog.dismiss();}catch(Exception e){toast(e.getMessage());}}));dialog.show();
    }
    void toolsMenu(){CharSequence[] items={label("AI chat",SEAFOAM),label("Workbench",PINK),label("Page tools",CYAN),label("Bookmarks & notes",SEAFOAM),"Downloads","Settings",label("ChatGPT Browser Bridge",GOLD),"Writing archive"};new AlertDialog.Builder(this).setTitle(label("Tools",PINK)).setItems(items,(d,n)->{switch(n){
        case 0:runTool(0);break;case 1:runTool(1);break;
        case 2:toolGroup("Page tools",CYAN,new int[]{2,3,4,5,9,10,11,12});break;
        case 3:toolGroup("Bookmarks & notes",SEAFOAM,new int[]{6,7,8});break;
        case 4:runTool(13);break;case 5:runTool(14);break;case 6:startActivity(new Intent(this,BridgeActivity.class));break;case 7:openLocal(ORIGIN+"/assets/workbench/writing.html");break;
    }}).show();}
    void toolGroup(String title,int color,int[] actions){CharSequence[] items=new CharSequence[actions.length];for(int i=0;i<actions.length;i++)items[i]=TOOL_LABELS[actions[i]];new AlertDialog.Builder(this).setTitle(label(title,color)).setItems(items,(d,n)->runTool(actions[n])).setNegativeButton("Back",(d,w)->toolsMenu()).show();}
    void runTool(int n){try{switch(n){
        case 0:openLocal(STUDIO);break;case 1:openLocal(WORKBENCH);break;case 2:current.reload();break;
        case 3:{EditText input=new EditText(this);input.setSingleLine();AlertDialog find=new AlertDialog.Builder(this).setTitle(label("Find in page",CYAN)).setView(input).setPositiveButton("Next",null).setNegativeButton("Done",(x,w)->current.clearMatches()).create();find.setOnShowListener(x->{find.getButton(-1).setOnClickListener(v->current.findNext(true));input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){current.findAllAsync(s.toString());}public void afterTextChanged(android.text.Editable e){}});});find.show();break;}
        case 4:current.evaluateJavascript(asset("tools/reader.js"),null);break;
        case 5:current.evaluateJavascript(engine+"VoodooEngine.cleanUrl(location.href)",s->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Link",decode(s)));toast("Clean link copied.");});break;
        case 6:choosePin();break;
        case 7:bookmarks();break;case 8:pageNote();break;
        case 9:new AlertDialog.Builder(this).setTitle(label("Text size",CYAN)).setItems(new String[]{"85%","100%","115%","130%","150%"},(x,w)->current.getSettings().setTextZoom(new int[]{85,100,115,130,150}[w])).show();break;
        case 10:{boolean desktop=current.getSettings().getUserAgentString().contains("Mobile");current.getSettings().setUserAgentString(desktop?WebSettings.getDefaultUserAgent(this).replace("; wv","").replace("Mobile ","").replaceAll("Linux; Android[^)]*","X11; Linux x86_64"):WebSettings.getDefaultUserAgent(this));current.reload();break;}
        case 11:startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,current.getUrl()),"Share page"));break;
        case 12:((android.print.PrintManager)getSystemService(PRINT_SERVICE)).print("Voodoo page",current.createPrintDocumentAdapter("Voodoo page"),null);break;
        case 13:startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));break;
        case 14:openLocal(STUDIO+"#settings");break;
    }}catch(Exception e){toast(e.getMessage());}}
    void bookmarks(){JSONArray data=array(prefs.getString("bookmarks","[]"));CharSequence[] labels=new CharSequence[data.length()];for(int i=0;i<data.length();i++)labels[i]=label(data.optJSONObject(i).optString("title",data.optJSONObject(i).optString("url")),SEAFOAM);new AlertDialog.Builder(this).setTitle(label("Bookmarks",SEAFOAM)).setItems(labels,(d,w)->navigate(data.optJSONObject(w).optString("url"))).setNegativeButton("Close",null).show();}
    void pageNote(){String key=Uri.parse(current.getUrl()==null?HOME:current.getUrl()).getHost();JSONObject notes=object(prefs.getString("notes","{}"));EditText text=new EditText(this);text.setMinLines(5);text.setText(notes.optString(key));new AlertDialog.Builder(this).setTitle(label("Notes · "+key,SEAFOAM)).setView(text).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{try{notes.put(key,text.getText().toString());prefs.edit().putString("notes",notes.toString()).apply();}catch(Exception ignored){}}).show();}
    void download(String url,String ua,String disposition,String mime){if(!url.startsWith("https://")){toast("This download needs an HTTPS link. Blob downloads are not supported on external pages yet.");return;}String name=URLUtil.guessFileName(url,disposition,mime);new AlertDialog.Builder(this).setTitle(label("Download file?",GOLD)).setMessage(name+"\n"+Uri.parse(url).getHost()).setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->{try{DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url)).setTitle(name).setMimeType(mime).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);request.addRequestHeader("User-Agent",ua);String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null)request.addRequestHeader("Cookie",cookie);((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);toast("Download started.");}catch(Exception e){toast("Download could not start.");}}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10&&chooser!=null){chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));chooser=null;}else if(request==11&&exportText!=null){String text=exportText;ValueCallback<String> cb=exportReply;exportText=null;exportReply=null;if(result!=RESULT_OK||data==null||data.getData()==null){if(cb!=null)cb.onReceiveValue("cancelled");return;}Uri uri=data.getData();io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(text.getBytes(StandardCharsets.UTF_8));runOnUiThread(()->{toast("File saved.");if(cb!=null)cb.onReceiveValue("saved");});}catch(Exception e){runOnUiThread(()->{toast("Could not save this file.");if(cb!=null)cb.onReceiveValue("cancelled");});}});}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==VoiceController.PERMISSION&&voice!=null)voice.permissionResult(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(webUrl(intent.getDataString()))newTab(normalize(intent.getDataString()));}
    @Override protected void onStart(){super.onStart();bridge(this).foreground(true);}
    @Override protected void onStop(){if(voice!=null)voice.stop();bridge(this).foreground(false);super.onStop();}
    @Override protected void onPause(){super.onPause();for(WebView v:tabs){homeActive(v,false);}CookieManager.getInstance().flush();saveTabs();if(current!=null&&!isInMultiWindowMode())current.onPause();}
    @Override protected void onResume(){super.onResume();if(current!=null){current.onResume();homeActive(current,true);}if(discovery!=null)discovery.checkUpdates();}
    @Override public void onBackPressed(){if(fullScreen!=null)hideFullscreen();else if(current!=null&&current.canGoBack())current.goBack();else if(current!=null&&local(current.getUrl())&&site()!=null)showTab(site());else super.onBackPressed();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW&&localAi!=null){localAi.unload();localAi.pause();}}
    @Override protected void onDestroy(){alive=false;bridge(this).detach(this);ai.cancelAll();if(scripts!=null)scripts.close();if(discovery!=null)discovery.close();if(localAi!=null)localAi.close();if(voice!=null)voice.close();io.shutdownNow();if(chooser!=null)chooser.onReceiveValue(null);for(WebView v:tabs)v.destroy();super.onDestroy();}
    void toast(String message){Toast.makeText(this,message==null?"Action unavailable.":message,Toast.LENGTH_LONG).show();}
}
