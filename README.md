# Odysseus — Android WebView wrapper

A minimal, full-screen Android app (Kotlin) that wraps the locally hosted
**Odysseus** service in a WebView, giving a clean, browser-like chat/agent
experience without the browser chrome.

- **Package:** `no.ambulanse.odysseus`
- **Default URL:** `http://192.168.68.84:7000` (editable in Settings)
- **Min SDK:** 24 (Android 7.0) · **Target SDK:** 34 (Android 14)

## Features

| Feature | Where |
| --- | --- |
| Full WebView (JS, DOM storage, mixed content, custom User-Agent) | `MainActivity.kt` |
| **Agent Mode + Shell Access** toggles (URL flags) | `MainActivity.kt`, `PrefsHelper.buildUrl()` |
| **Web-terminal support** (key bar, keyboard, clipboard, WebSockets) | `MainActivity.kt`, `TerminalKeys.kt` |
| Pull-to-refresh (terminal-safe: only at top, toggleable) | `SwipeRefreshLayout` in `activity_main.xml` |
| Back-button navigation (WebView history, then exit) | `MainActivity.setupBackNavigation()` |
| Friendly error page when the server is unreachable | `MainActivity.showErrorPage()` |
| Settings screen (URL, login, key bar, pull-to-refresh) | `SettingsActivity.kt` |
| Optional login (Basic Auth) with "Remember me" | `LoginActivity.kt` |
| Preferences + credential obfuscation + Basic Auth header | `PrefsHelper.kt` |
| Dark-blue (#003366) / white branding | `themes.xml`, `colors.xml` |
| Ambulance launcher icon | `res/drawable/ic_launcher_foreground.xml` |

## Agent Mode & Shell Access

Two toggles (in the ⋮ menu and in Settings) tell the Odysseus **server**
which interface to load, by appending flags to the URL:

- **Agent Mode** → adds `?agent=true` (loads the agent interface instead
  of the default chat).
- **Shell Access** → adds `&shell=true` (lets the agent run shell
  commands on the server). It requires Agent Mode, so it is disabled
  until Agent Mode is on.

The combined URL is built in `PrefsHelper.buildUrl()`, e.g.
`http://192.168.68.84:7000?agent=true&shell=true`. Toggling either one
reloads the page with the new URL.

## Using the built-in web terminal

The Odysseus service exposes its own shell/agent terminal in the web UI.
A web terminal (xterm.js / ttyd / wetty …) is hard to use on a phone
because the soft keyboard has no Ctrl, Tab, Esc or arrow keys. This app
solves that:

- **Terminal key bar** — a scrollable row of special keys (Esc, Tab,
  Ctrl, Alt, arrows, ^C ^D ^Z ^L, `|` `~` `/` `\` `-`, Home/End, PgUp/PgDn).
  Tapping a key injects a real `KeyboardEvent` into the terminal.
  **Ctrl** and **Alt** are sticky: tap Ctrl, then C to send `Ctrl+C`.
  Toggle the bar from the ⋮ menu ("Terminal keys") or in Settings.
- **Keyboard button** (⋮ menu / toolbar) — force-shows the soft keyboard
  if tapping the terminal doesn't bring it up.
- **Clipboard bridge** — copy/paste works even over plain `http://`,
  where the browser's `navigator.clipboard` is normally blocked.
- **Pull-to-refresh is off by default** so a swipe never reloads (which
  would kill your shell session). Turn it on in Settings if you want it;
  even then it only fires when the page is scrolled to the very top.
- The layout uses `adjustResize`, so the keyboard never covers the
  terminal, and the key bar sits directly above the keyboard.

## Project structure

```
app/src/main/
├── AndroidManifest.xml          # INTERNET perm + usesCleartextTraffic=true
├── java/no/ambulanse/odysseus/
│   ├── MainActivity.kt          # WebView host
│   ├── SettingsActivity.kt      # URL + login toggle
│   ├── LoginActivity.kt         # optional sign-in
│   └── PrefsHelper.kt           # SharedPreferences + auth helper
└── res/
    ├── layout/                  # activity_main / settings / login
    ├── drawable/                # ambulance icon layers
    ├── mipmap-*/                # launcher icons (adaptive + PNG fallback)
    ├── menu/main_menu.xml       # Reload / Settings
    └── values/                  # colors, strings, themes
```

## Build a debug APK

Requires the Android SDK (e.g. via Android Studio) and JDK 17+.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Build a SIGNED release APK (sideload, no Play Store)

1. **Create a keystore once** (keep it safe — losing it means you can't
   ship updates signed with the same key):
   ```bash
   keytool -genkeypair -v \
     -keystore odysseus-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias odysseus
   ```
2. **Add the signing secrets** to `~/.gradle/gradle.properties` (NOT
   committed):
   ```properties
   ODYSSEUS_STORE_FILE=/absolute/path/odysseus-release.jks
   ODYSSEUS_STORE_PASSWORD=yourStorePassword
   ODYSSEUS_KEY_ALIAS=odysseus
   ODYSSEUS_KEY_PASSWORD=yourKeyPassword
   ```
3. **Build it:**
   ```bash
   ./gradlew assembleRelease
   # output: app/build/outputs/apk/release/app-release.apk
   ```
4. **Install:** copy the APK to the phone and tap it. Enable
   "Install unknown apps" for your file manager the first time.

(These steps are also documented as comments in `app/build.gradle.kts`.)

## Notes

- The app allows **cleartext HTTP** (`usesCleartextTraffic="true"`) so it
  can reach the local Odysseus service over the LAN / Tailscale.
- The login screen is **off by default**. Turn it on in Settings; when on,
  the entered username/password are sent to Odysseus as HTTP Basic Auth.
- Credential storage uses light XOR+Base64 obfuscation — fine for a
  prototype, but use `EncryptedSharedPreferences` for production.
