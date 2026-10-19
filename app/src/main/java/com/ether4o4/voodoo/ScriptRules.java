package com.ether4o4.voodoo;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Native scope checks keep scripts for unrelated sites out of each page's JS context. */
final class ScriptRules {
    final ArrayList<String> matches=new ArrayList<>(),excludes=new ArrayList<>();
    ScriptRules(String code)throws Exception{
        if(code.length()>200000)throw new Exception("Script too large.");
        Matcher header=Pattern.compile("//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==").matcher(code);
        if(!header.find())throw new Exception("Missing userscript header.");
        Matcher fields=Pattern.compile("(?m)^\\s*//\\s*@([\\w-]+)\\s+(.+)$").matcher(header.group(1));
        while(fields.find()){String key=fields.group(1),value=fields.group(2).trim();if(key.equals("match")){pattern(value);matches.add(value);}else if(key.equals("exclude-match")){pattern(value);excludes.add(value);}else if(Arrays.asList("require","resource","connect","include","exclude").contains(key))throw new Exception("Unsupported @"+key);else if(key.equals("grant")&&!Arrays.asList("none","GM_addStyle","GM_getValue","GM_setValue","GM_deleteValue").contains(value))throw new Exception("Unsupported grant.");}
        if(matches.isEmpty())throw new Exception("Missing @match.");
    }
    static Matcher parts(String value)throws Exception{Matcher m=Pattern.compile("^(https?|\\*):[/]{2}(\\*|\\*\\.[A-Za-z0-9.-]+|[A-Za-z0-9.-]+)(/[^\\s]*)$").matcher(value);if(!m.matches())throw new Exception("Invalid @match pattern.");return m;}
    static Pattern pattern(String value)throws Exception{
        if(value.equals("<all_urls>"))return Pattern.compile("^https?://.*");
        Matcher m=parts(value);String host=m.group(2).toLowerCase(Locale.ROOT);String h=host.equals("*")?"[^/]+":host.startsWith("*.")?"(?:[^/]+\\.)?"+Pattern.quote(host.substring(2)):Pattern.quote(host);
        String[] pieces=m.group(3).split("\\*",-1);StringBuilder path=new StringBuilder();for(int i=0;i<pieces.length;i++){if(i>0)path.append(".*");path.append(Pattern.quote(pieces[i]));}
        return Pattern.compile("^"+(m.group(1).equals("*")?"https?":m.group(1))+"://"+h+"(?::[0-9]+)?"+path+"$");
    }
    boolean allows(String input){try{URI u=new URI(input);if(u.getHost()==null||u.getHost().equalsIgnoreCase("appassets.androidplatform.net")||u.getUserInfo()!=null)return false;String scheme=u.getScheme().toLowerCase(Locale.ROOT);if(!scheme.equals("https")&&!scheme.equals("http"))return false;String url=scheme+"://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()<0?"":":"+u.getPort())+(u.getRawPath().isEmpty()?"/":u.getRawPath())+(u.getRawQuery()==null?"":"?"+u.getRawQuery());boolean yes=false;for(String p:matches)if(pattern(p).matcher(url).matches())yes=true;for(String p:excludes)if(pattern(p).matcher(url).matches())return false;return yes;}catch(Exception e){return false;}}
    Set<String> origins()throws Exception{HashSet<String> out=new HashSet<>();for(String p:matches){if(p.equals("<all_urls>")){out.add("*");continue;}Matcher m=parts(p);if(m.group(2).equals("*")){out.add("*");continue;}String host=m.group(2).toLowerCase(Locale.ROOT);if(m.group(1).equals("*")){out.add("https://"+host);out.add("http://"+host);}else out.add(m.group(1)+"://"+host);}return out;}
}
