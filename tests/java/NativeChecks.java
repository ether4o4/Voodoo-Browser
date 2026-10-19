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
        System.out.println(checks+" native scope/endpoint checks passed.");
    }
}
