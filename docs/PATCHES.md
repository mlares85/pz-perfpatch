# PZ Perf Patch — full technical writeup

Every patch in this build, what it targets, and the measured before/after numbers behind the
headline claims in the README.

## The finding that started this

A Java Flight Recorder profile captured live during a 300-second, ~1,200-zombie stress test found
that **20.9% of all sampled CPU time** (1,943 of 9,302 stack samples) traced to one call chain:

```
IsoGameCharacter.getStateMachineComponent()
  -> ECSEntity.getECSComponent() -> ECSEntity.tryGetECSComponent()
  -> ECSComponent.getECSClass() -> HashMap.getNode()
```

Every time any character/zombie needs to know its own AI state — which happens constantly, from at
least five different per-tick call sites — the game re-resolves the state-machine component via an
uncached, `Class`-keyed `HashMap` lookup instead of reusing an already-resolved reference. The cost
scales directly with character/zombie count.

**Fix**: cache the resolved component after the first lookup. Safe because the component is stable
for a character's entire lifetime — confirmed true even through the game's own zombie
object-pooling/reuse system (`resetForReuse()` resets the component in place, never replaces it).

## Benchmark results (measured, not estimated)

### Zombie-horde stress test — primary controlled test
Stationary character, horde spawned around them. Raw zombie counts differed slightly between the
two runs (vanilla ~1,400, patched ~1,740), so the fair comparison is frame-time normalized per
zombie:

| | Vanilla | Patched | Change |
|---|---|---|---|
| ms per zombie per frame | ~15.8 | ~12.8 | **~19% less simulation cost per zombie** |

### Same test, on real Steam Deck hardware
FPS is capped on this hardware, so CPU% is the more sensitive metric, normalized per 100 zombies:

| | Vanilla | Patched | Change |
|---|---|---|---|
| CPU% per 100 zombies | ~13.6% | ~9.2% | **~32% less CPU per zombie** |

Bigger win on the Deck than the desktop test machine — consistent with weaker cores making wasted
CPU work more visible.

### Vehicle driving comparison — softer signal, supporting evidence only
Same pinned spawn point and vehicle, 120s per leg. Zombie density wasn't controlled and FPS was
pinned at the Deck's 30fps cap the whole time (masks some of the difference):

| | Vanilla | Patched | Change |
|---|---|---|---|
| Avg process CPU | ~26.0% | ~23.0% | **~12% relative reduction** |

### Container/item stress test — honest negative result
18 containers / 2,700 items vs. none, same position, same zombie count (32). **No measurable
steady-state FPS difference** — both runs held a rock-solid 60fps. At this scale, item count isn't
a bottleneck; may surface at a much larger item count.

## Patch list (14 performance fixes)

Each patch checks its own toggle name in `~/Zomboid/PZPerfPatch/fixes.ini` first (see README) —
disabled means the original vanilla method runs completely untouched, not just "cache disabled."

### Character/ECS caches

| Patch | Target | Invariant assumed |
|---|---|---|
| `Patch_CacheStateMachineComponent` | `IsoGameCharacter.getStateMachineComponent()` | The resolved component is stable for a character's lifetime, including through pooling/reuse. The 20.9%-CPU fix above. |
| `Patch_CacheStateMachine` | `IsoGameCharacter.getStateMachine()` | One-line delegate to the cached component above; equally stable. |
| `Patch_CacheECSClass` | `ECSComponent.getECSClass(Class)` (static overload) | A `Class`'s superclass chain is fixed for the JVM's lifetime — unconditionally safe to memoize. |
| `Patch_CacheIsValidThread` | `GameProfiler.isValidThread()` | A thread's identity never changes over its lifetime — cacheable per-thread forever. |
| `Patch_FixDoubleBoneLookup` | `AnimationPlayer.getSkinningBoneIndex(String,int)` | Not a cache — collapses a `containsKey()`+`get()` double lookup into one. |

### Vehicle/world caches

| Patch | Target | Invariant assumed |
|---|---|---|
| `Patch_CacheVehiclePartById` | `BaseVehicle.getPartById(String)` | `this.parts` is populated once at vehicle creation/load and never mutated during simulation. |
| `Patch_SkipRedundantVehicleZoneCheck` | `IsoMetaGrid.checkVehiclesZones()` | The zone list only grows between calls (never mutated another way) — so "size unchanged since last check" is a sound no-op proof. |

### Found via decompiled-bytecode analysis

| Patch | Target | Invariant assumed |
|---|---|---|
| `Patch_CacheGameTimeMultiplier` | `GameTime.getMultiplier()` | Confirmed called 78x/character/tick inside damage/thermoregulation updates alone. Cached per-frame. |
| `Patch_SkipUnchangedMovingSquare` | `IsoMovingObject.setMovingSquare(IsoGridSquare)` | Skips an O(n) list scan on the overwhelmingly common case of not actually changing squares this tick. |
| `Patch_FastZombieSoundManagerAddCharacter` | `BaseZombieSoundManager.addCharacter(IsoZombie)` | The real list is cleared every tick by `update()`; an external tracked set self-heals whenever it observes that. |
| `Patch_FastAnimalSoundManagerAddCharacter` | `BaseAnimalSoundManager.addCharacter(IsoAnimal)` | Same pattern, sibling class. |
| `Patch_SkipSCBADrainForZombies` | `IsoGameCharacter.checkSCBADrain()` | Zombies can never wear SCBA gear — a permanent, safe skip for `IsoZombie` instances only. |
| `Patch_FixThermoregulatorEnergyLookup` | `BodyDamage.Thermoregulator.getEnergy()` | Pure function of two stat lookups called 5x where 2 would do; collapsed to 2. |

### Engine threading flags — vanilla features that ship disabled

| Patch | What it does |
|---|---|
| `Patch_EnableThreadingFlags` | Force-enables 4 of Project Zomboid's own 6 built-in `DebugOptions.Threading.*` parallelization flags: **World, Lighting, RecalculateGridStacks, Ambient** — individually toggleable (`Patch_ThreadWorld`, etc.). |

The other 2 of the 6 vanilla flags are **permanently excluded**, not just left toggled off — both
are confirmed real vanilla concurrency bugs found live during testing, not theoretical risks:
- `Threading.Animation` — its parallelized pass calls into Lua from a background `ForkJoinPool`
  thread; Kahlua (the Lua/Java bridge) correctly refuses any call not from the main thread. Threw
  ~2,340 "Lua code called from the wrong thread" errors under a 210-zombie stress test.
- `Threading.Sound` — threw `ArrayIndexOutOfBoundsException` from unsynchronized shared scratch
  state in `NearestWalls.getNearestWallOnSameChunk()`, caught live during a 200-zombie stress test.
  Non-fatal (one caught exception, game kept running) but a real bug, not force-enabled here.

## Toggle names reference (`~/Zomboid/PZPerfPatch/fixes.ini`)

```
Patch_CacheStateMachineComponent
Patch_CacheStateMachine
Patch_CacheECSClass
Patch_CacheIsValidThread
Patch_CacheVehiclePartById
Patch_SkipRedundantVehicleZoneCheck
Patch_CacheGameTimeMultiplier
Patch_SkipUnchangedMovingSquare
Patch_FastZombieSoundManagerAddCharacter
Patch_FastAnimalSoundManagerAddCharacter
Patch_SkipSCBADrainForZombies
Patch_FixThermoregulatorEnergyLookup
Patch_ThreadWorld
Patch_ThreadLighting
Patch_ThreadGridStacks
Patch_ThreadAmbient
```

(`Patch_FixDoubleBoneLookup` is a full-method replacement with no branch to gate, so it has no
separate toggle — same for `Patch_AutoSkipLoadingClick` and `Patch_FpsLogger`, which are QoL/
diagnostics, not performance fixes, and always run.)

## Bonus (non-performance) patches included

- `Patch_AutoSkipLoadingClick` — auto-dismisses the "click to continue" prompt after a save
  finishes loading, so you don't need a keyboard/mouse/controller press to get past it. Harmless,
  scoped to only that one loading-screen state.
- `Patch_FpsLogger` — logs an FPS/hiccup/cache-hit-rate summary to `~/Zomboid/console.txt` every 5
  seconds, plus an immediate line the instant any single frame takes >50ms. Purely observational.

## Honest caveats

- Every number above comes from a small number of test runs (one or two per scenario), same
  machine/save each time — real measured data, not a large-sample statistical study.
- The vehicle-driving number is the softest of the results (FPS-capped, zombie density
  uncontrolled) — treat it as supporting evidence, not a standalone claim.
- An earlier attempt to patch the game's jar directly (bypassing ZombieBuddy's runtime-patching
  approach) caused permanent, unrecoverable freezes under load and was abandoned — root cause was
  never fully confirmed. The ZombieBuddy-based approach used here has had zero freezes or crashes
  across all testing since switching to it.
- Confirmed against Build 42.19.0 (`steam/release`) — numbers and exact method signatures may drift
  on a future PZ update.

## Ideas investigated and deliberately NOT implemented

Documented so they aren't re-attempted blindly:
- `Stats`/`CharacterTraits` `Map<Enum,Boxed>` → `EnumMap` — infeasible via ZombieBuddy's
  retransform-an-already-loaded-class approach (can't add/retype a `private final` field).
- `IsoPlayer.checkSpottedPLayerTimer()` duplicate lookup — safe to patch, but dead code in
  singleplayer (its only caller short-circuits when `GameClient.client` is false).
- A whole-cell-scan spatial pre-filter for animal/LOS/combat scans — the safe cache-and-skip
  technique used everywhere else doesn't apply cleanly; would require reimplementing real branchy
  gameplay logic, where a mistake means subtly wrong behavior, not a crash.
- `IsoFireManager.Update()`'s per-tick lock — real, but skipping the whole method would also skip
  unrelated fire-flicker math that must run regardless.
- `IsoGameCharacter.updateLightInfo()` — already a complete no-op in singleplayer.
- `HitReactionNetworkAI` / `SafetySystemManager` — confirmed multiplayer-only code paths.
