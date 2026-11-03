package com.ether4o4.voodoo;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/**
 * Chrome extension packages: CRX2/CRX3 parsing and signature verification, bounded
 * ZIP extraction, manifest parsing, extension IDs, locales and match patterns.
 * Pure Java so every check runs in JVM unit tests.
 */
final class ExtensionPackage {
    static final int MAX_FILES=12000;
    static final long MAX_TOTAL=300L*1024*1024,MAX_FILE=80L*1024*1024;

    /** A CRX or ZIP split into its verified ZIP archive and Chrome extension ID. */
    static final class Archive {
        final byte[] zip;final String id;final boolean signed;
        Archive(byte[] zip,String id,boolean signed){this.zip=zip;this.id=id;this.signed=signed;}
    }

    static String idFromHash(byte[] hash){StringBuilder s=new StringBuilder();for(int i=0;i<16;i++){s.append((char)('a'+((hash[i]>>4)&15)));s.append((char)('a'+(hash[i]&15)));}return s.toString();}
    static String idFromKey(byte[] publicKey)throws Exception{return idFromHash(MessageDigest.getInstance("SHA-256").digest(publicKey));}
    static boolean validId(String id){return id!=null&&id.matches("[a-p]{32}");}

    /** Reads a .crx (v2/v3, signatures verified) or a plain .zip. */
    static Archive open(byte[] data)throws Exception{
        if(data.length<8)throw new IOException("File is too small to be an extension.");
        if(data[0]=='C'&&data[1]=='r'&&data[2]=='2'&&data[3]=='4'){
            int version=le32(data,4);
            if(version==3)return crx3(data);
            if(version==2)return crx2(data);
            throw new IOException("Unsupported CRX version "+version+".");
        }
        if(data[0]=='P'&&data[1]=='K')return new Archive(data,null,false);
        throw new IOException("Not a Chrome extension (.crx) or .zip file.");
    }
    static int le32(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}
    static Archive crx2(byte[] data)throws Exception{
        int keyLength=le32(data,8),sigLength=le32(data,12);long start=16L+keyLength+sigLength;
        if(keyLength<=0||sigLength<=0||start>data.length)throw new IOException("Corrupt CRX2 header.");
        byte[] key=Arrays.copyOfRange(data,16,16+keyLength),sig=Arrays.copyOfRange(data,16+keyLength,(int)start),zip=Arrays.copyOfRange(data,(int)start,data.length);
        Signature verifier=Signature.getInstance("SHA1withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(key)));verifier.update(zip);
        if(!verifier.verify(sig))throw new SecurityException("CRX2 signature is invalid.");
        return new Archive(zip,idFromKey(key),true);
    }
    /** Minimal protobuf reader for CrxFileHeader. */
    static Map<Integer,List<byte[]>> proto(byte[] b)throws IOException{
        Map<Integer,List<byte[]>> out=new HashMap<>();int i=0;
        while(i<b.length){
            long tag=0;int shift=0;while(true){if(i>=b.length)throw new IOException("Corrupt CRX header.");int x=b[i++]&255;tag|=(long)(x&127)<<shift;if(x<128)break;shift+=7;if(shift>63)throw new IOException("Corrupt CRX header.");}
            int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(wire==0){while(i<b.length&&(b[i++]&128)!=0){}}
            else if(wire==1)i+=8;else if(wire==5)i+=4;
            else if(wire==2){long len=0;shift=0;while(true){if(i>=b.length)throw new IOException("Corrupt CRX header.");int x=b[i++]&255;len|=(long)(x&127)<<shift;if(x<128)break;shift+=7;}if(len<0||i+len>b.length)throw new IOException("Corrupt CRX header.");out.computeIfAbsent(field,k->new ArrayList<>()).add(Arrays.copyOfRange(b,i,(int)(i+len)));i+=len;}
            else throw new IOException("Corrupt CRX header.");
        }
        return out;
    }
    static Archive crx3(byte[] data)throws Exception{
        int headerLength=le32(data,8);long start=12L+headerLength;
        if(headerLength<0||start>data.length)throw new IOException("Corrupt CRX3 header.");
        Map<Integer,List<byte[]>> header=proto(Arrays.copyOfRange(data,12,(int)start));
        byte[] zip=Arrays.copyOfRange(data,(int)start,data.length);
        List<byte[]> signed=header.get(10000);if(signed==null||signed.size()!=1)throw new SecurityException("CRX3 file has no signed header.");
        List<byte[]> ids=proto(signed.get(0)).get(1);if(ids==null||ids.isEmpty()||ids.get(0).length!=16)throw new SecurityException("CRX3 file has no extension ID.");
        String id=idFromHash(ids.get(0));
        ByteArrayOutputStream message=new ByteArrayOutputStream();message.write("CRX3 SignedData\0".getBytes(StandardCharsets.US_ASCII));int n=signed.get(0).length;message.write(new byte[]{(byte)n,(byte)(n>>8),(byte)(n>>16),(byte)(n>>24)});message.write(signed.get(0));message.write(zip);byte[] signedBytes=message.toByteArray();
        boolean idProven=false;int proofs=0;
        for(int field:new int[]{2,3}){List<byte[]> list=header.get(field);if(list==null)continue;for(byte[] proofBytes:list){
            Map<Integer,List<byte[]>> proof=proto(proofBytes);if(!proof.containsKey(1)||!proof.containsKey(2))throw new SecurityException("CRX3 proof is incomplete.");
            byte[] key=proof.get(1).get(0),sig=proof.get(2).get(0);
            Signature verifier=Signature.getInstance(field==2?"SHA256withRSA":"SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance(field==2?"RSA":"EC").generatePublic(new X509EncodedKeySpec(key)));verifier.update(signedBytes);
            if(!verifier.verify(sig))throw new SecurityException("CRX3 signature is invalid.");
            proofs++;if(idFromKey(key).equals(id))idProven=true;
        }}
        if(proofs==0||!idProven)throw new SecurityException("CRX3 extension ID is not proven by a signature.");
        return new Archive(zip,id,true);
    }

    static String safeName(String name)throws IOException{
        String n=name.replace('\\','/');while(n.startsWith("./"))n=n.substring(2);
        if(n.startsWith("/")||n.contains("\0")||n.matches("^[A-Za-z]:.*"))throw new IOException("Unsafe path in archive: "+name);
        for(String part:n.split("/"))if(part.equals(".."))throw new IOException("Unsafe path in archive: "+name);
        return n;
    }
    /** Extracts to dir with path, count and size limits. Returns the manifest's folder prefix ("" or "sub/"). */
    static String unpack(byte[] zip,File dir)throws Exception{
        int files=0;long total=0;Set<String> names=new HashSet<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip))){
            ZipEntry e;byte[] buffer=new byte[16384];
            while((e=in.getNextEntry())!=null){
                String name=safeName(e.getName());if(e.isDirectory()||name.isEmpty()||name.endsWith("/"))continue;
                if(++files>MAX_FILES)throw new IOException("Extension has too many files.");
                File out=new File(dir,name);if(!out.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator))throw new IOException("Unsafe path in archive: "+name);
                File parent=out.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create "+parent);
                long size=0;try(OutputStream o=new FileOutputStream(out)){int r;while((r=in.read(buffer))>0){size+=r;total+=r;if(size>MAX_FILE||total>MAX_TOTAL)throw new IOException("Extension is too large.");o.write(buffer,0,r);}}
                names.add(name);
            }
        }
        if(names.contains("manifest.json"))return "";
        String prefix=null;for(String n:names)if(n.endsWith("/manifest.json")&&n.indexOf('/')==n.length()-"/manifest.json".length()){if(prefix!=null)throw new IOException("Archive contains several extensions.");prefix=n.substring(0,n.length()-"manifest.json".length());}
        if(prefix==null)throw new IOException("manifest.json is missing.");
        return prefix;
    }

    /** JSON with Chrome-tolerated comments and BOM. */
    static String stripComments(String s){
        StringBuilder out=new StringBuilder(s.length());boolean str=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);
            if(str){out.append(c);if(c=='\\'&&i+1<s.length())out.append(s.charAt(++i));else if(c=='"')str=false;continue;}
            if(c=='"'){str=true;out.append(c);continue;}
            if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='/'){while(i<s.length()&&s.charAt(i)!='\n')i++;out.append('\n');continue;}
            if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='*'){int end=s.indexOf("*/",i+2);i=end<0?s.length():end+1;out.append(' ');continue;}
            out.append(c);
        }
        return out.toString();
    }
    static JSONObject json(String text)throws JSONException{if(text.startsWith("\uFEFF"))text=text.substring(1);return new JSONObject(stripComments(text));}
    static JSONObject manifest(String text)throws Exception{
        JSONObject m=json(text);int version=m.optInt("manifest_version",0);
        if(version!=2&&version!=3)throw new Exception("Only manifest_version 2 and 3 extensions are supported.");
        if(m.optString("name").isEmpty()||m.optString("version").isEmpty())throw new Exception("Manifest needs a name and version.");
        if(m.has("app"))throw new Exception("Chrome apps are not extensions and are not supported.");
        return m;
    }
    /** ID for unsigned ZIPs: manifest key (as Chrome does for unpacked extensions with key), else name-based. */
    static String unpackedId(JSONObject manifest)throws Exception{
        String key=manifest.optString("key");
        if(!key.isEmpty())try{return idFromKey(Base64.getMimeDecoder().decode(key));}catch(IllegalArgumentException ignored){}
        return idFromHash(MessageDigest.getInstance("SHA-256").digest(("voodoo-unpacked:"+manifest.optString("name")).getBytes(StandardCharsets.UTF_8)));
    }

    /** chrome.i18n message table, best locale first with default_locale fallback. */
    static JSONObject messages(File root,JSONObject manifest,Locale locale){
        JSONObject out=new JSONObject();String def=manifest.optString("default_locale","");
        List<String> chain=new ArrayList<>();if(!def.isEmpty())chain.add(def);
        String lang=locale.getLanguage(),country=locale.getCountry();chain.add(lang);if(!country.isEmpty())chain.add(lang+"_"+country);
        for(String name:chain){File f=new File(root,"_locales/"+name+"/messages.json");if(!f.isFile())continue;
            try{JSONObject m=json(read(f));for(Iterator<String> it=m.keys();it.hasNext();){String k=it.next();out.put(k.toLowerCase(Locale.ROOT),m.get(k));}}catch(Exception ignored){}}
        return out;
    }
    static String message(JSONObject messages,String name,List<String> subs){
        JSONObject m=messages.optJSONObject(name.toLowerCase(Locale.ROOT));if(m==null)return "";String text=m.optString("message");
        JSONObject ph=m.optJSONObject("placeholders");
        if(ph!=null)for(Iterator<String> it=ph.keys();it.hasNext();){String k=it.next();String content=ph.optJSONObject(k)==null?"":ph.optJSONObject(k).optString("content");text=text.replaceAll("(?i)\\$"+java.util.regex.Pattern.quote(k)+"\\$",java.util.regex.Matcher.quoteReplacement(content));}
        for(int i=9;i>=1;i--)text=text.replace("$"+i,subs!=null&&subs.size()>=i?subs.get(i-1):"");
        return text.replace("$$","$");
    }
    static String localize(String value,JSONObject messages){
        if(value==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("__MSG_(\\w+)__").matcher(value);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(message(messages,m.group(1),null)));m.appendTail(out);return out.toString();
    }
    static String read(File f)throws IOException{return new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}

    // ----- Match patterns. Output regexes use only backslash escapes valid in both Java and JavaScript. -----
    static String escape(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray()){if("\\^$.|?*+()[]{}/-".indexOf(c)>=0)b.append('\\');b.append(c);}return b.toString();}
    /** Chrome match pattern → anchored regex over scheme://host[:port]/path?query. */
    static String matchRegex(String pattern)throws Exception{
        if(pattern.equals("<all_urls>"))return "^(?:https?|wss?|ftp|file):\\/\\/.*$";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(\\*|https?|wss?|ftp|file|urn):\\/\\/([^/]*)(\\/.*)$").matcher(pattern);
        if(!m.matches())throw new Exception("Invalid match pattern: "+pattern);
        String scheme=m.group(1),host=m.group(2).toLowerCase(Locale.ROOT),path=m.group(3);
        if(host.contains(":"))host=host.substring(0,host.indexOf(':'));
        String s=scheme.equals("*")?"https?":escape(scheme);
        String h;if(host.equals("*"))h="[^\\/]*";else if(host.startsWith("*."))h="(?:[^\\/]*\\.)?"+escape(host.substring(2));else if(host.contains("*"))throw new Exception("Invalid match pattern host: "+pattern);else h=escape(host);
        String pathRegex=path.equals("/*")?"\\/.*":rebuild(path);
        return "^"+s+":\\/\\/"+h+"(?::\\d+)?"+pathRegex+"$";
    }
    private static String rebuild(String path){String[] pieces=path.split("\\*",-1);StringBuilder p=new StringBuilder();for(int i=0;i<pieces.length;i++){if(i>0)p.append(".*");p.append(escape(pieces[i]));}return p.toString();}
    static String globRegex(String glob){StringBuilder b=new StringBuilder("^");for(char c:glob.toCharArray()){if(c=='*')b.append(".*");else if(c=='?')b.append('.');else b.append(escape(String.valueOf(c)));}return b.append('$').toString();}
    static boolean matches(Collection<String> patterns,String url){
        for(String p:patterns)try{if(java.util.regex.Pattern.compile(matchRegex(p)).matcher(normalized(url)).matches())return true;}catch(Exception ignored){}
        return false;
    }
    /** Lower-case host, no fragment or credentials, as Chrome matches. */
    static String normalized(String url){
        try{java.net.URI u=new java.net.URI(url);if(u.getHost()==null)return url;String path=u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath();
            return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getHost().toLowerCase(Locale.ROOT)+(u.getPort()<0?"":":"+u.getPort())+path+(u.getRawQuery()==null?"":"?"+u.getRawQuery());}catch(Exception e){return url;}
    }
    /** Host permissions: MV3 host_permissions, MV2 URL-shaped permissions, optional ones and content script matches. */
    static List<String> hostPermissions(JSONObject m){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String key:new String[]{"host_permissions","optional_host_permissions","permissions","optional_permissions"}){JSONArray a=m.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++){String p=a.optString(i);if(p.equals("<all_urls>")||p.contains("://"))out.add(p);}}
        JSONArray cs=m.optJSONArray("content_scripts");if(cs!=null)for(int i=0;i<cs.length();i++){JSONArray mm=cs.optJSONObject(i)==null?null:cs.optJSONObject(i).optJSONArray("matches");if(mm!=null)for(int j=0;j<mm.length();j++)out.add(mm.optString(j));}
        return new ArrayList<>(out);
    }
    static List<String> apiPermissions(JSONObject m){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String key:new String[]{"permissions","optional_permissions"}){JSONArray a=m.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++){String p=a.optString(i);if(!p.equals("<all_urls>")&&!p.contains("://"))out.add(p);}}
        return new ArrayList<>(out);
    }
    /** Normalized list of web_accessible_resources globs (MV2 strings or MV3 objects). */
    static List<String> webAccessible(JSONObject m){
        List<String> out=new ArrayList<>();JSONArray a=m.optJSONArray("web_accessible_resources");if(a==null)return out;
        for(int i=0;i<a.length();i++){Object o=a.opt(i);if(o instanceof String)out.add((String)o);else if(o instanceof JSONObject){JSONArray r=((JSONObject)o).optJSONArray("resources");if(r!=null)for(int j=0;j<r.length();j++)out.add(r.optString(j));}}
        return out;
    }
    static boolean webAccessible(List<String> globs,String path){
        String p=path.startsWith("/")?path.substring(1):path;
        for(String g:globs){String gg=g.startsWith("/")?g.substring(1):g;if(gg.equals("*")||java.util.regex.Pattern.compile(globRegex(gg)).matcher(p).matches())return true;}
        return false;
    }
    static String mime(String path){
        String p=path.toLowerCase(Locale.ROOT);int dot=p.lastIndexOf('.');String ext=dot<0?"":p.substring(dot+1);
        switch(ext){
            case "html":case "htm":return "text/html";case "js":case "mjs":return "text/javascript";case "css":return "text/css";case "json":return "application/json";
            case "png":return "image/png";case "jpg":case "jpeg":return "image/jpeg";case "gif":return "image/gif";case "svg":return "image/svg+xml";case "webp":return "image/webp";case "ico":return "image/x-icon";
            case "woff":return "font/woff";case "woff2":return "font/woff2";case "ttf":return "font/ttf";case "otf":return "font/otf";case "wasm":return "application/wasm";
            case "txt":return "text/plain";case "xml":return "application/xml";case "mp3":return "audio/mpeg";case "ogg":return "audio/ogg";case "wav":return "audio/wav";case "mp4":return "video/mp4";case "webm":return "video/webm";
            default:return "application/octet-stream";
        }
    }
    /** Web Store detail URL or bare ID → extension ID, or null. */
    static String storeId(String text){
        if(text==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|[/=])([a-p]{32})(?:$|[/?#&])").matcher(text.trim());
        return m.find()?m.group(1):null;
    }
}
