package com.ether4o4.voodoo;

import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Interoperable authenticated envelope; its direction is cryptographically bound. */
final class SyncCrypto {
    static final int MAX_ENVELOPE = 1572864;
    static final SecureRandom RANDOM = new SecureRandom();
    static byte[] bytes(String encoded, int size) throws Exception {
        byte[] result;
        try { result = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException e) { throw new Exception("Invalid sync encoding."); }
        if ((size >= 0 && result.length != size) || !Base64.getEncoder().encodeToString(result).equals(encoded))
            throw new Exception("Invalid sync encoding.");
        return result;
    }
    static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for(byte b:value) result.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return result.toString();
    }
    static String randomId() { byte[] value = new byte[16]; RANDOM.nextBytes(value); return hex(value); }
    static String utf8(byte[] value) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    static void pairId(String id) throws Exception { if (!id.matches("[0-9a-f]{32}")) throw new Exception("Invalid pairing ID."); }
    static byte[] aad(String pairId, String direction) throws Exception {
        pairId(pairId);
        if (!"request".equals(direction) && !"response".equals(direction)) throw new Exception("Invalid envelope direction.");
        return ("voodoo-sync-v1\n"+pairId+"\n"+direction).getBytes(StandardCharsets.UTF_8);
    }
    static JSONObject encrypt(String pairId, byte[] key, String direction, JSONObject payload) throws Exception {
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        return encrypt(pairId,key,direction,payload,nonce);
    }
    static JSONObject encrypt(String pairId, byte[] key, String direction, JSONObject payload, byte[] nonce) throws Exception {
        if (key.length != 32 || nonce.length != 12) throw new Exception("Invalid encryption material.");
        byte[] plain = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > SyncData.MAX_BYTES + 4096) throw new Exception("Sync message is too large.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad(pairId,direction));
        return new JSONObject().put("v",1).put("pairId",pairId).put("nonce",Base64.getEncoder().encodeToString(nonce))
                .put("data",Base64.getEncoder().encodeToString(cipher.doFinal(plain)));
    }
    static JSONObject decrypt(String expectedPair, byte[] key, String direction, JSONObject envelope) throws Exception {
        SyncData.fields(envelope,"v","pairId","nonce","data");
        if (SyncData.integer(envelope,"v",1) != 1 || !expectedPair.equals(SyncData.text(envelope,"pairId",32)) || key.length != 32)
            throw new Exception("Invalid sync envelope.");
        byte[] nonce = bytes(SyncData.text(envelope,"nonce",16),12);
        byte[] data = bytes(SyncData.text(envelope,"data",MAX_ENVELOPE),-1);
        if (data.length < 16 || data.length > SyncData.MAX_BYTES+4112) throw new Exception("Invalid sync envelope size.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(aad(expectedPair,direction));
        return new JSONObject(utf8(cipher.doFinal(data)));
    }
    static String endpoint(String value) throws Exception {
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null ||
                (uri.getRawPath()!=null && !uri.getRawPath().isEmpty()) || uri.getPort()<1 || uri.getPort()>65535)
            throw new Exception("Use a private-network HTTPS pairing address.");
        String host = uri.getHost();
        if (host==null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw new Exception("Pairing needs a private IPv4 address.");
        String[] parts=host.split("\\."); int[] ip=new int[4];
        for(int i=0;i<4;i++){ip[i]=Integer.parseInt(parts[i]);if(ip[i]>255)throw new Exception("Invalid pairing address.");}
        if (!(ip[0]==10 || (ip[0]==172 && ip[1]>=16 && ip[1]<=31) || (ip[0]==192 && ip[1]==168)))
            throw new Exception("Pairing works on the same Wi-Fi or phone hotspot.");
        if (!value.equals("https://"+host+":"+uri.getPort())) throw new Exception("Invalid pairing address.");
        return value;
    }
    static JSONObject pairing(String code, long now) throws Exception {
        String prefix="voodoo-pair:v1:";
        if (code.length()>4096 || !code.startsWith(prefix)) throw new Exception("Paste a Voodoo desktop pairing code.");
        JSONObject pair=new JSONObject(utf8(bytes(code.substring(prefix.length()),-1)));
        SyncData.fields(pair,"v","url","fingerprint","pairId","key","expiresAt");
        if(SyncData.integer(pair,"v",1)!=1)throw new Exception("Unsupported pairing version.");
        endpoint(SyncData.text(pair,"url",100)); pairId(SyncData.text(pair,"pairId",32));
        if(!SyncData.text(pair,"fingerprint",64).matches("[0-9a-f]{64}"))throw new Exception("Invalid certificate fingerprint.");
        bytes(SyncData.text(pair,"key",44),32);
        long expires=SyncData.integer(pair,"expiresAt",1);
        if(expires<=now || expires>now+310000)throw new Exception("Pairing code expired, or the device clocks differ. Create a fresh code.");
        return pair;
    }
    static boolean fingerprint(byte[] cert, String expected) throws Exception {
        return MessageDigest.isEqual(hex(MessageDigest.getInstance("SHA-256").digest(cert)).getBytes(StandardCharsets.US_ASCII),expected.getBytes(StandardCharsets.US_ASCII));
    }
    static JSONObject response(JSONObject clear, String requestId, long lastReceived) throws Exception {
        SyncData.fields(clear,"v","requestId","seq","items");
        if(SyncData.integer(clear,"v",1)!=1 || !requestId.matches("[0-9a-f]{32}") || !requestId.equals(SyncData.text(clear,"requestId",32)))
            throw new Exception("The sync response did not match this request.");
        long seq=SyncData.integer(clear,"seq",1);
        if(seq<=lastReceived || seq>=SyncData.MAX_INTEGER)throw new Exception("Replayed or invalid sync response rejected.");
        clear.put("items",SyncData.merge(new org.json.JSONArray(),clear.getJSONArray("items")));
        return clear;
    }
}
