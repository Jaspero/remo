# Remo

A fast Android remote for Google TV / Android TV, in Kotlin + Jetpack Compose.

It talks straight to the TV over the Android TV Remote protocol v2 (the one the Google TV app uses):
pairing on port 6467 with a code shown on the TV, then a TLS connection on port 6466. No cloud,
no Google Home, nothing installed on the TV.

- One-tap apps: YouTube, Netflix, Disney+, Kodi, plus your own (package name or link; long-press to remove)
- D-pad with hold-to-repeat, or a swipe touchpad; long-press OK and Home
- Keyboard on any screen; it opens by itself when the TV shows a text field
- Phone volume buttons control the TV; TV volume level shown
- Remembers several TVs, finds them on the Wi-Fi, follows a TV whose IP changed
- Install apps on the TV from your phone (over ADB): one tap for [SmartTube](https://github.com/yuliskov/SmartTube)
  (YouTube without ads, latest stable build from its GitHub releases), an APK on the phone, or a link.
  Installed apps get their own button. Needs Developer options → USB debugging on the TV, once.

## Install

Download the APK from [Releases](https://github.com/Jaspero/remo/releases), open it on your phone and allow
installing from that source. Then, on the same Wi-Fi as the TV, pick the TV (or enter its IP address) and type
the code it shows. Pairing happens once.

## Build

    ./build-release.sh 1.0.0     # signed APK at app/build/outputs/apk/release/app-release.apk
    ./gradlew :app:testDebugUnitTest

The signing key is in `secrets/` (not in git). Keep it: updates only install over the app when signed with it.

`app/src/test/.../FakeTvServer.kt` is a pretend TV for trying the app in an emulator:
`REMO_FAKE_TV=/tmp/faketv ./gradlew :app:testDebugUnitTest --tests '*FakeTvServer*'`, then add TV `10.0.2.2`.

## Credits

ADB is done with [dadb](https://github.com/mobile-dev-inc/dadb). The protocol details follow [androidtvremote2](https://github.com/tronikos/androidtvremote2) and the
community write-ups it links to. Not affiliated with Google. YouTube, Netflix, Disney+ and Kodi are
trademarks of their owners; the app only opens them.

## License

MIT
