package com.ether4o4.voodoo;

import android.app.ActivityManager;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Dedicated offline coding runtime. No cloud client, localhost server, or WebView bridge. */
final class LocalScriptAi {
    private final MainActivity a;
    private final JSONObject catalog,reference;
    private final SharedPreferences prefs;
    private final File models;
    private final ExecutorService downloads=Executors.newSingleThreadExecutor(),inference=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false),downloading=new AtomicBoolean(false);
    private volatile boolean paused=false,cancelled=false;
    private volatile HttpURLConnection connection;
    private volatile Process process;
    private volatile String downloadId="",downloadState="idle",error="",inferenceState="unloaded";
    private volatile long downloaded=0;
    LocalScriptAi(MainActivity activity){
        a=activity;catalog=a.object(a.asset("script-ai/models.json"));reference=a.object(a.asset("script-ai/reference.json"));
        prefs=a.getSharedPreferences("local_script_ai_v1",Context.MODE_PRIVATE);models=new File(a.getFilesDir(),"script-models");
        if(!models.exists()&&!models.mkdirs())error="Cannot create private model storage.";
    }
    JSONObject model(String id)throws Exception{JSONArray list=catalog.getJSONArray("models");for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i);throw new Exception("Unknown local model.");}
    String selected(){return prefs.getString("selected",catalog.optString("default"));}
    File file(String id,boolean partial)throws Exception{model(id);return new File(models,id+(partial?".partial":".gguf"));}
    File executable(){return new File(a.getApplicationInfo().nativeLibraryDir,"libvoodoo-completion.so");}
    boolean ready(JSONObject m)throws Exception{File f=file(m.getString("id"),false);return f.isFile()&&f.length()==m.getLong("bytes")&&m.getString("sha256").equals(prefs.getString("verified."+m.getString("id"),""));}
    JSONObject status()throws Exception{
        JSONArray list=new JSONArray();for(int i=0;i<catalog.getJSONArray("models").length();i++){JSONObject m=new JSONObject(catalog.getJSONArray("models").getJSONObject(i).toString());m.put("ready",ready(m)).put("partialBytes",file(m.getString("id"),true).length());list.put(m);}
        return new JSONObject().put("selected",selected()).put("models",list).put("runtimeAvailable",executable().canExecute()&&Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")).put("runtime","llama.cpp b11868 · Android ARM64 CPU")
            .put("inferenceState",inferenceState).put("downloading",downloading.get()).put("downloadId",downloadId).put("downloadState",downloadState).put("downloaded",downloaded).put("error",error).put("freeStorage",models.getUsableSpace()).put("lastBenchmark",a.object(prefs.getString("lastBenchmark","{}")));
    }
    void select(String id)throws Exception{model(id);if(busy.get())throw new Exception("Unload the running model before switching.");if(!prefs.edit().putString("selected",id).commit())throw new Exception("Could not select model.");}
    void startDownload(String id)throws Exception{
        JSONObject m=model(id);if(ready(m)){downloadState="ready";return;}
        if(!downloading.compareAndSet(false,true))throw new Exception("A model download is already running.");
        paused=false;downloadId=id;downloadState="downloading";error="";downloads.execute(()->{
            try{downloadModel(m);downloadState="ready";}
            catch(Exception e){downloadState=paused?"paused":"failed";error=paused?"Download paused; resume to continue.":e.getMessage();}
            finally{HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;downloading.set(false);}
        });
    }
    void downloadModel(JSONObject m)throws Exception{
        String id=m.getString("id");File partial=file(id,true);long expected=m.getLong("bytes"),offset=partial.length();downloaded=offset;
        if(offset>expected)throw new Exception("Partial file is invalid. Delete this model and retry.");
        if(models.getUsableSpace()<expected-offset+100_000_000L)throw new Exception("Not enough storage for this model.");
        if(offset<expected){
            String url=m.getString("url");HttpURLConnection c=null;
            for(int redirect=0;redirect<8;redirect++){
                if(paused)throw new IOException("Paused.");
                URI uri=new URI(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IOException("Model download requires HTTPS.");
                c=(HttpURLConnection)new URL(url).openConnection();connection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept-Encoding","identity");if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
                int code=c.getResponseCode();
                if(code>=300&&code<400){String next=c.getHeaderField("Location");if(next==null)throw new IOException("Missing model redirect.");url=uri.resolve(next).toString();c.disconnect();continue;}
                if(code!=200&&code!=206)throw new IOException("Model download HTTP "+code);
                if(code==206){String range=c.getHeaderField("Content-Range");if(range==null||!range.startsWith("bytes "+offset+"-")||!range.endsWith("/"+expected))throw new IOException("Invalid resumed download range.");}
                else {offset=0;downloaded=0;}
                try(InputStream in=c.getInputStream();RandomAccessFile out=new RandomAccessFile(partial,"rw")){
                    if(offset==0)out.setLength(0);out.seek(offset);byte[] buffer=new byte[65536];int count;
                    while((count=in.read(buffer))!=-1){if(paused)throw new IOException("Paused.");if(downloaded+count>expected)throw new IOException("Model exceeded its published size.");out.write(buffer,0,count);downloaded+=count;}
                    out.getFD().sync();
                }c.disconnect();break;
            }
        }
        if(partial.length()!=expected)throw new IOException("Download incomplete. Resume to continue.");
        downloadState="verifying";MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(partial)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(paused)throw new IOException("Paused.");digest.update(buffer,0,n);}}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b));
        if(!hex.toString().equals(m.getString("sha256")))throw new IOException("SHA-256 verification failed. Delete the partial model and download again.");
        File complete=file(id,false);if(!partial.renameTo(complete))throw new IOException("Cannot finalize verified model.");
        if(!prefs.edit().putString("verified."+id,m.getString("sha256")).commit())throw new IOException("Could not save verification receipt.");
    }
    void pause(){paused=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    void delete(String id)throws Exception{
        model(id);if(busy.get()||(downloading.get()&&id.equals(downloadId)))throw new Exception("Pause downloads and unload inference before deleting.");
        for(boolean partial:new boolean[]{true,false}){File f=file(id,partial);if(f.exists()&&!f.delete())throw new Exception("Could not delete model file.");}
        prefs.edit().remove("verified."+id).apply();error="";downloadState="idle";
    }
    void generate(JSONObject input,java.util.function.Consumer<String> delta,java.util.function.Consumer<JSONObject> ok,java.util.function.Consumer<String> fail){
        if(!busy.compareAndSet(false,true)){fail.accept("Script AI is already running.");return;}
        cancelled=false;error="";inferenceState="loading";String id=selected();
        inference.execute(()->{
            File promptFile=null;ScheduledFuture<?> timeout=null;Process child=null;
            try{
                JSONObject m=model(id);if(!ready(m))throw new Exception("Download and verify the selected model first.");
                if(!executable().canExecute())throw new Exception("ARM64 runtime is unavailable. Install the ARM64 APK.");
                ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)a.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
                if(memory.lowMemory||memory.availMem<m.getLong("minimumFreeMemory"))throw new Exception("Insufficient free memory. Close background apps or choose the 1.5B model.");
                String prompt=ScriptKnowledge.prompt(reference,input);promptFile=File.createTempFile("voodoo-script-ai-",".txt",a.getCacheDir());
                try(OutputStream out=new FileOutputStream(promptFile)){out.write(prompt.getBytes(StandardCharsets.UTF_8));}
                List<String> command=Arrays.asList(executable().getAbsolutePath(),"-m",file(id,false).getAbsolutePath(),"-f",promptFile.getAbsolutePath(),"-n","768","-c","8192","-b","128","-ub","128","-t","4","-ngl","0","--temp","0.2","--seed","42","-no-cnv","--no-display-prompt","--simple-io","--offline","--no-warmup");
                ProcessBuilder builder=new ProcessBuilder(command);builder.directory(models);builder.environment().put("LD_LIBRARY_PATH",a.getApplicationInfo().nativeLibraryDir);builder.environment().put("GGML_BACKEND_PATH",a.getApplicationInfo().nativeLibraryDir);
                // No shell, network listener, user-provided executable, or command-line interpolation.
                long began=System.nanoTime();if(cancelled)throw new IOException("Cancelled.");
                child=builder.start();process=child;child.getOutputStream().close();inferenceState="generating";Process running=child;
                timeout=watchdog.schedule(()->{cancelled=true;running.destroyForcibly();},240,TimeUnit.SECONDS);
                StringBuilder diagnostic=new StringBuilder();Thread stderr=new Thread(()->{try(Reader reader=new InputStreamReader(running.getErrorStream(),StandardCharsets.UTF_8)){char[] buffer=new char[2048];int n;while((n=reader.read(buffer))!=-1)synchronized(diagnostic){diagnostic.append(buffer,0,n);if(diagnostic.length()>60000)diagnostic.delete(0,diagnostic.length()-60000);}}catch(IOException ignored){}},"script-ai-stderr");stderr.start();
                StringBuilder output=new StringBuilder();long first=0;
                try(Reader reader=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)){char[] buffer=new char[256];int n;while((n=reader.read(buffer))!=-1){if(cancelled)throw new IOException("Script AI cancelled or exceeded four minutes.");if(first==0)first=System.nanoTime();String part=new String(buffer,0,n);output.append(part);if(output.length()>100000)throw new IOException("Local response too large.");delta.accept(part);}}
                int exit=child.waitFor();stderr.join(3000);String logs; synchronized(diagnostic){logs=diagnostic.toString();}
                if(cancelled)throw new IOException("Script AI cancelled or exceeded four minutes.");
                if(exit!=0)throw new IOException("Local runtime exited "+exit+": "+logs.substring(Math.max(0,logs.length()-3000)));
                String text=output.toString().replace("<|im_end|>","").trim();if(text.isEmpty())throw new IOException("Local model returned no text.");
                JSONObject benchmark=new JSONObject().put("model",id).put("runtime","b11868").put("elapsedMs",(System.nanoTime()-began)/1_000_000).put("firstOutputMs",first==0?JSONObject.NULL:(first-began)/1_000_000).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("android",Build.VERSION.RELEASE).put("memoryMeasurement","Runtime-reported model/KV buffers; not peak RSS").put("runtimeStatistics",logs.substring(Math.max(0,logs.length()-12000)));
                Matcher speed=Pattern.compile("([0-9.]+) tokens per second").matcher(logs);String last="";while(speed.find())last=speed.group(1);if(!last.isEmpty())benchmark.put("reportedTokensPerSecond",Double.parseDouble(last));
                prefs.edit().putString("lastBenchmark",benchmark.toString()).apply();
                ok.accept(new JSONObject().put("text",text).put("benchmark",benchmark).put("referenceVersion",reference.optString("version")));
            }catch(Exception e){error=e.getMessage();fail.accept(error==null?"Local inference failed.":error);}
            finally{if(timeout!=null)timeout.cancel(false);if(child!=null&&child.isAlive())child.destroyForcibly();process=null;if(promptFile!=null&&promptFile.exists())promptFile.delete();inferenceState="unloaded";busy.set(false);}
        });
    }
    void unload(){cancelled=true;Process p=process;if(p!=null)p.destroyForcibly();}
    void close(){pause();unload();downloads.shutdownNow();inference.shutdownNow();watchdog.shutdownNow();}
}
