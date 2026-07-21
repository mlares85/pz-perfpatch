package com.mlares.pzperfpatch;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.IsoZombie;

/**
 * BaseZombieSoundManager.addCharacter(IsoZombie) does an O(n) ArrayList.contains() before
 * inserting, every time a zombie makes a thump/footstep-type sound -- confirmed via
 * docs/decompile-findings/zombie-characters.md Finding 6. Its own sibling classes
 * (ZombieVocalsManager/AnimalVocalsManager) already solve the identical problem correctly with a
 * HashSet, just not applied here -- the "characters" list accumulates across every noisy zombie in
 * one tick before being cleared, so at horde density this is O(n^2) within a single tick.
 *
 * Can't add a field to the already-loaded class, so an external per-instance tracked Set stands in
 * for a real membership set. Self-healing without needing to also patch update() (which clears the
 * real "characters" list every tick): if the real list is observed empty at entry, that means a
 * clear() must have happened since our last call, so our tracked set is reset too. This converts a
 * guaranteed O(n) scan on every call into an O(1) skip for a zombie already added this tick (the
 * repeated-noise-events case the finding flags), while a genuinely new entry this tick still does
 * one real scan against a list that's usually still short at that point.
 */
@Patch(className = "zombie.characters.BaseZombieSoundManager", methodName = "addCharacter", warmUp = true)
public class Patch_FastZombieSoundManagerAddCharacter {
    private static final Map<Object, Set<IsoZombie>> TRACKED = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self, @Patch.Argument(0) IsoZombie chr,
            @Patch.Field(readOnly = true) ArrayList<IsoZombie> characters) {
        if (!PatchToggles.isEnabled("Patch_FastZombieSoundManagerAddCharacter")) {
            return false;
        }
        Set<IsoZombie> tracked = TRACKED.computeIfAbsent(self, k -> ConcurrentHashMap.newKeySet());
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
