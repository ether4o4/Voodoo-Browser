# Dependencies

Android System WebView is supplied and updated by the device. AndroidX WebKit is distributed under Apache License 2.0; Gradle's wrapper is distributed under Apache License 2.0. The Android SDK and JDK are build tools and are not included in this repository or APK. Node/jsdom are development-only test dependencies and are not included in the APK. Their licenses remain with those packages.

The built-in tools are project code. No third-party extension packages, Tampermonkey code, Mozilla Firefox APK, browser credentials, or provider API keys are bundled.

## Tor (private mode)

Private mode bundles `info.guardianproject:tor-android` 0.4.9.5 from the Guardian
Project: the Tor client (`libtor.so`) and its Android service. Tor is distributed under
the 3-clause BSD license by The Tor Project, Inc. The library also includes OpenSSL,
libevent, zlib and zstd, each under its own license. It depends on `jtorctl`, licensed
under the 3-clause BSD license. Tor is not affiliated with or endorsed by Voodoo.
