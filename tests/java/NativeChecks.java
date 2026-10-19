package com.ether4o4.voodoo;
public class NativeChecks {
    static int checks=0;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks+" failed");}
    public static void main(String[] args)throws Exception{
        String header="// ==UserScript==\n// @name Test\n// @match https://*.example.com/Create*\n// @exclude-match https://private.example.com/*\n// ==/UserScript==\n";
        ScriptRules r=new ScriptRules(header);
        check(r.allows("https://example.com/Create"));check(r.allows("https://a.example.com/Create?q=1#section"));
        check(!r.allows("https://private.example.com/Create"));check(!r.allows("https://example.com.evil/Create"));check(!r.allows("http://example.com/Create"));check(!r.allows("https://example.com/create"));check(!r.allows("https://user@example.com/Create"));
        check(r.origins().contains("https://*.example.com"));
        ScriptRules all=new ScriptRules(header.replace("https://*.example.com/Create*","<all_urls>"));check(!all.allows("https://appassets.androidplatform.net/assets/home.html"));check(!all.allows("file:///test"));
        check(AiClient.endpoint("https://api.venice.ai/api/v1").getHost().equals("api.venice.ai"));
        for(String bad:new String[]{"http://api.example.com/v1","https://key@api.example.com/v1","https://api.example.com/v1?key=x","https://api.example.com/v1#fragment","file:///secret"}){boolean rejected=false;try{AiClient.endpoint(bad);}catch(Exception e){rejected=true;}check(rejected);}
        for(String bad:new String[]{"http://example.com","about:blank","file:///secret","https://user:pass@example.com/","https://APPASSETS.ANDROIDPLATFORM.NET/assets/home.html","https://appassets.androidplatform.net./assets/home.html","https://example.com:65536/","https://example.com/"+"x".repeat(4096)})check(RecentSites.site(bad,"Bad",null)==null);
        RecentSites.Site old=RecentSites.site("https://EXAMPLE.com/old","  Old\n title  ",null);
        check(old.host.equals("example.com")&&old.title.equals("Old title"));
        RecentSites.Site fresh=RecentSites.site("https://example.com./new","x".repeat(150),"https://tracking.example/icon.png");
        check(fresh.host.equals(old.host)&&fresh.title.length()==120&&fresh.icon==null);
        java.util.List<RecentSites.Site> recent=new java.util.ArrayList<>();recent.add(old);recent.add(RecentSites.site("https://second.example/","Second",null));
        recent=RecentSites.visited(recent,fresh);check(recent.size()==2&&recent.get(0).url.equals(fresh.url)&&recent.get(1).host.equals("second.example"));
        for(int i=0;i<30;i++)recent=RecentSites.visited(recent,RecentSites.site("https://site"+i+".example/","Site "+i,null));
        check(recent.size()==24&&recent.get(0).host.equals("site29.example")&&recent.get(23).host.equals("site6.example"));
        RecentSites.Visit visit=new RecentSites.Visit();String page="https://example.com/page";
        check(!visit.finish(page,page));visit.start();visit.fail();check(!visit.finish(page,page)&&!visit.ready(page));
        visit.start();check(!visit.finish(page,"https://other.example/"));check(visit.finish(page,page)&&visit.ready(page));
        visit.start();check(!visit.ready(page));check(!visit.finish("http://example.com/","http://example.com/"));
        check(visit.finish("https://redirect.example/","https://redirect.example/"));visit.fail();check(!visit.ready("https://redirect.example/"));
        visit.start();visit.sslFailure("https://blocked-resource.example/image.png",page,page);check(visit.finish(page,page));
        visit.sslFailure(page,page,page);check(!visit.ready(page));
        visit.start();visit.sslFailure("https://next.example/",page,"https://next.example/");check(!visit.finish("https://next.example/","https://next.example/"));
        System.out.println(checks+" native scope/endpoint/recent-site checks passed.");
    }
}
