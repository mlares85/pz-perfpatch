package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.vehicles.VehiclePart;

/**
 * BaseVehicle.getPartById(String) does an uncached linear scan over the vehicle's parts
 * list by string key, on the driving hot path: CarController.checkTire() does 8 scans/tick
 * (each tire's id looked up twice in the same condition), updateBulletStatsWheel() does 2
 * scans/wheel/tick with a wasteful string-concat allocation each time, updateLights() does
 * up to 12 scans/frame per visible vehicle. this.parts is populated once at vehicle
 * creation/load and never mutated during simulation -- same "stable collection, looked up
 * by string key, every tick, no cache" shape as the character/getStateMachineComponent bug.
 *
 * Cached per-vehicle (outer map keyed by vehicle identity, inner map by part id) rather
 * than as an added field, for the same reason as the character cache: retransformation of
 * an already-loaded class can't change its field layout. ConcurrentHashMap from the start
 * (not WeakHashMap+synchronizedMap, not ThreadLocal) -- both of those turned into bigger
 * problems than the bugs they fixed on the character-cache patch; no reason to repeat it.
 * Negative lookups (id not found on this vehicle) are not cached, matching original
 * behavior for that case -- all real call sites query ids that do exist on the vehicle.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "getPartById", warmUp = true)
public class Patch_CacheVehiclePartById {
    private static final Map<Object, Map<String, VehiclePart>> CACHE = new ConcurrentHashMap<>();

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.This Object self, @Patch.Argument(0) String id) {
        if (!PatchToggles.isEnabled("Patch_CacheVehiclePartById") || id == null) {
            return false;
        }
        Map<String, VehiclePart> vehicleCache = CACHE.get(self);
        boolean hit = vehicleCache != null && vehicleCache.containsKey(id);
        Stats.increment(hit ? "getPartById.hit" : "getPartById.miss");
        return hit;
    }

    @Patch.OnExit
    public static void exit(@Patch.This Object self, @Patch.Argument(0) String id,
            @Patch.Return(readOnly = false) VehiclePart result) {
        if (id == null) {
            return;
        }
        Map<String, VehiclePart> vehicleCache = CACHE.computeIfAbsent(self, k -> new ConcurrentHashMap<>());
        VehiclePart cached = vehicleCache.get(id);
        if (cached != null) {
            result = cached;
        } else if (result != null) {
            vehicleCache.put(id, result);
        }
    }
}
