package dev.local.sunwindowdemo.features.shade;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Fake host shapes mirroring the archived legacy and QPR2 StackedMobileIconKt declarations. */
public class StackedSignalTargetsTest {
    static final class DualSim {}
    static final class Modifier {}
    static final class Composer {}
    static final class StackedMobileIconViewModel {}
    static final class StackedMobileIconViewModelImpl {}

    @SuppressWarnings("unused")
    static final class LegacyKt {
        public static void StackedMobileIcon(StackedMobileIconViewModel vm, Modifier m, Composer c, int changed) {}
        public static void StackedMobileIcon(DualSim d, long tint, String description, Modifier m, Composer c, int changed) {}
        public static int StackedMobileIcon(DualSim d, long tint, Modifier m, Composer c, int changed) { return 0; }
    }

    @SuppressWarnings("unused")
    static final class Qpr2Kt {
        public static void StackedMobileIcon(StackedMobileIconViewModelImpl vm, Modifier m, Composer c, int changed) {}
        public static void StackedMobileIcon(DualSim d, long tint, Modifier m, Composer c, int changed) {}
        public static void drawMobileIconBar(Object scope, int a, int b, long c, long d, long e) {}
    }

    @SuppressWarnings("unused")
    static final class MissingKt {
        public static void StackedMobileIcon(DualSim d, int tint, Modifier m, Composer c, int changed) {}
    }

    @SuppressWarnings("unused")
    static final class AmbiguousKt {
        public static void StackedMobileIcon(DualSim d, long tint, String description, Modifier m, Composer c, int changed) {}
        public static void StackedMobileIcon(DualSim d, long tint, Modifier m, Composer c, int changed) {}
    }

    @SuppressWarnings("unused")
    static final class Restart {
        DualSim f$0; long f$1; Modifier f$2;
        public Object invoke(Object composer, Object changed) { return null; }
    }

    @SuppressWarnings("unused")
    static final class Paint {
        DualSim f$2;
        public Object invoke(Object scope) { return null; }
    }

    @Test public void legacyRenderKeepsStringSignatureAndChangedIndex() throws Exception {
        Method render = StackedSignalTargets.render(LegacyKt.class, DualSim.class, Modifier.class, Composer.class);
        assertEquals(6, render.getParameterCount());
        assertEquals(String.class, render.getParameterTypes()[2]);
        assertEquals(5, StackedSignalTargets.changedIndex(render));
        Method parent = StackedSignalTargets.parent(LegacyKt.class, Modifier.class, Composer.class);
        assertEquals(StackedMobileIconViewModel.class, parent.getParameterTypes()[0]);
    }

    @Test public void qpr2RenderWithoutStringAndImplParent() throws Exception {
        Method render = StackedSignalTargets.render(Qpr2Kt.class, DualSim.class, Modifier.class, Composer.class);
        assertEquals(5, render.getParameterCount());
        assertEquals(Modifier.class, render.getParameterTypes()[2]);
        assertEquals(4, StackedSignalTargets.changedIndex(render));
        Method parent = StackedSignalTargets.parent(Qpr2Kt.class, Modifier.class, Composer.class);
        assertEquals(StackedMobileIconViewModelImpl.class, parent.getParameterTypes()[0]);
    }

    @Test public void missingOrAmbiguousRenderNamesTheMember() {
        try {
            StackedSignalTargets.render(MissingKt.class, DualSim.class, Modifier.class, Composer.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("MissingKt#StackedMobileIcon-*"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("StackedMobileIcon(DualSim,int,Modifier,Composer,int)"));
        }
        try {
            StackedSignalTargets.render(AmbiguousKt.class, DualSim.class, Modifier.class, Composer.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ambiguous"));
        }
        try {
            StackedSignalTargets.parent(MissingKt.class, Modifier.class, Composer.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("StackedMobileIconViewModel*"));
        }
    }

    @Test public void restartRequiresDualSimCaptureAndTwoArgumentInvoke() throws Exception {
        assertEquals(2, StackedSignalTargets.restart(Restart.class, DualSim.class).getParameterCount());
        try {
            StackedSignalTargets.restart(Paint.class, DualSim.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().endsWith("#invoke(Object,Object)"));
        }
        try {
            StackedSignalTargets.restart(Modifier.class, DualSim.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("has no DualSim field"));
        }
    }

    @Test public void memberLookupsAndLogTextIncludeMissingName() {
        try {
            StackedSignalTargets.method(Restart.class, "invoke", String.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(StackedSignalTargets.describe(expected).startsWith("NoSuchMethodException "));
            assertTrue(expected.getMessage(), expected.getMessage().endsWith("Restart#invoke(String)"));
        }
        try {
            StackedSignalTargets.field(Restart.class, "primarySubId");
            fail();
        } catch (NoSuchFieldException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().endsWith("Restart.primarySubId"));
        }
        try {
            StackedSignalTargets.constructor(Restart.class, int.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().endsWith("Restart#<init>(int)"));
        }
        assertEquals("IllegalStateException", StackedSignalTargets.describe(new IllegalStateException()));
        assertTrue(StackedSignalTargets.describe(new IllegalStateException("x".repeat(1000))).length() <= 403);
    }
}
