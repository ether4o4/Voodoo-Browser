package com.ether4o4.voodoo;

import org.junit.Assume;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import javax.net.ssl.SSLException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import static org.junit.Assert.*;

/** Opt-in real Node HTTPS <-> Java/OkHttp interoperability. Never outputs the private code. */
public class SyncInteropTest {
    private static final String DESKTOP_ID="11111111-1111-4111-8111-111111111111";
    private static final String JAVA_ID="22222222-2222-4222-8222-222222222222";
    private Request request(String endpoint,JSONObject envelope){
        return new Request.Builder().url(endpoint+"/v1/exchange")
                .post(RequestBody.create(envelope.toString(),MediaType.parse("application/json"))).build();
    }
    private JSONObject read(Response response)throws Exception{
        assertEquals("Expected accepted encrypted exchange",200,response.code());
        assertNotNull(response.body());
        assertTrue(response.body().contentLength()<=SyncCrypto.MAX_ENVELOPE);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream input=response.body().byteStream()){
            byte[] buffer=new byte[8192];int size;
            while((size=input.read(buffer))!=-1){assertTrue(output.size()+size<=SyncCrypto.MAX_ENVELOPE);output.write(buffer,0,size);}
        }
        return new JSONObject(SyncCrypto.utf8(output.toByteArray()));
    }
    private JSONObject body(String id,long seq,String op,JSONArray items)throws Exception{
        return new JSONObject().put("v",1).put("requestId",id).put("seq",seq).put("op",op).put("name","Java TLS interoperability fixture").put("items",items);
    }
    private JSONObject find(JSONArray items,String id)throws Exception{
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(id.equals(item.getString("id")))return item;}
        fail("Expected shared fixture item was not returned");return null;
    }
    private void close(OkHttpClient client){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    @Test public void nodeDesktopAndActualAndroidTransportPairExchangeAndRejectReplay()throws Exception{
        String code=System.getenv("VOODOO_INTEROP_CODE");
        Assume.assumeTrue("Set VOODOO_INTEROP_CODE for the local Node HTTPS fixture",code!=null&&!code.isEmpty());
        JSONObject pair=SyncCrypto.pairing(code,System.currentTimeMillis());
        byte[] key=SyncCrypto.bytes(pair.getString("key"),32);
        String pairId=pair.getString("pairId"),endpoint=pair.getString("url");
        JSONObject javaNote=new JSONObject().put("id",JAVA_ID).put("kind","note").put("title","Java interop fixture")
                .put("body","Java -> Node").put("createdAt",2);
        String pairRequestId=SyncCrypto.randomId();
        JSONObject pairEnvelope=SyncCrypto.encrypt(pairId,key,"request",body(pairRequestId,1,"pair",new JSONArray().put(javaNote)));
        JSONObject wrongConfig=new JSONObject(pair.toString()).put("fingerprint","0".repeat(64));
        OkHttpClient wrong=SyncClient.transport(wrongConfig),actual=SyncClient.transport(pair);
        try{
            boolean rejected=false;
            try(Response ignored=wrong.newCall(request(endpoint,pairEnvelope)).execute()){
                fail("A changed certificate pin reached the HTTPS endpoint");
            }catch(SSLException expected){rejected=true;}
            assertTrue("The wrong certificate pin must fail TLS",rejected);

            JSONObject first;
            try(Response response=actual.newCall(request(endpoint,pairEnvelope)).execute()){
                first=SyncCrypto.decrypt(pairId,key,"response",read(response));
            }
            SyncCrypto.response(first,pairRequestId,0);
            assertEquals("Node -> Java",find(first.getJSONArray("items"),DESKTOP_ID).getString("body"));
            assertEquals("Java -> Node",find(first.getJSONArray("items"),JAVA_ID).getString("body"));

            // Empty outgoing items prove the Java note persisted on the desktop, not merely echoed.
            String syncRequestId=SyncCrypto.randomId();
            JSONObject syncEnvelope=SyncCrypto.encrypt(pairId,key,"request",body(syncRequestId,2,"sync",new JSONArray()));
            JSONObject second;
            try(Response response=actual.newCall(request(endpoint,syncEnvelope)).execute()){
                second=SyncCrypto.decrypt(pairId,key,"response",read(response));
            }
            SyncCrypto.response(second,syncRequestId,first.getLong("seq"));
            assertEquals("Node -> Java",find(second.getJSONArray("items"),DESKTOP_ID).getString("body"));
            assertEquals("Java -> Node",find(second.getJSONArray("items"),JAVA_ID).getString("body"));
            try(Response response=actual.newCall(request(endpoint,syncEnvelope)).execute()){
                assertFalse("A repeated request sequence must be rejected",response.isSuccessful());
            }
        }finally{Arrays.fill(key,(byte)0);close(wrong);close(actual);}
    }
}
