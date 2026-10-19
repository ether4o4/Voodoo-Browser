package com.ether4o4.voodoo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureKeys {
    private static final String ALIAS="voodoo.provider.keys.v1";
    private final SharedPreferences prefs;
    SecureKeys(Context context) { prefs=context.getSharedPreferences("encrypted_keys",Context.MODE_PRIVATE); }
    private synchronized SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    void put(String id,String value) throws Exception {
        if(value.isEmpty()){ prefs.edit().remove(id).commit(); return; }
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
        c.updateAAD(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String encrypted=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString(id,encrypted).commit())throw new Exception("Could not save this key.");
    }
    boolean has(String id){return prefs.contains(id);}
    String get(String id) throws Exception {
        String stored=prefs.getString(id,""); if(stored.isEmpty())return "";
        String[] parts=stored.split(":",2); Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        c.updateAAD(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
}
