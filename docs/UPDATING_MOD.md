# Installing and updating this fork

## First install (once)

1. Open the repository's **Actions** tab, choose the newest **Build Windows installer (mod)** run on
   `main`, and download the **windows-installers** artifact (you must be signed in to GitHub).
2. Use a **portable** package (`*.portable.zip`): NVIDIA → the `nvidia` one, AMD/Intel → `opencl`,
   no graphics card → `with-katago`. Unzip it into a folder you own, for example
   `Documents\LizzieYzy`, and start `LizzieYzy Next*.exe` there.
   The installers (`*.installer.exe`) work too, but they go to Program Files, where updates need
   administrator rights.

## Updates (minutes, no reinstall)

Every push to `main` runs **Publish mod update**, which builds only the program file (a few
minutes) and publishes it on the `mod-latest` release with its SHA-256 and size.

In LizzieYzy: **Help → Update LizzieYzy (mod)…**

- It compares the published build with the one running and asks before downloading.
- The download is checked (size and SHA-256) before anything is replaced.
- On Windows the new build is saved next to the old one as `app\lizzie-mod-<commit>.jar`, and the
  launcher file `app\LizzieYzy Next*.cfg` is pointed at it (the previous one is kept as `.cfg.bak`).
  Nothing in use is replaced, so the next start always uses the new build. **Restart now** restarts
  for you.
- After the restart LizzieYzy tells you whether the update took effect.
- The previous version stays on disk; when you are up to date, the same menu item offers **Restore
  previous version**. Elsewhere (macOS, Linux) the old file is kept as `.bak`.

Engines, networks, settings, the opening database and your games are not touched by an update.
A new full installer is only needed when the packaged parts change (Java runtime, bundled KataGo).

The updater trusts this repository's GitHub releases over HTTPS; the checksum protects against
broken downloads, not against someone who controls the repository.
