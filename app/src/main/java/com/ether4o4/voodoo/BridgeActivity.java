package com.ether4o4.voodoo;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import okhttp3.*;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public final class BridgeActivity extends Activity {
    private BridgeRuntime runtime;
    private LinearLayout root;
    private TextView status;
    private final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable update=new Runnable(){public void run(){refresh();timer.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);runtime=MainActivity.bridge(this);build();refresh();}
    private void build(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,28,28,28);root.setBackgroundColor(Color.rgb(37,38,43));
        TextView title=new TextView(this);title.setText("ChatGPT Browser Bridge");title.setTextColor(0xFFFFC5DC);title.setTextSize(24);root.addView(title);
        status=new TextView(this);status.setTextColor(0xFF9BBEB4);status.setPadding(0,18,0,18);root.addView(status);
        Button pair=button("Pair device",this::pair);root.addView(pair);
        root.addView(button("Allowed websites",this::sites));
        Button connect=button("Reconnect",()->{try{runtime.connect();toast("Reconnect requested.");}catch(Exception e){toast(e.getMessage());}});root.addView(connect);
        Button pause=button("Disconnect",()->{runtime.disconnect();refresh();});root.addView(pause);
        Button pauseTask=button("Pause bridge tasks",()->{runtime.setPaused(true);refresh();});root.addView(pauseTask);
        Button resumeTask=button("Resume bridge tasks",()->{runtime.setPaused(false);refresh();});root.addView(resumeTask);
        Button revoke=button("Revoke pairing",()->new AlertDialog.Builder(this).setTitle("Revoke pairing?").setMessage("ChatGPT will no longer be able to reach this phone.").setNegativeButton("Cancel",null).setPositiveButton("Revoke",(d,w)->{runtime.revoke(message->{toast(message);refresh();});refresh();}).show());root.addView(revoke);
        TextView help=new TextView(this);help.setText("\nThe bridge only exposes structured browser actions. Website cookies stay in Voodoo. The browser must remain visible for actions that need a live WebView.");help.setTextColor(0xFFB0ACB7);help.setTextSize(14);root.addView(help);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private Button button(String t, Runnable r){Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextColor(0xFFEEE7EF);b.setOnClickListener(v->r.run());return b;}
    private void sites(){
        EditText input=new EditText(this);input.setMinLines(4);input.setHint("example.com\nwww.example.com");input.setText(runtime.allowedSites());
        new AlertDialog.Builder(this).setTitle("Allowed websites").setMessage("Enter one HTTPS website host per line. Only these sites can be read or controlled. Changes pause the bridge.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{try{runtime.saveSites(input.getText().toString());refresh();}catch(Exception e){toast(e.getMessage());}}).show();
    }
    private void pair(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        EditText relay=new EditText(this);relay.setHint("https://bridge.example.com");relay.setSingleLine();box.addView(relay);
        EditText code=new EditText(this);code.setHint("One-time pairing code");code.setSingleLine();box.addView(code);
        new AlertDialog.Builder(this).setTitle("Pair with relay").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Pair",(d,w)->completePair(relay.getText().toString().trim(),code.getText().toString().trim())).show();
    }
    private void completePair(String relay,String code){
        try{if(!relay.replaceAll("/+$","").equals(BridgePolicy.origin(relay)))throw new Exception("Use an HTTPS relay origin without a path.");}catch(Exception e){toast(e.getMessage());return;}
        RequestBody body=RequestBody.create("{\"code\":"+JSONObject.quote(code)+"}",MediaType.parse("application/json"));
        new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15,TimeUnit.SECONDS).build().newCall(new Request.Builder().url(relay.replaceAll("/+$","")+"/pair/complete").post(body).build()).enqueue(new Callback(){
            public void onFailure(Call c,java.io.IOException e){runOnUiThread(()->toast("Pairing failed: "+e.getMessage()));}
            public void onResponse(Call c,Response r)throws java.io.IOException{String raw=r.body()==null?"":r.body().string();runOnUiThread(()->{try{JSONObject o=new JSONObject(raw);if(!r.isSuccessful()||!o.optBoolean("ok"))throw new Exception(o.optString("error","Pairing rejected."));runtime.configure(relay,o.getString("deviceId"),o.optString("accountId"),o.getString("deviceToken"));toast("Paired.");refresh();}catch(Exception e){toast(e.getMessage());}});}
        });
    }
    private void refresh(){try{JSONObject s=new JSONObject(runtime.state());status.setText((s.optBoolean("connected")?"Connected":"Disconnected")+" · "+(s.optBoolean("paused")?"Paused":"Ready")+"\n"+(s.optBoolean("paired")?s.optString("relayUrl"):"Not paired")+"\nAllowed websites: "+(s.optString("allowedSites").isEmpty()?"none":s.optString("allowedSites").replace("\n",", "))+"\nLast command: "+s.optJSONObject("task").optString("op","none")+" "+s.optJSONObject("task").optString("status","")+"\nResume, then return to the browser. Keep Voodoo visible (for example in split screen).");}catch(Exception ignored){}}
    private void toast(String s){Toast.makeText(this,s==null?"Action failed.":s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();timer.post(update);}
    @Override protected void onPause(){timer.removeCallbacks(update);super.onPause();}
}
