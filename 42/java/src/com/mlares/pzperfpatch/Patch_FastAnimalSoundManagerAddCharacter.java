package com.mlares.pzperfpatch;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.animals.IsoAnimal;

/**
 * Same bug, same fix as Patch_FastZombieSoundManagerAddCharacter, on the animal-sound sibling
 * class -- see that patch's doc comment for the full rationale. Confirmed via
 * docs/decompile-findings/zombie-characters.md Finding 6 (both classes flagged together).
 */
@Patch(className = "zombie.characters.BaseAnimalSoundManager", methodName = "addCharacter", warmUp = true)
public class Patch_FastAnimalSoundManagerAddCharacter {
    private static final Map<Object, Set<IsoAnimal>> TRACKED = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self, @Patch.Argument(0) IsoAnimal chr,
            @Patch.Field(readOnly = true) ArrayList<IsoAnimal> characters) {
        if (!PatchToggles.isEnabled("Patch_FastAnimalSoundManagerAddCharacter")) {
            return false;
        }
        Set<IsoAnimal> tracked = TRACKED.computeIfAbsent(self, k -> ConcurrentHashMap.newKeySet());
        if (characters.isEmpty()) {
            tracked.clear();
        }
        if (tracked.contains(chr)) {
            return true;
        }
        tracked.add(chr);
        return false;
    }
}
