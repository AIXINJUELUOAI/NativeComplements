package dev.local.sunwindowdemo;

import dev.local.sunwindowdemo.features.navbar.NavbarColors;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavbarColorsTest {
    @Test public void solidAndNearbyColorsKeepTheirRepresentativeColor() {
        assertEquals(0xff123456, NavbarColors.background(new int[]{0xff123456, 0xff123456}));
        assertEquals(0xff121212, NavbarColors.background(new int[]{0xff111111, 0xff131313}));
    }
    @Test public void transparentAndEmptyRowsKeepOriginalBehavior() {
        assertEquals(0, NavbarColors.background(new int[]{}));
        assertEquals(0, NavbarColors.background(new int[]{0, 0, 0, 0xffabcdef}));
    }
    @Test public void complexRowsUsePageBrightness() {
        assertEquals(0xff000000, NavbarColors.background(new int[]{0xff100000, 0xff001000, 0xff000010, 0xff202020}));
        assertEquals(0xffffffff, NavbarColors.background(new int[]{0xffffeeee, 0xffeeffee, 0xffeeeeff, 0xffdddddd}));
    }
}
