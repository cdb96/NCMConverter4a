package com.cdb96.ncmconverter4a

private const val IDLE_HEAP_THRESHOLD = 64L * 1024 * 1024

/** Call after a whole batch has released its buffers and worker threads. */
internal fun releaseIdleDesktopHeap() {
    // Serial GC has no periodic idle collection. Large covers/databases can
    // leave an expanded heap after work ends, when no more allocations trigger
    // collection. Request it once at this boundary, only if the heap expanded.
    if (Runtime.getRuntime().totalMemory() > IDLE_HEAP_THRESHOLD) {
        System.gc()
    }
}
