package com.mlares.pzperfpatch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-fix enable/disable toggle, so each new patch can be measured individually and in
 * combination -- an A/B testing need, not a shipping feature. Reads a plain
 * `name=true`/`name=false` text file once at class-load time (mirrors the exact format the real
 * game itself uses for zombie.debug.DebugOptions' own debug-options.ini, confirmed this session):
 * `~/Zomboid/PZPerfPatch/fixes.ini`. A fix with no entry in the file (including when the file
 * doesn't exist at all) defaults to ENABLED -- normal play should get all fixes, this file is only
 * for deliberately isolating one/some fixes during a test run.
 *
 * Every new patch calls {@link #isEnabled(String)} as the very first check in its OnEnter, using
 * its own class's simple name as the key, and does a true no-op (lets the original vanilla method
 * run untouched) when disabled -- not "cache disabled but still intercepting," an actual vanilla
 * fallback, so a disabled fix is a clean baseline for comparison.
 */
public final class PatchToggles {
    private static final File CONFIG_FILE =
            new File(System.getProperty("user.home"), "Zomboid/PZPerfPatch/fixes.ini");
    private static final Map<String, Boolean> ENABLED = new LinkedHashMap<>();

    static {
        load();
    }

    private PatchToggles() {
    }

    private static void load() {
        if (!CONFIG_FILE.exists()) {
            System.out.println("[PZPerfPatch] PatchToggles: no " + CONFIG_FILE
                    + " found, all fixes default ENABLED");
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(CONFIG_FILE))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                ENABLED.put(name, Boolean.parseBoolean(value));
            }
            System.out.println("[PZPerfPatch] PatchToggles: loaded " + ENABLED.size()
                    + " override(s) from " + CONFIG_FILE);
        } catch (IOException e) {
            System.out.println("[PZPerfPatch] PatchToggles: failed to read " + CONFIG_FILE + ": " + e);
        }
    }

    public static boolean isEnabled(String name) {
        return ENABLED.getOrDefault(name, true);
    }

    /**
     * Same override file, opposite default -- for opt-in features (currently just verbose
     * logging) that should stay silent for normal players unless explicitly turned on.
     */
    public static boolean isEnabledDefaultOff(String name) {
        return ENABLED.getOrDefault(name, false);
    }

    /** Snapshot for RunManifest to record which fixes were actually active this run. */
    public static Map<String, Boolean> snapshot(String[] allFixNames) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String name : allFixNames) {
            out.put(name, isEnabled(name));
        }
        return out;
    }
}
