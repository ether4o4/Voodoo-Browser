# 0.3.9 — preview updates install in place

No browser changes from 0.3.8 (Chrome extensions and private mode included).

Preview APKs were signed with a temporary key created on each CI machine, so Android
refused to install a new preview over an older one. CI now signs development builds
with one persistent key stored in repository secrets (`VOODOO_DEV_KEYSTORE_BASE64`,
`VOODOO_DEV_KEYSTORE_PASSWORD`). The publish workflow refuses any APK whose
certificate does not match `tools/dev-signing-cert.sha256`
(`c3fa24da…fba27436`).

## Installation

Installing 0.3.9 requires uninstalling an existing `com.ether4o4.voodoo.dev` once,
because older previews have unrecoverable temporary keys. Later previews update in
place and keep their data. Export a backup first if you need the old dev app's data.
The original `com.ether4o4.voodoo` release is separate and unaffected.

The published preview APK is a development build, package `com.ether4o4.voodoo.dev`.
Keep a private backup of the development keystore; if it is lost, the next preview
needs one more uninstall.
