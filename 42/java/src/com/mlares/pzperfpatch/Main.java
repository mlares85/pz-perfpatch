package com.mlares.pzperfpatch;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

import com.sun.management.GarbageCollectionNotificationInfo;

public class Main {
    private static final long GC_LOG_THRESHOLD_MILLIS = 20; // ZGC pauses are normally sub-ms; log anything notable

    public static void main(String[] args) {
        System.out.println("[PZPerfPatch] loaded - caching getStateMachineComponent()");
        installGcPauseLogger();
    }

    // A GC pause is a distinct hiccup source from anything our CPU-hotspot patches can see --
    // separate mechanism (allocation/collection, not wasted computation), so a separate listener.
    private static void installGcPauseLogger() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            if (!(bean instanceof NotificationEmitter emitter)) {
                continue;
            }
            NotificationListener listener = (Notification notification, Object handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                GarbageCollectionNotificationInfo info =
                        GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData) notification.getUserData());
                long durationMillis = info.getGcInfo().getDuration();
                if (durationMillis >= GC_LOG_THRESHOLD_MILLIS) {
                    System.out.println("[PZPerfPatch] GC PAUSE: " + durationMillis + "ms ("
                            + info.getGcName() + ", " + info.getGcAction() + ")");
                }
            };
            emitter.addNotificationListener(listener, null, null);
        }
        System.out.println("[PZPerfPatch] GC pause logger installed on " + beans.size() + " collector(s)");
    }
}
