package dev.local.sunwindowdemo.features.alive;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.provider.Settings;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

/** One native sleep episode; no authentication, power, input or global scrim state is replaced. */
public final class AliveSleepHandoff {
    public static final String ACTION_PREPARE="dev.nativecomplements.module.alive.PREPARE_SLEEP";
    public static final String ACTION_CANCEL="dev.nativecomplements.module.alive.CANCEL_SLEEP";
    public static final int REPLY_CODE=IBinder.FIRST_CALL_TRANSACTION;
    private static final String DESCRIPTOR="dev.nativecomplements.module.alive.SleepReply";
    private static final long MAX_MS=7800;
    private AliveSleepHandoff() {}

    public static final class Request {
        public final long expires;
        public final String token;
        private final IBinder reply;
        private final int display;
        private Request(long expires,String token,IBinder reply,int display){this.expires=expires;this.token=token;this.reply=reply;this.display=display;}
        public boolean expired(){return SystemClock.uptimeMillis()>=expires;}
        /** Call only after this visible Engine's valid Surface has successfully swapped its sleep frame. */
        public void ready(){send(1);}
        public void cancel(){send(0);}
        private void send(int state){
            if(expired()||!reply.isBinderAlive())return;
            Parcel data=Parcel.obtain();
            try{data.writeInterfaceToken(DESCRIPTOR);data.writeString(token);data.writeInt(display);data.writeInt(state);
                reply.transact(REPLY_CODE,data,null,IBinder.FLAG_ONEWAY);
            }catch(RemoteException|RuntimeException ignored){}finally{data.recycle();}
        }
    }
    public static Request parse(String action,Bundle extras,int displayId){
        if(!ACTION_PREPARE.equals(action)||extras==null||displayId!=Display.DEFAULT_DISPLAY)return null;
        try{
            long expires=extras.getLong("expires"),now=SystemClock.uptimeMillis();
            String token=extras.getString("token");IBinder reply=extras.getBinder("reply");
            return expires>now&&expires-now<=MAX_MS&&token!=null&&token.length()==36&&reply!=null
                ?new Request(expires,token,reply,displayId):null;
        }catch(RuntimeException unknown){return null;}
    }

    interface Selection {
        Object token(Object repository);
        int uid(Object repository);
        void changes(Runnable listener);
    }
    static void install(XposedModule module,ClassLoader loader,Selection selection,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        Host host=null;
        try{
            host=new Host(loader,selection,log);Host h=host;
            hooks.add(module.hook(h.layout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                try{h.captureWindow(chain.getThisObject());h.beforeLayout(chain.getThisObject());}catch(ReflectiveOperationException|RuntimeException failure){h.cancel("layout-failed");}
                try{return chain.proceed();}finally{h.afterLayout(chain.getThisObject());}
            }));
            hooks.add(module.hook(h.draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                View view=(View)chain.getThisObject();
                boolean clip=false;
                try{if(h.sameRoot(view))h.scrim=new WeakReference<>(view);clip=h.enabled&&h.ready&&h.safe()&&h.sameRoot(view);}catch(ReflectiveOperationException|RuntimeException unknown){h.cancel("draw-unknown");}
                h.traceClip(view,clip);
                if(clip&&!h.visual){h.visual=true;h.log.accept("ALIVE_HANDOFF_VISUAL ready=true ep="+h.episode());}
                if(!clip)return chain.proceed();
                Canvas canvas=(Canvas)chain.getArg(0);int saved=canvas.save();
                try{canvas.clipRect(0,0,0,0);return chain.proceed();}finally{canvas.restoreToCount(saved);}
            }));
            hooks.add(module.hook(h.sleep).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();h.main.post(h::begin);return result;
            }));
            hooks.add(module.hook(h.wake).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();h.main.post(()->{h.cancel("wake");h.sleepSignalled=false;});return result;
            }));
            hooks.add(module.hook(h.sleepAnimations).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(Looper.myLooper()!=Looper.getMainLooper())return chain.proceed();
                h.begin();Object previous=h.sleepScope.get();h.sleepScope.set(chain.getThisObject());
                try{return chain.proceed();}finally{if(previous==null)h.sleepScope.remove();else h.sleepScope.set(previous);}
            }));
            hooks.add(module.hook(h.startAnimation).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject(),previous=h.admissionScope.get();boolean eligible=false;
                try{eligible=h.sleepScope.get()!=null&&h.admissionSafe(owner);}catch(ReflectiveOperationException|RuntimeException unknown){h.log.accept("ALIVE_HANDOFF_ADMISSION unavailable="+unknown.getClass().getSimpleName());}
                h.log.accept("ALIVE_HANDOFF_ADMISSION enabled="+h.enabled+" selected="+eligible);
                if(eligible)h.admissionScope.set(owner);else h.admissionScope.remove();
                try{return chain.proceed();}finally{if(previous==null)h.admissionScope.remove();else h.admissionScope.set(previous);}
            }));
            hooks.add(module.hook(h.shouldPlay).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                if(Boolean.TRUE.equals(result)&&h.admissionScope.get()==chain.getThisObject()){
                    try{
                        if(h.admissionSafe(chain.getThisObject())){
                            // Only original startAnimation's own false branch writes its native decision cache.
                            h.log.accept("ALIVE_HANDOFF_ADMISSION selected=true native=true projected=false");return Boolean.FALSE;
                        }
                    }catch(ReflectiveOperationException|RuntimeException unknown){h.log.accept("ALIVE_HANDOFF_ADMISSION native-fallback="+unknown.getClass().getSimpleName());}
                }
                return result;
            }));
            for(Method method:new Method[]{h.layout,h.draw,h.sleep,h.wake,h.sleepAnimations,h.startAnimation,h.shouldPlay})
                if(!module.deoptimize(method))throw new IllegalStateException("sleep handoff deoptimize");
            h.enabled=true;selection.changes(()->h.main.post(h::selectionChanged));
            log.accept("ALIVE_HANDOFF_READY native window and first-swap scoped");
            // Diagnostics are optional: their failure never changes the installed handoff.
            try{AliveTransitionTrace.install(module,loader,h::traceEpisode,()->h.traceId,h::traceRoot,log);}
            catch(Throwable unavailable){log.accept("ALIVE_TRACE_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
        }catch(Throwable unknown){
            if(host!=null){host.enabled=false;host.cancel("install-failed");}
            for(XposedInterface.HookHandle hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("ALIVE_HANDOFF_UNAVAILABLE "+unknown.getClass().getSimpleName());
        }
    }

    private static final class Host {
        final Handler main=new Handler(Looper.getMainLooper());
        final Selection selection;final Consumer<String> log;
        final Field root,params,state,keyguard,showing,occluded,going,fading,bouncer,depth,depthOwner,wallpapers,repository;
        final Field stateShowing,stateOccluded,stateBouncer,stateGoing;
        final Method layout,apply,draw,sleep,wake,sleepAnimations,startAnimation,shouldPlay,lazyGet;
        final Field nativeWindow,nativeScrim,nativePlaying,nativeAnimator,nativeDecision,animations;
        final ThreadLocal<Object> sleepScope=new ThreadLocal<>(),admissionScope=new ThreadLocal<>();
        WeakReference<Object> window=new WeakReference<>(null);
        WeakReference<View> scrim=new WeakReference<>(null);
        boolean enabled,active,ready,visual,restoring,sleepSignalled,resent;
        IBinder response;
        DisplayManager displays;
        Context observedContext;
        final ContentObserver settingObserver=new ContentObserver(main){
            @Override public void onChange(boolean selfChange){cancel("AOD-setting");}
        };
        final BroadcastReceiver changes=new BroadcastReceiver(){
            public void onReceive(Context context,Intent intent){
                if(Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())||"android.intent.action.USER_SWITCHED".equals(intent.getAction()))cancel("wallpaper-user");
            }
        };
        final ComponentCallbacks configuration=new ComponentCallbacks(){
            public void onConfigurationChanged(Configuration value){cancel("configuration");}
            public void onLowMemory(){}
        };
        int rotation,width,height;
        final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){}
            public void onDisplayRemoved(int id){if(id==Display.DEFAULT_DISPLAY)cancel("display-removed");}
            public void onDisplayChanged(int id){
                if(id!=Display.DEFAULT_DISPLAY||!active)return;
                try{
                    View value=view();Display display=value==null?null:value.getDisplay();
                    if(display==null||(display.getState()!=Display.STATE_ON&&display.getState()!=Display.STATE_DOZE)
                            ||display.getRotation()!=rotation||value.getWidth()!=width||value.getHeight()!=height)cancel("display-config");
                }catch(RuntimeException|ReflectiveOperationException unknown){cancel("display-unknown");}
            }
        };
        boolean layoutPending;int layoutOriginal;
        int clipEdges;boolean clipKnown,lastClip;
        volatile String traceId;
        String episode(){return traceId==null?"none":traceId;}
        String traceEpisode(){
            // Host visual callbacks are main-thread only; no cross-thread reflection or settings polling.
            if(Looper.myLooper()!=Looper.getMainLooper()||!enabled||!active||nonce==null||SystemClock.uptimeMillis()>=expires)return null;
            try{
                Object owner=window.get();View value=view();
                if(owner==null||value==null||!value.isAttachedToWindow()||value.getDisplay()==null
                        ||value.getDisplay().getDisplayId()!=Display.DEFAULT_DISPLAY)return null;
                Object token=selection.token(repo(owner));
                return identity instanceof AliveSystemUi.MatchToken old&&token instanceof AliveSystemUi.MatchToken now&&old.same(now)?episode():null;
            }catch(ReflectiveOperationException|RuntimeException unknown){return null;}
        }
        boolean traceRoot(View value){
            try{return value!=null&&sameRoot(value);}catch(IllegalAccessException|RuntimeException unknown){return false;}
        }
        void traceClip(View value,boolean clip){
            try{
                if(clipEdges>=8||traceEpisode()==null||!traceRoot(value)||clipKnown&&clip==lastClip)return;
                clipKnown=true;lastClip=clip;clipEdges++;
                log.accept("ALIVE_TRACE ep="+episode()+" clip="+clip+" ready="+ready+" visual="+visual);
            }catch(RuntimeException ignored){/* Diagnostics never alter the draw path. */}
        }
        volatile String nonce;
        long expires;Object identity;int uid;
        final Runnable timeout=()->cancel("timeout");
        final View.OnAttachStateChangeListener attached=new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){}
            public void onViewDetachedFromWindow(View view){cancel("detach");}
        };
        Host(ClassLoader loader,Selection selection,Consumer<String> log)throws ReflectiveOperationException {
            this.selection=selection;this.log=log;
            Class<?> owner=type(loader,"com.android.systemui.shade.NotificationShadeWindowControllerImpl");
            Class<?> windowState=type(loader,"com.android.systemui.shade.NotificationShadeWindowState");
            root=field(owner,"mWindowRootView",type(loader,"com.android.systemui.scene.ui.view.WindowRootView"));
            params=field(owner,"mLpChanged",WindowManager.LayoutParams.class);
            state=field(owner,"mCurrentState",windowState);
            keyguard=field(owner,"mKeyguardStateController",type(loader,"com.android.systemui.statusbar.policy.KeyguardStateController"));
            Class<?> keyguardImpl=type(loader,"com.android.systemui.statusbar.policy.KeyguardStateControllerImpl");
            showing=field(keyguardImpl,"mShowing",boolean.class);occluded=field(keyguardImpl,"mOccluded",boolean.class);
            going=field(keyguardImpl,"mKeyguardGoingAway",boolean.class);fading=field(keyguardImpl,"mKeyguardFadingAway",boolean.class);
            bouncer=field(keyguardImpl,"mPrimaryBouncerShowing",boolean.class);
            stateShowing=field(windowState,"keyguardShowing",boolean.class);stateOccluded=field(windowState,"keyguardOccluded",boolean.class);
            stateBouncer=field(windowState,"bouncerShowing",boolean.class);stateGoing=field(windowState,"keyguardGoingAway",boolean.class);
            Class<?> blur=type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController");
            depth=field(owner,"mScrimsVisibilityListener",type(loader,"com.android.systemui.statusbar.NotificationShadeDepthController$1"));
            Class<?> interactor=type(loader,"com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor");
            wallpapers=field(blur,"wallpaperInteractor",interactor);
            repository=field(interactor,"wallpaperRepository",type(loader,"com.android.systemui.wallpapers.data.repository.WallpaperRepositoryImpl"));
            layout=owner.getDeclaredMethod("applyWindowLayoutParams");apply=owner.getDeclaredMethod("apply",windowState);
            draw=type(loader,"com.android.systemui.statusbar.LightRevealScrim").getDeclaredMethod("onDraw",Canvas.class);
            Class<?> service=type(loader,"com.android.systemui.keyguard.KeyguardService$2");
            sleep=service.getDeclaredMethod("onStartedGoingToSleep",int.class);wake=service.getDeclaredMethod("onStartedWakingUp",int.class,boolean.class);
            Class<?> controller=type(loader,"com.android.systemui.statusbar.phone.ScreenOffAnimationController");
            Class<?> unlocked=type(loader,"com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController");
            sleepAnimations=controller.getDeclaredMethod("onStartedGoingToSleep");
            animations=field(controller,"animations",java.util.List.class);
            startAnimation=unlocked.getDeclaredMethod("startAnimation");shouldPlay=unlocked.getDeclaredMethod("shouldPlayUnlockedScreenOffAnimation");
            nativeWindow=field(unlocked,"notifShadeWindowControllerLazy",type(loader,"dagger.Lazy"));
            nativeScrim=field(unlocked,"lightRevealScrim",draw.getDeclaringClass());
            nativePlaying=field(unlocked,"lightRevealAnimationPlaying",boolean.class);
            nativeAnimator=field(unlocked,"lightRevealAnimator",android.animation.ValueAnimator.class);
            nativeDecision=field(unlocked,"decidedToAnimateGoingToSleep",Boolean.class);
            lazyGet=nativeWindow.getType().getDeclaredMethod("get");lazyGet.setAccessible(true);
            for(Method method:new Method[]{layout,apply,draw,sleep,wake,sleepAnimations}){method.setAccessible(true);if(method.getReturnType()!=void.class)throw new NoSuchMethodException("handoff ABI");}
            for(Method method:new Method[]{startAnimation,shouldPlay}){method.setAccessible(true);if(method.getReturnType()!=boolean.class)throw new NoSuchMethodException("admission ABI");}
            // DepthAnimation listener is exact to this native window; no global singleton guessed from a different display.
            depthOwner=field(depth.getType(),"this$0",blur);
        }
        Object repo(Object owner)throws ReflectiveOperationException {
            Object listener=depth.get(owner);if(listener==null)return null;
            Object blur=depthOwner.get(listener);
            return repository.get(wallpapers.get(blur));
        }
        void captureWindow(Object owner)throws IllegalAccessException {
            View candidate=(View)root.get(owner);
            if(candidate!=null&&candidate.getDisplay()!=null&&candidate.getDisplay().getDisplayId()==Display.DEFAULT_DISPLAY)
                window=new WeakReference<>(owner);
        }
        View view()throws IllegalAccessException {Object owner=window.get();return owner==null?null:(View)root.get(owner);}
        boolean sameRoot(View candidate)throws IllegalAccessException {
            View rootView=view();return rootView!=null&&candidate.isAttachedToWindow()&&candidate.getRootView()==rootView.getRootView();
        }
        boolean admissionSafe(Object animation)throws ReflectiveOperationException {
            if(Looper.myLooper()!=Looper.getMainLooper()||!enabled||!active||SystemClock.uptimeMillis()>=expires)return false;
            Object owner=window.get(),controller=sleepScope.get();View value=view();
            if(owner==null||controller==null||value==null||!value.isAttachedToWindow()||value.getDisplay()==null
                    ||value.getDisplay().getDisplayId()!=Display.DEFAULT_DISPLAY||value.getDisplay().getState()!=Display.STATE_ON)return false;
            if(!((List<?>)animations.get(controller)).contains(animation)||lazyGet.invoke(nativeWindow.get(animation))!=owner)return false;
            View reveal=(View)nativeScrim.get(animation);if(reveal==null||!sameRoot(reveal))return false;
            if(nativePlaying.getBoolean(animation)||((android.animation.ValueAnimator)nativeAnimator.get(animation)).isStarted()
                    ||nativeDecision.get(animation)!=null)return false;
            PowerManager power=value.getContext().getSystemService(PowerManager.class);
            if(power==null||power.isInteractive())return false;
            Object token=selection.token(repo(owner));
            if(!(identity instanceof AliveSystemUi.MatchToken old)||!(token instanceof AliveSystemUi.MatchToken now)||!old.same(now))return false;
            Object guard=keyguard.get(owner),snapshot=state.get(owner);
            return !occluded.getBoolean(guard)&&!going.getBoolean(guard)&&!fading.getBoolean(guard)&&!bouncer.getBoolean(guard)
                &&!stateOccluded.getBoolean(snapshot)&&!stateBouncer.getBoolean(snapshot)&&!stateGoing.getBoolean(snapshot);
        }
        boolean safe()throws ReflectiveOperationException {
            Object owner=window.get();View view=view();
            if(!enabled||!active||SystemClock.uptimeMillis()>=expires||owner==null||view==null||!view.isAttachedToWindow())return false;
            Display display=view.getDisplay();
            if(display==null||display.getDisplayId()!=Display.DEFAULT_DISPLAY||(display.getState()!=Display.STATE_ON&&display.getState()!=Display.STATE_DOZE))return false;
            PowerManager power=view.getContext().getSystemService(PowerManager.class);
            if(power==null||power.isInteractive())return false;
            Object now=selection.token(repo(owner));
            if(!(identity instanceof AliveSystemUi.MatchToken old)||!(now instanceof AliveSystemUi.MatchToken current)||!old.same(current))return false;
            Object guard=keyguard.get(owner),snapshot=state.get(owner);
            return showing.getBoolean(guard)&&!occluded.getBoolean(guard)&&!going.getBoolean(guard)&&!fading.getBoolean(guard)&&!bouncer.getBoolean(guard)
                &&stateShowing.getBoolean(snapshot)&&!stateOccluded.getBoolean(snapshot)&&!stateBouncer.getBoolean(snapshot)&&!stateGoing.getBoolean(snapshot);
        }
        void begin(){
            if(!enabled||sleepSignalled)return;
            sleepSignalled=true;
            try{
                Object owner=window.get();View view=view();if(owner==null||view==null||view.getDisplay()==null||view.getDisplay().getDisplayId()!=0)return;
                Object repo=repo(owner);Object token=selection.token(repo);int expected=selection.uid(repo);
                if(token==null||expected<0)return;
                identity=token;uid=expected;nonce=UUID.randomUUID().toString();expires=SystemClock.uptimeMillis()+MAX_MS;active=true;ready=false;resent=false;
                traceId=UUID.randomUUID().toString().substring(0,8);clipEdges=0;clipKnown=false;
                rotation=view.getDisplay().getRotation();width=view.getWidth();height=view.getHeight();
                displays=view.getContext().getSystemService(DisplayManager.class);
                if(displays!=null)displays.registerDisplayListener(displayListener,main);
                observedContext=view.getContext();observedContext.registerComponentCallbacks(configuration);
                IntentFilter filter=new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);filter.addAction("android.intent.action.USER_SWITCHED");
                observedContext.registerReceiver(changes,filter,Context.RECEIVER_EXPORTED);
                observedContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("doze_always_on"),false,settingObserver);
                view.addOnAttachStateChangeListener(attached);main.postDelayed(timeout,MAX_MS);
                String episode=nonce;int expectedUid=uid;
                response=new Binder(){
                    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                        if(code!=REPLY_CODE||Binder.getCallingUid()!=expectedUid)return false;
                        try{
                            data.enforceInterface(DESCRIPTOR);String value=data.readString();int display=data.readInt(),status=data.readInt();data.enforceNoDataAvail();
                            if(!Objects.equals(episode,value)||!Objects.equals(nonce,value)||display!=0)return false;
                            main.post(()->ack(value,status));return true;
                        }catch(RuntimeException malformed){return false;}
                    }
                };
                send(ACTION_PREPARE,response);
                refresh();log.accept("ALIVE_HANDOFF_PREPARE expires="+expires+" ep="+episode());
            }catch(ReflectiveOperationException|RuntimeException unknown){cancel("begin-failed");}
        }
        void beforeLayout(Object owner)throws ReflectiveOperationException {
            if(restoring||!active||owner!=window.get())return;
            if(safe()){
                WindowManager.LayoutParams value=(WindowManager.LayoutParams)params.get(owner);
                layoutOriginal=value.flags;layoutPending=true;value.flags|=WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                View overlay=scrim.get();if(ready&&overlay!=null)overlay.invalidate();
            }else if(visual)main.post(()->cancel("state"));
        }
        void afterLayout(Object owner){
            if(!layoutPending)return;
            layoutPending=false;
            try{
                ((WindowManager.LayoutParams)params.get(owner)).flags=layoutOriginal;
                if(active&&!resent){
                    resent=true;String token=nonce;
                    main.post(()->{
                        if(!active||!Objects.equals(nonce,token))return;
                        try{if(safe())send(ACTION_PREPARE,response);}catch(ReflectiveOperationException|RuntimeException unknown){cancel("resend-failed");}
                    });
                }
            }catch(IllegalAccessException|RuntimeException unknown){enabled=false;log.accept("ALIVE_HANDOFF_LAYOUT_RESET_FAILED");}
        }
        void ack(String token,int status){
            if(!active||!Objects.equals(nonce,token))return;
            if(status==0){cancel("engine-cancel");return;}
            try{
                if(status!=1||SystemClock.uptimeMillis()>=expires){cancel("unsafe-ack");return;}
                ready=true;View value=scrim.get();if(value!=null)value.invalidate();
                refresh();log.accept("ALIVE_HANDOFF_FIRST_SWAP safe="+safe()+" ep="+episode());
            }catch(ReflectiveOperationException|RuntimeException unknown){cancel("ack-failed");}
        }
        void selectionChanged(){
            if(!active)return;
            try{
                Object token=selection.token(repo(window.get()));
                if(!(identity instanceof AliveSystemUi.MatchToken old)||!(token instanceof AliveSystemUi.MatchToken now)||!old.same(now))cancel("selection");
            }catch(ReflectiveOperationException|RuntimeException unknown){cancel("selection-failed");}
        }
        void send(String action,IBinder reply)throws ReflectiveOperationException {
            View view=view();if(view==null||view.getWindowToken()==null)return;
            Bundle extras=new Bundle();extras.putLong("expires",expires);extras.putString("token",nonce);if(reply!=null)extras.putBinder("reply",reply);
            WallpaperManager.getInstance(view.getContext()).sendWallpaperCommand(view.getWindowToken(),action,0,0,0,extras);
        }
        void refresh(){
            Object owner=window.get();if(owner==null)return;
            try{apply.invoke(owner,state.get(owner));}catch(ReflectiveOperationException|RuntimeException unknown){
                enabled=false;log.accept("ALIVE_HANDOFF_RESTORE_FAILED disabled "+unknown.getClass().getSimpleName());
            }
            View value=scrim.get();if(value!=null)value.invalidate();
        }
        void cancel(String reason){
            if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->cancel(reason));return;}
            String ended=episode();traceId=null;
            boolean was=active;active=false;ready=false;visual=false;main.removeCallbacks(timeout);
            if(displays!=null){displays.unregisterDisplayListener(displayListener);displays=null;}
            if(observedContext!=null){
                observedContext.unregisterComponentCallbacks(configuration);
                try{observedContext.unregisterReceiver(changes);}catch(IllegalArgumentException ignored){}
                observedContext.getContentResolver().unregisterContentObserver(settingObserver);observedContext=null;
            }
            try{View view=view();if(view!=null)view.removeOnAttachStateChangeListener(attached);if(was)send(ACTION_CANCEL,null);}catch(ReflectiveOperationException|RuntimeException ignored){}
            if(was){restoring=true;try{refresh();}finally{restoring=false;}log.accept("ALIVE_HANDOFF_END "+reason+" ep="+ended);}
            identity=null;nonce=null;response=null;traceId=null;
        }
    }
    private static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> owner,String name,Class<?> type)throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);if(field.getType()!=type)throw new NoSuchFieldException(name);return field;
    }
}
