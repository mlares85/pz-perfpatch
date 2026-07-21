package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.component.StateMachineComponent;

/**
 * IsoGameCharacter.getStateMachineComponent() does an uncached lookup
 * (ECSEntity.getECSComponent() -> ECSComponent.getECSClass() -> HashMap.getNode())
 * on every call, measured via JFR at ~20.9% of CPU samples under heavy zombie load.
 * The resolved component is stable for a given character instance across its lifetime,
 * including PZ's object-pooling/reuse (resetForReuse() does not replace the component).
 *
 * Cached externally rather than as an added field, since retransformation of an
 * already-loaded class cannot change its field layout.
 *
 * ConcurrentHashMap, not Collections.synchronizedMap(WeakHashMap): a first version used
 * a synchronized WeakHashMap, and profiling at ~1900 concurrent characters showed
 * Collections$SynchronizedMap.get() (lock contention) and WeakHashMap's internal
 * ReferenceQueue.poll() (weak-reference cleanup) had together become the single biggest
 * cost in the whole profile -- the fix scaling worse than the bug it replaced.
 * ConcurrentHashMap is lock-striped (no single-lock contention) and has no weak-reference
 * housekeeping. Trade-off: entries are never auto-removed, so this leaks one entry per
 * distinct character for the process lifetime -- acceptable here since even extreme
 * stress tests this session only produced a few thousand distinct characters, bounded by
 * realistic max concurrent population, not a memory concern in practice. Relies on
 * IsoGameCharacter not overriding equals()/hashCode() (i.e. plain identity semantics,
 * standard for a game entity class) -- if that assumption is ever wrong, this cache would
 * need an IdentityHashMap-based concurrent alternative instead.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "getStateMachineComponent", warmUp = true)
public class Patch_CacheStateMachineComponent {
    private static final Map<Object, Object> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();

    // No ThreadLocal handoff: OnExit re-queries CACHE by the same key instead of
    // OnEnter passing the value through a ThreadLocal. A first version used a
    // ThreadLocal here, and profiling showed ThreadLocalMap.set/get/remove
    // (including its stale-entry expunge housekeeping) had become the single
    // biggest cost in the whole profile at ~450M+ calls/session -- more expensive
    // than the bug it replaced. The cache entry can't change between OnEnter and
    // OnExit of the same call, so re-reading it is both simpler and cheaper.
    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self) {
        if (!PatchToggles.isEnabled("Patch_CacheStateMachineComponent")) {
            return false;
        }
        boolean hit = CACHE.get(self) != null;
        if (hit) {
            long hits = HITS.incrementAndGet();
            if (hits % 50000 == 0) {
                long misses = MISSES.get();
                double rate = 100.0 * hits / (hits + misses);
                System.out.println("[PZPerfPatch] cache: " + hits + " hits, " + misses + " misses, " + rate + "% hit rate");
            }
        } else {
            long misses = MISSES.incrementAndGet();
            System.out.println("[PZPerfPatch] cache MISS #" + misses);
        }
        return hit; // skip the original (expensive) method body only on a hit
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) StateMachineComponent result) {
        Object cached = CACHE.get(self);
        if (cached != null) {
            result = (StateMachineComponent) cached;
        } else if (result != null) {
            CACHE.put(self, result);
        }
    }

    // Exposed for Patch_FpsLogger's consolidated summary -- a snapshot read on the 5-second
    // timer, not a per-call increment, so this adds zero cost to the hot path above.
    public static long getHits() {
        return HITS.get();
    }

    public static long getMisses() {
        return MISSES.get();
    }
}
