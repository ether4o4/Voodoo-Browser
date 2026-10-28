package com.ether4o4.voodoo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Small, closed shared-data format. Credentials and browser profiles are not accepted. */
final class SyncData {
    static final int MAX_ITEMS = 1000, MAX_BYTES = 1024 * 1024;
    static final long MAX_INTEGER = 9007199254740991L;
    static void fields(JSONObject value, String... names) throws Exception {
        Set<String> expected = new HashSet<>(Arrays.asList(names));
        if (value.length() != expected.size()) throw new Exception("Unexpected sync fields.");
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!expected.contains(keys.next())) throw new Exception("Unexpected sync field.");
    }
    static String text(JSONObject object, String key, int max) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String)value).length() > max) throw new Exception("Invalid sync text.");
        String string=(String)value;
        for(int i=0;i<string.length();i++) {
            char c=string.charAt(i);
            if(Character.isHighSurrogate(c)){if(i+1>=string.length() || !Character.isLowSurrogate(string.charAt(++i)))throw new Exception("Invalid sync text.");}
            else if(Character.isLowSurrogate(c))throw new Exception("Invalid sync text.");
        }
        return string;
    }
    static long integer(JSONObject object, String key, long minimum) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new Exception("Invalid sync number.");
        double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number) || Math.floor(number) != number || number < minimum || number > MAX_INTEGER)
            throw new Exception("Invalid sync number.");
        return ((Number)value).longValue();
    }
    static String https(String value) throws Exception {
        if (value.length() > 2048 || value.matches("(?s).*[\\x00-\\x20\\x7f].*")) throw new Exception("Use an HTTPS link.");
        URI u = new URI(value);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null ||
                u.getPort() == 0 || u.getPort() > 65535) throw new Exception("Use an HTTPS link without sign-in details.");
        if(u.getRawFragment()!=null && !u.getRawFragment().isEmpty())throw new Exception("Remove the link fragment before sharing; it can contain sign-in details.");
        String query=u.getRawQuery();
        if(query!=null)for(String field:query.split("&")) {
            String name=field.split("=",2)[0];
            for(int i=0;i<3;i++) {try{String decoded=URLDecoder.decode(name,"UTF-8");if(decoded.equals(name))break;name=decoded;}catch(IllegalArgumentException e){throw new Exception("Invalid link query.");}}
            name=name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");
            if(name.matches(".*(?:token|password|passwd|secret|apikey|authorization|session|oauth|signature|credential).*" ) ||
                    Arrays.asList("code","key","auth","sig","jwt","csrf","xsrf","accesskey","clientkey","privatekey","publickey","authcode","verificationcode").contains(name))
                throw new Exception("This link may contain credentials. Share the website's normal address instead.");
        }
        return value;
    }
    static JSONObject item(JSONObject source) throws Exception {
        String kind = text(source,"kind",10);
        boolean note = "note".equals(kind);
        if (!note && !"bookmark".equals(kind) && !"tab".equals(kind)) throw new Exception("Unknown shared item.");
        fields(source,"id","kind","title",note ? "body" : "url","createdAt");
        String id = text(source,"id",36);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new Exception("Invalid item ID.");
        JSONObject result = new JSONObject().put("id",id).put("kind",kind).put("title",text(source,"title",200));
        result.put(note ? "body" : "url",note ? text(source,"body",20000) : https(text(source,"url",2048)));
        return result.put("createdAt",integer(source,"createdAt",0));
    }
    static JSONObject create(String kind, String title, String value) throws Exception {
        JSONObject result = new JSONObject().put("id",UUID.randomUUID().toString()).put("kind",kind)
                .put("title",title).put("createdAt",System.currentTimeMillis());
        result.put("note".equals(kind) ? "body" : "url",value);
        return item(result);
    }
    static boolean equal(JSONObject a, JSONObject b) throws Exception {
        if (a.length() != b.length()) return false;
        Iterator<String> keys = a.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!a.get(key).equals(b.get(key))) return false; }
        return true;
    }
    static JSONArray merge(JSONArray local, JSONArray incoming) throws Exception {
        if (local.length() > MAX_ITEMS || incoming.length() > MAX_ITEMS) throw new Exception("Shared library is full.");
        LinkedHashMap<String,JSONObject> items = new LinkedHashMap<>();
        for (JSONArray group : Arrays.asList(local,incoming)) {
          Set<String> groupIds=new HashSet<>();
          for (int i=0;i<group.length();i++) {
            JSONObject value = item(group.getJSONObject(i));
            if(!groupIds.add(value.getString("id")))throw new Exception("Duplicate shared item ID.");
            JSONObject old = items.get(value.getString("id"));
            if (old != null && !equal(old,value)) throw new Exception("Conflicting item ID; no library data was replaced.");
            items.put(value.getString("id"),value);
            if (items.size() > MAX_ITEMS) throw new Exception("Shared library is full.");
          }
        }
        JSONArray result = new JSONArray();
        for (JSONObject value : items.values()) result.put(value);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new Exception("Shared library exceeds 1 MB.");
        return result;
    }
}
