package com.ether4o4.voodoo;

import android.os.*;
import okhttp3.*;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

final class BridgeClient extends WebSocketListener {
    private final BridgeRuntime runtime;
    private final String relay,device,token;
    private final OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).pingInterval(20,TimeUnit.SECONDS).build();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile WebSocket socket;private volatile boolean closed,online;private int attempts;
    private final Runnable retry=()->{if(!closed)try{connect();}catch(Exception e){lost();}};
    BridgeClient(BridgeRuntime runtime,String relay,String device,String token){this.runtime=runtime;this.relay=relay;this.device=device;this.token=token;}
    boolean connected(){return online&&!closed;}
    void connect()throws Exception{
        if(closed)return;String origin=BridgePolicy.origin(relay);
        Request request=new Request.Builder().url(origin.replaceFirst("^https://","wss://")+"/device").header("X-Device-Id",device).header("Authorization","Bearer "+token).build();
        socket=http.newWebSocket(request,this);
    }
    void send(JSONObject value){WebSocket ws=socket;if(connected()&&ws!=null&&!ws.send(value.toString()))lost();}
    void close(){closed=true;online=false;main.removeCallbacks(retry);WebSocket ws=socket;socket=null;if(ws!=null)ws.cancel();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
    @Override public void onOpen(WebSocket ws,Response response){main.post(()->{if(closed||ws!=socket){ws.cancel();return;}online=true;attempts=0;main.removeCallbacks(retry);});}
    @Override public void onMessage(WebSocket ws,String text){if(closed||ws!=socket||text.length()>24000)return;try{JSONObject c=new JSONObject(text);if("command".equals(c.optString("type")))runtime.command(this,c);}catch(Exception ignored){}}
    @Override public void onFailure(WebSocket ws,Throwable t,Response response){if(ws!=socket||closed)return;if(response!=null&&(response.code()==401||response.code()==403)){closed=true;online=false;runtime.transportLost(this);return;}lost();}
    @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);}
    @Override public void onClosed(WebSocket ws,int code,String reason){if(ws!=socket||closed)return;if(code==4001||code==4003){closed=true;online=false;runtime.transportLost(this);return;}lost();}
    private void lost(){online=false;runtime.transportLost(this);main.removeCallbacks(retry);if(!closed)main.postDelayed(retry,Math.min(30000,1000L<<Math.min(attempts++,5)));}
}
