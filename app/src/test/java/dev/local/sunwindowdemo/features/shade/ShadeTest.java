package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.os.Process;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShadeTest {
    private final Context context = RuntimeEnvironment.getApplication();
    @Before public void reset() { ShadeStore.preferences(context).edit().clear().commit(); }
    @After public void restoreUid() { ShadowBinder.setCallingUid(Process.myUid()); }

    @Test public void onlyEnabledLandscapeAtLeast600dpOverridesNative() {
        Configuration c = new Configuration();
        c.orientation = Configuration.ORIENTATION_LANDSCAPE;
        c.screenWidthDp = 600;
        assertTrue(LandscapeShade.forceSplit(true, c));
        assertFalse(LandscapeShade.forceSplit(false, c));
        c.screenWidthDp = 599;
        assertFalse(LandscapeShade.forceSplit(true, c));
        c.screenWidthDp = 900;
        c.orientation = Configuration.ORIENTATION_PORTRAIT;
        assertFalse(LandscapeShade.forceSplit(true, c));
        c.orientation = Configuration.ORIENTATION_UNDEFINED;
        assertFalse(LandscapeShade.forceSplit(true, c));
        assertTrue(ShadePolicy.forceSplit(true, true, 600));
        assertFalse(ShadePolicy.forceSplit(true, true, 599));
        assertFalse(ShadePolicy.forceSplit(false, true, 900));
    }

    @Test public void deoptimizationIncludesIndependentQsLayoutAndOtherNativeConsumers() {
        var callers = java.util.Arrays.stream(LandscapeShade.CALLERS)
                .map(value -> value[0] + "#" + value[1]).collect(java.util.stream.Collectors.toSet());
        assertEquals(11, callers.size());
        assertTrue(callers.contains("shade.QuickSettingsControllerImpl#updateResources"));
        assertTrue(callers.contains("shade.NotificationsQSContainerController#updateResources"));
        assertTrue(callers.contains("statusbar.notification.stack.NotificationStackScrollLayout#updateSplitNotificationShade"));
        assertTrue(callers.stream().anyMatch(value -> value.endsWith("#emit")));
        assertEquals(5, LandscapeShade.CONSTRUCTORS.length);
    }

    static final class LegacyRepository { Object legacyUseSplitShade; }
    static final class HydratedRepository { Object legacyUseSplitShade$delegate; }
    @Test public void composeAbiReadsFieldShapeWithoutTouchingNativeFlow() throws Exception {
        assertFalse(ComposeShadeLayout.hydrated(LegacyRepository.class));
        assertTrue(ComposeShadeLayout.hydrated(HydratedRepository.class));
        assertThrows(NoSuchFieldException.class, () -> ComposeShadeLayout.hydrated(Object.class));
    }

    @Test public void binderRedrawPreservesNativeStateForDisableRestore() throws Exception {
        var paints = SignalDisplay.class.getDeclaredField("PAINTS"); paints.setAccessible(true);
        var redraw = SignalDisplay.class.getDeclaredField("REDRAW"); redraw.setAccessible(true);
        var values = (java.util.List<?>) paints.get(null);
        var drawable = new android.graphics.drawable.ColorDrawable();
        int nativeState = (5 << 8) | 2;
        try {
            assertEquals(nativeState, SignalDisplay.present(drawable, 1, nativeState));
            ((Runnable) redraw.get(null)).run();
            assertEquals(nativeState, drawable.getLevel());
            var state = values.get(0).getClass().getDeclaredField("nativeState"); state.setAccessible(true);
            assertEquals(nativeState, state.getInt(values.get(0)));
            assertFalse(SignalDisplay.redrawing());
        } finally { values.clear(); }
    }

    @Test public void composeResourceOverrideIsScopedAndRestoresNativeOutsideForcedLandscape() {
        assertEquals(true, ComposeShadeLayout.overrideBoolean(false, 1, true, true, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(true, 2, true, true, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(false, 1, false, true, 1, 2));
        assertEquals(true, ComposeShadeLayout.overrideBoolean(true, 2, true, false, 1, 2));
        assertEquals(false, ComposeShadeLayout.overrideBoolean(false, 3, true, true, 1, 2));
        assertNull(ShadePolicy.resourceOverride(3, true, true, 1, 2));
        Object nativeValue = new Object();
        assertSame(nativeValue, ComposeShadeLayout.overrideBoolean(nativeValue, 3, true, true, 1, 2));
    }

    @Test public void composeIdsComeFromHostResourcesWithoutRuntimeRClass() throws Exception {
        var split = ComposeShadeLayout.class.getDeclaredField("splitId"); split.setAccessible(true);
        var full = ComposeShadeLayout.class.getDeclaredField("fullWidthId"); full.setAccessible(true);
        var base = context.getResources();
        var host = new android.content.res.Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public int getIdentifier(String name, String kind, String pkg) {
                assertEquals("bool", kind); assertEquals("com.android.systemui", pkg);
                return switch (name) {
                    case "config_use_split_notification_shade" -> 101;
                    case "config_isFullWidthShade" -> 102;
                    default -> 0;
                };
            }
        };
        try {
            split.setInt(null, 0); full.setInt(null, 0);
            ComposeShadeLayout.resolveResourceIds(host);
            assertEquals(101, split.getInt(null)); assertEquals(102, full.getInt(null));
            split.setInt(null, 0); full.setInt(null, 0);
            assertThrows(android.content.res.Resources.NotFoundException.class,
                    () -> ComposeShadeLayout.resolveResourceIds(base));
            assertEquals(0, split.getInt(null)); assertEquals(0, full.getInt(null));
        } finally { split.setInt(null, 0); full.setInt(null, 0); }
    }

    static class AsyncRepository {
        final android.content.res.Resources resources;
        final java.util.List<Boolean> values = new java.util.concurrent.CopyOnWriteArrayList<>();
        kotlinx.coroutines.flow.FlowCollector<? super Boolean> downstream;
        final MapEmission mapper = new MapEmission();
        AsyncRepository(android.content.res.Resources resources) { this.resources = resources; }
        Object collect() {
            kotlinx.coroutines.flow.Flow<Boolean> nativeMap = (collector, continuation) -> {
                downstream = collector;
                return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
            };
            return kotlinx.coroutines.flow.FlowKt.distinctUntilChanged(nativeMap).collect((value, continuation) -> {
                values.add(value); return kotlin.Unit.INSTANCE;
            }, continuation());
        }
        static kotlin.coroutines.Continuation<kotlin.Unit> continuation() {
            return new kotlin.coroutines.Continuation<>() {
                public kotlin.coroutines.CoroutineContext getContext() { return kotlin.coroutines.EmptyCoroutineContext.INSTANCE; }
                public void resumeWith(Object result) { kotlin.ResultKt.throwOnFailure(result); }
            };
        }
        final class MapEmission {
            Object emit(int id) { return downstream.emit(resources.getBoolean(id), continuation()); }
        }
        final class UnrelatedNested {
            boolean invokeSuspend(int id) { return resources.getBoolean(id); }
        }
    }
    public static class AsyncConfigCallback {
        final AsyncRepository split, fullWidth;
        final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        AsyncConfigCallback(AsyncRepository split, AsyncRepository fullWidth) { this.split=split; this.fullWidth=fullWidth; }
        public void onConfigChanged(Configuration value) throws Exception {
            worker.submit(() -> { split.mapper.emit(101); fullWidth.mapper.emit(102); }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test public void suspendedFlowsEvaluateAsyncBeforeDistinctAndRestoreOnDisableAndRotation() throws Exception {
        var type = ComposeShadeLayout.class;
        var owner = type.getDeclaredField("repositoryOwner"); owner.setAccessible(true);
        var refs = type.getDeclaredField("repositoryResources"); refs.setAccessible(true);
        var callbacks = type.getDeclaredField("callbacks"); callbacks.setAccessible(true);
        var callbackMethod = type.getDeclaredField("onConfigChanged"); callbackMethod.setAccessible(true);
        var splitId = type.getDeclaredField("splitId"); splitId.setAccessible(true);
        var fullWidthId = type.getDeclaredField("fullWidthId"); fullWidthId.setAccessible(true);
        var resourcesList = (java.util.List<java.lang.ref.WeakReference<android.content.res.Resources>>) refs.get(null);
        var callbackList = (java.util.List<java.lang.ref.WeakReference<Object>>) callbacks.get(null);
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.orientation = Configuration.ORIENTATION_LANDSCAPE; config.screenWidthDp = 874;
        var base = context.getResources();
        var resources = new android.content.res.Resources(base.getAssets(), base.getDisplayMetrics(), config) {
            @Override public boolean getBoolean(int id) {
                boolean nativeValue = id == 102;
                return (Boolean) ComposeShadeLayout.readBoolean(nativeValue, this, id,
                        value -> LandscapeShade.forceSplit(enabled.get(), value.getConfiguration()));
            }
        };
        AsyncRepository split = new AsyncRepository(resources), full = new AsyncRepository(resources);
        AsyncConfigCallback callback = new AsyncConfigCallback(split, full);
        try {
            owner.set(null, AsyncRepository.class.getName()); splitId.setInt(null,101); fullWidthId.setInt(null,102);
            resourcesList.add(new java.lang.ref.WeakReference<>(resources));
            callbackMethod.set(null, AsyncConfigCallback.class.getDeclaredMethod("onConfigChanged",Configuration.class));
            callbackList.add(new java.lang.ref.WeakReference<>(callback));
            assertSame(kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED(), split.collect());
            assertSame(kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED(), full.collect());
            ComposeShadeLayout.refresh(config);
            enabled.set(true); ComposeShadeLayout.refresh(config);
            ComposeShadeLayout.refresh(config); // Native distinctUntilChanged still suppresses duplicates.
            enabled.set(false); ComposeShadeLayout.refresh(config);
            enabled.set(true); config.orientation=Configuration.ORIENTATION_PORTRAIT;
            resources.updateConfiguration(config,base.getDisplayMetrics()); ComposeShadeLayout.refresh(config);
            assertEquals(java.util.List.of(false,true,false),split.values);
            assertEquals(java.util.List.of(true,false,true),full.values);
            config.orientation=Configuration.ORIENTATION_LANDSCAPE;
            resources.updateConfiguration(config,base.getDisplayMetrics()); ComposeShadeLayout.refresh(config);
            assertEquals(java.util.List.of(false,true,false,true),split.values);
            assertFalse("same resources outside the repository mapper stay native",resources.getBoolean(101));
            assertFalse("unrelated nested continuation stays native",split.new UnrelatedNested().invokeSuspend(101));
            assertFalse("unrelated bool stays native",(Boolean)ComposeShadeLayout.readBoolean(false,resources,103,value->true));
            resourcesList.clear(); ComposeShadeLayout.refresh(config);
            assertEquals("another Resources identity must not inherit the scope",false,split.values.get(split.values.size()-1));
        } finally {
            callback.worker.shutdownNow(); resourcesList.clear(); callbackList.clear();
            owner.set(null,null); callbackMethod.set(null,null); splitId.setInt(null,0); fullWidthId.setInt(null,0);
        }
    }
    @Test public void missingNativeRefreshCallbackFailsBeforeInstallingAnOverride() throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if(name.contains("ConfigurationRepositoryImpl$onConfigurationChange")) throw new ClassNotFoundException(name);
                return super.loadClass(name);
            }
        };
        var resolve=ComposeShadeLayout.class.getDeclaredMethod("resolve",ClassLoader.class); resolve.setAccessible(true);
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->resolve.invoke(null,loader));
        assertTrue(error.getCause() instanceof ClassNotFoundException);
        assertTrue(error.getCause().getMessage().contains("ConfigurationRepositoryImpl$onConfigurationChange"));
    }
    @Test public void repositoryScopeRejectsLookalikeOwnerAndUnrelatedNestedMethods() {
        String owner="com.android.systemui.shade.data.repository.ShadeConfigRepository";
        assertTrue(ComposeShadeLayout.repositoryEvaluation(new StackTraceElement[]{new StackTraceElement(owner+"$legacy$map$1$2","emit","file",1)},owner));
        assertFalse(ComposeShadeLayout.repositoryEvaluation(new StackTraceElement[]{new StackTraceElement(owner+"Other$map$1$2","emit","file",1)},owner));
        assertFalse(ComposeShadeLayout.repositoryEvaluation(new StackTraceElement[]{new StackTraceElement(owner+"$callback","invokeSuspend","file",1)},owner));
        assertFalse(ComposeShadeLayout.repositoryEvaluation(new StackTraceElement[]{new StackTraceElement(owner,"getLegacyUseSplitShade","file",1)},owner));
        assertFalse(ComposeShadeLayout.repositoryEvaluation(new StackTraceElement[0],null));
    }

    public static class ConfigCallback {
        Configuration last;
        int calls;
        public void onConfigChanged(Configuration value) { last = value; calls++; }
    }
    @Test public void composeRefreshUsesNativeRepositoryCallbackAndUnmodifiedConfiguration() throws Exception {
        var type = ComposeShadeLayout.class;
        var refs = type.getDeclaredField("callbacks"); refs.setAccessible(true);
        var method = type.getDeclaredField("onConfigChanged"); method.setAccessible(true);
        var list = (java.util.List<java.lang.ref.WeakReference<Object>>) refs.get(null);
        ConfigCallback callback = new ConfigCallback();
        Configuration real = new Configuration();
        real.orientation = Configuration.ORIENTATION_LANDSCAPE; real.screenWidthDp = 874;
        real.densityDpi = 489;
        try {
            method.set(null, ConfigCallback.class.getDeclaredMethod("onConfigChanged", Configuration.class));
            list.add(new java.lang.ref.WeakReference<>(callback));
            ComposeShadeLayout.refresh(real);
            assertEquals(1, callback.calls);
            assertNotSame(real, callback.last);
            assertEquals(real, callback.last);
        } finally { list.clear(); method.set(null, null); }
    }

    @Test public void statusBarOffsetClampsToThirtyTwoAndStaysIndependentFromSplitShade() {
        ShadeStore.setOffset(context, 40);
        assertEquals(32, ShadeStore.offset(context));
        assertFalse("offset must not enable split shade", ShadeStore.read(context).getBoolean("enabled"));
        assertEquals(32, ShadeStore.read(context).getInt("statusBarOffset"));
        ShadeStore.setOffset(context, -40);
        assertEquals(-32, ShadeStore.offset(context));
        ShadeStore.setOffset(context, 0);
        assertEquals(0, ShadeStore.read(context).getInt("statusBarOffset", -1));
    }

    @Test public void signalDisplayIsOneIndependentDefaultOffSwitch() {
        assertFalse(ShadeStore.read(context).getBoolean("signalDisplay"));
        ShadeStore.setSignalDisplay(context,true);
        assertTrue(ShadeStore.read(context.createDeviceProtectedStorageContext()).getBoolean("signalDisplay"));
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        ShadeStore.setSignalDisplay(context,false);
        assertFalse(ShadeStore.read(context).getBoolean("signalDisplay"));
    }

    @Test public void settingsDefaultOffAndUseDeviceProtectedStorage() {
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        ShadeStore.setEnabled(context, true);
        assertTrue(ShadeStore.read(context.createDeviceProtectedStorageContext()).getBoolean("enabled"));
        assertFalse(context.getSharedPreferences("landscape_shade", Context.MODE_PRIVATE).getBoolean("enabled", false));
        ShadeStore.setEnabled(context, false);
        assertFalse(ShadeStore.read(context).getBoolean("enabled"));
        var intent = Shadows.shadowOf((android.app.Application) context).getBroadcastIntents().get(0);
        assertEquals(ShadeStore.CHANGED, intent.getAction());
        assertEquals(ShadeStore.SYSTEM_UI, intent.getPackage());
    }

    public static class Panel {
        int updates;
        boolean fail;
        public void updateResources() {
            assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
            updates++;
            if (fail) throw new IllegalStateException("native reconfiguration failed");
        }
    }

    @Test public void hotRefreshUsesExistingPanelAndFailureDisablesOverride() throws Exception {
        var type = LandscapeShade.class;
        var method = type.getDeclaredField("updateResources"); method.setAccessible(true);
        var ready = type.getDeclaredField("ready"); ready.setAccessible(true);
        var enabled = type.getDeclaredField("enabled"); enabled.setAccessible(true);
        var refs = type.getDeclaredField("panels"); refs.setAccessible(true);
        var main = type.getDeclaredField("main"); main.setAccessible(true);
        var remember = type.getDeclaredMethod("remember", Object.class); remember.setAccessible(true);
        var refresh = type.getDeclaredMethod("refresh"); refresh.setAccessible(true);
        Panel panel = new Panel();
        try {
            method.set(null, Panel.class.getDeclaredMethod("updateResources"));
            main.set(null, new android.os.Handler(android.os.Looper.getMainLooper()));
            ready.setBoolean(null, true); enabled.setBoolean(null, true);
            remember.invoke(null, panel); remember.invoke(null, panel);
            refresh.invoke(null);
            assertEquals(1, panel.updates); // weak identity tracking must not duplicate calls
            Panel broken = new Panel();
            broken.fail = true;
            remember.invoke(null, broken);
            refresh.invoke(null);
            assertEquals(3, panel.updates); // success followed by native restore after another panel fails
            assertEquals(2, broken.updates); // one attempt, one best-effort native restore
            assertFalse(ready.getBoolean(null));
            assertFalse(enabled.getBoolean(null));
        } finally {
            ((java.util.List<?>) refs.get(null)).clear();
            method.set(null, null); main.set(null, null);
            ready.setBoolean(null, false); enabled.setBoolean(null, false);
        }
    }

    @Test public void providerAllowsOnlyOwnAndActualSystemUiUidAndNeverWrites() {
        PackageInfo systemUi = new PackageInfo();
        systemUi.packageName = ShadeStore.SYSTEM_UI;
        systemUi.applicationInfo = new ApplicationInfo();
        systemUi.applicationInfo.packageName = ShadeStore.SYSTEM_UI;
        systemUi.applicationInfo.uid = 10042;
        Shadows.shadowOf(context.getPackageManager()).installPackage(systemUi);
        ShadeProvider provider = new ShadeProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = ShadeStore.AUTHORITY;
        provider.attachInfo(context, info);
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        assertFalse(provider.call("read", null, null).getBoolean("enabled"));
        ShadowBinder.setCallingUid(10042);
        assertFalse(provider.call("read", null, null).getBoolean("enabled"));
        assertThrows(IllegalArgumentException.class, () -> provider.call("write", null, null));
        ShadowBinder.setCallingUid(12345);
        assertThrows(SecurityException.class, () -> provider.call("read", null, null));
        assertThrows(UnsupportedOperationException.class, () -> provider.update(null, null, null, null));
    }
}
