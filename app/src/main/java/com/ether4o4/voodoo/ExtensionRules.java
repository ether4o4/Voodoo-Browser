package com.ether4o4.voodoo;

import org.json.*;
import java.util.*;
import java.util.regex.*;

/**
 * chrome.declarativeNetRequest subset evaluated in shouldInterceptRequest.
 * Supported actions: block, allow, allowAllRequests (treated as allow).
 * redirect, upgradeScheme and modifyHeaders cannot be expressed by WebView
 * interception and are counted as unsupported rather than silently approximated.
 * Immutable once built, so it is safe to read from WebView's IO threads.
 */
final class ExtensionRules {
    static final class Rule {
        int id,priority;String action;Pattern url;
        Set<String> requestDomains,excludedRequestDomains,initiatorDomains,excludedInitiatorDomains,resourceTypes,excludedResourceTypes,methods;
        String domainType,token;
    }
    static final class Request {
        final String url,lower,host,initiatorHost,type,method;
        Request(String url,String initiatorHost,String type,String method){this.url=url;this.lower=url.toLowerCase(Locale.ROOT);String h="";try{h=new java.net.URI(url).getHost();}catch(Exception ignored){}this.host=h==null?"":h.toLowerCase(Locale.ROOT);this.initiatorHost=initiatorHost==null?"":initiatorHost.toLowerCase(Locale.ROOT);this.type=type;this.method=method==null?"get":method.toLowerCase(Locale.ROOT);}
    }
    final Map<String,List<Rule>> byHost=new HashMap<>(),byInitiator=new HashMap<>(),byKeyword=new HashMap<>();
    final List<Rule> generic=new ArrayList<>();
    int count,unsupported;

    static final ExtensionRules EMPTY=new ExtensionRules();

    void addAll(JSONArray rules){if(rules!=null)for(int i=0;i<rules.length();i++){JSONObject r=rules.optJSONObject(i);if(r!=null)add(r);}}
    void add(JSONObject json){
        try{
            JSONObject action=json.getJSONObject("action"),c=json.optJSONObject("condition");if(c==null)c=new JSONObject();
            String type=action.getString("type");
            if(!type.equals("block")&&!type.equals("allow")&&!type.equals("allowAllRequests")){unsupported++;return;}
            Rule r=new Rule();r.id=json.optInt("id");r.priority=json.optInt("priority",1);r.action=type.equals("block")?"block":"allow";
            boolean caseSensitive=c.optBoolean("isUrlFilterCaseSensitive",false);
            if(c.has("regexFilter"))r.url=Pattern.compile(c.getString("regexFilter"),caseSensitive?0:Pattern.CASE_INSENSITIVE);
            else if(c.has("urlFilter"))r.url=Pattern.compile(urlFilter(c.getString("urlFilter")),caseSensitive?0:Pattern.CASE_INSENSITIVE);
            r.requestDomains=set(c,"requestDomains");r.excludedRequestDomains=set(c,"excludedRequestDomains");
            r.initiatorDomains=set(c,"initiatorDomains");if(r.initiatorDomains==null)r.initiatorDomains=set(c,"domains");
            r.excludedInitiatorDomains=set(c,"excludedInitiatorDomains");if(r.excludedInitiatorDomains==null)r.excludedInitiatorDomains=set(c,"excludedDomains");
            r.resourceTypes=set(c,"resourceTypes");r.excludedResourceTypes=set(c,"excludedResourceTypes");r.methods=set(c,"requestMethods");r.domainType=c.optString("domainType",null);
            if(r.resourceTypes==null&&!type.equals("allowAllRequests")){r.excludedResourceTypes=r.excludedResourceTypes==null?new HashSet<>():r.excludedResourceTypes;r.excludedResourceTypes.add("main_frame");}
            count++;
            String anchor=c.has("urlFilter")?anchorHost(c.getString("urlFilter")):null;
            if(r.requestDomains!=null&&!r.requestDomains.isEmpty())for(String d:r.requestDomains)byHost.computeIfAbsent(d,k->new ArrayList<>()).add(r);
            else if(anchor!=null)byHost.computeIfAbsent(anchor,k->new ArrayList<>()).add(r);
            else if(r.initiatorDomains!=null&&!r.initiatorDomains.isEmpty())for(String d:r.initiatorDomains)byInitiator.computeIfAbsent(d,k->new ArrayList<>()).add(r);
            else{String key=c.has("urlFilter")?keyword(c.getString("urlFilter")):null;if(key!=null)byKeyword.computeIfAbsent(key,k->new ArrayList<>()).add(r);else{if(c.has("urlFilter")&&!caseSensitive)r.token=token(c.getString("urlFilter"));generic.add(r);}}
        }catch(Exception e){unsupported++;}
    }
    /** Longest literal run of a urlFilter; a cheap substring prefilter before the regex. */
    static String token(String filter){String best="";for(String part:filter.split("[*^|]+"))if(part.length()>best.length())best=part;return best.length()>=3?best.toLowerCase(Locale.ROOT):null;}
    static final Pattern WORD=Pattern.compile("[a-z0-9%]+");
    static final Set<String> COMMON=new HashSet<>(Arrays.asList("http","https","www","com","net","org","js","html","php"));
    /** A whole URL word the filter requires (not touching a wildcard), so rules can be found by hashing URL words. */
    static String keyword(String filter){
        String f=filter.toLowerCase(Locale.ROOT),best=null;Matcher m=WORD.matcher(f);
        while(m.find()){int s=m.start(),e=m.end();if(s==0||e>=f.length()||f.charAt(s-1)=='*'||f.charAt(e)=='*'||m.group().length()<3||COMMON.contains(m.group()))continue;if(best==null||m.group().length()>best.length())best=m.group();}
        return best;
    }
    static Set<String> set(JSONObject c,String key){JSONArray a=c.optJSONArray(key);if(a==null)return null;Set<String> s=new HashSet<>();for(int i=0;i<a.length();i++)s.add(a.optString(i).toLowerCase(Locale.ROOT));return s;}
    static String anchorHost(String filter){
        if(!filter.startsWith("||"))return null;Matcher m=Pattern.compile("^\\|\\|([a-z0-9.-]+)(?:\\^|/|$)",Pattern.CASE_INSENSITIVE).matcher(filter);
        return m.find()?m.group(1).toLowerCase(Locale.ROOT):null;
    }
    /** DNR urlFilter syntax: ||domain anchor, | start/end anchors, ^ separator, * wildcard. */
    static String urlFilter(String f){
        StringBuilder b=new StringBuilder();int i=0;
        if(f.startsWith("||")){b.append("^[a-z][a-z0-9+.-]*:\\/\\/(?:[^\\/?#]*\\.)?");i=2;}else if(f.startsWith("|")){b.append("^");i=1;}
        boolean endAnchor=f.length()>i&&f.endsWith("|");int end=endAnchor?f.length()-1:f.length();
        for(;i<end;i++){char c=f.charAt(i);if(c=='*')b.append(".*");else if(c=='^')b.append("(?:[^A-Za-z0-9_\\-.%]|$)");else b.append(ExtensionPackage.escape(String.valueOf(c)));}
        if(endAnchor)b.append("$");
        return b.toString();
    }
    static boolean domainIn(String host,Set<String> domains){if(domains==null)return false;String h=host;while(true){if(domains.contains(h))return true;int dot=h.indexOf('.');if(dot<0)return false;h=h.substring(dot+1);}}
    static String site(String host){String[] p=host.split("\\.");return p.length<=2?host:p[p.length-2]+"."+p[p.length-1];}
    boolean applies(Rule r,Request q){
        if(r.requestDomains!=null&&!domainIn(q.host,r.requestDomains))return false;
        if(domainIn(q.host,r.excludedRequestDomains))return false;
        if(r.initiatorDomains!=null&&!domainIn(q.initiatorHost,r.initiatorDomains))return false;
        if(domainIn(q.initiatorHost,r.excludedInitiatorDomains))return false;
        if(r.resourceTypes!=null&&!(q.type.equals("other")&&!r.resourceTypes.contains("main_frame")||r.resourceTypes.contains(q.type)))return false;
        if(r.excludedResourceTypes!=null&&r.excludedResourceTypes.contains(q.type))return false;
        if(r.methods!=null&&!r.methods.contains(q.method))return false;
        if(r.domainType!=null&&!q.initiatorHost.isEmpty()){boolean third=!site(q.host).equals(site(q.initiatorHost));if(r.domainType.equals("thirdParty")!=third)return false;}
        return r.url==null||r.url.matcher(q.url).find();
    }
    /** "block", "allow" or null when no rule matches. Higher priority wins; allow wins ties. */
    String decide(Request q){
        Rule best=null;
        String h=q.host;while(!h.isEmpty()){List<Rule> list=byHost.get(h);if(list!=null)for(Rule r:list)best=better(best,r,q);int dot=h.indexOf('.');if(dot<0)break;h=h.substring(dot+1);}
        h=q.initiatorHost;while(!h.isEmpty()){List<Rule> list=byInitiator.get(h);if(list!=null)for(Rule r:list)best=better(best,r,q);int dot=h.indexOf('.');if(dot<0)break;h=h.substring(dot+1);}
        if(!byKeyword.isEmpty()){Set<String> seen=new HashSet<>();Matcher m=WORD.matcher(q.lower);while(m.find()){String w=m.group();if(!seen.add(w))continue;List<Rule> list=byKeyword.get(w);if(list!=null)for(Rule r:list)best=better(best,r,q);}}
        for(Rule r:generic)if(r.token==null||q.lower.contains(r.token))best=better(best,r,q);
        return best==null?null:best.action;
    }
    private Rule better(Rule best,Rule r,Request q){if(best!=null&&(r.priority<best.priority||r.priority==best.priority&&(best.action.equals("allow")||r.action.equals("block"))))return best;return applies(r,q)?r:best;}
    /** Best guess for resourceTypes; WebView does not expose the request destination. */
    static String type(boolean mainFrame,String url,String accept){
        if(mainFrame)return "main_frame";String a=accept==null?"":accept;String p=url.toLowerCase(Locale.ROOT).split("[?#]",2)[0];
        if(a.startsWith("text/html"))return "sub_frame";if(a.startsWith("text/css")||p.endsWith(".css"))return "stylesheet";if(a.startsWith("image/")||p.matches(".*\\.(png|jpe?g|gif|webp|svg|ico|avif)$"))return "image";
        if(p.endsWith(".js")||p.endsWith(".mjs"))return "script";if(p.matches(".*\\.(woff2?|ttf|otf)$"))return "font";if(p.matches(".*\\.(mp4|webm|m3u8|mp3|ogg|ts)$"))return "media";
        return "other";
    }
}
