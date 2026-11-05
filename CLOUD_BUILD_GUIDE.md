# Build in the cloud with GitHub Actions (no Android Studio)

GitHub compiles the app on its servers and gives you the APK + AAB to download.
Free for this.

## What you get
- **app-debug.apk** — install straight onto a phone to test
- **app-release.aab** — the signed file you upload to Google Play

---

## One-time setup

### 1. Put the project on GitHub
- Create a free account at github.com, then create a new **private** repository.
- Upload the whole project folder (drag-and-drop via "Add file > Upload files",
  or use git). Make sure the `.github/workflows/build.yml` file is included.
- IMPORTANT: do **not** upload `umkm.jks` or the `.base64.txt` file.
  They're already in `.gitignore`. The keystore goes into Secrets instead (below).

### 2. Add the signing secrets
In your repo: **Settings > Secrets and variables > Actions > New repository secret**.
Add these four:

| Secret name        | Value                                              |
|--------------------|----------------------------------------------------|
| KEYSTORE_BASE64    | the full contents of `umkm.jks.base64.txt`    |
| KEYSTORE_PASSWORD  | `umkm2026`                                    |
| KEY_ALIAS          | `umkm`                                         |
| KEY_PASSWORD       | `umkm2026`                                    |

(You can change these passwords by generating your own keystore — command at the
bottom. Keep the keystore safe: you need the same one for every app update.)

### 3. Run the build
- Go to the **Actions** tab in your repo.
- Open **Build UMKM Market (APK + AAB)** and click **Run workflow**
  (it also runs automatically on every push).
- When it finishes (green check), open the run and download the files under
  **Artifacts**: `UMKM-debug-apk` and `UMKM-release-aab`.

That's it — no Android Studio, no SDK on your side.

---

## Notes
- If you skip the secrets, the build still produces the **debug APK** (great for
  testing); only the signed AAB needs the keystore.
- To upload the AAB to Google Play: Play Console > create app > upload the `.aab`,
  then fill the listing + Data safety form + privacy policy URL (required for
  camera + location).

## Make your own keystore (optional, recommended for real releases)
```bash
keytool -genkeypair -v -keystore umkm.jks -alias umkm \
  -keyalg RSA -keysize 2048 -validity 10000
# then: base64 -w0 umkm.jks   -> paste into the KEYSTORE_BASE64 secret
```
