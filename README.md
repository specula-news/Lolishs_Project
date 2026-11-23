# Lolish's Project

A Jellyfin client for Android TV / NVIDIA Shield in the Li'Server look, with a built-in player you can switch on the fly, plus the Li'Server torrent site inside the app.

## Features

- **Pre-connected** to `http://lolish.no-ip.com:8096`. Sign in once by picking your profile, using Quick Connect, or typing a username.
- **Home:** a featured banner, then rows for Continue watching, Next up, Latest per library, Upcoming and Favorites. The background artwork follows the poster you're focused on.
- **Libraries:** poster grids for Movies, Shows and Collections, with sort (A–Z, recently added, release date, rating, runtime, random) and filters (unwatched, favorites, in progress).
- **Details pages:** logo or title, rating, runtime, "ends at" time, genres, plot and a video/audio summary.
  - Buttons: Play / Resume, Start over, Player choice, Mark watched and Favorite.
  - Seasons and episodes, cast, and More like this.
- **Search, Favorites and Upcoming Shows** pages.
- **Built-in player** (ExoPlayer + VLC):
  - **Switch players instantly** with the ⇄ button: ExoPlayer · Direct, VLC · Direct, or a server transcode at 20 / 10 / 4 Mbps.
  - **Stutter detection:** if the video keeps buffering or drops frames, the app asks whether to switch to another player and suggests the best next options. It keeps asking until playback is smooth, then remembers the player that worked for that video or show.
  - Subtitle and audio delay, subtitle styling, skip intro/credits, chapters, seek thumbnails, refresh-rate matching, HDR/Dolby Vision and audio passthrough.
  - Reports progress to Jellyfin (resume and watched status) and offers **Up next** at the end of an episode.
- **Li'Server** (`https://lolish.no-ip.com:5000`) inside the app:
  - A remote-controlled pointer: arrows move it, OK clicks, pushing against an edge scrolls. **Menu** toggles between pointer and focus mode.
  - Stays signed in. It can also save your login, encrypted with the Android Keystore, and sign you in automatically.
  - Page zoom setting.
- **Appearance settings:** poster size, episode card size, icon size, text size, sidebar mode (expanded / icons / expand on focus), titles and ratings on posters, featured banner, and which Home rows to show.
- It still works as an **external player** for the official Jellyfin app.

## Building

Every push to GitHub builds the APKs with GitHub Actions (**Actions → Build APK → Artifacts**). Install `lolish-project-v2.0.0-jellyfin-release.apk` on the TV.

## Licences

Media3 (Apache 2.0) · Jellyfin media3-ffmpeg-decoder (GPL-3.0) · ass-media (MIT) / libass (ISC) · libVLC (LGPL-2.1) · Fraunces and DM Sans fonts (SIL OFL 1.1, see `FONTS_OFL_*.txt`).
