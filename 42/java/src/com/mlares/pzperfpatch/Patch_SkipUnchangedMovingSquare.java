package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.iso.IsoGridSquare;

/**
 * IsoMovingObject.setMovingSquare(IsoGridSquare) always pays an O(n) contains() scan on the target
 * square's movingObjects list before adding, even when newMovingSquare == this.movingSq (the
 * overwhelmingly common case -- an object standing still or moving within the same square
 * tick-to-tick) -- confirmed via docs/decompile-findings/zombie-iso.md Finding 11. Called every
 * tick for every moving object (zombies, players, animals, vehicles) from the main per-tick
 * movement path.
 *
 * Whole-method, skip-on-enter: the decision needs only the method's own argument and an
 * already-accessible instance field (this.movingSq), both available at entry -- no field write
 * needed, no invalidation to track, since we're not caching anything, just short-circuiting a
 * no-op call before it does real work.
 */
@Patch(className = "zombie.iso.IsoMovingObject", methodName = "setMovingSquare", warmUp = true)
public class Patch_SkipUnchangedMovingSquare {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self, @Patch.Argument(0) IsoGridSquare newMovingSquare,
            @Patch.Field(readOnly = true) IsoGridSquare movingSq) {
        if (!PatchToggles.isEnabled("Patch_SkipUnchangedMovingSquare")) {
            return false;
        }
        return newMovingSquare == movingSq;
    }
}
