package com.mlares.pzperfpatch;

import java.lang.management.ManagementFactory;

import com.sun.management.OperatingSystemMXBean;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * A plain 5-second average FPS number hides hiccups: one 200ms stutter buried inside
 * otherwise-smooth frames barely moves a window average. This tracks worst-frame-in-window
 * (not just average) and fires an immediate, un-batched alert the instant any single frame
 * crosses HICCUP_THRESHOLD_NANOS, so a spike is visible at the moment it happens with its
 * real cost in ms, not smoothed away five seconds later.
 */
@Patch(className = "zombie.core.Core", methodName = "EndFrameUI", warmUp = true)
public class Patch_FpsLogger {
    private static long windowStartNanos = System.nanoTime();
    private static long lastFrameNanos = System.nanoTime();
    private static int frameCount = 0;
    private static long totalFrameNanos = 0;
    private static long worstFrameNanos = 0;
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long HICCUP_THRESHOLD_NANOS = 50_000_000L; // 50ms = worse than 20fps for one frame

    // JMX, in-JVM, no shelling out to an external process -- same category as Main.java's GC-pause
    // listener. getProcessCpuLoad() is a 0.0-1.0 fraction of one core's worth of total CPU capacity
    // consumed by this process (so >100% is possible when multiple cores are busy). There's no
    // equivalent portable JVM API for GPU utilization -- would need a platform-specific external
    // tool (e.g. macOS's powermetrics, which needs sudo), so that's deliberately not attempted here.
    private static final OperatingSystemMXBean OS_BEAN =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    @Patch.OnEnter
    public static void enter() {
        if (!PatchToggles.isEnabledDefaultOff("Patch_VerboseLogging")) {
            return;
        }
        RunManifest.ensureInitialized();

        long now = System.nanoTime();
        long frameNanos = now - lastFrameNanos;
        lastFrameNanos = now;

        if (frameCount > 0 && frameNanos > HICCUP_THRESHOLD_NANOS) {
            System.out.println("[PZPerfPatch] HICCUP: " + String.format("%.1f", frameNanos / 1_000_000.0)
                    + "ms frame, live zombies: " + liveZombieCount() + ", player pos: " + playerPos());
        }

        frameCount++;
        totalFrameNanos += frameNanos;
        if (frameNanos > worstFrameNanos) {
            worstFrameNanos = frameNanos;
        }

        long elapsed = now - windowStartNanos;
        if (elapsed >= LOG_INTERVAL_NANOS) {
            double seconds = elapsed / 1_000_000_000.0;
            double fps = frameCount / seconds;
            double avgMs = (totalFrameNanos / (double) frameCount) / 1_000_000.0;
            double worstMs = worstFrameNanos / 1_000_000.0;
            System.out.println("[PZPerfPatch] FPS: " + String.format("%.1f", fps)
                    + " (" + frameCount + " frames / " + String.format("%.1f", seconds) + "s)"
                    + ", avg: " + String.format("%.1f", avgMs) + "ms"
                    + ", worst: " + String.format("%.1f", worstMs) + "ms"
                    + ", live zombies: " + liveZombieCount()
                    + ", player pos: " + playerPos()
                    + ", process CPU: " + cpuPercent());
            System.out.println("[PZPerfPatch] cache stats: getStateMachineComponent.hit="
                    + Patch_CacheStateMachineComponent.getHits() + " getStateMachineComponent.miss="
                    + Patch_CacheStateMachineComponent.getMisses() + " " + Stats.summary());
            frameCount = 0;
            totalFrameNanos = 0;
            worstFrameNanos = 0;
            windowStartNanos = now;
        }
    }

    private static int liveZombieCount() {
        IsoWorld world = IsoWorld.instance;
        if (world == null) {
            return -1;
        }
        IsoCell cell = world.getCell();
        if (cell == null || cell.getZombieList() == null) {
            return -1;
        }
        return cell.getZombieList().size();
    }

    private static String playerPos() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return "n/a";
        }
        return String.format("%.0f,%.0f,%.0f", player.getX(), player.getY(), player.getZ());
    }

    private static String cpuPercent() {
        if (OS_BEAN == null) {
            return "n/a";
        }
        double load = OS_BEAN.getProcessCpuLoad();
        if (load < 0) {
            return "n/a";
        }
        return String.format("%.0f%%", load * 100.0);
    }
}
