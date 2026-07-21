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

**macOS / Linux (including Steam Deck)** — manual install:
1. Subscribe to ZombieBuddy on the Steam Workshop (or extract the GitHub release into
   `~/Zomboid/mods/ZombieBuddy/`).
2. Copy `ZombieBuddy.jar` from that mod's `libs/` folder into the game's own install directory:
   - macOS: `~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/`
   - Linux / Steam Deck: `~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/` (or
     `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/`, depending on your
     Steam install)
3. In Steam, open Project Zomboid's **Properties → Launch Options** and set:
   ```
   -javaagent:ZombieBuddy.jar --
   ```
   (the trailing `--` is mandatory, don't drop it)
4. Enable **ZombieBuddy** in Project Zomboid's in-game mod manager.

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

1. Switch to **Desktop Mode** (needed for file access + the Steam launch-options dialog).
2. Do the ZombieBuddy install above first — same steps, same `-javaagent:ZombieBuddy.jar --`
   launch option, set via Steam's Properties dialog in Desktop Mode.
3. Open a file manager (Dolphin) or terminal, navigate to `~/Zomboid/mods/`, and extract this
   mod's release zip there.
4. Switch back to Gaming Mode (or stay in Desktop Mode) and launch Project Zomboid normally —
   Steam Deck runs PZ's native Linux build, no Proton-specific steps needed.
5. Enable both **ZombieBuddy** and **PZ Perf Patch** in the in-game mod manager, same as any other
   mod.
6. This was verified live on real Steam Deck hardware — see the benchmark table above. The bundled
   JVM on the Deck matches the desktop version tested against, so the same jar works unmodified.

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

Diagnostics are also logged automatically to `~/Zomboid/console.txt` (FPS/hiccup/cache-hit-rate
line every 5 seconds) and `~/Zomboid/PZPerfPatch/runs/<timestamp>/` (a manifest of exactly which
patches and threading flags were active for that run).

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
