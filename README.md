# Travian Farm Assistant 2.0

Updated prototype with account-aware Farm List loading through a WebView session.

## Features
- Server + username + password fields.
- Login through Travian WebView session.
- Opens `/build.php?gid=16&tt=99` after login.
- Parses Farm Lists dynamically and renders checkboxes.
- Saves selected Farm List IDs locally.
- Interval scheduler with Last run / Next run status.
- Attempts to click the matching Send/Raid button for selected lists while the WebView is active.

## Important
The HTML structure of Travian Farm List can differ between servers/game versions. The parser and Send button selector are intentionally isolated in `MainActivity.kt` so they can be adjusted from an actual Farm List page if needed.

The scheduler in this version is foreground/activity based. Android may pause app execution when the app is fully backgrounded or killed. Reliable background automation requires a foreground service plus a persistent WebView/automation mechanism and should be implemented only if compatible with the game's rules.
