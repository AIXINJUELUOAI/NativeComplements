package dev.local.sunwindowdemo.features.edgetouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEdgeTouchTest {
    @Test fun portraitOnlyAddsConditionalStripsBelowTheMidpoint() {
        val rectangles = NativeEdgeTouch.portraitTable().toList().chunked(8)
        assertEquals(12, rectangles.size)
        assertEquals(listOf(1, 2, 0, 1334, 50, 2669, 0, 0), rectangles[6])
        assertEquals(listOf(1, 3, 1149, 1334, 1199, 2669, 0, 0), rectangles[7])
        rectangles.forEachIndexed { index, rect ->
            if (index != 6 && index != 7) assertEquals(List(6) { 0 }, rect.drop(2))
        }
    }

    @Test fun probesAndWritesRequireOneSuccessfulBinderReply() {
        val version = "Result: Parcel(\t00000000 00000001   '........')"
        assertEquals(1, NativeEdgeTouch.parcelValue("root banner\n$version"))
        assertEquals(0, NativeEdgeTouch.parcelValue(version.replace("00000001", "00000000")))
        assertNull(NativeEdgeTouch.parcelValue("Service not found"))
        assertNull(NativeEdgeTouch.parcelValue(version.replace("00000000", "fffffffb")))
        assertNull(NativeEdgeTouch.parcelValue("$version\n$version"))
    }
}
