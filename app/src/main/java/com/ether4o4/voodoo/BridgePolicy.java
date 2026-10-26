package com.ether4o4.voodoo;

import java.net.URI;
import java.util.*;
import org.json.JSONObject;

final class BridgePolicy {
    static final Set<String> OPS=new HashSet<>(Arrays.asList("list_tabs","open_tab","select_tab","navigate","reload","close_tab","read_page","click","type","select","scroll","screenshot","wait_for","task_status","cancel_task"));
    static String origin(String value) throws Exception {
        URI u=new URI(value);String host=u.getHost();
        if(!"https".equalsIgnoreCase(u.getScheme())||host==null||u.getRawUserInfo()!=null||u.getPort()>65535||u.getPort()==0)throw new Exception("Use a valid HTTPS website without embedded credentials.");
        host=host.toLowerCase(Locale.ROOT);if(host.endsWith(".")||host.equals("appassets.androidplatform.net"))throw new Exception("This website cannot be controlled remotely.");
        return "https://"+host+(u.getPort()==-1||u.getPort()==443?"":":"+u.getPort());
    }
    static Set<String> sites(String input)throws Exception{
        Set<String> out=new LinkedHashSet<>();for(String item:input.split("[\\s,]+")){if(item.isEmpty())continue;String url=item.contains("://")?item:"https://"+item;URI u=new URI(url);if(u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception("Enter website hosts, not page URLs.");out.add(origin(url));if(out.size()>50)throw new Exception("At most 50 websites are supported.");}return out;
    }
    static void validate(JSONObject c,long now)throws Exception{
        String id=c.getString("id"),op=c.getString("op");
        if(!id.matches("[A-Za-z0-9_-]{8,100}")||!OPS.contains(op))throw new Exception("Invalid bridge command.");
        long deadline=c.getLong("deadline");if(deadline<=now||deadline>now+60000)throw new Exception("Command expired or deadline is invalid.");
        if(c.toString().length()>24000)throw new Exception("Bridge command is too large.");
        if(Arrays.asList("click","type","select").contains(op)){
            if(!c.getString("ref").matches("e[0-9]{1,3}")||c.getString("snapshotId").length()>150||c.getLong("documentVersion")<1)throw new Exception("Invalid element reference.");
        }
        if(op.equals("type")&&c.getString("text").length()>10000)throw new Exception("Text is too long.");
        if(op.equals("select")&&c.getString("value").length()>1000)throw new Exception("Option is too long.");
        if(op.equals("scroll")&&Math.abs((long)c.optInt("pixels",600))>4000)throw new Exception("Scroll is out of bounds.");
        if(op.equals("wait_for")&&(c.optString("text").isEmpty()||c.getString("text").length()>300||c.optInt("timeoutMs",5000)<100||c.optInt("timeoutMs",5000)>15000))throw new Exception("Invalid wait condition.");
        if(op.equals("open_tab")||op.equals("navigate"))origin(c.getString("url"));
    }
    private BridgePolicy(){}
}
