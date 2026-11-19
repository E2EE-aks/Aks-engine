# FastGet – Browser + Media Downloader (Android)

IDM-style app: built-in browser, jab page par video/audio milta hai to **Download (N)** button aata hai,
tap karke list se download kar sakte ho. Download speed, ETA, progress sab dikhta hai
(downloads screen, bottom bar aur notification me).

## Features
- Built-in browser (WebView) – address bar, back/forward/home/refresh, fullscreen video
- **Media capture**: page ke network requests + `<video>/<audio>` tags se mp4, webm, mkv, mp3, m4a... detect karta hai
- Direct file links (zip, apk, pdf...) par "Download file?" dialog
- **Multi-connection download** (4 parts parallel, IDM jaisa) + **pause / resume** (app restart ke baad bhi)
- Live **speed (MB/s), ETA, progress**; foreground service + notification
- Files save hoti hain: `Downloads/FastGet/`
- Cookies / User-Agent / Referer page se forward hote hain (login wali sites ke liye)

## APK kaise banaye (GitHub se, koi setup nahi)
1. Is poore folder ko naye GitHub repo me upload karo (branch `main`).
2. Repo → **Actions** tab → "Build APK" workflow khud chalega (ya "Run workflow" dabao).
3. Run complete hone par neeche **Artifacts → FastGet-apk** se `FastGet.apk` download karo aur phone me install karo.
4. Release chahiye to tag lagao: `git tag v1.0 && git push origin v1.0` → APK Releases me aa jayegi.

## Android Studio se
Folder open karo → Run. (JDK 17, Android SDK 34)

## Limitations
- YouTube / DRM / HLS (`.m3u8`) / `blob:` streams download nahi hote.
- Sirf jo content aapke use ke liye allowed hai wahi download karo.
