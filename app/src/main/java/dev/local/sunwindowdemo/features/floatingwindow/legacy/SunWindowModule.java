package dev.local.sunwindowdemo;

import android.graphics.Rect;
import io.github.libxposed.api.*;
import java.util.*;

public final class SunWindowModule extends XposedModule {
    private boolean eligible, attempted;
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        eligible = p.isSystemServer();
        note("MODULE_LOADED process=" + p.getProcessName() + " systemServer=" + eligible + " version=0.8.2-test1");
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam p) {
        note("SYSTEM_SERVER_START eligible=" + eligible + " attempted=" + attempted);
        if (!eligible || attempted) return;
        attempted = true;
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            FrameworkAccess a = new FrameworkAccess(p.getClassLoader());
            WindowEngine engine = new WindowEngine(a, this::note);
            handles.add(hook(a.systemReady).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); note("WMS_READY_SEEN"); engine.attach(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.prepare).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.prepared(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.changed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.configurationChanged(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.freeform).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.owns(chain.getThisObject())) { engine.bounds(chain.getThisObject(), (Rect) chain.getArg(0)); return null; }
                return chain.proceed();
            }));
            handles.add(hook(a.recentVisible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if(engine.hideRecent(chain.getArg(0)))return false;
                return chain.proceed();
            }));
            handles.add(hook(a.systemBars).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if((Boolean)chain.getArg(3)&&engine.preserveImmersive(chain.getThisObject(),chain.getArg(0))){
                    return chain.proceed(new Object[]{chain.getArg(0),chain.getArg(1),chain.getArg(2),false});
                }
                return chain.proceed();
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBack",a.task)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.backToClose(chain.getThisObject()))return true;
                return chain.proceed();
            }));
            Class<?> window=Class.forName("com.android.server.wm.WindowState",false,p.getClassLoader());
            handles.add(hook(a.method(window,"canReceiveTouchInput")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.blockContentTouch(chain.getThisObject()))return false;
                return chain.proceed();
            }));
            Class<?> transition=Class.forName("com.android.server.wm.Transition",false,p.getClassLoader());
            handles.add(hook(a.method(transition,"collect",a.wc)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();engine.collected(chain.getThisObject(),chain.getArg(0));return result;
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBackInner",a.task,transition)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.beforeBackInner(chain.getThisObject(),chain.getArg(1));
                Object result=chain.proceed();engine.afterBackInner(chain.getThisObject());return result;
            }));
            handles.add(hook(a.requestTransition).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.requesting(chain.getArg(0),chain.getArg(1));return chain.proceed();
            }));
            note("HOOKS_READY count=11 sunwindow-0.8.2-test1; disarmed until explicit command");
        } catch (Throwable e) {
            for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (Throwable ignored) { }
            note("DISABLED signature-preflight " + FrameworkAccess.reason(e));
        }
    }
    private void note(String message) {
        try { android.util.Log.i("SunWindowDemo5", message); } catch (Throwable ignored) { }
        try { log(4, "SunWindowDemo5", message); } catch (Throwable ignored) { }
    }
}
