package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.debug.BooleanDebugOption;
import zombie.debug.DebugOptions;

/**
 * PZ already ships a real parallel-execution layer -- a shared ForkJoinPool sized to cores-1,
 * with 6 DebugOptions.Threading.* flags wired to CompletableFuture.runAsync for
 * animation/world-update/lighting/grid-stacks/sound/ambient audio (confirmed via
 * docs/decompile-findings/threading-architecture-map.md) -- but every one defaults to false.
 *
 * A live 150->210-zombie stress test (2026-07-17, see docs/pz-performance-report.md
 * "Threading-flag live experiment") found the root cause of why: enabling all 6 produced ~2,340
 * "Lua code called from the wrong thread" errors, traced to Threading.Animation specifically --
 * MovingObjectUpdateScheduler.postupdate()'s parallelized pass reaches
 * IsoGameCharacter.postUpdateAnimating() -> applyDeltas() -> getCurrentTimedActionDeltaModifiers(),
 * which calls into Lua from a ForkJoinPool worker thread, and Kahlua correctly refuses any call
 * not from MainThread. Disabling just Threading.Animation (keeping the other 5 on) dropped the
 * error count to zero across a full second run at the same density.
 *
 * This patch force-enables 4 flags (World, Lighting, RecalculateGridStacks, Ambient) -- individually,
 * via PatchToggles, exactly like every other fix in this project, since even a clean stress test is
 * one density on one machine, not a blanket green light. Threading.Animation is deliberately never
 * touched here (known broken -- see below). Threading.Sound is ALSO deliberately excluded
 * (2026-07-17, live on the Deck, confirmed after fixing the hook-timing bug below): a real
 * 200-zombie stress test with the hook actually working threw
 * `ArrayIndexOutOfBoundsException: Index 508 out of bounds for length 256` from
 * `GameWindow.java:325`'s `threadSound`-gated `CompletableFuture.runAsync` block --
 * `SoundManager.Update()` -> `AmbientStreamManager.update()` -> `NearestWalls.ClosestWallDistance()`
 * -> `getNearestWallOnSameChunk()` (`NearestWalls.java:145`). Same failure class as
 * Threading.Animation (shared/scratch state unsafe to touch from a background thread, per
 * `docs/decompile-findings/simulation-thread-safety-audit.md`'s prediction), just a different
 * subsystem and a different symptom (array bounds, not a Lua cross-thread call) -- not a fluke to
 * retry, a real vanilla concurrency bug. Non-fatal in that one run (a single caught exception, game
 * kept running), but not something to force on for anyone who didn't opt in.
 * Threading.ModelSlotInit is also left alone -- it already defaults to true in vanilla
 * (zombie.debug.DebugOptions.java: `newOption("Threading.ModelSlotInit", true)`) and was never part
 * of any stress test, so there's nothing to force and no basis to touch it.
 *
 * Originally hooked on DebugOptions.init()'s exit -- confirmed broken (2026-07-17, live on the
 * Deck): diagnostic logging added to that version never printed at all, even though ZombieBuddy's
 * own log claimed the patch applied successfully ("Applied advice", "Transformed", "warming up
 * class"), and RunManifest's independent DebugOptions.Threading.* snapshot (taken later, from
 * Patch_FpsLogger's first frame) showed all 5 flags still false. Root cause: DebugOptions.instance.
 * init() is called from GameWindow.java:638, itself deep inside the SAME GameWindow.init() call
 * that later triggers this mod's own loading/patching -- by the time our retransformation of
 * DebugOptions attaches, the one and only real init() call has already run. This is the exact same
 * "runs before mod loading" trap RunManifest.ensureInitialized() already documents working around
 * by hooking Patch_FpsLogger's first frame instead of Main.java's early boot. Applying the same
 * fix here: hooked on Core.EndFrameUI (same proven-firing target as Patch_FpsLogger/
 * Patch_Invulnerable), gated to run exactly once. Nothing else in the engine re-reads or resets
 * these flags after DebugOptions.init() (confirmed: DebugOptions.load() has exactly one caller,
 * init(), which itself has exactly one real call site for singleplayer), so forcing them on this
 * much later is safe -- there's no gameplay/threading dispatch this early relative to a save
 * actually loading.
 */
@Patch(className = "zombie.core.Core", methodName = "EndFrameUI", warmUp = true)
public class Patch_EnableThreadingFlags {
    private static volatile boolean applied = false;

    @Patch.OnEnter
    public static void enter() {
        if (applied) {
            return;
        }
        applied = true;
        DebugOptions d = DebugOptions.instance;
        if (d == null) {
            applied = false; // retry next frame -- DebugOptions.instance not ready yet
            return;
        }
        applyIfEnabled("Patch_ThreadWorld", d.threadWorld);
        applyIfEnabled("Patch_ThreadLighting", d.threadLighting);
        applyIfEnabled("Patch_ThreadGridStacks", d.threadGridStacks);
        applyIfEnabled("Patch_ThreadAmbient", d.threadAmbient);
        // Patch_ThreadSound deliberately NOT applied -- confirmed real crash (ArrayIndexOutOfBoundsException
        // in NearestWalls, see class doc comment). Not toggleable back on via fixes.ini; would need a
        // code change here to re-add it, so a stray fixes.ini line can't silently re-enable a known-broken flag.
    }

    private static void applyIfEnabled(String toggleName, BooleanDebugOption option) {
        boolean enabled = PatchToggles.isEnabled(toggleName);
        if (enabled) {
            option.setValue(true);
        }
        if (PatchToggles.isEnabledDefaultOff("Patch_VerboseLogging")) {
            System.out.println("[PZPerfPatch] " + toggleName + ": toggleEnabled=" + enabled
                    + " optionNowReads=" + option.getValue());
        }
    }
}
