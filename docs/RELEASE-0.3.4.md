# 0.3.4 — mobile userscript discovery

The previous build sent a full hostname to Greasy Fork's by-site catalog.
Live checks on 2026-10-31 returned zero records for `m.youtube.com`,
`www.youtube.com` and `m.facebook.com`, and 100 records each for `youtube.com`
and `facebook.com`. Login state and the page path are not used by this search.

Discovery now uses the registrable domain from OkHttp's bundled public suffix
list. This restores Magic Userscript+'s domain lookup behavior without the
upstream last-two-label shortcut that mishandles `co.uk` and private hosting.
The panel identifies the searched domain, mobile/desktop subdomains share a
catalog cache, and Refresh performs a new request even after an empty result.

This normalization affects discovery only. Script matches, excludes, grants,
installation review and execution permissions still use the original rules
and actual page URL. No profile or pin storage is modified.

Seven native regression tests cover Facebook/YouTube variants, multi-label
public suffixes, separate hosting tenants, IDNs/case/ports/trailing dots,
IP/local hosts, invalid inputs and unchanged execution scopes. Build and test
results are available in the associated GitHub Actions run. Live catalog
checks do not substitute for testing the panel on the user's phone.

The existing 100-result limit and supported userscript API subset still apply.
The JS button opens discovery on tap; this patch does not auto-install scripts.

The stable APK and checksum links remain in the README's rolling-download
section. Consult the published release notes for the delivered APK's package,
signing identity and update compatibility; development and original-release
profiles are separate and are not automatically migrated.
