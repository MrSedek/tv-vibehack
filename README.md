# tv-vibehack

**Your TV, minus the ads.** Debloat any Android TV / Google TV over ADB and give it a clean,
tvOS-inspired launcher. Hand the prompt to Claude Code or any coding assistant, answer a few
questions about which apps you use, and it does the rest on the real TV.

| Stock home screen | After |
|---|---|
| ![Stock Google TV home with a sponsored ad](docs/stock-home.jpg) | ![tvOS-style launcher](docs/new-home.jpg) |

Everything is reversible: packages are disabled, never uninstalled, and a restore script puts the
stock launcher back.

## Quick start

1. On the TV: Settings → Device Preferences → About → press OK on **Build** seven times → back →
   Developer options → turn on **USB debugging** (and Network/ADB debugging if your TV shows it).
   Keep the TV on and on the same Wi-Fi as your computer.
2. Open Claude Code (or your assistant of choice) on your computer and paste the prompt from
   [`PROMPT.md`](PROMPT.md), filling in your TV model.
3. Accept the "Allow USB debugging?" prompt on the TV when told to, then answer the checklist
   questions (which apps you use, which four go in the dock).

That's it. The assistant backs up, debloats, builds and installs the launcher, and verifies each
step with screenshots from the TV. If you'd rather do it by hand, the pieces are below.

## What's in the repo

| Path | What it is |
|---|---|
| [`PROMPT.md`](PROMPT.md) | The prompt. Brand-neutral; the assistant discovers your TV's bloat. |
| [`launcher/`](launcher/) | The tvOS-style launcher. Edit `app/src/main/res/values/config.xml` for your dock, hidden apps and Live TV package, then run `build-and-install.sh`. |
| [`launcher/bridge/`](launcher/bridge/) | Companion app that records where you paused in your players (and asks Jellyfin), for the launcher's continue-watching shelf. |
| [`launcher/measure.sh`](launcher/measure.sh) | Start time, warm return, frame and memory numbers from the TV, for before/after a change. |
| [`tools/make-keystore.sh`](tools/make-keystore.sh) | One signing key for the launcher and the bridge, kept outside the repo. |
| [`tools/tv.py`](tools/tv.py) | Pure-Python ADB client (shell, batch, push, pull). Works where the stock `adb` is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files (APKs) as base64 chunks with an md5 check. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. |
| [`examples/`](examples/) | Per-TV debloat lists. Add yours, see [CONTRIBUTING.md](CONTRIBUTING.md). |

## The launcher

A small native Android TV home screen (`com.gathin.tvhome`), built for sets with 1 GB of RAM:

- Pitch-black background, like Apple TV 4K.
- A dock at the bottom in a lighter rounded band with the four apps you choose. The shelf above
  shows the focused dock app's name in large type with an ambient glow sampled from its banner.
  Press Down and the dock slides up to reveal the rest of your apps in a grid.
- One translucent glass highlight springs from tile to tile. The focused tile lifts and shows its
  name.
- Frosted buttons top right: search, **Wi-Fi** (shows the network name), settings, and **Inputs**
  (a dropdown of HDMI ports, AV and Live TV) on TVs that have any. Greeting, a large thin clock and
  the date top left. The clock is set in [Outfit](https://fonts.google.com/specimen/Outfit), the
  rest in [Manrope](https://fonts.google.com/specimen/Manrope), which also covers Cyrillic.
- Deterministic D-pad navigation, last focused tile remembered, staggered entrance animation.
  Back and Home return to the dock.
- Press Up from the dock for the continue-watching shelf (below). Long-press a tile to pin, move,
  hide or inspect it. The settings button (top right) toggles the shelf, switches the grid between
  alphabetical and recently opened, restores hidden apps and resets the dock.
- A screensaver to match: a large clock on black, glowing in the colour of the last dock app. The
  install script makes it the TV's screensaver (`DREAM=0` keeps the TV's own).
  No blur, no video, no network, no background services.

Configure it in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
dock order, hidden apps, Live TV package and columns. The dock holds the favourites that are
installed, up to the column count, and may be a different width from the grid. One flavour per TV
model overrides that file: `FLAVOR=stick` builds the Xiaomi TV Stick 4K profile from
[`launcher/app/src/stick/`](launcher/app/src/stick/res/values/config.xml) (five columns, no inputs).
Build and install with

```bash
TV_HOST=192.168.1.50 FLAVOR=stick launcher/build-and-install.sh
```

The script installs the bridge, the launcher and the screensaver, sets the launcher as home,
compiles it ahead of time (`pm compile -m speed`) and disables the stock launcher only after
confirming that Home resolves to ours.

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:
`brew install openjdk@17 android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`.

**Signing.** Debug builds carry this machine's debug key, which is fine until you build on another
machine: the bridge and the launcher must share a key, or the shelf goes empty and `pm install`
refuses the update. For a key that lasts, run `tools/make-keystore.sh` once and export the
variables it prints; the script then builds signed, shrunk release APKs instead. Switching keys
needs a one-time `pm uninstall` of both apps on the TV (dock order, hidden apps and the shelf
history go with them).

**Measuring.** `TV_HOST=192.168.1.50 launcher/measure.sh` prints cold start, warm return from
another app, frame stats over twenty D-pad moves and memory, five runs each. Keep the numbers from
before a change. On the stick the warm return went from 0.9–1.6 s to 0.17–0.35 s once the launcher
stopped rereading the app list on every Home press.

### Continue watching

The shelf right above the dock shows where you stopped: thumbnail, title, time left and progress.
Press Up from the dock to reach it, select a card to pick up where you left off, long-press it for
"Open app" or "Remove". It stays hidden until there is something to continue, and while you browse
the app grid. Entries nobody returns to disappear after 30 days.

Android TV's own "Play next" row can only be read by the system launcher, so a small companion
app records it instead: the bridge (`com.gathin.tvbridge`, in [`launcher/bridge/`](launcher/bridge/)).
As an enabled notification listener it may watch other apps' media sessions, and it takes a
snapshot on every pause and whenever a player closes its session. That keeps the launcher itself
free of background services; the bridge goes online only for thumbnails, YouTube titles and
Jellyfin.

`build-and-install.sh` installs the bridge first and enables it with
`cmd notification allow_listener com.gathin.tvbridge/com.gathin.tvbridge.MediaWatchService`;
`BRIDGE=0` skips it. The launcher reads the history through a content provider guarded by a
signature permission, so both APKs must be signed with the same key (see Signing above). The
watched players are listed in
[`launcher/bridge/src/main/res/values/config.xml`](launcher/bridge/src/main/res/values/config.xml).
What each gives, as tested on a Xiaomi TV Stick 4K:

| Player | Shelf card | Selecting it |
|---|---|---|
| SmartTube | Title, channel, thumbnail, progress | Reopens the video at the saved second |
| VK Video | Progress only; its banner stands in for the thumbnail | Opens VK, which shows its own "continue" row |
| Jellyfin | Films and episodes from the server's Resume and Next up lists, with artwork and progress from every device | Opens the item's page in the Jellyfin app |
| HDrezka (AstroApps) | Nothing: no media session at all | — |

**Jellyfin** needs the server address and an API key (Dashboard → API Keys), handed to the bridge
once over adb; the bridge's settings receiver accepts only the adb shell:

```bash
tools/venv/bin/python -I tools/tv.py 'am broadcast -n com.gathin.tvbridge/.ConfigReceiver -a com.gathin.tvbridge.CONFIGURE --es jellyfin_url http://192.168.1.10:8096 --es jellyfin_key YOUR_KEY'
```

The bridge uses the first enabled user (add `--es jellyfin_user <id>` for another), syncs every
15 minutes and a few seconds after the Jellyfin app pauses, and forgets the server when sent an
empty `jellyfin_url`. Any other player that doesn't report what it plays can't appear.

## Talking to the TV by hand

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP
tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it, restore stock launcher
```

### Gotchas we hit

- **macOS: `adb connect` says "No route to host"** while the port is clearly open. Recent macOS
  treats the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **`tools/tv.py` gets "No route to host" too** when the venv was made with a third-party Python
  build (PlatformIO's, for one) that Local Network privacy blocks the same way. Create the venv with
  Apple's or Homebrew's `python3`.
- **No "wireless debugging" toggle** on some TVs (Xiaomi among them). USB debugging alone opens
  network ADB on port 5555.
- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does this.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **Third-party launchers** often charge for basics like a custom wallpaper. Hence `launcher/`.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** from `pm install`: the APK is signed with a different key
  than the installed one (another machine, or debug to release). `pm uninstall` both apps first.
- **No on-screen keyboard** after switching launchers (seen on Xiaomi): the default input method
  pointed at a Baidu IME that isn't installed. Fix with
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.

## Supported TVs

Anything running Android TV or Google TV: Xiaomi/Redmi, TCL, Hisense, Sony, OnePlus, Realme,
Nvidia Shield and most others. Not Samsung Tizen, LG webOS or Amazon Fire TV (different OS, no ADB
launcher replacement). Tested so far on the models in [`examples/`](examples/).

## Contributing

Add your TV's debloat list, improve the launcher, or sharpen the prompt. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit and Manrope typefaces are under the SIL Open Font
License, see `launcher/FONT-LICENSE-OFL.txt` and `launcher/FONT-LICENSE-OFL-Manrope.txt`.
