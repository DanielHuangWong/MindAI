package com.mindai.app;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide guard shared by app, scheduled work and widgets. */
final class ProcessingCoordinator {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ProcessingCoordinator() {}

    static boolean tryAcquire() { return RUNNING.compareAndSet(false, true); }
    static void release() { RUNNING.set(false); }
    static boolean isRunning() { return RUNNING.get(); }
}
