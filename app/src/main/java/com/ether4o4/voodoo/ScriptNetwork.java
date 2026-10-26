package com.ether4o4.voodoo;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/** Cookie-free bounded script HTTP. Never shares provider transport, keys or WebView cookies. */
final class ScriptNetwork {
    interface Policy { void check(String url) throws Exception; }
    private final Map<String,HttpURLConnection> active=new ConcurrentHashMap<>();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"voodoo-script-network-deadline");t.setDaemon(true);return t;});
    void reserve(String id){cancelled.remove(id);}
    void cancel(String id){cancelled.add(id);HttpURLConnection c=active.get(id);if(c!=null)c.disconnect();}
    void finish(String id){active.remove(id);cancelled.remove(id);}
    void close(){for(String id:active.keySet())cancel(id);deadlines.shutdownNow();}
    static void https(String url)throws Exception{
        URI u=AiClient.endpoint(url.split("#",2)[0].split("\\?",2)[0]);
        if("appassets.androidplatform.net".equalsIgnoreCase(u.getHost().replaceAll("\\.$","")))throw new IOException("Internal browser URLs are forbidden.");
    }
    JSONObject request(String id,JSONObject details,Policy policy,int max)throws Exception{
        String url=details.getString("url"),method=details.optString("method","GET").toUpperCase(Locale.ROOT);
        if(!Arrays.asList("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(method))throw new IOException("Unsupported HTTP method.");
        if(details.optBoolean("synchronous"))throw new IOException("Synchronous network requests are unsupported.");
        String data=details.optString("data","");if(data.length()>1000000)throw new IOException("Request body exceeds 1 MB.");
        JSONObject headers=details.optJSONObject("headers");if(headers==null)headers=new JSONObject();
        long deadline=System.nanoTime()+60_000_000_000L;
        ScheduledFuture<?> timeout=deadlines.schedule(()->cancel(id),60,TimeUnit.SECONDS);
        try{
            for(int hop=0;hop<6;hop++){
                if(cancelled.contains(id))throw new IOException("Request aborted.");
                https(url);policy.check(url);
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();active.put(id,c);
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(Math.max(1000,Math.min(30000,details.optInt("timeout",30000))));c.setRequestMethod(method);
                c.setRequestProperty("User-Agent","Voodoo-Browser/0.3 userscripts");
                for(Iterator<String> it=headers.keys();it.hasNext();){String key=it.next();String lower=key.toLowerCase(Locale.ROOT);if(Arrays.asList("cookie","cookie2","host","origin","referer","connection","content-length","proxy-authorization").contains(lower)||lower.startsWith("sec-")||key.contains("\r")||key.contains("\n"))throw new IOException("Forbidden request header: "+key);String value=headers.getString(key);if(value.contains("\r")||value.contains("\n"))throw new IOException("Invalid request header.");c.setRequestProperty(key,value);}
                if(cancelled.contains(id))throw new IOException("Request aborted.");
                if(!data.isEmpty()&&!method.equals("GET")&&!method.equals("HEAD")){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(data.getBytes(StandardCharsets.UTF_8));}}
                int status=c.getResponseCode();
                if(status>=300&&status<400&&c.getHeaderField("Location")!=null){
                    String next=new URI(url).resolve(c.getHeaderField("Location")).toString();https(next);policy.check(next);
                    if(!new URI(url).getAuthority().equals(new URI(next).getAuthority()))headers=new JSONObject();
                    if(status==303||((status==301||status==302)&&method.equals("POST"))){method="GET";data="";}
                    c.disconnect();url=next;continue;
                }
                StringBuilder responseHeaders=new StringBuilder();for(Map.Entry<String,List<String>> h:c.getHeaderFields().entrySet())if(h.getKey()!=null&&!h.getKey().equalsIgnoreCase("set-cookie"))for(String value:h.getValue())responseHeaders.append(h.getKey()).append(": ").append(value).append("\r\n");
                byte[] bytes;
                InputStream body=status>=400?c.getErrorStream():c.getInputStream();
                try(InputStream in=body;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;
                    if(in!=null)while((n=in.read(buffer))!=-1){if(cancelled.contains(id))throw new IOException("Request aborted.");if(System.nanoTime()>deadline)throw new IOException("Request exceeded 60 seconds.");if(out.size()+n>max)throw new IOException("Response exceeds "+max+" bytes.");out.write(buffer,0,n);}bytes=out.toByteArray();
                }
                JSONObject result=new JSONObject().put("status",status).put("statusText",c.getResponseMessage()).put("finalUrl",url).put("responseHeaders",responseHeaders.toString()).put("readyState",4)
                    .put("responseText",new String(bytes,StandardCharsets.UTF_8)).put("base64",Base64.getEncoder().encodeToString(bytes)).put("contentType",c.getContentType()==null?"application/octet-stream":c.getContentType());
                c.disconnect();return result;
            }
            throw new IOException("Too many redirects.");
        }finally{timeout.cancel(false);HttpURLConnection c=active.remove(id);if(c!=null)c.disconnect();finish(id);}
    }
    static String digest(String source)throws Exception{return digest(source.getBytes(StandardCharsets.UTF_8));}
    static String digest(byte[] bytes)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
}
