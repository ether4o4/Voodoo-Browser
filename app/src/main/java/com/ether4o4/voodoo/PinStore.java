package com.ether4o4.voodoo;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.util.*;

final class PinStore {
    static final String[] GROUPS={"cyan","pink","green","gold"};
    static final String[] DEFAULT_NAMES={"Browse","Create","Build","Saved"};
    static final String[] COLORS={"#8BBAC4","#CA96B5","#ACC19C","#D2B580"};
    final SharedPreferences prefs;
    PinStore(SharedPreferences prefs){this.prefs=prefs;}
    JSONArray all(){try{return new JSONArray(prefs.getString("pins","[]"));}catch(Exception e){return new JSONArray();}}
    static int groupIndex(String group){for(int i=0;i<GROUPS.length;i++)if(GROUPS[i].equals(group))return i;return 0;}
    static String group(String value){return GROUPS[groupIndex(value)];}
    String name(String group){try{JSONObject options=new JSONObject(prefs.getString("options","{}"));JSONObject names=options.optJSONObject("workspaceNames");return names==null?DEFAULT_NAMES[groupIndex(group)]:names.optString(group,DEFAULT_NAMES[groupIndex(group)]);}catch(Exception e){return DEFAULT_NAMES[groupIndex(group)];}}
    JSONObject find(String id)throws Exception{JSONArray pins=all();for(int i=0;i<pins.length();i++)if(id.equals(pins.getJSONObject(i).optString("id")))return pins.getJSONObject(i);throw new Exception("This pin no longer exists.");}
    static void validate(JSONArray pins)throws Exception{if(pins.length()>60)throw new Exception("Too many pins.");Set<String> ids=new HashSet<>();for(int i=0;i<pins.length();i++){JSONObject pin=pins.getJSONObject(i);Uri url=Uri.parse(pin.getString("url"));String id=pin.getString("id"),group=pin.getString("group");if(!"https".equals(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||"appassets.androidplatform.net".equalsIgnoreCase(url.getHost())||id.isEmpty()||id.length()>100||!ids.add(id)||!Arrays.asList(GROUPS).contains(group)||pin.getString("title").length()>120)throw new Exception("Invalid pin in backup.");double lat=pin.getDouble("lat"),lon=pin.getDouble("lon");if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)throw new Exception("Invalid globe position.");if(pin.has("icon")&&(!pin.getString("icon").startsWith("data:image/png;base64,")||pin.getString("icon").length()>50000))pin.remove("icon");}}
    JSONObject save(String url,String title,String group,String icon)throws Exception{
        Uri uri=Uri.parse(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getHost().equalsIgnoreCase("appassets.androidplatform.net"))throw new Exception("Pin an HTTPS website.");
        JSONArray all=all();JSONObject pin=null;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).optString("url").equals(url))pin=all.getJSONObject(i);
        if(pin==null){if(all.length()>=60)throw new Exception("Unpin a site first (60-pin limit).");int hash=uri.getHost().hashCode();pin=new JSONObject().put("id",UUID.randomUUID().toString()).put("lat",Math.floorMod(hash,120)-60).put("lon",Math.floorMod(hash/127,360)-180);all.put(pin);}
        pin.put("url",url).put("title",title==null||title.isEmpty()?uri.getHost():title.substring(0,Math.min(120,title.length()))).put("group",group(group)).put("updatedAt",System.currentTimeMillis());
        if(icon!=null&&icon.length()<50000)pin.put("icon",icon);
        write(all);return pin;
    }
    JSONObject update(JSONObject update)throws Exception{JSONArray all=all();JSONObject pin=null;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).optString("id").equals(update.optString("id")))pin=all.getJSONObject(i);if(pin==null)throw new Exception("This pin no longer exists.");
        if(update.has("title")){String title=update.getString("title").trim();if(title.isEmpty()||title.length()>120)throw new Exception("Use a pin name under 120 characters.");pin.put("title",title);}
        if(update.has("group"))pin.put("group",group(update.getString("group")));
        if(update.has("lat")&&update.has("lon")){double lat=update.getDouble("lat"),lon=update.getDouble("lon");if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat< -85||lat>85||lon< -180||lon>180)throw new Exception("Choose a point on the globe.");pin.put("lat",lat).put("lon",lon);}
        write(all);return pin;
    }
    void remove(String id)throws Exception{JSONArray before=all(),after=new JSONArray();for(int i=0;i<before.length();i++)if(!before.getJSONObject(i).optString("id").equals(id))after.put(before.get(i));write(after);}
    void write(JSONArray pins)throws Exception{if(!prefs.edit().putString("pins",pins.toString()).commit())throw new Exception("Could not save pins.");}
}
