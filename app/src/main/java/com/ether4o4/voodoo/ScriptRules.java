package com.ether4o4.voodoo;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Native scope checks keep scripts for unrelated sites out of each page's JS context. */
final class ScriptRules {
    static String canonicalHost(String host){return host==null?"":host.toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.$", "");}
    final ArrayList<String> matches=new ArrayList<>(),excludes=new ArrayList<>(),includes=new ArrayList<>(),excludeGlobs=new ArrayList<>();
    final Map<String,List<String>> fields=new LinkedHashMap<>();
    static final Set<String> APIS=new HashSet<>(Arrays.asList("addStyle","addElement","getValue","setValue","deleteValue","listValues","getValues","setValues","deleteValues","addValueChangeListener","removeValueChangeListener","xmlhttpRequest","getResourceText","getResourceURL","download","setClipboard","registerMenuCommand","unregisterMenuCommand","notification","info","log","openInTab"));
    ScriptRules(String code)throws Exception{
        if(code.length()>200000)throw new Exception("Script too large.");
        Matcher header=Pattern.compile("//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==").matcher(code);
        if(!header.find())throw new Exception("Missing userscript header.");
        Matcher entries=Pattern.compile("(?m)^\\s*//[ \\t]*@([\\w-]+)(?:[ \\t]+([^\\r\\n]*))?").matcher(header.group(1));
        while(entries.find()){String key=entries.group(1),value=entries.group(2)==null?"":entries.group(2).trim();fields.computeIfAbsent(key,k->new ArrayList<>()).add(value);if(key.equals("match")){pattern(value);matches.add(value);}else if(key.equals("exclude-match")){pattern(value);excludes.add(value);}else if(key.equals("include")){glob(value);includes.add(value);}else if(key.equals("exclude")){glob(value);excludeGlobs.add(value);}}
        if(matches.isEmpty()&&includes.isEmpty())throw new Exception("Missing @match or @include.");
        if(!Arrays.asList("document-start","document-body","document-end","document-idle").contains(first("run-at","document-idle")))throw new Exception("Unsupported @run-at.");
    }
    List<String> values(String key){return fields.getOrDefault(key,Collections.emptyList());}
    String first(String key,String fallback){return values(key).isEmpty()?fallback:values(key).get(0);}
    boolean grant(String api){return values("grant").contains("GM_"+api)||values("grant").contains("GM."+api)||api.equals("xmlhttpRequest")&&values("grant").contains("GM.xmlHttpRequest")||api.equals("info");}
    List<String> problems(){ArrayList<String> out=new ArrayList<>();for(String g:values("grant"))if(!g.equals("none")&&!APIS.contains(g.replaceFirst("^GM[_.]","").replace("xmlHttpRequest","xmlhttpRequest")))out.add("Unsupported grant: "+g);if(values("grant").contains("none")&&values("grant").size()>1)out.add("@grant none cannot be combined with other grants.");for(String f:Arrays.asList("unwrap","webRequest","sandbox"))if(fields.containsKey(f))out.add("Unsupported execution directive: @"+f);return out;}
    static Pattern glob(String p)throws Exception{if(p.length()>2048)throw new Exception("Include/exclude pattern too long.");if(p.startsWith("/")&&p.endsWith("/")&&p.length()>1)return Pattern.compile(p.substring(1,p.length()-1));return Pattern.compile("^"+Pattern.quote(p).replace("*","\\E.*\\Q")+"$");}
    static Matcher parts(String value)throws Exception{Matcher m=Pattern.compile("^(https?|\\*):[/]{2}(\\*|\\*\\.[A-Za-z0-9.-]+|[A-Za-z0-9.-]+)(/[^\\s]*)$").matcher(value);if(!m.matches())throw new Exception("Invalid @match pattern.");return m;}
    static Pattern pattern(String value)throws Exception{
        if(value.equals("<all_urls>"))return Pattern.compile("^https?://.*");
        Matcher m=parts(value);String host=m.group(2).toLowerCase(Locale.ROOT);String h=host.equals("*")?"[^/]+":host.startsWith("*.")?"(?:[^/]+\\.)?"+Pattern.quote(host.substring(2)):Pattern.quote(host);
        String[] pieces=m.group(3).split("\\*",-1);StringBuilder path=new StringBuilder();for(int i=0;i<pieces.length;i++){if(i>0)path.append(".*");path.append(Pattern.quote(pieces[i]));}
        return Pattern.compile("^"+(m.group(1).equals("*")?"https?":m.group(1))+"://"+h+"(?::[0-9]+)?"+path+"$");
    }
    boolean allows(String input){try{URI u=new URI(input);if(u.getHost()==null||u.getHost().replaceAll("\\.$","").equalsIgnoreCase("appassets.androidplatform.net")||u.getUserInfo()!=null)return false;String scheme=u.getScheme().toLowerCase(Locale.ROOT);if(!scheme.equals("https")&&!scheme.equals("http"))return false;String url=scheme+"://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()<0?"":":"+u.getPort())+(u.getRawPath().isEmpty()?"/":u.getRawPath())+(u.getRawQuery()==null?"":"?"+u.getRawQuery());boolean yes=false;for(String p:matches)if(pattern(p).matcher(url).matches())yes=true;for(String p:includes)if(glob(p).matcher(url).find())yes=true;for(String p:excludes)if(pattern(p).matcher(url).matches())return false;for(String p:excludeGlobs)if(glob(p).matcher(url).find())return false;return yes;}catch(Exception e){return false;}}
    Set<String> origins()throws Exception{HashSet<String> out=new HashSet<>();if(!includes.isEmpty())out.add("*");for(String p:matches){if(p.equals("<all_urls>")){out.add("*");continue;}Matcher m=parts(p);if(m.group(2).equals("*")){out.add("*");continue;}String host=m.group(2).toLowerCase(Locale.ROOT);if(m.group(1).equals("*")){out.add("https://"+host);out.add("http://"+host);}else out.add(m.group(1)+"://"+host);}return out;}
    boolean connects(String address,String page)throws Exception{
        URI uri=new URI(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new Exception("GM networking requires HTTPS without URL credentials.");
        String host=uri.getHost().toLowerCase(Locale.ROOT),self=new URI(page).getHost();if(host.replaceAll("\\.$","").equals("appassets.androidplatform.net"))return false;
        for(String rule:values("connect")){String r=rule.toLowerCase(Locale.ROOT);if(r.equals("self"))r=self;if(r.equals("*")||host.equals(r)||host.endsWith("."+r))return true;}return false;
    }
}
