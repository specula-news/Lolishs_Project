# Lumen Player

Lumen Player is an external video player for the **Jellyfin Android TV app** (NVIDIA Shield, Google TV, Fire TV). In Jellyfin you pick it as the "External app" player. It feels like Jellyfin's own player but plays more formats and handles subtitles better.

## What it does

- **Plays almost everything.** ExoPlayer (Media3) uses the Shield's hardware decoders: H.264, HEVC 10-bit, VP9, MPEG-2, VC-1, and AV1 where the device supports it.
  - **HDR:** HDR10, HLG and Dolby Vision output.
  - **Audio passthrough:** Dolby TrueHD/Atmos, DTS-HD MA/DTS:X and E-AC3 go to your receiver untouched.
  - **FFmpeg** decodes any audio your receiver can't take (DTS, TrueHD, AC3, FLAC, ALAC…).
  - **VLC fallback:** if a file still won't play (AVI/DivX, WMV, RMVB, odd codecs), the app switches to the built-in libVLC engine automatically, at the same position.
- **Subtitles**
  - SRT, ASS/SSA (full styling via libass), PGS, VobSub, DVB, WebVTT and TTML.
  - External subtitles from Jellyfin, with their real names.
  - **Subtitle delay** in ±50 ms steps (hold the button to move faster) and **audio delay** for lip-sync. Both can be remembered per video.
  - Size, position, outline/shadow/box style and white/yellow colour.
- **Integrated with Jellyfin**
  - **Resume:** the position is sent back to Jellyfin when you stop, so "Continue watching" works.
  - **Watched status:** a video that plays to the end is marked as watched, and Jellyfin starts the **next episode** automatically.
  - **Optional server link** (Quick Connect): adds **Skip intro / Skip credits** buttons (Jellyfin 10.10+ media segments), **chapters**, **seek thumbnails** (trickplay) and nicer titles ("Series · S1 · E3 — Name").
- **TV-first UI**
  - Dark, minimal overlay showing a clock, the "Ends at" time and badges such as 4K / Dolby Vision / TrueHD 7.1.
  - Settings open in a side panel.
  - Display refresh rate matching: 23.976 / 24 / 25 / 50 / 60 Hz.
  - Stats overlay showing the decoder, passthrough status and dropped frames.

## The two builds

| Build | Package ID | Use it when |
|---|---|---|
| **jellyfin** (recommended) | `is.xyz.mpv` | You use the Jellyfin Android TV app. Jellyfin only sends subtitles and reads back resume/watched info for players it recognises, and it recognises mpv's package ID. This build uses that ID, so Jellyfin treats Lumen like mpv. **It can't be installed next to the real mpv-android app**; uninstall mpv first if you have it. |
| **standalone** | `app.lumen.player` | Generic "Open with" player for other apps. With the Jellyfin TV app this build plays fine, but Jellyfin marks every video as watched and doesn't save your resume position (Jellyfin's own limitation for unknown players). |

## 1. Build the APK

The project needs the Android SDK and the internet to download the libraries. The easiest free way is GitHub Actions.

### Option A: GitHub Actions (no tools needed)

1. Create a free account at github.com, then click **New repository**. Make it **Private** and create it.
2. Click **uploading an existing file**, then drag in **everything inside** the `lumen-player` folder. Commit.
   - Check that `.github/workflows/build.yml` was uploaded. Some browsers skip hidden folders. If it's missing, click **Add file → Create new file**, name it `.github/workflows/build.yml`, paste in the file's contents and commit.
3. Open the **Actions** tab. The "Build APK" run starts automatically; if it doesn't, select it and click **Run workflow**. It takes about 5–10 minutes.
4. Open the finished run and download **lumen-player-apks** under *Artifacts*. The zip contains:
   - `lumen-player-v1.0.0-jellyfin-release.apk` ← install this one
   - `lumen-player-v1.0.0-standalone-release.apk`

### Option B: Android Studio

Open the `lumen-player` folder in Android Studio and let Gradle sync. Choose **Build → Generate App Bundles or APKs → Build APK(s)** with the `jellyfinRelease` variant selected (Build Variants panel).

Both options sign the APK with the included key (`app/lumen-release.jks`), so new builds install as updates over old ones. Keep the repository private, or replace the key with your own.

## 2. Install on the Shield

1. If **mpv** is installed on the Shield, uninstall it.
2. Sideload the APK in one of these ways:
   - **Downloader app:** upload the APK somewhere you can reach from the TV (Google Drive link, a NAS share, a local web server) and open it in the Downloader app.
   - **Send Files to TV app:** send the APK from your phone to the TV.
   - **ADB:** `adb connect <shield-ip>` then `adb install lumen-player-v1.0.0-jellyfin-release.apk`.
3. When Android asks, allow that app to "install unknown apps".

## 3. Turn it on in Jellyfin

In the Jellyfin Android TV app go to **Settings → Playback → Video player**. Under **External app**, choose **Lumen Player**.

Press play on anything. Jellyfin hands the video to Lumen and gets your position back when you stop.

### Optional: link your server (skip intro, chapters, thumbnails)

1. Open **Lumen Player** from the TV home screen and select **Link server**.
2. The server address is filled in from the last stream Jellyfin sent. Choose **Quick Connect**.
3. On your phone or computer, open Jellyfin → your profile → **Quick Connect** and enter the code shown on the TV.

Username and password sign-in also works.

## Remote control

| Button | What it does |
|---|---|
| OK / Play-Pause | Play / pause |
| ◀ ▶ | Seek (hold to speed up). Shows thumbnails when the server is linked. |
| ▲ / ▼ | Show the controls |
| Menu | Settings panel |
| Captions button | Subtitle panel |
| ⏭ ⏮ | Next / previous chapter |
| Back | Close panel → hide controls → exit (your position is saved) |

On the control bar: **Subtitles**, **Audio**, **Sync** (clock icon: subtitle and audio delay), **Chapters**, **Settings** (speed, zoom, switch engine, frame-rate matching), **Info** (stats).

## Tips for the Shield

- **Surround sound:** for receiver passthrough, set Android's Surround sound option to *Auto*, or enable your receiver's formats manually.
- **HDR / Dolby Vision:** enable "match dynamic range" (or the equivalent) in the Shield's display settings so the TV switches HDR mode for each video.
- **Frame-rate matching:** Lumen requests the matching refresh rate itself. Turn it off under Settings → *Match frame rate* if your TV blanks for too long when switching.
- **Stats overlay:** shows "Passthrough / bitstream" when audio goes to the receiver untouched, and "(hardware)" next to the decoder name.

## Troubleshooting

- **The build fails on GitHub:** open the failed step, copy the red error lines and send them over. They're usually a one-line fix.
- **Lumen isn't in Jellyfin's External app list:** make sure the APK installed, then restart the Jellyfin app.
- **A file won't play in ExoPlayer:** Lumen switches to VLC automatically. To force an engine, use the settings panel or Home screen → *Engine*.
- **Subtitles out of sync:** press the clock button and adjust *Subtitle delay*. Positive values show subtitles later.

## Project layout

```
app/src/main/java/app/lumen/player/
  IntentParser.kt        mpv / MX / VLC / Vimu intent extras in, result (position, completion) out
  PlayRequest.kt         stream + subtitles + Jellyfin item id parsed from the URL
  engine/ExoEngine.kt    Media3 ExoPlayer + FFmpeg + libass
  engine/ExoSync.kt      subtitle delay / audio delay for Media3
  engine/VlcEngine.kt    libVLC fallback
  jellyfin/JellyfinApi.kt  Quick Connect, item details, chapters, media segments, trickplay
  ui/PlayerActivity.kt   the player UI and remote handling
  ui/MainActivity.kt     home screen, settings and server linking
```

## Licences

Media3 (Apache 2.0) · Jellyfin media3-ffmpeg-decoder (GPL-3.0, includes FFmpeg) · ass-media / libass (MIT / ISC) · libVLC (LGPL-2.1). Because the FFmpeg decoder is GPL, keep this app for personal use or publish its source under the GPL.
