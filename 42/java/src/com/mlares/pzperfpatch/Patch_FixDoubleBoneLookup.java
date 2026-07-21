package com.mlares.pzperfpatch;

import java.util.HashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * AnimationPlayer.getSkinningBoneIndex(String, int) does containsKey() then get() on the
 * same key -- two full hash lookups where one would do. Found via JFR: this method (and
 * the identical-shaped inline loop in SkinTransformData.checkBoneMap) account for ~25% of
 * a persistent HashMap.getNode hotspot at horde scale. boneIndices itself
 * (AnimationPlayer.getSkinningBoneIndices()) is stable -- built once in SkinningData's
 * constructor, never reassigned -- so this isn't a caching opportunity like the other
 * fixes, just removing a redundant lookup. Whole self-contained method (unlike the
 * IsoPlayer.updateLOS()/Vector.indexOf finding from the same investigation, which is
 * embedded mid-method and not safely patchable this way), so plain skip-and-substitute
 * applies cleanly.
 */
@Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "getSkinningBoneIndex", warmUp = true)
public class Patch_FixDoubleBoneLookup {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        // always skip when enabled; OnExit always recomputes correctly with one lookup
        return PatchToggles.isEnabled("Patch_FixDoubleBoneLookup");
    }

    @Patch.OnExit
    public static void exit(@Patch.This AnimationPlayer self, @Patch.Argument(0) String boneName,
            @Patch.Argument(1) int defaultVal, @Patch.Return(readOnly = false) int result) {
        if (!PatchToggles.isEnabled("Patch_FixDoubleBoneLookup")) {
            return;
        }
        HashMap<String, Integer> boneIndices = self.getSkinningBoneIndices();
        Integer idx = boneIndices != null ? boneIndices.get(boneName) : null;
        result = idx != null ? idx : defaultVal;
    }
}
