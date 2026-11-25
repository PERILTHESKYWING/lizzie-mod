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
- The current program file is kept as `app\lizzie-yzy2.5.3-shaded.jar.bak`; when you are up to date,
  the same menu item offers **Restore previous version**.
- On Windows the file is swapped after LizzieYzy closes (**Restart now** does that for you).

Engines, networks, settings, the opening database and your games are not touched by an update.
A new full installer is only needed when the packaged parts change (Java runtime, bundled KataGo).

The updater trusts this repository's GitHub releases over HTTPS; the checksum protects against
broken downloads, not against someone who controls the repository.

## Staying in sync with the original project

This mod is its own repository, started from `wimi321/lizzieyzy-next`. Once a day the **Sync
original project** workflow (`.github/workflows/sync-upstream.yml`) merges the original's new
changes:

- Conflicts that are known to be harmless (translation lines, CHANGELOG entries and line budgets
  added next to each other) are resolved automatically; anything else stops the sync.
- The merged code runs the same Linux and Windows Java test suites as CI. Only when they pass does
  it reach `main`, and a new mod build is published, ready for **Help → Update LizzieYzy (mod)…**.
- If the merge conflicts, changes workflow files (the workflow's token may not push those) or the
  tests fail, `main` is left alone and an issue titled *Syncing the original project needs a look*
  explains what happened.

You can also start it any time from the repository's **Actions** tab (**Sync original project →
Run workflow**).
