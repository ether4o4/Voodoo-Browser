package com.ether4o4.voodoo;

import okhttp3.HttpUrl;

/** Catalog lookup only: never use this broader host for script execution permissions. */
final class DiscoverySite {
    private DiscoverySite() {}

    static String catalogHost(String pageUrl) {
        HttpUrl parsed = pageUrl == null ? null : HttpUrl.parse(pageUrl);
        if (parsed == null) throw new IllegalArgumentException("Open an HTTP or HTTPS website first.");
        String host = parsed.host();
        // A trailing DNS dot should not create a separate catalog/cache key.
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        parsed = parsed.newBuilder().host(host).build();
        // OkHttp's bundled public suffix list handles co.uk and private suffixes
        // such as github.io; simply retaining the last two labels is incorrect.
        String domain = parsed.topPrivateDomain();
        return domain == null ? host : domain;
    }

    static String catalogUrl(String pageUrl) {
        return catalogUrlForHost(catalogHost(pageUrl));
    }

    static String catalogUrlForHost(String normalizedHost) {
        return new HttpUrl.Builder().scheme("https").host("api.greasyfork.org")
                .addPathSegment("scripts").addPathSegment("by-site")
                .addPathSegment(normalizedHost + ".json")
                .addQueryParameter("language", "all").build().toString();
    }
}
