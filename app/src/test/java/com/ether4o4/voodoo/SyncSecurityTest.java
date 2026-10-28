package com.ether4o4.voodoo;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class SyncSecurityTest {
    private final String pairId="00112233445566778899aabbccddeeff";
    private final byte[] key=Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    private interface Checked {void run()throws Exception;}
    private void rejects(Checked work){try{work.run();fail("Expected rejection");}catch(Exception expected){}}
    private JSONObject note()throws Exception{return new JSONObject().put("id","12345678-1234-4234-9234-123456789abc").put("kind","note").put("title","Draft").put("body","Keep this line.").put("createdAt",1793228400000L);}
    private JSONObject vector()throws Exception{return new JSONObject().put("v",1).put("pairId",pairId).put("nonce","ICEiIyQlJicoKSor").put("data","qRjQUlapNixoGTO7pGuAsLRr1r7h5gaLCJIPcSvoP2hMt908uRID7CHIWqZrOi360xvcvrgQDxKrE10nfZ4osqgE9ynm9ozkqUbLSdowXEPvtWdhxI+mbqcgrE84MLy1djFDJep+xYjr9cAlmdT8IUOlunSm5oI8YsEzIsG1sRwR3U+tD8Myg1a7l1w5ByXHtakJGfHzs+RUKocN5LylqPvZHlu1hxvpw43r+NRLfd+Jn57mJqe2H1HipcGDSvPei8oFNhjvqjp7P5mcjKHOrblAkV+y/SmuNoY6bnVsfz0MJF674DICzx3IVacmW+3P+nJbWk6Y1A==");}
    @Test public void decryptsDesktopNodeKnownAnswer()throws Exception{
        JSONObject plain=SyncCrypto.decrypt(pairId,key,"request",vector());
        assertEquals("ffeeddccbbaa99887766554433221100",plain.getString("requestId"));
        assertEquals("pair",plain.getString("op"));assertEquals(1,plain.getLong("seq"));
        assertTrue(SyncData.equal(note(),SyncData.item(plain.getJSONArray("items").getJSONObject(0))));
    }
    @Test public void rejectsTamperingWrongDirectionWrongKeyAndPair()throws Exception{
        JSONObject envelope=vector();byte[] modified=Base64.getDecoder().decode(envelope.getString("data"));modified[0]^=1;
        envelope.put("data",Base64.getEncoder().encodeToString(modified));
        rejects(()->SyncCrypto.decrypt(pairId,key,"request",envelope));
        rejects(()->SyncCrypto.decrypt(pairId,key,"response",vector()));
        rejects(()->SyncCrypto.decrypt(pairId,new byte[32],"request",vector()));
        rejects(()->SyncCrypto.decrypt("00112233445566778899aabbccddeeee",key,"request",vector()));
    }
    @Test public void eachEncryptionUsesFreshNonceAndRoundTrips()throws Exception{
        JSONObject plain=new JSONObject().put("message","Private note 🚀");
        JSONObject a=SyncCrypto.encrypt(pairId,key,"response",plain),b=SyncCrypto.encrypt(pairId,key,"response",plain);
        assertNotEquals(a.getString("nonce"),b.getString("nonce"));
        assertEquals(plain.getString("message"),SyncCrypto.decrypt(pairId,key,"response",a).getString("message"));
    }
    @Test public void canonicalBase64AndStrictUtf8()throws Exception{
        rejects(()->SyncCrypto.bytes("AQ",1));rejects(()->SyncCrypto.bytes("AQ==\n",1));
        rejects(()->SyncCrypto.utf8(new byte[]{(byte)0xc3,0x28}));
        assertEquals(1,SyncCrypto.bytes("AQ==",1)[0]);
    }
    @Test public void requiresPrivateLiteralHttpsEndpoint()throws Exception{
        assertEquals("https://192.168.1.2:45443",SyncCrypto.endpoint("https://192.168.1.2:45443"));
        assertEquals("https://10.0.0.2:45443",SyncCrypto.endpoint("https://10.0.0.2:45443"));
        assertEquals("https://172.31.1.2:45443",SyncCrypto.endpoint("https://172.31.1.2:45443"));
        for(String value:new String[]{"http://192.168.1.2:45443","https://example.com:45443","https://127.0.0.1:45443","https://8.8.8.8:443","https://192.168.01.2:45443","https://192.168.1.2:45443/","https://192.168.1.2:45443?q=x","https://user:pass@192.168.1.2:45443","https://172.32.0.1:45443","https://192.168.1.999:45443"})rejects(()->SyncCrypto.endpoint(value));
    }
    @Test public void pairingExpiryAndClosedSchema()throws Exception{
        long now=1793228400000L;
        JSONObject p=new JSONObject().put("v",1).put("url","https://192.168.1.2:45443").put("fingerprint",new String(new char[64]).replace('\0','a'))
                .put("pairId",pairId).put("key",Base64.getEncoder().encodeToString(key)).put("expiresAt",now+300000);
        String prefix="voodoo-pair:v1:";
        String valid=prefix+Base64.getEncoder().encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(pairId,SyncCrypto.pairing(valid,now).getString("pairId"));rejects(()->SyncCrypto.pairing(valid,now+300000));
        p.put("secret","not accepted");String extra=prefix+Base64.getEncoder().encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));rejects(()->SyncCrypto.pairing(extra,now));
    }
    @Test public void mergingPreservesBothNotesAndRejectsReplacements()throws Exception{
        JSONObject old=note(),other=SyncData.create("note","Other","Both stay");
        JSONArray merged=SyncData.merge(new JSONArray().put(old),new JSONArray().put(other).put(old));assertEquals(2,merged.length());
        JSONObject conflict=new JSONObject(old.toString()).put("body","Replace old");rejects(()->SyncData.merge(new JSONArray().put(old),new JSONArray().put(conflict)));
        assertEquals("Keep this line.",old.getString("body"));
    }
    @Test public void credentialFieldsUnsafeUrlsAndOversizedDataRejected()throws Exception{
        rejects(()->SyncData.item(note().put("apiKey","secret")));
        rejects(()->SyncData.create("cookie","x","x"));
        for(String value:new String[]{"javascript:alert(1)","file:///secret","https://user:pass@example.com/","https://example.com\\@evil.test/","https://example.com/a\nb"})rejects(()->SyncData.create("tab","x",value));
        rejects(()->SyncData.create("note","x","x".repeat(20001)));
        JSONArray tooMany=new JSONArray();for(int i=0;i<1001;i++)tooMany.put(SyncData.create("note","x","x"));rejects(()->SyncData.merge(new JSONArray(),tooMany));
    }
    @Test public void numbersMustBeSafeIntegers()throws Exception{
        rejects(()->SyncData.item(note().put("createdAt",0.5)));
        rejects(()->SyncData.item(note().put("createdAt",9007199254740992L)));
        rejects(()->SyncData.item(note().put("createdAt","123")));
    }
    @Test public void rejectsReplayCrossRequestAndUnknownResponseFields()throws Exception{
        String requestId="ffeeddccbbaa99887766554433221100";
        JSONObject response=new JSONObject().put("v",1).put("requestId",requestId).put("seq",2).put("items",new JSONArray().put(note()));
        assertEquals(2,SyncCrypto.response(response,requestId,1).getLong("seq"));
        rejects(()->SyncCrypto.response(response,requestId,2));
        rejects(()->SyncCrypto.response(response,"00112233445566778899aabbccddeeff",1));
        rejects(()->SyncCrypto.response(new JSONObject(response.toString()).put("password","x"),requestId,1));
    }
    @Test public void rejectsAmbiguousTextDuplicateIdsAndCredentialLinks()throws Exception{
        rejects(()->SyncData.create("note","bad\ud800","x"));
        rejects(()->SyncData.merge(new JSONArray(),new JSONArray().put(note()).put(note())));
        for(String link:new String[]{"https://example.com/?access_token=x","https://example.com/?%2563ode=x","https://example.com/?X-Amz-Signature=x","https://example.com/#secret","https://example.com/?API_KEY=x","https://example.com/?sessionId=x"})rejects(()->SyncData.https(link));
        assertEquals("https://example.com/?q=music",SyncData.https("https://example.com/?q=music"));
        assertEquals("HTTPS://example.com/",SyncData.https("HTTPS://example.com/"));
    }
}
