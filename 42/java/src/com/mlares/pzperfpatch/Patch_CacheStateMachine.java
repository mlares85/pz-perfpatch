package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.ai.StateMachine;

/**
 * IsoGameCharacter.getStateMachine() is a one-line delegate to
 * getStateMachineComponent().getStateMachine() -- no logic of its own, but it still pays the
 * ConcurrentHashMap lookup cost of the (already-cached) component on every single call,
 * across 51+ call sites (isCurrentState(), IsoZombie.update(), CombatManager, StatePacket,
 * network AI sync, etc. -- some call it twice in one expression). StateMachineComponent's
 * stateMachine field is final, assigned once in its constructor, so the result is exactly
 * as stable as the component reference itself (already proven stable under PZ's object
 * pooling via the getStateMachineComponent fix).
 *
 * Same external-cache pattern as that fix, for the same reason: retransformation of an
 * already-loaded class can't add a field, so this is a second cache, not a new field on
 * IsoGameCharacter.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getStateMachine", warmUp = true)
public class Patch_CacheStateMachine {
    private static final Map<Object, Object> CACHE = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self) {
        if (!PatchToggles.isEnabled("Patch_CacheStateMachine")) {
            return false;
        }
        boolean hit = CACHE.get(self) != null;
        Stats.increment(hit ? "getStateMachine.hit" : "getStateMachine.miss");
        return hit;
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) StateMachine result) {
        Object cached = CACHE.get(self);
        if (cached != null) {
            result = (StateMachine) cached;
        } else if (result != null) {
            CACHE.put(self, result);
        }
    }
}
