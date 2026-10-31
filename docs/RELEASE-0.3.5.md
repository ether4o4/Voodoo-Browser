# 0.3.5 — script activation and stability

## Controls

Selecting a discovered or saved script opens three exclusive choices:

- Save to scripts: store it without automatic execution.
- Automatic activation: store it and run it on matching document loads.
- Manual activation: store it disabled for automatic execution. Tap Activate
  in the saved list or editor for each run.

Selecting Manual or pressing Save never runs a script. Activate retains the
existing permission review and deliberately reloads the target website once.
Automatic execution starts on the next matching document, so applying it to
an already open website requires a refresh. Script options have a Back control;
the discovery panel has Saved scripts and Back to website; the editor has direct
website and Magic scripts shortcuts. Existing automatic settings remain valid.

## Targeted corrections

- The offscreen syntax-validation WebView previously used the default renderer
  termination behavior. It now handles termination, cancels pending validation
  on Activity destruction, and ignores late callbacks. A renderer failure can
  otherwise terminate its host process; this is a plausible failure path, not
  a diagnosis of the user's specific phone crash without its exit log.
- A dead page's script and browser-bridge registrations are discarded without
  calling renderer-dependent removal APIs. Page replacement is deferred, late
  page callbacks are ignored, and a failed URL is not automatically reloaded.
- Inactive script edits and name/order changes no longer replace every tab's
  script registrations. Changes to automatic source or permissions still revoke
  and replace registrations; editing/removing a saved manual script revokes its
  current temporary execution.
- Route checks stop while a document is hidden. Native revocation immediately
  clears route work, body observers, commands and listeners. Waiting for body
  creation uses an observer instead of a 10 ms polling loop.
- Discovery retains at most eight domain catalogs and creates 20 result rows at
  a time. Obsolete dialog results are ignored and their network requests can be
  cancelled. The existing 100-result ceiling remains.
- The launcher and runtime source are reused. Unchanged dependency content is
  reused from its previously hash-recorded cache. Script status reads the store
  once, and failed editor saves retain the draft without a phantom saved item.
- Saves made in native Magic merge into the editor without dropping either
  screen's installed scripts or the open draft. Concurrent changes to the same
  saved source are rejected with the local draft preserved.

The app's browser engine, grant boundaries, source-review flow, saved profile,
workspace pins and API connections remain the existing implementation.

## Validation and delivery

Local browser/DOM, relay and mocked-download checks cover activation choices,
saved-disabled manual scripts, explicit execution, draft retention, route
visibility and revocation. Native regression tests cover activation migration,
selective registration refresh and manual-source revocation. The exact CI commit,
package versions, native test totals, APK hashes and signing evidence are in the
build artifact. CI includes the public apksigner tool for local same-key signing;
private signing material is not in the repository or workflow artifact.

Version code is 12. The signed release package is `com.ether4o4.voodoo`; the CI
debug package is `com.ether4o4.voodoo.dev` and is a separate profile. Verify the
published APK's certificate against the installed 0.3.4 before delivering it.
Install a verified same-key release over 0.3.4 to keep its data. Do not uninstall
the installed app to resolve an update conflict.

No physical-phone crash reproduction, battery benchmark or complete isolated
WebView lifecycle test has been performed in this workspace. A post-save refresh
is intentional only for an explicit activation; a browser process crash is not.

Android lifecycle reference:
https://developer.android.com/develop/ui/views/layout/webapps/handle-termination
