package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.IsoZombie;

/**
 * IsoGameCharacter.checkSCBADrain() unconditionally scans getWornItems() looking for SCBA gear,
 * every tick, for every character -- including every zombie in a horde, none of which can ever
 * wear equippable SCBA gear. Confirmed via docs/decompile-findings/zombie-characters.md Finding 3.
 *
 * The finding's ideal fix (a maintained "hasSCBAEquipped" flag updated at worn-item-change mutation
 * points) would need hooking clothing-change methods too -- multi-site invalidation, the higher-risk
 * category this project avoids when a simpler safe option exists. Zombies are the actual
 * high-volume case the finding calls out ("every zombie in a horde"), and zombies can never
 * meaningfully reach this code path's real branch, so skipping entirely for
 * self instanceof IsoZombie captures the bulk of the value with a single, self-contained,
 * whole-method entry check -- no external cache, no invalidation, nothing to keep in sync.
 * Players/NPCs (which legitimately can wear SCBA gear) are untouched and still run the real check.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "checkSCBADrain", warmUp = true)
public class Patch_SkipSCBADrainForZombies {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self) {
        if (!PatchToggles.isEnabled("Patch_SkipSCBADrainForZombies")) {
            return false;
        }
        return self instanceof IsoZombie;
    }
}
