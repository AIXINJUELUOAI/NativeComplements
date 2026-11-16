package dev.local.sunwindowdemo.features.iconlayers;

import android.os.UserHandle;
import dev.local.sunwindowdemo.core.ReflectiveAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherIconAccessTest {
    static class Fields { private final Object iconCache = new Object(); }
    static class Properties {
        private final Object cache = new Object();
        public Object getIconCache() { return cache; }
        public Object getIconCache(String ignored) { throw new AssertionError("wrong overload"); }
    }
    static class WrongGetter { public Object getIconCache(String ignored) { return new Object(); } }
    static class BrokenField {
        private Object iconCache;
        public Object getIconCache() { throw new AssertionError("null field is not absent"); }
    }
    static class UserFirst {
        String pkg; UserHandle user;
        private void removeIconsForPkg(UserHandle user, String pkg) { this.user = user; this.pkg = pkg; }
        public void removeIconsForPkg(Object ignored) { throw new AssertionError("wrong overload"); }
    }
    static class PackageFirst {
        String pkg; UserHandle user;
        private void removeIconsForPkg(String pkg, UserHandle user) { this.user = user; this.pkg = pkg; }
    }
    static class Unknown { public void removeIconsForPkg(Object pkg, Object user) {} }

    @Test public void fieldsAndExactNoArgGettersOnly() throws Exception {
        Fields fields = new Fields(); Properties properties = new Properties();
        assertSame(fields.iconCache, LauncherIconAccess.iconCache(fields));
        assertSame(properties.cache, LauncherIconAccess.iconCache(properties));
        assertNull(LauncherIconAccess.iconCache(new BrokenField()));
        assertThrows(NoSuchMethodException.class, () -> LauncherIconAccess.iconCache(new WrongGetter()));
        assertThrows(NoSuchMethodException.class, () -> ReflectiveAccess.method(Properties.class, "getIconCache", int.class));
    }

    @Test public void packageRemovalSupportsOnlyTheTwoVerifiedOrders() throws Exception {
        UserFirst old = new UserFirst(); PackageFirst current = new PackageFirst();
        LauncherIconAccess.remove(LauncherIconAccess.removeIconsForPkg(UserFirst.class), old, "old", null);
        LauncherIconAccess.remove(LauncherIconAccess.removeIconsForPkg(PackageFirst.class), current, "new", null);
        assertEquals("old", old.pkg); assertEquals("new", current.pkg);
        assertThrows(NoSuchMethodException.class, () -> LauncherIconAccess.removeIconsForPkg(Unknown.class));
    }
}
