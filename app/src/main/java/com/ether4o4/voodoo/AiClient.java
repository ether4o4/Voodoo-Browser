package com.ether4o4.voodoo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/** Native HTTPS transport. Keys never enter page JavaScript or exported backups. */
final class AiClient {
    interface Stream { void delta(String text); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws Exception; }
    private final ConnectionFactory connections;
    private static final class Call {
        volatile HttpURLConnection connection; volatile boolean cancelled;
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    }
    private final ConcurrentHashMap<String,Call> active=new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"voodoo-ai-deadline");t.setDaemon(true);return t;});
    AiClient(){this(url->(HttpURLConnection)url.openConnection());}
    AiClient(ConnectionFactory factory){connections=factory;}
    static URI endpoint(String base) throws Exception {
        URI uri=new URI(base);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new Exception("Enter an HTTPS API base URL without credentials, query, or fragment.");
        return uri;
    }
    void reserve(String id){active.put(id,new Call());}
    void cancel(String id){Call c=active.get(id);if(c!=null)c.cancel();}
    void cancelPrefix(String prefix){for(String id:active.keySet())if(id.startsWith(prefix))cancel(id);}
    void cancelAll(){for(String id:active.keySet())cancel(id);deadlines.shutdownNow();}
    JSONObject request(String id,JSONObject profile,String key,JSONObject input,boolean models) throws Exception {
        return request(id,profile,key,input,models,null);
    }
    JSONObject request(String id,JSONObject profile,String key,JSONObject input,boolean models,Stream stream) throws Exception {
        Call call=active.computeIfAbsent(id,unused->new Call());
        ScheduledFuture<?> deadline=deadlines.schedule(call::cancel,135,TimeUnit.SECONDS);
        try { return perform(id,call,profile,key,input,models,stream); }
        catch(Exception e){if(call.cancelled)throw new IOException("Request cancelled or exceeded the 135-second time limit.");throw e;}
        finally{deadline.cancel(false);active.remove(id,call);if(call.connection!=null)call.connection.disconnect();}
    }
    private JSONObject perform(String id,Call call,JSONObject profile,String key,JSONObject input,boolean models,Stream stream) throws Exception {
        if(call.cancelled)throw new IOException("Request cancelled.");
        String base=profile.getString("base").replaceAll("/+$",""); endpoint(base);
        String kind=profile.optString("kind","openai"),model=profile.optString("model");
        if(key.trim().isEmpty()&&!profile.optString("id").equals("custom"))throw new Exception("No API key saved for this provider. Open API keys and save a key.");
        boolean streaming=!models&&kind.equals("openai")&&stream!=null;
        if(!models&&model.trim().isEmpty())throw new Exception("Choose a model in Providers first.");
        String path;JSONObject body=new JSONObject();JSONArray history=input.optJSONArray("messages");if(history==null)history=new JSONArray();
        if(history.length()>40)throw new Exception("Start a new chat after 40 messages.");
        String system=input.optString("system");
        if(kind.equals("anthropic")){
            path=models?"/models":"/messages";
            body.put("model",model).put("max_tokens",8192).put("system",system).put("messages",history);
        }else if(kind.equals("gemini")){
            if(!models&&!model.matches("[A-Za-z0-9._-]+"))throw new Exception("Use the Gemini model ID without the models/ prefix.");
            path=models?"/models":"/models/"+model+":generateContent";
            JSONArray contents=new JSONArray();for(int i=0;i<history.length();i++){JSONObject m=history.getJSONObject(i);contents.put(new JSONObject().put("role",m.optString("role").equals("assistant")?"model":"user").put("parts",new JSONArray().put(new JSONObject().put("text",m.optString("content")))));}
            body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system)))).put("contents",contents);
        }else{
            path=models?"/models":"/chat/completions";
            JSONArray messages=new JSONArray();messages.put(new JSONObject().put("role","system").put("content",system));
            for(int i=0;i<history.length();i++)messages.put(history.getJSONObject(i));
            body.put("model",model).put("messages",messages).put("stream",streaming);
        }
        HttpURLConnection c=connections.open(new URL(base+path));call.connection=c;
        if(call.cancelled)throw new IOException("Request cancelled.");
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept",streaming?"text/event-stream, application/json":"application/json");
        if("openrouter.ai".equals(endpoint(base).getHost())){c.setRequestProperty("HTTP-Referer","https://github.com/ether4o4/Voodoo-Browser");c.setRequestProperty("X-Title","Voodoo Browser");}
        if(kind.equals("anthropic")){c.setRequestProperty("x-api-key",key);c.setRequestProperty("anthropic-version","2023-06-01");}
        else if(kind.equals("gemini"))c.setRequestProperty("x-goog-api-key",key);
        else if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);
        try{
            c.setRequestMethod(models?"GET":"POST");
            if(!models){c.setDoOutput(true);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>500000)throw new Exception("This conversation is too large. Start a new chat.");try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();
            if(status>=300&&status<400)throw new Exception("The API redirected. Update its base URL; credentials were not forwarded.");
            if(status<400&&streaming&&String.valueOf(c.getContentType()).contains("text/event-stream"))
                return readEvents(c.getInputStream(),stream);
            String raw=read(status>=400?c.getErrorStream():c.getInputStream(),4000000);
            if(status>=400){String detail="";try{Object e=new JSONObject(raw).opt("error");if(e instanceof JSONObject)detail=((JSONObject)e).optString("message");else if(e!=null)detail=e.toString();}catch(Exception ignored){}
                if(!key.isEmpty())detail=detail.replace(key,"[redacted]");
                throw new Exception("API "+status+(detail.trim().isEmpty()?"":": "+detail.substring(0,Math.min(detail.length(),500))));}
            JSONObject response=new JSONObject(raw);
            if(models){JSONArray entries=response.optJSONArray(kind.equals("gemini")?"models":"data");JSONArray ids=new JSONArray();if(entries!=null)for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e!=null){String value=e.optString(kind.equals("gemini")?"name":"id");if(kind.equals("gemini")){JSONArray methods=e.optJSONArray("supportedGenerationMethods");if(methods!=null&&!methods.toString().contains("generateContent"))continue;value=value.replaceFirst("^models/","");}if(!value.trim().isEmpty())ids.put(value);}}return new JSONObject().put("models",ids);}
            String text="",finish="";
            if(kind.equals("anthropic")){JSONArray parts=response.optJSONArray("content");if(parts!=null)for(int i=0;i<parts.length();i++)text+=parts.getJSONObject(i).optString("text");finish=response.optString("stop_reason");}
            else if(kind.equals("gemini")){JSONObject candidate=response.getJSONArray("candidates").getJSONObject(0);JSONArray parts=candidate.getJSONObject("content").getJSONArray("parts");for(int i=0;i<parts.length();i++){JSONObject p=parts.getJSONObject(i);if(!p.optBoolean("thought"))text+=p.optString("text");}finish=candidate.optString("finishReason");}
            else {JSONObject choice=response.getJSONArray("choices").getJSONObject(0);Object content=choice.getJSONObject("message").opt("content");if(content instanceof String)text=(String)content;else if(content instanceof JSONArray){JSONArray parts=(JSONArray)content;for(int i=0;i<parts.length();i++)text+=parts.getJSONObject(i).optString("text");}finish=choice.optString("finish_reason");}
            if(text.trim().isEmpty())throw new Exception("The model returned no text. Choose a chat or code model.");
            return new JSONObject().put("text",text).put("truncated",finish.equals("length")||finish.equals("max_tokens")||finish.equals("MAX_TOKENS"));
        }finally{c.disconnect();}
    }
    /** OpenAI-compatible SSE: event boundaries, multi-line data, bounded input and explicit EOF. */
    static JSONObject readEvents(InputStream input,Stream stream)throws Exception{
        StringBuilder text=new StringBuilder(),event=new StringBuilder();boolean complete=false;String finish="";int size=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
            String line;
            while((line=reader.readLine())!=null){
                size+=line.length();if(size>4000000)throw new IOException("Response is too large.");
                if(line.isEmpty()){
                    String data=event.toString().trim();event.setLength(0);
                    if(data.isEmpty())continue;
                    if(data.equals("[DONE]")){complete=true;break;}
                    JSONObject packet=new JSONObject(data);
                    if(packet.has("error"))throw new IOException("Stream error: "+packet.get("error").toString().substring(0,Math.min(500,packet.get("error").toString().length())));
                    JSONArray choices=packet.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                    JSONObject choice=choices.getJSONObject(0),delta=choice.optJSONObject("delta");
                    if(delta!=null){String part=delta.optString("content","");if(!part.isEmpty()){text.append(part);stream.delta(part);}}
                    if(!choice.isNull("finish_reason")){finish=choice.optString("finish_reason");complete=!finish.isEmpty();}
                }else if(line.startsWith("data:"))event.append(line.substring(5).replaceFirst("^[ \\t]+","")).append('\n');
            }
        }
        if(!complete)throw new IOException("The stream ended early. Partial text was not saved; retry.");
        if(text.toString().trim().isEmpty())throw new IOException("The model returned no text. Choose a chat or code model.");
        return new JSONObject().put("text",text.toString()).put("truncated","length".equals(finish));
    }
    static String read(InputStream in,int max) throws Exception {if(in==null)return "";try(in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Response is too large.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
}
