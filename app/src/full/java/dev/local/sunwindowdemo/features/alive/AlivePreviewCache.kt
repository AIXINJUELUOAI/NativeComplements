package dev.local.sunwindowdemo.features.alive

import android.graphics.Bitmap

/** Process-only editor thumbnails. No photo pixels are persisted or moved into DE. */
internal object AlivePreviewCache {
    const val MAX_SIDE = 512
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 12
    data class Key(val snapshot: AliveSettings.Snapshot, val phase: Int, val revision: Long, val width: Int, val height: Int)
    private val frames = LinkedHashMap<Key, Bitmap>(12, .75f, true)
    private var bytes = 0

    @Synchronized fun get(key: Key): Bitmap? = frames[key]?.takeUnless { it.isRecycled }
    @Synchronized fun put(key: Key, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width > MAX_SIDE || bitmap.height > MAX_SIDE || bitmap.allocationByteCount > MAX_BYTES) return
        frames.put(key, bitmap)?.let { bytes -= it.allocationByteCount }
        bytes += bitmap.allocationByteCount
        val oldest = frames.entries.iterator()
        while (bytes > MAX_BYTES || frames.size > MAX_ENTRIES) {
            bytes -= oldest.next().value.allocationByteCount
            oldest.remove()
        }
        // ImageViews may still display evicted frames; leave their final lifetime to bitmap GC.
    }
    @Synchronized fun clear() { frames.clear(); bytes = 0 }
}
