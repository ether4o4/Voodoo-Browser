package com.ether4o4.voodoo;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.Test;

public class ExtensionPackageTest {
    static byte[] zip(Map<String,String> files)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(out)){for(Map.Entry<String,String> f:files.entrySet()){z.putNextEntry(new ZipEntry(f.getKey()));z.write(f.getValue().getBytes(StandardCharsets.UTF_8));z.closeEntry();}}
        return out.toByteArray();
    }
    static void varint(ByteArrayOutputStream o,long v){while((v&~0x7FL)!=0){o.write((int)((v&0x7F)|0x80));v>>>=7;}o.write((int)v);}
    static void field(ByteArrayOutputStream o,int number,byte[] value){varint(o,((long)number<<3)|2);varint(o,value.length);o.write(value,0,value.length);}
    static byte[] le(int n){return new byte[]{(byte)n,(byte)(n>>8),(byte)(n>>16),(byte)(n>>24)};}
    /** Builds a CRX3 the way Chrome's packer does; crxIdKey lets a test claim someone else's ID. */
    static byte[] crx3(byte[] archive,KeyPair signer,PublicKey crxIdKey)throws Exception{
        byte[] id=Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(crxIdKey.getEncoded()),16);
        ByteArrayOutputStream signedData=new ByteArrayOutputStream();field(signedData,1,id);byte[] sd=signedData.toByteArray();
        ByteArrayOutputStream message=new ByteArrayOutputStream();message.write("CRX3 SignedData\0".getBytes(StandardCharsets.US_ASCII));message.write(le(sd.length));message.write(sd);message.write(archive);
        Signature s=Signature.getInstance("SHA256withRSA");s.initSign(signer.getPrivate());s.update(message.toByteArray());
        ByteArrayOutputStream proof=new ByteArrayOutputStream();field(proof,1,signer.getPublic().getEncoded());field(proof,2,s.sign());
        ByteArrayOutputStream header=new ByteArrayOutputStream();field(header,2,proof.toByteArray());field(header,10000,sd);byte[] h=header.toByteArray();
        ByteArrayOutputStream crx=new ByteArrayOutputStream();crx.write("Cr24".getBytes(StandardCharsets.US_ASCII));crx.write(le(3));crx.write(le(h.length));crx.write(h);crx.write(archive);
        return crx.toByteArray();
    }
    static KeyPair rsa()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    static final String MANIFEST="{\n // comment Chrome tolerates\n \"manifest_version\": 3, \"name\": \"__MSG_name__\", \"version\": \"1.2\",\n \"default_locale\": \"en\", /* block */ \"permissions\": [\"storage\",\"tabs\"],\n \"host_permissions\": [\"https://*.example.com/*\"],\n \"web_accessible_resources\": [{\"resources\": [\"img/*.png\"], \"matches\": [\"<all_urls>\"]}]\n}";

    @Test public void verifiesCrx3AndDerivesChromeId()throws Exception{
        KeyPair key=rsa();byte[] archive=zip(Collections.singletonMap("manifest.json",MANIFEST));
        ExtensionPackage.Archive a=ExtensionPackage.open(crx3(archive,key,key.getPublic()));
        assertTrue(a.signed);assertEquals(ExtensionPackage.idFromKey(key.getPublic().getEncoded()),a.id);assertTrue(ExtensionPackage.validId(a.id));assertArrayEquals(archive,a.zip);
    }
    @Test public void rejectsTamperedCrx3()throws Exception{
        KeyPair key=rsa();byte[] crx=crx3(zip(Collections.singletonMap("manifest.json",MANIFEST)),key,key.getPublic());crx[crx.length-30]^=1;
        try{ExtensionPackage.open(crx);fail();}catch(SecurityException expected){}
    }
    @Test public void rejectsCrx3ClaimingAnotherExtensionsId()throws Exception{
        KeyPair attacker=rsa(),victim=rsa();
        try{ExtensionPackage.open(crx3(zip(Collections.singletonMap("manifest.json",MANIFEST)),attacker,victim.getPublic()));fail();}catch(SecurityException expected){}
    }
    @Test public void zipIsUnsignedAndJunkIsRejected()throws Exception{
        assertFalse(ExtensionPackage.open(zip(Collections.singletonMap("manifest.json",MANIFEST))).signed);
        try{ExtensionPackage.open("not an extension".getBytes(StandardCharsets.UTF_8));fail();}catch(IOException expected){}
    }
    @Test public void unpackBlocksTraversalAndFindsNestedManifest()throws Exception{
        File dir=Files.createTempDirectory("ext").toFile();
        try{ExtensionPackage.unpack(zip(Collections.singletonMap("../evil.js","x")),dir);fail();}catch(IOException expected){}
        File dir2=Files.createTempDirectory("ext").toFile();Map<String,String> files=new LinkedHashMap<>();files.put("my-ext/manifest.json",MANIFEST);files.put("my-ext/a.js","1");
        assertEquals("my-ext/",ExtensionPackage.unpack(zip(files),dir2));assertTrue(new File(dir2,"my-ext/a.js").isFile());
    }
    @Test public void manifestCommentsLocalesAndPermissions()throws Exception{
        JSONObject m=ExtensionPackage.manifest(MANIFEST);
        assertEquals(Arrays.asList("storage","tabs"),ExtensionPackage.apiPermissions(m));
        assertEquals(Collections.singletonList("https://*.example.com/*"),ExtensionPackage.hostPermissions(m));
        File dir=Files.createTempDirectory("ext").toFile();new File(dir,"_locales/en").mkdirs();
        Files.write(new File(dir,"_locales/en/messages.json").toPath(),"{\"name\":{\"message\":\"Fixture $who$ $1\",\"placeholders\":{\"who\":{\"content\":\"Ext\"}}}}".getBytes(StandardCharsets.UTF_8));
        JSONObject messages=ExtensionPackage.messages(dir,m,Locale.US);
        assertEquals("Fixture Ext ",ExtensionPackage.localize(m.getString("name"),messages));
        assertEquals("Fixture Ext one",ExtensionPackage.message(messages,"NAME",Collections.singletonList("one")));
        try{ExtensionPackage.manifest("{\"manifest_version\":1,\"name\":\"x\",\"version\":\"1\"}");fail();}catch(Exception expected){}
        assertTrue(ExtensionPackage.webAccessible(ExtensionPackage.webAccessible(m),"/img/logo.png"));
        assertFalse(ExtensionPackage.webAccessible(ExtensionPackage.webAccessible(m),"/background.js"));
    }
    @Test public void unpackedIdsAreStableAndHonorKey()throws Exception{
        JSONObject a=new JSONObject("{\"name\":\"A\"}");assertEquals(ExtensionPackage.unpackedId(a),ExtensionPackage.unpackedId(new JSONObject("{\"name\":\"A\"}")));
        KeyPair key=rsa();JSONObject withKey=new JSONObject().put("name","A").put("key",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        assertEquals(ExtensionPackage.idFromKey(key.getPublic().getEncoded()),ExtensionPackage.unpackedId(withKey));
    }
    @Test public void matchPatternsFollowChromeRules()throws Exception{
        List<String> p=Collections.singletonList("*://*.example.com/*");
        assertTrue(ExtensionPackage.matches(p,"https://example.com/"));assertTrue(ExtensionPackage.matches(p,"http://a.b.EXAMPLE.com/x?y#z"));
        assertFalse(ExtensionPackage.matches(p,"https://example.com.evil/"));assertFalse(ExtensionPackage.matches(p,"ftp://example.com/"));
        assertTrue(ExtensionPackage.matches(Collections.singletonList("https://x.com/a*b"),"https://x.com/a/zz/b"));
        assertFalse(ExtensionPackage.matches(Collections.singletonList("https://x.com/a*b"),"https://x.com/ab/c"));
        assertTrue(ExtensionPackage.matches(Collections.singletonList("<all_urls>"),"https://anything.test:8443/"));
        String js=ExtensionPackage.matchRegex("https://*.example.com/path-[x]*");assertFalse(js.contains("\\Q"));
        assertEquals("abcdefghijklmnopabcdefghijklmnop",ExtensionPackage.storeId("https://chromewebstore.google.com/detail/name/abcdefghijklmnopabcdefghijklmnop?hl=en"));
        assertNull(ExtensionPackage.storeId("https://example.com/"));
    }
    @Test public void declarativeNetRequestBlocksAndAllows()throws Exception{
        ExtensionRules r=new ExtensionRules();
        r.addAll(new JSONArray("[{\"id\":1,\"action\":{\"type\":\"block\"},\"condition\":{\"urlFilter\":\"||ads.example^\"}},"
            +"{\"id\":2,\"priority\":2,\"action\":{\"type\":\"allow\"},\"condition\":{\"urlFilter\":\"||ads.example/ok.js\"}},"
            +"{\"id\":3,\"action\":{\"type\":\"block\"},\"condition\":{\"requestDomains\":[\"tracker.test\"],\"resourceTypes\":[\"script\"],\"initiatorDomains\":[\"news.test\"]}},"
            +"{\"id\":4,\"action\":{\"type\":\"redirect\",\"redirect\":{\"url\":\"https://x\"}},\"condition\":{\"urlFilter\":\"x\"}},"
            +"{\"id\":5,\"action\":{\"type\":\"block\"},\"condition\":{\"regexFilter\":\"/banner[0-9]+\\\\.gif$\"}}]"));
        assertEquals(4,r.count);assertEquals(1,r.unsupported);
        assertEquals("block",r.decide(new ExtensionRules.Request("https://cdn.ads.example/x.js","site.test","script","GET")));
        assertNull(r.decide(new ExtensionRules.Request("https://ads.examples.com/x.js","site.test","script","GET")));
        assertEquals("allow",r.decide(new ExtensionRules.Request("https://ads.example/ok.js","site.test","script","GET")));
        assertNull("main_frame is excluded unless listed",r.decide(new ExtensionRules.Request("https://ads.example/","",ExtensionRules.type(true,"https://ads.example/",null),"GET")));
        assertEquals("block",r.decide(new ExtensionRules.Request("https://a.tracker.test/t.js","www.news.test","script","GET")));
        assertNull(r.decide(new ExtensionRules.Request("https://a.tracker.test/t.js","other.test","script","GET")));
        assertNull(r.decide(new ExtensionRules.Request("https://a.tracker.test/t.png","www.news.test","image","GET")));
        assertEquals("block",r.decide(new ExtensionRules.Request("https://img.test/banner12.gif","site.test","image","GET")));
        assertEquals("stylesheet",ExtensionRules.type(false,"https://a.test/x.css?v=1",null));
        assertEquals("image",ExtensionRules.type(false,"https://a.test/x",(String)"image/avif,image/webp"));
    }
}
