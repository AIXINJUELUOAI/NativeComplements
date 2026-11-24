package dev.local.sunwindowdemo.features.shade;

import android.graphics.drawable.Drawable;
import android.telephony.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import dev.local.sunwindowdemo.core.ReflectiveAccess;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Public binder/callback ABI when the synthetic mobile emitter is absent. No ROM-name gate. */
final class MobileSignalBinder {
    private final Map<View,Integer> views = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Drawable,Integer> drawables = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object,Integer> subscriptions = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Class<?>> callbackTypes = new HashSet<>();
    private final Class<?> signalDrawable;
    private final Consumer<String> log;
    private volatile int mobileId;

    private MobileSignalBinder(Class<?> signalDrawable, Consumer<String> log) {
        this.signalDrawable = signalDrawable;
        this.log = log;
    }

    static void install(XposedModule module, ClassLoader loader, Class<?> signalDrawable,
                        List<XposedInterface.HookHandle> installed, Consumer<String> log) throws Exception {
        MobileSignalBinder host = new MobileSignalBinder(signalDrawable, log);
        Class<?> binder = Class.forName("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder", false, loader);
        Class<?> model = Class.forName("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel", false, loader);
        Class<?> logger = Class.forName("com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger", false, loader);
        Method bind = bindMethod(binder,model,logger);
        Method subscriptionId = ReflectiveAccess.method(model, "getSubscriptionId");
        if (subscriptionId.getReturnType() != int.class || java.lang.reflect.Modifier.isStatic(subscriptionId.getModifiers()))
            throw new NoSuchMethodException("mobile subscription id");
        Method image = ImageView.class.getDeclaredMethod("setImageDrawable", Drawable.class);
        Method level = Drawable.class.getDeclaredMethod("setLevel", int.class);
        Method register = TelephonyManager.class.getMethod("registerTelephonyCallback", java.util.concurrent.Executor.class, TelephonyCallback.class);
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        try {
            hooks.add(module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                try {
                    ViewGroup group = (ViewGroup) chain.getArg(0);
                    int sub = (Integer) subscriptionId.invoke(chain.getArg(1));
                    host.mobileId = group.getResources().getIdentifier("mobile_signal", "id", "com.android.systemui");
                    View view = host.mobileId == 0 ? null : group.findViewById(host.mobileId);
                    if (view != null) {
                        Drawable old = view instanceof ImageView icon ? icon.getDrawable() : null;
                        if (old != null) { host.drawables.remove(old); SignalDisplay.forget(old); }
                        host.views.remove(view);
                        if (SubscriptionManager.isValidSubscriptionId(sub)) host.views.put(view, sub);
                    }
                } catch (Exception failure) { log.accept("SIGNAL_BINDER_BIND_FAILED " + StackedSignalTargets.describe(failure)); }
                return chain.proceed();
            }));
            hooks.add(module.hook(image).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!(chain.getThisObject() instanceof ImageView view) || host.mobileId == 0 || view.getId() != host.mobileId)
                    return chain.proceed();
                Integer sub = host.views.get(view);
                Drawable old = view.getDrawable();
                Drawable next = (Drawable) chain.getArg(0);
                // Map before native setImageDrawable, which can synchronously call setLevel.
                if (sub != null && next != null && signalDrawable.isInstance(next)) host.drawables.put(next, sub);
                Object result;
                try { result = chain.proceed(); }
                catch (Throwable failure) {
                    if (next != old && next != null) host.drawables.remove(next);
                    throw failure;
                }
                if (old != null && old != next) { host.drawables.remove(old); SignalDisplay.forget(old); }
                return result;
            }));
            hooks.add(module.hook(level).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (SignalDisplay.redrawing() || !signalDrawable.isInstance(chain.getThisObject())) return chain.proceed();
                Drawable drawable = (Drawable) chain.getThisObject();
                Integer sub = host.drawables.get(drawable);
                if (sub == null) return chain.proceed();
                int nativeState = (Integer) chain.getArg(0);
                int adjusted = SignalDisplay.present(drawable, sub, nativeState);
                return adjusted == nativeState ? chain.proceed() : chain.proceed(new Object[]{adjusted});
            }));
            hooks.add(module.hook(register).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object callback = chain.getArg(1);
                // Only this repository's callback instances are associated, not unrelated SystemUI clients.
                if (callback != null && repositoryCallback(callback.getClass())) try {
                    int sub = ((TelephonyManager) chain.getThisObject()).getSubscriptionId();
                    if (SubscriptionManager.isValidSubscriptionId(sub)) host.callback(module, callback, sub, installed);
                } catch (Exception failure) { log.accept("SIGNAL_BINDER_CALLBACK_FAILED " + StackedSignalTargets.describe(failure)); }
                return chain.proceed();
            }));
            if (!module.deoptimize(bind)) throw new IllegalStateException("mobile bind deoptimize");
            installed.addAll(hooks);
        } catch (Throwable failure) {
            for (var hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
            throw failure;
        }
    }

    static Method bindMethod(Class<?> binder,Class<?> model,Class<?> logger)throws NoSuchMethodException{
        Method method=binder.getDeclaredMethod("bind",ViewGroup.class,model,int.class,logger);
        // Kotlin @JvmStatic and instance bind have identical argument positions; neither needs a receiver here.
        if(!method.getReturnType().getSimpleName().equals("ModernStatusBarViewBinding"))
            throw new NoSuchMethodException("MobileIconBinder.bind return ABI");
        method.setAccessible(true);return method;
    }

    static boolean repositoryCallback(Class<?> type) {
        return type.getName().startsWith("com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileConnectionRepositoryImpl$");
    }

    private synchronized void callback(XposedModule module, Object callback, int sub,
                                       List<XposedInterface.HookHandle> installed) throws Exception {
        Class<?> type = callback.getClass();
        if (!callbackTypes.contains(type)) {
            Method strength = ReflectiveAccess.method(type, "onSignalStrengthsChanged", SignalStrength.class);
            Method service = ReflectiveAccess.method(type, "onServiceStateChanged", ServiceState.class);
            Method ntn = null;
            try { ntn = ReflectiveAccess.method(type, "onCarrierRoamingNtnModeChanged", boolean.class); }
            catch (NoSuchMethodException optional) {}
            for (Method method : new Method[]{strength, service, ntn}) {
                if (method != null && (method.getReturnType() != void.class
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())))
                    throw new NoSuchMethodException(method.getName() + " callback ABI");
            }
            List<XposedInterface.HookHandle> hooks = new ArrayList<>();
            try {
                Method[] methods = {strength, service, ntn};
                for (int i = 0; i < methods.length; i++) {
                    if (methods[i] == null) continue;
                    final int kind = i;
                    hooks.add(module.hook(methods[i]).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object result = chain.proceed();
                        Integer subscription = subscriptions.get(chain.getThisObject());
                        if (subscription != null) SignalDisplay.observe(chain.getThisObject(), subscription, kind, chain.getArg(0));
                        return result;
                    }));
                }
                installed.addAll(hooks);
                callbackTypes.add(type);
            } catch (Throwable failure) {
                for (var hook : hooks) try { hook.unhook(); } catch (Throwable ignored) {}
                throw failure;
            }
        }
        subscriptions.put(callback, sub);
    }
}
