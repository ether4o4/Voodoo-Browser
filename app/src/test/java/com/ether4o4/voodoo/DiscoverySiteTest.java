package com.ether4o4.voodoo;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoverySiteTest {
    @Test public void facebookAndYoutubeSubdomainsUseTheirCatalogs() {
        for (String domain : new String[]{"facebook.com", "youtube.com"}) {
            for (String prefix : new String[]{"", "m.", "www.", "music."}) {
                String url = "https://" + prefix + domain + "/login?next=feed#section";
                assertEquals(domain, DiscoverySite.catalogHost(url));
                assertEquals("https://api.greasyfork.org/scripts/by-site/" + domain + ".json?language=all",
                        DiscoverySite.catalogUrl(url));
            }
        }
    }

    @Test public void publicSuffixesDoNotCollapseToCoUk() {
        assertEquals("bbc.co.uk", DiscoverySite.catalogHost("https://www.bbc.co.uk/news"));
        assertEquals("example.com.au", DiscoverySite.catalogHost("https://m.example.com.au/"));
    }

    @Test public void privateHostingTenantsRemainSeparate() {
        assertEquals("alice.github.io", DiscoverySite.catalogHost("https://m.alice.github.io/"));
        assertEquals("bob.github.io", DiscoverySite.catalogHost("https://bob.github.io/"));
    }

    @Test public void canonicalizesCasePortsAndDnsDot() {
        assertEquals("youtube.com", DiscoverySite.catalogHost("https://WWW.YouTube.COM.:8443/"));
        assertEquals("xn--bcher-kva.de", DiscoverySite.catalogHost("https://www.bücher.de/"));
        assertEquals("example.org", DiscoverySite.catalogHost("http://m.example.org/"));
    }

    @Test public void ipAndLocalHostsAreNotTruncated() {
        assertEquals("192.168.1.1", DiscoverySite.catalogHost("http://192.168.1.1/"));
        assertEquals("::1", DiscoverySite.catalogHost("http://[::1]/"));
        assertEquals("localhost", DiscoverySite.catalogHost("http://localhost/"));
    }

    @Test public void rejectsNonWebsiteUrls() {
        for (String url : new String[]{null, "", "file:///tmp/page", "javascript:alert(1)", "not a URL"}) {
            try { DiscoverySite.catalogHost(url); fail("Expected invalid URL to be rejected"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void catalogLookupDoesNotBroadenExecutionRules() throws Exception {
        String source = "// ==UserScript==\n// @name Test\n// @match https://www.youtube.com/watch*\n// ==/UserScript==\n";
        ScriptRules rules = new ScriptRules(source);
        assertEquals("youtube.com", DiscoverySite.catalogHost("https://m.youtube.com/"));
        assertTrue(rules.allows("https://www.youtube.com/watch?v=1"));
        assertFalse(rules.allows("https://m.youtube.com/watch?v=1"));
        assertFalse(rules.allows("https://www.youtube.com/login"));
    }
}
