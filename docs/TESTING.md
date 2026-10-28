# Validation

## Current 0.3.2 removal checks

Run the complete Node/DOM suite and Android unit tests, build and lint. The writing
regression boots the current HTML with legacy saved data, edits a draft, switches
sections and exports a backup while retaining old prompts, presets and attempts.
Verify Tools and Settings have no dedicated Suno section, and visiting Suno as an
ordinary website does not inject the old panel or generation loop. On a physical
device, install a same-key update and check saved writing, provider keys and normal
browser navigation. These device checks remain required.

## Earlier release checks (historical)

- Android release/debug compilation and APK packaging with JDK 17, Gradle 8.13, AGP 8.13.2, Android SDK 36.
- Android lint: no errors. Warnings include intentional synchronous preference commits, WebView JavaScript enablement, feature checks performed by callers, and pinned dependency versions.
- 44 Node/DOM tests: lyric locks and archive validation; completion/status transitions; stop/cap/timeout/duplicate protection; script host/path/exclusion matching; internal-page exclusions; GM helpers; reader sanitization; AI output becoming a disabled draft; stable script IDs; explicit page sharing; failed-request retry; edits made while an AI request is pending; pin backup validation, HTML-safe titles, workspace filtering/naming, placement, failed-save recovery, backup coverage; render scheduler rate limits, idle, visibility, resume, and disposal; initial Settings routing and section reuse without losing unsaved drafts; recent-site empty/resume states, safe titles and URLs, host deduplication, local favicons, current-tab navigation, and failed-refresh recovery.
- 39 native checks for script URL scope, privileged internal-origin exclusion, API endpoint restrictions, recent-site limits and deduplication, and successful versus failed main-frame visits.
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

## 0.2.3 interface and launcher compatibility

- Smaller homepage artwork, clearer headings and saved-site colors, simplified copy, collapsible help, consistent controls, and preserved mobile touch sizes. HTML IDs remain intact; CSS/DOM parsing passed. Selected accent contrast checks were at least 4.67:1 on their checked surfaces.
- Native address field and buttons use rounded surfaces; the empty pin row is hidden. The Tools menu groups all 18 existing actions into seven top-level entries.
- Settings opens using a section URL instead of evaluating JavaScript before Studio has loaded. A regression covers first-load Settings, route changes, invalid routes, and retained unsaved script/chat text.
- Clean release build, Android lint, and all 39 Node/DOM tests passed. The signed APK has version code 5 and the same application ID and signing certificate as 0.2.2. ZIP CRC, alignment, and installer contents were verified.
- The packaged icon is the transparent homepage PNG, referenced directly. Its visible pixels and complete alpha channel match the homepage image. Samsung frame opt-out metadata is present and true in the compiled manifest.
- No physical-device UI run or launcher test was available for this release. The owner's 0.2.2 screenshot showed a white launcher frame; whether the Samsung compatibility hint removes it on their current launcher remains unverified.

## 0.2.4 shortcuts and recent sites

- The six bottom controls remain 50 dp tall. Their order is Back, Forward, Shortcuts, Home, Tools, Tabs; every control uses an artwork accent color. Shortcuts preserves Suno, AI & Scripts, Workbench, and GitHub in one menu immediately left of Home.
- The homepage shows up to six recently visited HTTPS hosts in two columns. Twenty-four unique hosts persist locally. Internal pages and failed main-frame loads are excluded; background completion does not reorder the list until that tab is viewed. A blocked third-party SSL resource does not disqualify a successful main page.
- Recent cards use text-only titles, captured PNG favicons or initials, current-tab navigation, and explicit loading/empty/retry states. No remote favicon service is used. History is excluded from workspace exports.
- All 44 Node/DOM tests and 39 native checks passed. Native WebView callback behavior and final appearance still need an on-device run.
- Clean release build and Android lint passed. Version code 6, application ID, matching 0.2.3 signing certificate, APK alignment, transparent launcher artwork, ZIP CRC, and installer contents were verified.
