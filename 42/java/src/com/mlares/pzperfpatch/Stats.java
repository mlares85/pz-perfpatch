package com.mlares.pzperfpatch;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared hit/miss counter registry so every cache patch reports through one place, instead of
 * only getStateMachineComponent (the original patch) having visible counters. Patch_FpsLogger
 * prints a consolidated summary alongside its per-window FPS line.
 */
public class Stats {
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    public static void increment(String name) {
        COUNTERS.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public static String summary() {
        // TreeMap for stable, alphabetical ordering across log lines
        Map<String, Long> sorted = new TreeMap<>();
        COUNTERS.forEach((k, v) -> sorted.put(k, v.get()));
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
        return sb.toString().trim();
    }
}
