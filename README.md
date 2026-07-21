# PZ Perf Patch

Runtime performance patches for **Project Zomboid** (Build 42), built on
[ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)'s Java bytecode runtime-patching
framework. No gameplay changes — every patch either caches a lookup that's provably safe to
cache, skips redundant repeated work, or turns on existing-but-disabled engine parallelization
flags. Same simulation results, less CPU spent getting there.

Built and tested with a Steam Deck specifically in mind (weaker cores show wasted CPU work more
clearly), but it benefits any PC.

## What it fixes

The single biggest fix: `IsoGameCharacter.getStateMachineComponent()` was found (via a live Java
Flight Recorder profile, not guesswork) to spend **20.9% of all sampled CPU time** re-resolving an
already-known value through an uncached `HashMap` lookup, on every single call, from at least five
different per-tick call sites. That cost scales directly with zombie/character count — exactly why
Project Zomboid bogs down in large hordes. This patch caches the resolved value after the first
lookup.

13 more patches on top of that, found via decompiled-bytecode analysis and live stress testing —
more redundant per-tick lookups, and 4 of Project Zomboid's own built-in (but shipped-disabled)
`DebugOptions.Threading.*` parallelization flags, force-enabled after confirming they're safe (2 of
the 6 built-in flags are permanently *excluded* here — they're real, confirmed vanilla concurrency
bugs, not just untested). Full list and technical detail on each: [`docs/PATCHES.md`](docs/PATCHES.md).

## Measured results

| Test | Vanilla | Patched | Change |
|---|---|---|---|
| Zombie horde, per-zombie frame time (desktop) | ~15.8 ms/zombie | ~12.8 ms/zombie | **~19% less** |
| Same test, on real Steam Deck hardware (CPU% per 100 zombies) | ~13.6% | ~9.2% | **~32% less** |
| Vehicle driving (softer signal, FPS-capped, uncontrolled density) | ~26.0% avg CPU | ~23.0% avg CPU | ~12% less |

Full methodology, caveats, and an honest negative result (item/container count wasn't a bottleneck
at the scale tested) are in [`docs/PATCHES.md`](docs/PATCHES.md).

## Requirements

- **Project Zomboid**, Build 42 or later
- **[ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)** — required dependency, install this
  first (see below)

## 1. Install ZombieBuddy (prerequisite)

ZombieBuddy is a separate, third-party project this mod depends on for Java bytecode patching. Get
it from the [Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) or
[GitHub](https://github.com/zed-0xff/ZombieBuddy), then:

**Windows** — easiest path is the automated installer: download
[`ZombieBuddyInstaller.exe`](https://github.com/zed-0xff/ZombieBuddy/releases/tag/windows_installer)
and run it (handles copying files and setting launch options for you).

**macOS / Linux (including Steam Deck)** — there's no automated installer on these platforms, so
this is manual either way. **Subscribing on the Workshop only gets you the mod's files — it does
NOT copy the jar into the game directory or set the launch option for you.** Those two steps below
are still required no matter where the files came from:

1. Get the files, either way:
   - **Steam Workshop**: subscribe [here](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853).
     Steam downloads it to its workshop content folder, typically
     `~/.local/share/Steam/steamapps/workshop/content/108600/3619862853/` — **not**
     `~/Zomboid/mods/`. You'll dig `libs/ZombieBuddy.jar` out of that folder in step 2.
   - **GitHub**: download the release and extract it into `~/Zomboid/mods/ZombieBuddy/` yourself.
2. Copy `ZombieBuddy.jar` from that mod's `libs/` folder (wherever it landed in step 1) into the
   game's own install directory:
   - macOS: `~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/`
   - Linux / Steam Deck: `~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/` (or
     `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/`, depending on your
     Steam install)
3. In Steam, open Project Zomboid's **Properties → Launch Options** and set:
   ```
   -javaagent:ZombieBuddy.jar --
   ```
   (the trailing `--` is mandatory, don't drop it)
4. Enable **ZombieBuddy** in Project Zomboid's in-game mod manager (this is what makes the game
   recognize the Workshop/GitHub copy as the "ZombieBuddy" mod our `require=` line checks for — a
   separate thing from the raw jar you copied in step 2).

Full official instructions (security notes, the Java-mod-approval prompt, Windows-specific
details): ZombieBuddy's own [Installation Guide](https://github.com/zed-0xff/ZombieBuddy/blob/main/doc/Installation.md).

**Heads up**: Java mods loaded through ZombieBuddy run with full, unrestricted system access
(unlike Lua mods, which are sandboxed). ZombieBuddy will show you an approval prompt the first time
it sees this mod's jar (and again if it ever changes) before loading it — nothing loads silently.
Only install Java mods from sources you trust.

## 2. Install PZ Perf Patch

1. Download the latest release zip from this repo's [Releases](../../releases) page.
2. Extract it so you end up with `~/Zomboid/mods/PZPerfPatch/42/...` (the zip already contains the
   `PZPerfPatch/42/` folder structure — just extract into `~/Zomboid/mods/`).
3. Enable **PZ Perf Patch** (alongside ZombieBuddy) in the in-game mod manager.
4. Launch a save. Console output (`~/Zomboid/console.txt`) will show `[PZPerfPatch] loaded -
   caching getStateMachineComponent()` on a successful load.

### Installing on Steam Deck specifically

Full, self-contained walkthrough — you don't need to jump back to the sections above. Steam Deck
runs Project Zomboid's native Linux build (no Proton involved), so this is the standard Linux
install path.

1. **Switch to Desktop Mode** (Steam button → Power → Switch to Desktop). You need a file manager
   or terminal, and the Steam Properties dialog, neither of which are available in Gaming Mode.

2. **Install ZombieBuddy first** (required dependency). Subscribing on the Workshop only fetches
   the files — it does **not** copy the jar into the game directory or set the launch option for
   you, so steps 2.2–2.4 below are still required either way:
   1. Subscribe to ZombieBuddy on the
      [Steam Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853). Steam
      downloads it to its workshop content cache, typically
      `~/.local/share/Steam/steamapps/workshop/content/108600/3619862853/` — **not**
      `~/Zomboid/mods/`. (Or skip the Workshop and download the release from
      [GitHub](https://github.com/zed-0xff/ZombieBuddy) instead, extracted into
      `~/Zomboid/mods/ZombieBuddy/` yourself — either source works the same from here on.)
   2. Open the **Dolphin** file manager (or a terminal) and locate `ZombieBuddy.jar` inside that
      mod's `libs/` folder (wherever it landed above).
   3. Copy `ZombieBuddy.jar` into the game's own install directory:
      ```
      ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/
      ```
      (this is the folder that contains `ProjectZomboid64` and `projectzomboid.jar` — if you don't
      find it there, try `~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/` instead,
      the exact path depends on how Steam was set up on your Deck).
   4. In Steam (Desktop Mode), right-click **Project Zomboid → Properties → Launch Options**, and
      set:
      ```
      -javaagent:ZombieBuddy.jar --
      ```
      The trailing `--` is mandatory — don't drop it.
   5. Launch Project Zomboid once, open the in-game mod manager, and enable **ZombieBuddy**.
   6. The first time a Java mod jar loads, ZombieBuddy shows an approval prompt (mod id, jar path,
      SHA-256 fingerprint) — nothing loads until you approve it. This is expected; it happens once
      per jar (and again only if the jar's contents change).

3. **Install PZ Perf Patch**:
   1. Still in Desktop Mode, download the latest release zip from this repo's
      [Releases](../../releases) page (via the Deck's browser, or `scp`/`rsync` it over from
      another machine).
   2. Extract it directly into `~/Zomboid/mods/` — the zip already contains the
      `PZPerfPatch/42/...` folder structure, so you should end up with
      `~/Zomboid/mods/PZPerfPatch/42/mod.info` etc.
   3. Launch Project Zomboid, open the mod manager, and enable **PZ Perf Patch** (alongside
      ZombieBuddy, which must also stay enabled — it's a hard dependency).
   4. Approve the jar when ZombieBuddy's prompt appears (same as step 2.6 above).

4. **Confirm it loaded**: check `~/Zomboid/console.txt` after loading a save — you should see
   `[PZPerfPatch] loaded - caching getStateMachineComponent()`.

5. Switch back to Gaming Mode whenever you're done — nothing above needs Desktop Mode again unless
   you're changing launch options or updating the mod later.

This exact setup was verified live on real Steam Deck hardware — see the benchmark table above.
The Deck's bundled JVM matched the desktop machine's version during testing, so the same jar works
unmodified; no Deck-specific rebuild is needed.

## Configuration

Every patch is individually toggleable (for troubleshooting, or to isolate one fix at a time) via
a plain text file the mod reads at startup: `~/Zomboid/PZPerfPatch/fixes.ini`. It doesn't exist by
default — all patches are enabled unless you create it. One line per override:

```
Patch_CacheStateMachineComponent=false
Patch_ThreadAmbient=false
```

Anything not listed stays at its default (enabled). See [`docs/PATCHES.md`](docs/PATCHES.md) for
every patch's exact toggle name.

**Verbose logging is off by default** — normal play doesn't write anything extra to
`console.txt` or to disk. If you're troubleshooting (or I ask you to grab logs), add this line to
`fixes.ini` and relaunch:

```
Patch_VerboseLogging=true
```

That turns on the FPS/hiccup/cache-hit-rate line every 5 seconds in `console.txt`, plus a
per-run manifest of exactly which patches and threading flags were active, archived to
`~/Zomboid/PZPerfPatch/runs/<timestamp>/`.

## What's NOT in this build

The development version of this mod also includes test-only patches used to run unattended,
automated stress tests (a keybind-gated god-mode/invulnerability/noclip/invisibility toggle used
only so a test character could survive a horde unattended, plus other test-harness plumbing).
**None of that is in this public release.** This build is purely the 14 performance patches listed
in [`docs/PATCHES.md`](docs/PATCHES.md) — nothing else.

## Building from source

Requires a local Project Zomboid install with ZombieBuddy set up (the build reads
`ZombieBuddy.jar`/`projectzomboid.jar` from the game's install directory as compile-time-only
dependencies) and Gradle:

```
cd 42/java
gradle build
```

Output jar lands at `42/media/java/PZPerfPatch.jar`. Adjust `GAME_JAVA_DIR` in `build.gradle` and
`org.gradle.java.home` in `gradle.properties` for your machine if paths differ.

## Credits & caveats

- Built on [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) by Zed (zed-0xff) — a separate
  project, used here as a dependency, not redistributed.
- All benchmark numbers are from a small number of test runs (one or two per scenario) on the same
  save/machine each time — real measured data, not a large-sample statistical study. Full caveats
  in [`docs/PATCHES.md`](docs/PATCHES.md).
- Confirmed against Project Zomboid Build 42.19.0 — behavior may drift on future updates.
- No formal license attached to this repo's code. Feel free to read/fork/reference it for your own
  ZombieBuddy patches.
