package dev.local.sunwindowdemo.features.alive;

import android.app.AppOpsManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.UserHandle;
import android.os.UserManager;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class AliveWallpaperAccessTest {
    private static final String MODULE = "dev.nativecomplements.module";
    private static final ComponentName OWN = new ComponentName(MODULE,
        "dev.local.sunwindowdemo.features.alive.AliveAmbientWallpaperService");
    private static final int BOTH = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;

    @Test public void policyUsesSenderUserNotSystemOrAnyUserDeviceOwner() throws Exception {
        var policy = new AliveWallpaperAccess.SenderPolicy(Users.class, Owners.class);
        Users users = new Users(); Owners owners = new Owners();
        assertTrue(policy.allows(users, owners, 1010001));
        assertEquals(UserHandle.getUserHandleForUid(1010001), users.last);
        users.restricted = true;
        assertFalse(policy.allows(users, owners, 1010001));
        owners.device = OWN; owners.user = 0;
        assertFalse(policy.allows(users, owners, 1010001)); // owner in system user is not sender's owner
        owners.user = 10;
        assertTrue(policy.allows(users, owners, 1010001));
        owners.device = new ComponentName("other.owner", "Owner");
        assertFalse(policy.allows(users, owners, 1010001));
        owners.profile = OWN;
        assertTrue(policy.allows(users, owners, 1010001));
        assertEquals(10, owners.profileUser);
        users.fail = true;
        assertFalse(policy.allows(users, owners, 1010001));
        assertFalse(policy.allows(null, owners, 1010001));
        assertFalse(policy.allows(users, null, 1010001));
        assertFalse(policy.allows(users, owners, -1));
    }

    @Test public void realFrameworkPolicyReflectionPreflightsWithoutCallerUserConvenienceMethods() throws Exception {
        assertNotNull(new AliveWallpaperAccess.SenderPolicy(UserManager.class, DevicePolicyManager.class));
    }

    @Test public void fixedLineageSixArgumentHelperPreservesNativeForegroundAndFlags() throws Exception {
        Service service = new Service();
        var access = access(Service.class, new AliveWallpaperAccess.SenderPolicy(Users.class, Owners.class));
        access.apply(service, new Description(OWN), MODULE, BOTH, 10);
        assertEquals(1, service.applied); assertEquals(10, service.user); assertEquals(BOTH, service.which);
        assertTrue(service.force); assertFalse(service.foreground); assertNull(service.reply);
        service.fromForeground = true;
        access.apply(service, new Description(OWN), MODULE, BOTH, 10);
        assertTrue(service.foreground);
        service.success = false;
        try { access.apply(service, new Description(OWN), MODULE, BOTH, 10); fail(); }
        catch (IllegalStateException expected) { /* native false is not acceptance */ }
    }

    @Test public void legacyDescriptionAndComponentHelpersReceiveTheirActualFirstParameter() throws Exception {
        for (Class<?> type : new Class<?>[]{DescriptionService.class, ComponentService.class}) {
            Object service = type.getDeclaredConstructor().newInstance();
            var access = access(type, new AliveWallpaperAccess.SenderPolicy(Users.class, Owners.class));
            access.apply(service, new Description(OWN), MODULE, BOTH, 0);
            assertEquals(OWN, type.getDeclaredField("target").get(service));
        }
        try { AliveWallpaperAccess.descriptionApply(WrongService.class, Description.class, Callback.class); fail(); }
        catch (NoSuchMethodException expected) { /* an ABI with the wrong result is not used */ }
    }

    @Test public void senderOwnershipPolicyAndUnknownAllBlockBeforeNativeApply() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo pkg = new PackageInfo(); pkg.packageName = MODULE;
        pkg.applicationInfo = new ApplicationInfo(); pkg.applicationInfo.packageName = MODULE; pkg.applicationInfo.uid = 10001;
        Shadows.shadowOf(context.getPackageManager()).installPackage(pkg);
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        Shadows.shadowOf(ops).setMode("android:write_wallpaper", 10001, MODULE, AppOpsManager.MODE_ALLOWED);
        UserManager users = context.getSystemService(UserManager.class);
        var policy = new AliveWallpaperAccess.SenderPolicy(UserManager.class, DevicePolicyManager.class);
        var access = access(Service.class, policy);
        Service service = new Service();
        assertTrue(AliveWallpaperAccess.Access.own(context, 10001, MODULE, OWN, BOTH, 0));
        access.applyBroadcast(service, context, 10001, MODULE, OWN, BOTH, 0);
        assertEquals(1, service.applied); service.applied = 0;
        denied(() -> access.applyBroadcast(service, context, 10001, "other.sender", OWN, BOTH, 0));
        denied(() -> access.applyBroadcast(service, context, -1, MODULE, OWN, BOTH, 0));
        denied(() -> access.applyBroadcast(service, context, 10002, MODULE, OWN, BOTH, 0));
        denied(() -> access.applyBroadcast(service, context, 10001, MODULE, OWN, BOTH, 10));
        denied(() -> access.applyBroadcast(service, context, 10001, MODULE, OWN, WallpaperManager.FLAG_SYSTEM, 0));
        denied(() -> access.applyBroadcast(service, context, 10001, MODULE, new ComponentName(MODULE, "OtherService"), BOTH, 0));
        Shadows.shadowOf(ops).setMode("android:write_wallpaper", 10001, MODULE, AppOpsManager.MODE_IGNORED);
        denied(() -> access.applyBroadcast(service, context, 10001, MODULE, OWN, BOTH, 0));
        Shadows.shadowOf(ops).setMode("android:write_wallpaper", 10001, MODULE, AppOpsManager.MODE_ALLOWED);
        Shadows.shadowOf(users).setUserRestriction(UserHandle.getUserHandleForUid(10001), UserManager.DISALLOW_SET_WALLPAPER, true);
        denied(() -> access.applyBroadcast(service, context, 10001, MODULE, OWN, BOTH, 0));
        Context unknown = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                return Context.USER_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        denied(() -> access.applyBroadcast(service, unknown, 10001, MODULE, OWN, BOTH, 0));
        assertEquals(0, service.applied);
    }

    private static void denied(Throwing request) throws Exception {
        try { request.run(); fail("denied request applied"); } catch (SecurityException expected) {}
    }
    private interface Throwing { void run() throws Exception; }
    private static AliveWallpaperAccess.Access access(Class<?> service, AliveWallpaperAccess.SenderPolicy policy) throws Exception {
        Method apply = AliveWallpaperAccess.descriptionApply(service, Description.class, Callback.class);
        return new AliveWallpaperAccess.Access(null, apply, null, null, Builder.class.getDeclaredConstructor(),
            Builder.class.getDeclaredMethod("setComponent", ComponentName.class), Builder.class.getDeclaredMethod("build"), policy, ignored -> {});
    }
    public static class Users {
        boolean restricted, fail; UserHandle last;
        public boolean hasUserRestriction(String key, UserHandle user) {
            assertEquals(UserManager.DISALLOW_SET_WALLPAPER, key); last = user;
            if (fail) throw new IllegalStateException("unknown user restriction"); return restricted;
        }
    }
    public static class Owners {
        int user = -10000, profileUser; ComponentName device, profile;
        public int getDeviceOwnerUserId() { return user; }
        public ComponentName getDeviceOwnerComponentOnAnyUser() { return device; }
        public ComponentName getProfileOwnerAsUser(int user) { profileUser = user; return profile; }
    }
    public interface Callback {}
    public static class Description {
        private final ComponentName target;
        Description(ComponentName target) { this.target = target; }
        public ComponentName getComponent() { return target; }
    }
    public static class Builder {
        ComponentName target;
        public Builder() {}
        public Builder setComponent(ComponentName target) { this.target = target; return this; }
        public Description build() { return new Description(target); }
    }
    public static class Service {
        int applied, which, user; boolean force, foreground, fromForeground, success = true; Callback reply;
        private boolean isFromForegroundApp(String pkg) { assertEquals(MODULE, pkg); return fromForeground; }
        private boolean setWallpaperDescriptionInternal(Description description, int which, int user, boolean force,
                boolean foreground, Callback reply) {
            assertEquals(OWN, description.getComponent()); applied++; this.which = which; this.user = user;
            this.force = force; this.foreground = foreground; this.reply = reply; return success;
        }
    }
    public static class DescriptionService {
        public ComponentName target;
        private boolean setWallpaperDescription(Description description, String pkg, int which, int user) {
            target = description.getComponent(); return true;
        }
    }
    public static class ComponentService {
        public ComponentName target;
        private boolean setWallpaperComponent(ComponentName component, String pkg, int which, int user) {
            target = component; return true;
        }
    }
    public static class WrongService {
        private void setWallpaperDescription(Description description, String pkg, int which, int user) {}
    }
}
