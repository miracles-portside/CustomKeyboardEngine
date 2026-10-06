# CustomKeyboardEngine (fork)

A privacy-friendly Android keyboard engine with **dynamic transparency**,
**per-app theming**, and **iOS-style tap feedback**.

Built and tested on **Termux** for ARM64 devices (Redmi Note 15 Pro / MIUI HyperOS).

## Features

### Dynamic transparency
Opaque in normal apps, transparent on the MIUI home-screen search.

Whitelisted launcher packages:
- com.mi.appfinder (MIUI home search)
- com.miui.home
- com.mi.android.globallauncher
- com.google.android.apps.nexuslauncher
- com.google.android.googlequicksearchbox
- com.android.launcher3

### Per-app theme override
Always-dark apps (Termux, Telegram, etc.) force the keyboard dark regardless
of system theme. Everything else follows the system setting.

### iOS-style tap fade
Keys darken on press and fade back over 180ms. Re-press cancels the fade.

### Neutral theme
No accent tint. White keys in light mode, dark grey keys in dark mode.

## Building in Termux (ARM64)

Android's default aapt2 binary is x86_64 and won't run on ARM64. Add this
line to `gradle.properties`:

    android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2

Requires: `pkg install openjdk-17 gradle android-tools aapt2`

Build:
    ./gradlew assembleDebug

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Customization

### Transparency trigger packages
Edit `homeLauncherPackages` in `CustomKeyboardService.kt`.

### Per-app dark mode
Edit `forceDarkPackages` in `CustomKeyboardService.kt`.

### Tap fade duration
`KEY_FADE_DURATION_MS` in `CustomKeyboardView.kt` (default 180).

### Key opacity in transparent mode
In `CustomKeyboardView.onDraw`:
    keyBackgroundColor = keyBackgroundColor.withAlphaFraction(0.95f)
    keyModifierBackgroundColor = keyModifierBackgroundColor.withAlphaFraction(0.85f)

## Layouts

Loaded at runtime from:
    /sdcard/Android/media/com.roalyr.customkeyboardengine/layouts/
      layouts-language/keyboard_en_default.json
      layouts-service/keyboard_service_default.json
      layouts-clipboard/keyboard_clipboard_default.json

Filenames must match constants in `Constants.kt`. Fallback copies in `res/raw/`.

## Limitations

- iOS: not possible (Apple's extension API doesn't expose host bundle ID)
- True blur-behind: unreliable on MIUI
- Theme detection: system + whitelist only, not the host app's real theme

## Credits

Fork of roalyr/CustomKeyboardEngine.
