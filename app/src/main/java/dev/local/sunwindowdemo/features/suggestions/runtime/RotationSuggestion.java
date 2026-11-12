package dev.local.sunwindowdemo;

import android.os.Looper;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Replace presentation only; retain the native proposal, timeout and click policy. */
final class RotationSuggestion {
    private RotationSuggestion() {}

    private static android.os.IBinder launcherProposal;
    private static Object systemUiProposalOwner;
    static void connect(android.app.Application app) {
        RotationDiagnostics.connect(app);
        dev.local.sunwindowdemo.features.diagnostics.Diagnostics.initialize(app,dev.local.sunwindowdemo.features.diagnostics.Diagnostics.SYSTEM_UI);
        app.registerReceiver(new android.content.BroadcastReceiver(){
            @Override public void onReceive(android.content.Context c,android.content.Intent intent){
                try {
                    if(getSentFromUid()!=app.getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid)return;
                    if(intent.hasExtra("hook")){
                        RotationDiagnostics.update("launcher_hook",intent.getStringExtra("hook"));
                        if(SystemUiHost.live!=null)SystemUiHost.live.publishRotationSettings();return;
                    }
                    android.os.IBinder proposal=intent.getExtras()==null?null:intent.getExtras().getBinder("proposal");
                    if(proposal==null)return;
                    boolean shown=intent.getBooleanExtra("shown",false);
                    SystemUiHost host=SystemUiHost.live;
                    RotationDiagnostics.update("request",shown?"Launcher显示请求已命中":"Launcher撤销请求已命中");
                    if(host==null){RotationDiagnostics.update("host","SystemUI宿主未启动");return;}
                    if(!shown){if(launcherProposal==proposal){launcherProposal=null;host.rotationSuggestion(null,null);}return;}
                    Runnable click=()->{if(launcherProposal==proposal){launcherProposal=null;LauncherRotationSuggestion.send(proposal,2);}};
                    Runnable restore=()->{if(launcherProposal==proposal){launcherProposal=null;LauncherRotationSuggestion.send(proposal,3);}};
                    launcherProposal=proposal;
                    proposal.linkToDeath(()->new android.os.Handler(Looper.getMainLooper()).post(()->{
                        if(launcherProposal==proposal){launcherProposal=null;if(SystemUiHost.live!=null)SystemUiHost.live.rotationSuggestion(null,null);}
                    }),0);
                    if(host.rotationSuggestion(click,restore)){
                        systemUiProposalOwner=null;
                        if(!LauncherRotationSuggestion.send(proposal,1)){launcherProposal=null;host.rotationSuggestion(null,null);}
                    }else launcherProposal=null;
                }catch(Exception failure){RotationDiagnostics.update("host","Launcher桥接失败: "+failure.getClass().getSimpleName());}
            }
        },new android.content.IntentFilter(LauncherRotationSuggestion.ACTION),null,new android.os.Handler(Looper.getMainLooper()),android.content.Context.RECEIVER_EXPORTED);
    }

    static void install(XposedModule module, ClassLoader loader, Consumer<String> log) {
        try {
            Class<?> controller = Class.forName("com.android.systemui.shared.rotation.RotationButtonController", false, loader);
            Method state = controller.getDeclaredMethod("setRotateSuggestionButtonState", boolean.class, boolean.class);
            Field button = controller.getDeclaredField("mRotationButton");
            Field keyView = button.getType().getDeclaredField("mKeyButtonView");
            Field proposal = controller.getDeclaredField("mLastRotationSuggestion");
            Field pending = controller.getDeclaredField("mPendingRotationSuggestion");
            Field handler = controller.getDeclaredField("mUiHandler");
            Field cancel = controller.getDeclaredField("mCancelPendingRotationProposal");
            state.setAccessible(true); button.setAccessible(true); keyView.setAccessible(true); proposal.setAccessible(true);
            pending.setAccessible(true); handler.setAccessible(true); cancel.setAccessible(true);
            module.hook(state).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                SystemUiHost host = SystemUiHost.live;
                RotationDiagnostics.update("request", (Boolean)chain.getArg(0) ? "显示请求已命中" : "原生撤销请求已命中");
                if (host == null || Looper.myLooper() != Looper.getMainLooper()) {
                    RotationDiagnostics.update("host",host==null?"SystemUI宿主未启动":"请求不在主线程");
                    return chain.proceed();
                }
                if (!(Boolean) chain.getArg(0)) {
                    if(systemUiProposalOwner==chain.getThisObject()){systemUiProposalOwner=null;host.rotationSuggestion(null,null);}
                    return chain.proceed();
                }
                Object owner = chain.getThisObject();
                Object nativeButton = button.get(owner);
                View view = nativeButton == null ? null : (View) keyView.get(nativeButton);
                if (view == null || !view.hasOnClickListeners()) {
                    RotationDiagnostics.update("host", view==null?"原生按钮为空":"原生按钮尚无点击监听");
                    return chain.proceed();
                }
                int target = proposal.getInt(owner);
                Runnable click = () -> {
                    try {
                        // A queued tap must never accept a different or expired proposal.
                        if (proposal.getInt(owner) == target) {
                            RotationDiagnostics.update("click","已调用原生建议动作");view.performClick();
                        }
                    } catch (Exception failure) { log.accept("ROTATION_CLICK_FAILED " + failure); }
                };
                Runnable restore = () -> {
                    try { state.invoke(owner, true, false); }
                    catch (Exception failure) { log.accept("ROTATION_RESTORE_FAILED " + failure); }
                };
                // Fail open: settings/host/window failures must not swallow the native button.
                if (!host.rotationSuggestion(click, restore)) return chain.proceed();
                systemUiProposalOwner=owner;launcherProposal=null;
                // Native state(false) returns early for an invisible button; retain show-side cleanup.
                pending.setBoolean(owner, false);
                ((android.os.Handler) handler.get(owner)).removeCallbacks((Runnable) cancel.get(owner));
                return chain.proceed(new Object[]{false, true});
            });
            // The archived DEX invokes this method explicitly. Deopt its callers for ART inlining.
            String[] callers = {
                "com.android.systemui.navigationbar.views.NavigationBar",
                "com.android.systemui.navigationbar.views.NavigationBar$3",
                "com.android.systemui.shared.rotation.RotationButtonController",
                "com.android.systemui.shared.rotation.RotationButtonController$$ExternalSyntheticLambda0",
                "com.android.systemui.shared.rotation.RotationButtonController$TaskStackListenerImpl$$ExternalSyntheticLambda0"
            };
            for (String name : callers) {
                try {
                    for (Method method : Class.forName(name, false, loader).getDeclaredMethods()) {
                        if (!java.util.Set.of("onHomeRotationEnabled", "disable", "onRecentsAnimationStateChanged",
                                "onRotationProposal", "onViewAttached", "run", "onRotationWatcherChanged",
                                "showAndLogRotationSuggestion").contains(method.getName())) continue;
                        log.accept("ROTATION_DEOPT method=" + name + "#" + method.getName() + " result=" + module.deoptimize(method));
                    }
                } catch (Throwable failure) { log.accept("ROTATION_DEOPT_UNAVAILABLE " + name + " " + failure); }
            }
            RotationDiagnostics.update("hook","SystemUI旋转Hook已安装");
            log.accept("ROTATION_SUGGESTION_READY");
        } catch (Throwable failure) {
            RotationDiagnostics.update("hook","安装失败: "+failure.getClass().getSimpleName());
            log.accept("ROTATION_SUGGESTION_UNAVAILABLE " + failure);
        }
    }
}
