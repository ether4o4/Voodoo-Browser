# Voodoo Browser 0.3.2

The dedicated Suno section is removed from the mobile app:

- No Suno Tools menu, writing-archive Suno tab, field-filling command or floating panel.
- No bundled auto-create loop, Suno-specific host bridge or automatic Suno injection.
- No Suno or completion-loop settings.

Normal website navigation, AI chat, scripts, skills, bookmarks and writing tools
remain available. Existing script permissions are unchanged. A user-installed
script may still target Suno like any other allowed website; the browser no longer
provides its own Suno integration.

This update does not delete saved data. The writing storage key and archive schema
are unchanged. Previously saved style prompts, exclusions, presets and generation
notes remain in full writing backups. Existing drafts, locks, versions and writing
rules remain editable. Legacy Suno settings are ignored rather than erased.

The Android version is 0.3.2, version code 9. Install a release signed with the
original release key over the installed app to preserve its profile. Development
builds use the separate `.dev` application ID and do not update the release app.

The DOM regression exercises opening the writing page with legacy saved data,
editing lyrics, navigating sections and exporting preserved data. It does not
replace an on-device update or Android WebView acceptance test.
