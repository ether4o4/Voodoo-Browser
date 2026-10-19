package com.ether4o4.voodoo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Native HTTPS transport. Keys never enter page JavaScript or exported backups. */
final class AiClient {
    private final ConcurrentHashMap<String,HttpURLConnection> active=new ConcurrentHashMap<>();
    static URI endpoint(String base) throws Exception {
        URI uri=new URI(base);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new Exception("Enter an HTTPS API base URL without credentials, query, or fragment.");
        return uri;
    }
    void cancel(String id){HttpURLConnection c=active.remove(id);if(c!=null)c.disconnect();}
    void cancelAll(){for(String id:active.keySet())cancel(id);}
    JSONObject request(String id,JSONObject profile,String key,JSONObject input,boolean models) throws Exception {
        String base=profile.getString("base").replaceAll("/+$",""); endpoint(base);
        String kind=profile.optString("kind","openai"),model=profile.optString("model");
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
            body.put("model",model).put("messages",messages).put("stream",false);
        }
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        if(kind.equals("anthropic")){c.setRequestProperty("x-api-key",key);c.setRequestProperty("anthropic-version","2023-06-01");}
        else if(kind.equals("gemini"))c.setRequestProperty("x-goog-api-key",key);
        else if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);
        active.put(id,c);
        try{
            c.setRequestMethod(models?"GET":"POST");
            if(!models){c.setDoOutput(true);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>500000)throw new Exception("This conversation is too large. Start a new chat.");try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();
            if(status>=300&&status<400)throw new Exception("The API redirected. Update its base URL; credentials were not forwarded.");
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
        }finally{active.remove(id);c.disconnect();}
    }
    static String read(InputStream in,int max) throws Exception {if(in==null)return "";try(in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Response is too large.");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
}
