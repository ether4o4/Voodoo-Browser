package com.ether4o4.voodoo;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationEvidenceTest {
    @Test public void browserCanonicalizationRetainsExactDestination(){
        NavigationEvidence n=new NavigationEvidence("HTTPS://EXAMPLE.COM:443",1,"old");
        assertTrue(n.start("https://example.com/",2));
        assertTrue(n.finish("https://example.com/","https://example.com/",2));
        assertTrue(n.complete("new",2,"https://example.com/",true));
        assertFalse(n.complete("new",2,"https://example.com:444/",true));
        assertFalse(n.complete("new",2,"https://example.com/other",true));
        assertFalse(n.complete("new",2,"https://example.com/?changed",true));
    }
    @Test public void oldCallbacksCannotCompleteNewLoad(){
        NavigationEvidence n=new NavigationEvidence("https://example.com/new",4,"old-doc");
        assertFalse(n.finish(n.url,n.url,4));
        assertTrue(n.start(n.url,5));
        assertFalse(n.finish("https://example.com/old",n.url,5));
        assertTrue(n.finish(n.url,n.url,5));
        assertFalse(n.complete("old-doc",5,n.url,true));
        assertFalse(n.complete("new-doc",5,n.url,false));
        assertTrue(n.complete("new-doc",5,n.url,true));
        assertFalse(n.complete("new-doc",6,n.url,true));
    }
    @Test public void repeatedSameUrlStillRequiresFreshCompleteDocument(){
        NavigationEvidence n=new NavigationEvidence("https://example.com/",1,"before");
        assertTrue(n.start(n.url,2));
        assertTrue(n.finish(n.url,n.url,2)); // A late same-URL callback is not proof.
        assertFalse(n.complete("before",2,n.url,true));
        assertFalse(n.complete("after",2,n.url,false));
        assertTrue(n.complete("after",2,n.url,true));
        assertFalse(n.start(n.url,3)); // Second navigation interrupts the command.
    }
    @Test public void unrelatedNavigationAndRedirectsFailClosed(){
        NavigationEvidence n=new NavigationEvidence("https://example.com/a",7,"a");
        assertFalse(n.start("https://example.com/b",8));
        assertFalse(n.finish(n.url,n.url,8));
        assertFalse(n.complete("b",8,n.url,true));
    }
}
