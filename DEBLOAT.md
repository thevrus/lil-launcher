# Debloat your Android TV

Your TV runs a phone-grade operating system, and the company that sold it to you keeps working after you take it home: sponsored rows on the home screen, apps you never installed, telemetry services and background processes that eat a box with 2 GB of RAM. You can switch most of it off in about fifteen minutes, without root, and undo every step with one command.

This guide covers Google TV and Android TV devices: NVIDIA Shield, Chromecast with Google TV, Google TV Streamer, Sony, TCL, Philips and Xiaomi sets, onn. boxes. Pair it with [Lil Launcher](README.md) so the home screen you land on is yours.

## Why bother

- **Ads where your apps should be.** The stock launchers sell the top of your screen. On the Shield, Google updates brought Glance, an ad-supported overlay with news feeds, into the Backdrop screensaver ([report](https://github.com/bryanroscoe/shield_optimizer/issues/123)).
- **Your TV watches back.** Smart TVs use automatic content recognition (ACR) to fingerprint what is on screen. A 2024 audit of Samsung and LG sets found ACR kept running even when the TV was only a monitor for an HDMI device, and that the opt-out did stop the traffic ([IMC 2024](https://arxiv.org/abs/2409.06203)). Google TV's own software is not known to use ACR, but the TV maker's software can, so look for the switch in the brand's own settings menu.
- **Trackers in the apps.** A Princeton study found trackers in 69% of Roku channels and 89% of Fire TV channels ([The Register](https://www.theregister.com/2019/10/01/your_smart_tv_is_watching_you_says_princeton_privacy_prof/)).
- **Speed.** Fewer background services means more memory for the app you are actually watching, and a remote that answers on the first press.

People who have done it say the same things: "how fast the TV moves now" ([@doooyle](https://x.com/doooyle/status/2111247627748565091)), a set that showed "a sponsored ad before you could even get to Netflix" ([@gathint](https://x.com/gathint/status/2111379569030939102)), and sponsored tiles on a $1,600 TV from someone who "wasn't interested in becoming inventory too" ([@lazy_IT_guy](https://x.com/lazy_IT_guy/status/2103515875408310635)).

## The rules

1. **Disable, never uninstall.** `pm disable-user` keeps the app on the system partition and undoes with `pm enable`. Nothing is deleted.
2. **Back up first.** Save the list of enabled packages so you can restore exactly what you had.
3. **One group at a time, then reboot.** If something breaks, you know which group did it.
4. **Install your new launcher before you disable the old one.** A TV with no launcher boots to a black screen.
5. **One command per call.** Chained `pm` commands can stop silently after the first one.
6. A factory reset restores every stock app but erases everything else on the TV. A big firmware update can re-enable packages or restore the stock launcher, so check again after updates.

## Step by step

**1. Turn on debugging.** On the TV: Settings → System (or Device Preferences) → About → press OK on **Build** until it says you are a developer. Back out to Developer options and turn on **USB debugging**, and **Network debugging** where it exists (Shield).

**2. Connect.** Install [adb](https://developer.android.com/tools/releases/platform-tools) on a computer on the same network, find the TV's IP address under Network settings, then:

```sh
adb connect 192.168.1.50:5555     # accept the prompt on the TV, tick "Always allow"
```

Android 13 and newer TVs may use Wireless debugging instead: `adb pair <ip>:<port>` with the code shown on screen, then `adb connect`.

**3. Back up.**

```sh
adb shell pm list packages -e > enabled-before.txt
```

**4. Disable.** One package at a time:

```sh
adb shell pm disable-user --user 0 com.google.android.feedback
```

**5. Switch launchers safely.** Before disabling the stock launcher, install your new one, make it Home and press Home to confirm:

```sh
adb shell cmd package set-home-activity dev.vr.tvlaunch/.MainActivity
```

**6. Undo anything.**

```sh
adb shell pm enable com.google.android.feedback
```

## What to disable

These come from the lists that the community has tested most: [Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation), [shield_optimizer](https://github.com/bryanroscoe/shield_optimizer) and [TVSlim](https://github.com/jolabs40/TVSlim). They are also what runs on the Shield this launcher was built on. Check which ones your TV has with `adb shell pm list packages | grep <name>`.

### Safe on every Google TV and Android TV

| Package | What it is |
| --- | --- |
| `com.google.android.feedback` | Crash and feedback reports |
| `com.google.android.tv.bugreportsender` | Bug report uploader |
| `com.google.android.partnersetup` | Partner setup and telemetry shim |
| `com.google.android.onetimeinitializer` | First-run setup, already done |
| `com.google.android.syncadapters.calendar` | Calendar sync, useless on a TV |
| `com.google.android.backdrop` | Ambient screensaver that can carry ads |
| `com.google.android.apps.tv.dreamx` | Ambient Mode on Google TV |
| `com.google.android.leanbacklauncher.recommendations` | Recommendation rows feed |
| `com.android.printspooler` | Printing |
| `android.autoinstalls.config.<maker>` | Apps the maker installs for you; find the exact name with `pm list packages \| grep autoinstalls` |

### NVIDIA Shield

`com.nvidia.stats`, `com.nvidia.feedback`, `com.nvidia.diagtools`, `com.nvidia.tegrazone3`, `com.nvidia.nvgamecast`, `com.nvidia.shield.registration`, `com.nvidia.shield.welcome`, `com.nvidia.shieldbeta`, `com.nvidia.factory`, `com.nvidia.factorybundling`, `com.nvidia.factorysyschecker`, `com.nvidia.tegraprofiler.security`. These cover telemetry, the retired GameStream, first-run screens and factory tests. If you never use the Shield as a network drive, also disable `com.nvidia.shield.smbserver`, `com.nvidia.shield.smbauth` and `com.nvidia.shield.nas`.

### Sony

`com.sony.dtv.promos`, `com.sony.dtv.smarthelp`, `com.sony.dtv.osat.music`, `com.sony.dtv.sonyselect`, `com.sony.dtv.demomode`.

### TCL

`com.tcl.usercenter`, `com.tcl.esticker`, `com.tcl.exhibit`, `com.tcl.messagebox`.

### Ask yourself first

| Package | You lose |
| --- | --- |
| `com.google.android.apps.mediashell` | Chromecast: casting from your phone |
| `com.google.android.videos` | The Google TV app: your purchased movies and shows |
| `com.google.android.katniss` | The remote's microphone button and Assistant |
| `com.google.android.speech.pumpkin` | Voice typing in apps |
| `com.google.android.tts` | Text to speech, TalkBack and audio description |
| `com.nvidia.ota` | System updates, security fixes included |
| `com.tcl.ttvs` | TCL system service, including updates |
| `com.google.android.tvlauncher`, `com.google.android.apps.tv.launcherx` | The stock home screen. Only after your new launcher is set as Home |

### Never

| Package | Why |
| --- | --- |
| `com.google.android.gms`, `com.android.vending` | Play Store and sign-in stop working |
| `com.android.providers.tv`, `com.android.tv.settings` | Rated unsafe: channels and Settings break |
| Your only keyboard (e.g. `com.google.android.inputmethod.latin`) | No way to type |
| `com.google.android.tungsten.setupwraith` on Sony | Reported to break volume control |
| `com.sony.dtv.tvx` | HDMI inputs stop working |
| `com.tcl.waterfall.overseas` | One reported boot loop |
| Anything named `providers`, `inputdevices`, `bluetooth`, `systemui` or `permissioncontroller` | Core system |

## Or let an AI do it

If you use Claude Code, Codex or any coding assistant with shell access, paste this prompt. The assistant does the discovery and the typing, and you make the choices.

```text
You are debloating my Android TV over adb. Device: <brand and model>. TV IP: <ip>.

Goal: remove ads, telemetry and preinstalled junk, keep everything I actually use,
and make every change reversible.

Rules:
- Only ever use `pm disable-user --user 0`, one package per adb call. The only commands
  you may run are pm list, pm disable-user, pm enable, cmd package set-home-activity,
  reboot, getprop and screencap. Never pm uninstall, pm clear, settings put or factory reset.
- Never disable anything in the "Never" table of DEBLOAT.md, nor
  com.google.android.tts, com.google.android.tungsten.setupwraith, com.sony.dtv.tvx,
  permissioncontroller, inputdevices, bluetooth or the only installed keyboard.
- Any package that is not on a community list goes in the "ask me" group.
- Before changing anything, save `pm list packages -e` to enabled-before.txt and
  write every command you run to debloat-log.txt, with its undo command.

Steps:
1. Connect with `adb connect <ip>:5555` and wait for me to accept the prompt on the TV.
2. List every enabled package with its app label. Group them as: safe to disable,
   ask me, keep. Use the community lists in Lil Launcher's DEBLOAT.md
   (github.com/thevrus/lil-launcher) and Universal Android Debloater as references,
   and tell me where you are unsure instead of guessing.
3. Ask me about the "ask me" group in one short checklist: casting, voice remote,
   system updates, network drive, each preinstalled streaming app.
4. Show me the full safe list and wait for my yes. Then disable the approved packages
   one group at a time. After each group, reboot, run adb connect again (network adb
   drops on reboot), wait for `getprop sys.boot_completed` to return 1, and check that the home screen, the remote, Wi-Fi and
   my streaming apps still open. Take a screenshot with `adb exec-out screencap -p`
   to show me. If anything breaks, re-enable that group and tell me which package did it.
5. Never disable the current launcher until a replacement is installed and set with
   `cmd package set-home-activity`.
6. Finish with a summary table of what was disabled, what was kept and why, and the
   one-line command that restores everything.
```

## Restore everything

Re-enable every package that was on in your backup and is off now:

```sh
adb shell pm list packages -d > disabled-now.txt
grep -Fxf enabled-before.txt disabled-now.txt | tr -d '\r' | sed 's/package://' | while read p; do adb shell pm enable "$p" </dev/null; done
```

If the TV boots to a black screen, the launcher is the cause: `adb shell pm enable` the stock one (`com.google.android.tvlauncher` or `com.google.android.apps.tv.launcherx`). A factory reset restores every stock app but erases everything else on the TV.

## Thanks

The package ratings come from [Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation), [shield_optimizer](https://github.com/bryanroscoe/shield_optimizer) and [TVSlim](https://github.com/jolabs40/TVSlim). [tv-vibehack](https://github.com/Gathin23/tv-vibehack) showed how well this works with a coding assistant at the wheel.
