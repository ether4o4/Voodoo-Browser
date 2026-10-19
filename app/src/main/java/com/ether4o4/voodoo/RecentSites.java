package com.ether4o4.voodoo;

import android.content.SharedPreferences;
import org.json.*;
import java.net.URI;
import java.util.*;

/** Local recent-site shortcuts, deliberately separate from exportable workspace data. */
final class RecentSites {
    static final int STORED_LIMIT=24,DISPLAY_LIMIT=6;
    private static final String KEY="recent_sites_v1";
    private final SharedPreferences prefs;
    RecentSites(SharedPreferences prefs){this.prefs=prefs;}

    static final class Site {
        final String url,host,title,icon;
        Site(String url,String host,String title,String icon){this.url=url;this.host=host;this.title=title;this.icon=icon;}
    }
    static String host(String url){
        if(url==null||url.isEmpty()||url.length()>4096)return null;
        try{
            URI uri=new URI(url);String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||host.isEmpty()||uri.getRawUserInfo()!=null||uri.getPort()>65535||uri.getPort()< -1)return null;
            host=host.toLowerCase(Locale.ROOT);while(host.endsWith("."))host=host.substring(0,host.length()-1);
            return host.isEmpty()||host.equals("appassets.androidplatform.net")?null:host;
        }catch(Exception e){return null;}
    }
    static Site site(String url,String title,String icon){
        String host=host(url);if(host==null)return null;
        String name=title==null?"":title.replaceAll("[\\p{Cntrl}\\s]+"," ").trim();
        if(name.isEmpty())name=host;if(name.length()>120)name=name.substring(0,120);
        String image=icon!=null&&icon.length()<=50000&&icon.startsWith("data:image/png;base64,iVBORw0KGgo")&&icon.matches("data:image/png;base64,[A-Za-z0-9+/]+={0,2}")?icon:null;
        return new Site(url,host,name,image);
    }
    static List<Site> visited(List<Site> previous,Site incoming){
        ArrayList<Site> next=new ArrayList<>();if(incoming==null){next.addAll(previous);return next;}
        if(incoming.icon==null)for(Site old:previous)if(old!=null&&old.host.equals(incoming.host)&&old.icon!=null){incoming=new Site(incoming.url,incoming.host,incoming.title,old.icon);break;}
        next.add(incoming);Set<String> seen=new HashSet<>();seen.add(incoming.host);
        for(Site old:previous)if(old!=null&&seen.add(old.host)){next.add(old);if(next.size()==STORED_LIMIT)break;}
        return next;
    }
    private List<Site> read(){
        ArrayList<Site> sites=new ArrayList<>();Set<String> hosts=new HashSet<>();
        try{JSONArray stored=new JSONArray(prefs.getString(KEY,"[]"));for(int i=0;i<stored.length()&&sites.size()<STORED_LIMIT;i++){JSONObject item=stored.optJSONObject(i);if(item==null)continue;Site site=site(item.optString("url"),item.optString("title"),item.optString("icon",null));if(site!=null&&hosts.add(site.host))sites.add(site);}}catch(Exception ignored){}
        return sites;
    }
    private static JSONObject json(Site site)throws JSONException{JSONObject value=new JSONObject().put("url",site.url).put("title",site.title);if(site.icon!=null)value.put("icon",site.icon);return value;}
    JSONArray latest()throws JSONException{JSONArray result=new JSONArray();List<Site> sites=read();for(int i=0;i<Math.min(DISPLAY_LIMIT,sites.size());i++)result.put(json(sites.get(i)));return result;}
    boolean record(String url,String title,String icon){
        Site incoming=site(url,title,icon);if(incoming==null)return false;
        try{JSONArray result=new JSONArray();for(Site site:visited(read(),incoming))result.put(json(site));String raw=result.toString();if(raw.equals(prefs.getString(KEY,"[]")))return false;prefs.edit().putString(KEY,raw).apply();return true;}catch(Exception e){return false;}
    }

    /** Success belongs to a complete main-frame navigation, not to an error document. */
    static final class Visit {
        private boolean started,failed;
        private String completedUrl;
        void start(){started=true;failed=false;completedUrl=null;}
        void fail(){failed=true;completedUrl=null;}
        void sslFailure(String failingUrl,String mainUrl,String navigationUrl){if(failingUrl!=null&&(failingUrl.equals(mainUrl)||failingUrl.equals(navigationUrl)))fail();}
        boolean finish(String url,String currentUrl){if(!started||failed||!Objects.equals(url,currentUrl)||host(url)==null)return false;completedUrl=url;return true;}
        boolean ready(String url){return !failed&&completedUrl!=null&&completedUrl.equals(url);}
    }
}
