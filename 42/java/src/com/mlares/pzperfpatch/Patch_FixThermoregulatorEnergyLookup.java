package com.mlares.pzperfpatch;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.CharacterStat;
import zombie.characters.Stats;

/**
 * Thermoregulator.getEnergy() calls stats.get(CharacterStat.HUNGER) three times and
 * stats.get(CharacterStat.FATIGUE) twice within one call -- five HashMap-backed lookups
 * (Stats.get() is a getOrDefault + Float unbox, not a raw field read) where two locals would do.
 * Confirmed via docs/decompile-findings/zombie-characters.md Finding 5, called from
 * Thermoregulator.update() every tick for every live player/NPC.
 *
 * Pure function of two stat values, no branching, no side effects -- a full-method replacement
 * reproducing the exact same formula is low-risk here (unlike most force-override patches in this
 * project, which stand in for logic with real branches/side effects to get wrong). Always skips the
 * original body and recomputes with one lookup per stat instead of three/two.
 */
@Patch(className = "zombie.characters.BodyDamage.Thermoregulator", methodName = "getEnergy", warmUp = true)
public class Patch_FixThermoregulatorEnergyLookup {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        return PatchToggles.isEnabled("Patch_FixThermoregulatorEnergyLookup");
    }

    @Patch.OnExit
    public static void exit(@Patch.Field(readOnly = true) Stats stats,
            @Patch.Return(readOnly = false) float result) {
        if (!PatchToggles.isEnabled("Patch_FixThermoregulatorEnergyLookup")) {
            return;
        }
        float hunger = stats.get(CharacterStat.HUNGER);
        float fatigue = stats.get(CharacterStat.FATIGUE);
        float h = 1.0f - ((0.4f * hunger) + ((0.6f * hunger) * hunger));
        float f = 1.0f - ((0.4f * fatigue) + ((0.6f * fatigue) * fatigue));
        result = (0.6f * h) + (0.4f * f);
    }
}
