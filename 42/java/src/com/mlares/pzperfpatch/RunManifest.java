package com.mlares.pzperfpatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import zombie.debug.DebugOptions;

/**
 * Timestamped per-run logging so separate test runs (different PatchToggles/DebugOptions.Threading
 * combinations) can be compared later instead of relying on memory of what was set for a given run
 * -- console.txt is overwritten by the next launch, and nothing previously recorded which config
 * produced a given log.
 *
 * Deliberately NOT initialized from Main.java's main() -- that runs very early during mod loading,
 * before zombie.debug.DebugOptions.instance.init() has read debug-options.ini, so snapshotting the
 * Threading.* flags that early would capture stale defaults instead of the real loaded config.
 * Instead, ensureInitialized() is called (idempotently) from Patch_FpsLogger's first frame, which
 * only fires well after full game boot.
 */
public final class RunManifest {
    private static final String[] KNOWN_FIX_NAMES = {
            // Original 7 caches (2026-07-16/17), retrofitted with PatchToggles 2026-07-17 for
            // vanilla-vs-patched A/B testing. The other 7 of the original 14 patches
            // (Patch_ForceGodMod/Invulnerable/NoClip, Patch_Invulnerable, Patch_FpsLogger,
            // Patch_AutoSkipLoadingClick) are deliberately NOT toggleable -- they're test
            // infrastructure (survival, measurement, unattended operation), not performance fixes,
            // and disabling them would compromise the comparison itself rather than remove an
            // optimization being measured.
            "Patch_CacheStateMachineComponent",
            "Patch_CacheStateMachine",
            "Patch_CacheECSClass",
            "Patch_CacheIsValidThread",
            "Patch_FixDoubleBoneLookup",
            "Patch_CacheVehiclePartById",
            "Patch_SkipRedundantVehicleZoneCheck",
            // 6 new caches/skips found via the decompile-findings research round (2026-07-17).
            "Patch_CacheGameTimeMultiplier",
            "Patch_SkipUnchangedMovingSquare",
            "Patch_FastZombieSoundManagerAddCharacter",
            "Patch_FastAnimalSoundManagerAddCharacter",
            "Patch_SkipSCBADrainForZombies",
            "Patch_FixThermoregulatorEnergyLookup",
            // Threading.* flag force-enables (2026-07-17), one toggle per flag rather than one for
            // the whole Patch_EnableThreadingFlags class -- see that file for why each is
            // independently gated. Threading.Animation and Threading.Sound are both deliberately
            // excluded (confirmed broken, not just untoggled -- see Patch_EnableThreadingFlags.java).
            "Patch_ThreadWorld",
            "Patch_ThreadLighting",
            "Patch_ThreadGridStacks",
            "Patch_ThreadAmbient",
    };

    private static volatile boolean initialized = false;
    private static File runDir;

    private RunManifest() {
    }

    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File runsRoot = new File(System.getProperty("user.home"), "Zomboid/PZPerfPatch/runs");
            runDir = new File(runsRoot, timestamp);
            runDir.mkdirs();
            writeManifest(timestamp);
            registerShutdownHook();
            System.out.println("[PZPerfPatch] RunManifest: recording this run to " + runDir);
        } catch (Exception e) {
            System.out.println("[PZPerfPatch] RunManifest: failed to initialize: " + e);
        }
    }

    private static void writeManifest(String timestamp) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp=").append(timestamp).append('\n');
        sb.append("build_version=").append(readBuildVersion()).append('\n');

        sb.append("\n[DebugOptions.Threading]\n");
        DebugOptions d = DebugOptions.instance;
        sb.append("Threading.Animation=").append(d.threadAnimation.getValue()).append('\n');
        sb.append("Threading.World=").append(d.threadWorld.getValue()).append('\n');
        sb.append("Threading.Lighting=").append(d.threadLighting.getValue()).append('\n');
        sb.append("Threading.RecalculateGridStacks=").append(d.threadGridStacks.getValue()).append('\n');
        sb.append("Threading.Sound=").append(d.threadSound.getValue()).append('\n');
        sb.append("Threading.Ambient=").append(d.threadAmbient.getValue()).append('\n');
        sb.append("Threading.ModelSlotInit=").append(d.threadModelSlotInit.getValue()).append('\n');

        sb.append("\n[PatchToggles]\n");
        for (String name : KNOWN_FIX_NAMES) {
            sb.append(name).append('=').append(PatchToggles.isEnabled(name)).append('\n');
        }

        Files.write(new File(runDir, "manifest.txt").toPath(), sb.toString().getBytes());
    }

    private static String readBuildVersion() {
        try {
            File versionFile = new File(System.getProperty("user.home"), "Zomboid/version.txt");
            if (!versionFile.exists()) {
                return "unknown (version.txt not found)";
            }
            return Files.readAllLines(versionFile.toPath()).get(0);
        } catch (Exception e) {
            return "unknown (" + e + ")";
        }
    }

    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                File consoleLog = new File(System.getProperty("user.home"), "Zomboid/console.txt");
                if (consoleLog.exists()) {
                    Files.copy(consoleLog.toPath(), new File(runDir, "console.txt").toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                System.out.println("[PZPerfPatch] RunManifest: failed to archive console.txt: " + e);
            }
        }, "PZPerfPatch-RunManifest-Shutdown"));
    }
}
