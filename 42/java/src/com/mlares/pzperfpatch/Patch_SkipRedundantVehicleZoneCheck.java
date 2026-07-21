package com.mlares.pzperfpatch;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.iso.zones.VehicleZone;

/**
 * IsoMetaGrid.checkVehiclesZones() does an O(n^2) full rescan of the *entire session's*
 * accumulated vehiclesZones list on every call, deduping by (x,y,w,h). It's called once per
 * lot loaded (media/lua/server/metazones/metazoneHandler.lua:153, unconditional in
 * doMapZones()), regardless of whether that lot registered any vehicle zones at all -- most
 * lots register zero, so most calls are a full O(n^2) rescan for a guaranteed no-op. Measured
 * live: 88 samples in a single ~1-second window during active streaming, essentially the
 * whole main thread budget for that second. Confirmed independently by two separate agent
 * investigations.
 *
 * vehiclesZones is populated only by registerVehiclesZone() (always ArrayList.add, never
 * insert/reorder) and only cleared on world unload -- so between any two calls to
 * checkVehiclesZones(), the list only grows (aside from what checkVehiclesZones() itself
 * removes). That makes "list size unchanged since the last check" a sound signal that nothing
 * new needs deduping. Unlike the other two agent-found bugs this session that needed full
 * method delegation, this one is a genuine skip-and-cache case: no return value to fake, just
 * skip the whole void method body when we can prove it would be a no-op.
 */
@Patch(className = "zombie.iso.IsoMetaGrid", methodName = "checkVehiclesZones", warmUp = true)
public class Patch_SkipRedundantVehicleZoneCheck {
    private static final Map<Object, Integer> LAST_CHECKED_SIZE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> RUN_START_NANOS = new ThreadLocal<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self, @Patch.Field(readOnly = true) ArrayList<VehicleZone> vehiclesZones) {
        if (!PatchToggles.isEnabled("Patch_SkipRedundantVehicleZoneCheck")) {
            return false;
        }
        Integer lastSize = LAST_CHECKED_SIZE.get(self);
        boolean skip = lastSize != null && lastSize == vehiclesZones.size();
        Stats.increment(skip ? "checkVehiclesZones.skipped" : "checkVehiclesZones.ran");
        if (!skip) {
            // Only touched on the much rarer "actually runs" path (once per lot that has
            // vehicle zones, not once per lot loaded) -- not a per-call-on-every-lot cost,
            // so a ThreadLocal here doesn't repeat the earlier per-call-handoff pitfall.
            RUN_START_NANOS.set(System.nanoTime());
        }
        return skip;
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Field(readOnly = true) ArrayList<VehicleZone> vehiclesZones) {
        LAST_CHECKED_SIZE.put(self, vehiclesZones.size());
        Long start = RUN_START_NANOS.get();
        if (start != null) {
            RUN_START_NANOS.remove();
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            if (ms > 1.0) {
                System.out.println("[PZPerfPatch] checkVehiclesZones ran: " + String.format("%.2f", ms)
                        + "ms (list size " + vehiclesZones.size() + ")");
            }
        }
    }
}
