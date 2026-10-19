# Validation

## Checks performed

- Android release/debug compilation and APK packaging with JDK 17, Gradle 8.13, AGP 8.13.2, Android SDK 36.
- Android lint: no errors. Warnings include intentional synchronous preference commits, WebView JavaScript enablement, feature checks performed by callers, and pinned dependency versions.
- 38 Node/DOM tests: lyric locks and archive validation; completion/status transitions; stop/cap/timeout/duplicate protection; script host/path/exclusion matching; internal-page exclusions; GM helpers; reader sanitization; AI output becoming a disabled draft; stable script IDs; explicit page sharing; failed-request retry; edits made while an AI request is pending; pin backup validation, HTML-safe titles, workspace filtering/naming, placement, failed-save recovery, backup coverage; render scheduler rate limits, idle, visibility, resume, and disposal.
- 16 native checks for script URL scope, privileged internal-origin exclusion, and API endpoint restrictions.
- Release APK alignment/signature verification and package identity inspection.
- Owner confirmed that 0.2.1 installs after extracting the APK from the installer ZIP in the phone's file manager.

## Not yet verified

- Physical Android visual rendering (including WebGL/fabric appearance), lifecycle behavior, and Android Keystore persistence across a real update.
- Provider calls using real API keys. Request adapters follow provider documentation, but account/model availability and proprietary endpoint differences require an actual account test.
- Live Suno authentication, field matching, and credit-consuming generation.
- Full Tampermonkey compatibility, which is intentionally outside the implemented subset.

## Local checks

```sh
npm ci
npm run build:web
npm test
./gradlew assembleRelease lintRelease
```

The native check harness can be compiled against the release classes and Android platform jar, then run without a device. It does not use Android UI APIs.

For device testing: install the signed APK; visit an ordinary HTTPS site; test navigation/file picking; add a small domain-scoped script; verify disable/reload; save a provider key and make one chat request; explicitly share a page snapshot; verify AI changes stay in the editor; export and restore a backup. For an update, change `versionCode`, sign with the same key, and install with `adb install -r` before checking the profile remains.

The cloud preview browser could not open the local app files, so no visual-rendering claim is made. DOM fixtures exercise homepage behavior with a simulated native bridge; they do not substitute for a real Android WebView run.

For the workspace update: pin two sites to different colors; restart the app; check bar/filter persistence; rename a workspace; edit a pin; place it on the globe; open a workspace with the eight-tab limit reached; test portrait/landscape and reduced motion; export and restore pins; install the next same-key APK without uninstalling.

## 0.2.1 mobile pass

- Narrow-screen grid sizing and 48 px controls checked in source; stylesheet syntax validated.
- Deferred native tab restore reviewed for retained URLs, active index, tab reuse, and closing/reopening.
- Globe scheduler checked with a deterministic fake frame clock for idle, hidden/resume, cancellation, and bounded frame rate.
- No connected ADB device was available. No on-device performance, keyboard, display-scaling, heat, or battery benchmark has been performed.

## 0.2.2 launcher icon

- Launcher artwork reuses the exact transparent homepage cutout, with filtered scaling and no opaque background layer. Launcher-controlled icon framing remains device dependent.
- Clean release build and lint passed. The packaged launcher PNG matches all visible homepage pixels and the complete alpha channel; Android's PNG optimizer only changes invisible RGB values in fully transparent pixels.
- Version code increased to 4; application ID and signing certificate match 0.2.1. The installer ZIP contains the verified signed APK unchanged.
