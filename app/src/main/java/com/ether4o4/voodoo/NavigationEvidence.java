package com.ether4o4.voodoo;
import java.net.URI;
import java.util.Locale;

/** A navigation command succeeds only for its new, complete document. Redirects fail closed. */
final class NavigationEvidence {
    final String url, previousDocument;
    final long beforeVersion;
    long startedVersion;
    boolean finished;
    NavigationEvidence(String url,long beforeVersion,String previousDocument){this.url=canonical(url);this.beforeVersion=beforeVersion;this.previousDocument=previousDocument;}
    private static String canonical(String value){
        if(value==null)return "";
        try{URI u=new URI(value);String scheme=u.getScheme(),host=u.getHost();
            if(scheme==null||host==null||u.getRawUserInfo()!=null)return value;
            scheme=scheme.toLowerCase(Locale.ROOT);host=host.toLowerCase(Locale.ROOT);
            int port=u.getPort();if((scheme.equals("https")&&port==443)||(scheme.equals("http")&&port==80))port=-1;
            String path=u.getRawPath();if(path==null||path.isEmpty())path="/";
            return scheme+"://"+host+(port<0?"":":"+port)+path+(u.getRawQuery()==null?"":"?"+u.getRawQuery())+(u.getRawFragment()==null?"":"#"+u.getRawFragment());
        }catch(Exception e){return value;}
    }
    boolean start(String callback,long version){
        if(startedVersion!=0||version!=beforeVersion+1||!url.equals(canonical(callback)))return false;
        startedVersion=version;return true;
    }
    boolean finish(String callback,String current,long version){
        if(startedVersion==0||version!=startedVersion||!url.equals(canonical(callback))||!url.equals(canonical(current)))return false;
        finished=true;return true;
    }
    boolean currentDocument(String document,long version){return finished&&version==startedVersion&&document!=null&&!document.isEmpty()&&!document.equals(previousDocument);}
    boolean matches(String current){return url.equals(canonical(current));}
    boolean complete(String document,long version,String current,boolean ready){return currentDocument(document,version)&&url.equals(canonical(current))&&ready;}
}
