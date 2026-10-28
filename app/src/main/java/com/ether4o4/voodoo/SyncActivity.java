package com.ether4o4.voodoo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, foreground-only pairing and shared library. This activity is not exported. */
public final class SyncActivity extends Activity {
    private SyncClient client;
    private TextView status;
    private LinearLayout library;
    private boolean visible=false,sendOnResume=false;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        client=new SyncClient(this);build();
        if(saved==null && getIntent().hasExtra("shareUrl")) {
            try {
                String url=SyncData.https(getIntent().getStringExtra("shareUrl"));
                String title=getIntent().getStringExtra("shareTitle");
                if(title==null || title.isEmpty())title=url;
                if(title.length()>200)title=title.substring(0,200);
                client.add(SyncData.create("tab",title,url));sendOnResume=true;
            } catch(Exception e){toast("Only HTTPS website tabs can be sent.");}
        }
        refresh();
    }
    private void build() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(18),dp(20),dp(24));root.setBackgroundColor(0xFF08090D);
        root.addView(text("Device link",26,0xFFFFC5DC));
        root.addView(text("Your phone and PC, together on the same Wi-Fi or hotspot.",15,0xFFAFACBA));
        status=text("",15,0xFF9BBEB4);status.setPadding(0,dp(20),0,dp(12));root.addView(status);
        root.addView(button("Pair a PC",this::pair));
        root.addView(button("Sync now",this::sync));
        root.addView(button("Share a bookmark",this::bookmark));
        root.addView(button("Share a note snapshot",this::note));
        root.addView(button("Forget this PC",()->new AlertDialog.Builder(this).setTitle("Forget this PC?")
                .setMessage("Removes this phone's pairing key. Shared snapshots stay here. Also revoke the phone on the PC to remove access there.")
                .setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{try{client.forget();refresh();}catch(Exception e){toast("Pairing could not be removed.");}}).show()));
        TextView help=text("Only items you choose are shared. Passwords, cookies, API keys and browsing history stay on this device. Notes are snapshots; they never replace a writing draft. No background polling runs when you leave this screen.",14,0xFFAFACBA);
        help.setPadding(0,dp(18),0,dp(18));root.addView(help);
        root.addView(text("Shared library & received tabs",20,0xFFB5E4F0));
        library=new LinearLayout(this);library.setOrientation(LinearLayout.VERTICAL);root.addView(library);
        root.addView(button("Back to browser",this::finish));
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(String name,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextColor(0xFFF0EBF3);b.setOnClickListener(v->action.run());return b;}
    private EditText input(String hint,int max,boolean multiline){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xFFAAA6B4);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});e.setSingleLine(!multiline);e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);return e;}
    private void pair(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),0,dp(18),0);
        EditText name=input("Phone name",80,false);name.setText("My phone");box.addView(name);
        EditText code=input("Paste private desktop pairing code",4096,true);
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);code.setSaveEnabled(false);box.addView(code);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Pair with your PC")
                .setMessage("On desktop, open Device link, enable it, and create a code. Codes expire after five minutes. Keep this code private.")
                .setView(box).setNegativeButton("Cancel",null).setPositiveButton("Pair",null).create();
        dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{
            String value=code.getText().toString().trim(),device=name.getText().toString().trim();
            try{SyncCrypto.pairing(value,System.currentTimeMillis());if(device.isEmpty())throw new Exception("Enter a phone name.");}
            catch(Exception e){code.setError(e.getMessage());return;}
            code.setText("");dialog.dismiss();status.setText("Checking desktop identity and pairing…");client.pair(value,device,this::result);
        }));
        dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void sync(){status.setText("Synchronizing selected items…");client.sync(this::result);}
    private void result(String message,boolean success){runOnUiThread(()->{if(!visible||isFinishing()||isDestroyed())return;refresh();toast(message);});}
    private void refresh(){
        try{
            JSONObject value=client.status();JSONArray items=value.getJSONArray("items");
            String when=value.getLong("lastSync")==0?"Not synced yet":android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(value.getLong("lastSync")))+" "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(value.getLong("lastSync")));
            status.setText((value.getBoolean("paired")?"Paired · "+value.getString("url"):"No PC paired")+"\n"+items.length()+" shared items · "+when);
            library.removeAllViews();
            if(items.length()==0){library.addView(text("Send a tab from the browser's Tools menu, or share a bookmark or note here.",14,0xFFAFACBA));return;}
            for(int i=items.length()-1;i>=Math.max(0,items.length()-100);i--){JSONObject item=items.getJSONObject(i);String title=item.getString("title");if(title.isEmpty())title="Untitled "+item.getString("kind");
                Button row=button(title+"  ·  "+item.getString("kind"),()->open(item));library.addView(row);
            }
            if(items.length()>100)library.addView(text("Showing the newest 100 snapshots. All "+items.length()+" remain in the shared library.",14,0xFFAFACBA));
        }catch(Exception e){status.setText("Device-link data could not be read. Existing data was preserved.");}
    }
    private void open(JSONObject item){
        try{
            if(item.getString("kind").equals("note"))new AlertDialog.Builder(this).setTitle(item.getString("title"))
                    .setMessage(item.getString("body")).setPositiveButton("Done",null).show();
            else {
                String url=SyncData.https(item.getString("url"));
                new AlertDialog.Builder(this).setTitle("Open shared website?").setMessage(url).setNegativeButton("Cancel",null)
                        .setPositiveButton("Open",(d,w)->{startActivity(new Intent(this,MainActivity.class).setData(Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));finish();}).show();
            }
        }catch(Exception e){toast("This shared item could not be opened.");}
    }
    private void bookmark(){
        try{
            JSONArray saved=new JSONArray(getSharedPreferences("voodoo_data_v1",MODE_PRIVATE).getString("bookmarks","[]"));
            if(saved.length()==0){toast("Save a bookmark in the browser first.");return;}
            CharSequence[] names=new CharSequence[Math.min(saved.length(),1000)];
            for(int i=0;i<names.length;i++){JSONObject item=saved.getJSONObject(i);names[i]=item.optString("title",item.optString("url"));}
            new AlertDialog.Builder(this).setTitle("Choose a bookmark to share").setItems(names,(d,n)->{
                try{JSONObject b=saved.getJSONObject(n);String title=b.optString("title",b.getString("url"));if(title.length()>200)title=title.substring(0,200);
                    client.add(SyncData.create("bookmark",title,b.getString("url")));refresh();toast("Bookmark added. Tap Sync now to share it.");}
                catch(Exception e){toast("Only HTTPS bookmarks without sign-in details can be shared.");}
            }).setNegativeButton("Cancel",null).show();
        }catch(Exception e){toast("Bookmarks could not be loaded.");}
    }
    private void note(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),0,dp(18),0);
        EditText title=input("Snapshot title",200,false),body=input("Text to share",20000,true);body.setMinLines(5);box.addView(title);box.addView(body);
        new AlertDialog.Builder(this).setTitle("Share a note snapshot").setMessage("Copy only the text you want on both devices. Do not include passwords or API keys.")
                .setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save snapshot",(d,w)->{
                    try{client.add(SyncData.create("note",title.getText().toString(),body.getText().toString()));refresh();toast("Snapshot saved. Tap Sync now to share it.");}
                    catch(Exception e){toast("Snapshot could not be saved. The shared library may be full.");}
                }).show();
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();visible=true;refresh();if(sendOnResume){sendOnResume=false;sync();}}
    @Override protected void onStop(){visible=false;client.cancel();super.onStop();}
    @Override protected void onDestroy(){client.close();super.onDestroy();}
}
