package com.ether4o4.voodoo;

/** A navigation command succeeds only for its new, complete document. Redirects fail closed. */
final class NavigationEvidence {
    final String url, previousDocument;
    final long beforeVersion;
    long startedVersion;
    boolean finished;
    NavigationEvidence(String url,long beforeVersion,String previousDocument){this.url=url;this.beforeVersion=beforeVersion;this.previousDocument=previousDocument;}
    boolean start(String callback,long version){
        if(startedVersion!=0||version!=beforeVersion+1||!url.equals(callback))return false;
        startedVersion=version;return true;
    }
    boolean finish(String callback,String current,long version){
        if(startedVersion==0||version!=startedVersion||!url.equals(callback)||!url.equals(current))return false;
        finished=true;return true;
    }
    boolean currentDocument(String document,long version){return finished&&version==startedVersion&&document!=null&&!document.isEmpty()&&!document.equals(previousDocument);}
    boolean complete(String document,long version,String current,boolean ready){return currentDocument(document,version)&&url.equals(current)&&ready;}
}
