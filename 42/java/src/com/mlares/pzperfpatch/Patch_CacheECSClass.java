package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.ecs.ECSComponent;

/**
 * ECSComponent.getECSClass(Class) walks a class's superclass chain from scratch on every call
 * (uncached) to resolve the registered ECS component type -- called from
 * ECSEntity.tryGetECSComponent()/getECSComponent()/hasECSComponent(), which sit underneath
 * getStateMachineComponent(), isNpc(), isAiming(), and other hot per-character accessors.
 * A Class's superclass chain is fixed for the JVM's lifetime -- this is unconditionally safe
 * to memoize forever, more so than the character/vehicle identity caches (no pooling/reuse
 * concern at all, since Class objects are never "reused" the way game entities are).
 * Binding @Patch.Argument(0) here forces ZombieBuddy's signature-aware matching, so this
 * targets the static single-arg overload specifically, not the no-arg instance method of the
 * same name (confirmed both exist via javap).
 */
@SuppressWarnings("unchecked")
@Patch(className = "zombie.characters.ecs.ECSComponent", methodName = "getECSClass", warmUp = true)
public class Patch_CacheECSClass {
    private static final Map<Class<?>, Class<?>> CACHE = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(0) Class<?> clazz) {
        if (!PatchToggles.isEnabled("Patch_CacheECSClass")) {
            return false;
        }
        boolean hit = clazz != null && CACHE.containsKey(clazz);
        Stats.increment(hit ? "getECSClass.hit" : "getECSClass.miss");
        return hit;
    }

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Class<?> clazz,
            @Patch.Return(readOnly = false) Class<? extends ECSComponent> result) {
        if (clazz == null) {
            return;
        }
        Class<?> cached = CACHE.get(clazz);
        if (cached != null) {
            result = (Class<? extends ECSComponent>) cached;
        } else if (result != null) {
            CACHE.put(clazz, result);
        }
    }
}
