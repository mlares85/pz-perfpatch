package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.iso.IsoWorld;

/**
 * GameTime.getMultiplier() is a branchy, non-trivial computation (checks GameServer.server/
 * GameClient.fastForward/IsoPlayer.allPlayersAsleep(), reads 4 instance fields, calls
 * getSlomoMultiplier(), does several float multiplications) -- confirmed via
 * docs/decompile-findings/zombie-characters.md Finding 1: called 78 times per character per tick
 * inside BodyPart.DamageUpdate()/BodyDamage.Update() alone, recomputing the identical number every
 * time within that single tick (nothing in either method mutates anything the multiplier reads).
 *
 * Cached per-frame rather than per-call: IsoWorld.getFrameNo() (confirmed via javap, a real,
 * already-incrementing frame counter) is read at entry -- if unchanged since the last call, the
 * cached value from earlier this same frame is reused; otherwise the original method runs and its
 * result is cached against the new frame number. This is intentionally NOT invalidated by tracking
 * every individual field this method reads (this.multiplier, this.fpsMultiplier,
 * this.multiplierBias, this.perObjectMultiplier, sleep state, fast-forward state) -- those change
 * rarely and tracking each mutation site would be exactly the kind of multi-site invalidation this
 * project's patches deliberately avoid. A per-frame cache is correct because
 * MovingObjectUpdateScheduler.update() already resets perObjectMultiplier once per frame (confirmed
 * this session while mapping the game's threading architecture), meaning nothing meaningfully
 * changes this value more than once per frame in practice.
 */
@Patch(className = "zombie.GameTime", methodName = "getMultiplier", warmUp = true)
public class Patch_CacheGameTimeMultiplier {
    private static volatile int cachedFrame = Integer.MIN_VALUE;
    private static volatile float cachedValue;

    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        if (!PatchToggles.isEnabled("Patch_CacheGameTimeMultiplier")) {
            return false;
        }
        IsoWorld world = IsoWorld.instance;
        if (world == null) {
            return false;
        }
        return world.getFrameNo() == cachedFrame;
    }

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) float result) {
        IsoWorld world = IsoWorld.instance;
        if (world == null) {
            return;
        }
        int frame = world.getFrameNo();
        if (frame == cachedFrame) {
            result = cachedValue;
        } else {
            cachedValue = result;
            cachedFrame = frame;
        }
    }
}
