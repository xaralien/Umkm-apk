# Sapa UMKM — UMKM WebView App

Android app that wraps **https://market.kodesis.id/** and bridges native device
features to the website:

- **Location / GPS** -> `navigator.geolocation`
- **Camera + Mic** -> live camera (`getUserMedia`) and photo capture in file inputs
- **Local files** -> `<input type="file">` uploads (with a "take a photo" option)
- **Downloads** -> files the site offers are saved to the Downloads folder
- **Screenshots allowed** -> unlike the Kisa app, screenshots and screen recording
  are never blocked on any page

App name: **Sapa UMKM**  •  Package: `id.kodesis.market`
The launcher icon (SAPA UMKM logo) is already generated for every screen density,
including an adaptive icon for Android 8+.

---

## IMPORTANT: how the final APK / AAB is produced

The project is 100% build-ready, but the actual compile into an installable
`.apk` / `.aab` must run on a machine with the **Android SDK** (Android Studio).
That step needs internet the first time to download the SDK + libraries.
Once set up it's a single click or command below.

---

## Option A — Android Studio (easiest)

1. Install Android Studio, then `File > Open` and select this **Umkm-apk** folder.
2. Wait for the Gradle sync to finish (downloads dependencies automatically).
3. **Build an APK** (for direct install / sharing):
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`
   -> `app/build/outputs/apk/debug/app-debug.apk`
4. **Build a signed release AAB** (for Google Play):
   `Build > Generate Signed Bundle / APK > Android App Bundle`
   - First time: click **Create new...** to make a keystore (.jks). SAVE this file
     and its passwords — every future update must be signed with the same key.
   - Choose the **release** variant -> `app/release/app-release.aab`

## Option B — Command line

With the Android SDK installed and `ANDROID_HOME` set:

```bash
# generate the Gradle wrapper once (if ./gradlew is missing)
gradle wrapper

# Debug APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Signed release AAB for Play — create a keystore once:
keytool -genkey -v -keystore umkm.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias umkm

# then add signing to app/build.gradle (see "Signing" below) and run:
./gradlew bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

### Signing (command-line release)
Add this inside the `android { }` block of `app/build.gradle`:

```groovy
signingConfigs {
    release {
        storeFile file("../umkm.jks")
        storePassword "YOUR_STORE_PASSWORD"
        keyAlias "umkm"
        keyPassword "YOUR_KEY_PASSWORD"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled false
    }
}
```

---

## Publish to Google Play
1. One-time \$25 Play Console developer account.
2. Create an app, upload `app-release.aab` to a testing or production track.
3. Fill the listing + **Data safety** form and add a **privacy policy URL**
   (required because the app uses camera and location).

## Customize
- URL: `startUrl` in `app/src/main/java/id/kodesis/market/MainActivity.kt`
- Name: `app/src/main/res/values/strings.xml`
- Icon: replace the PNGs in `res/mipmap-*/` (or use `File > New > Image Asset`)

Camera/GPS only work if the website itself uses the standard web APIs over HTTPS
(it does). This app grants and forwards the OS permissions.
