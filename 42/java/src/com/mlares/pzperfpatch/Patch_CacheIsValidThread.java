package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * GameProfiler.isValidThread() scans a 2-element ArrayList (fixed at class-init, "main" and
 * "MainThread", never mutated) on every performance-probe start/end -- wrapping instrumentation
 * used across 28+ files, including directly around IsoZombie.update(). A thread's name never
 * changes over its lifetime, so this is a pure function of Thread.currentThread(), cacheable
 * per-thread forever. External ConcurrentHashMap keyed by Thread, same pattern as the other
 * fixes -- not a per-call ThreadLocal handoff, so it doesn't repeat the ThreadLocal pitfall
 * (that was specifically about OnEnter passing a value to OnExit via set/get/remove on every
 * call; this just reads a thread-keyed cache that's written once per thread, ever).
 */
@Patch(className = "zombie.GameProfiler", methodName = "isValidThread", warmUp = true)
public class Patch_CacheIsValidThread {
    private static final Map<Thread, Boolean> CACHE = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        if (!PatchToggles.isEnabled("Patch_CacheIsValidThread")) {
            return false;
        }
        return CACHE.containsKey(Thread.currentThread());
    }

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) boolean result) {
        Thread t = Thread.currentThread();
        Boolean cached = CACHE.get(t);
        if (cached != null) {
            result = cached;
        } else {
            CACHE.put(t, result);
        }
    }
}
