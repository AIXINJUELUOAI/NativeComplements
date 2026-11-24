package dev.local.sunwindowdemo.features.alive

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AlivePreviewCacheTest {
    @Test fun matchesOnlyExactRenderedInputAndEvictsWithinBudget() {
        AlivePreviewCache.clear()
        try {
            val input = AliveSettings.Snapshot(0, 0, 6, "alive-photo.jpg")
            val key = AlivePreviewCache.Key(input, 0, 13L, 300, 650)
            assertNull(AlivePreviewCache.get(key)) // A cold process has no fabricated thumbnail.
            val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.RED)
            AlivePreviewCache.put(key, image)
            assertSame(image, AlivePreviewCache.get(key))
            assertNull(AlivePreviewCache.get(key.copy(phase = 1)))
            assertNull(AlivePreviewCache.get(key.copy(revision = 14L)))
            assertNull(AlivePreviewCache.get(key.copy(width = 301)))
            assertNull(AlivePreviewCache.get(key.copy(snapshot = AliveSettings.Snapshot(1, 0, 6, input.photo))))
            assertNull(AlivePreviewCache.get(key.copy(snapshot = AliveSettings.Snapshot(0, 0, 6, "alive-image-abc.jpg"))))
            for (revision in 14L..17L) {
                AlivePreviewCache.put(key.copy(revision = revision), Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888))
            }
            assertNull(AlivePreviewCache.get(key)) // Five 1MiB frames cannot fit the 4MiB cache.
            assertFalse(image.isRecycled) // A cover may still hold the evicted bitmap.
            assertNotNull(AlivePreviewCache.get(key.copy(revision = 17L)))
            val oversized = Bitmap.createBitmap(513, 1, Bitmap.Config.ARGB_8888)
            AlivePreviewCache.put(key, oversized)
            assertNull(AlivePreviewCache.get(key))
        } finally { AlivePreviewCache.clear() }
    }
}
