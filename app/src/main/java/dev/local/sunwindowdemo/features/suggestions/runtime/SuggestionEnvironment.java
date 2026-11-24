package dev.local.sunwindowdemo;

import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.*;
import android.view.WindowInsets;
import android.view.WindowManager;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.List;
import java.util.function.Consumer;

/** Read-only companion facts and explicit OTP input; never acquires or controls a window task. */
final class SuggestionEnvironment {
    static final String RESULT=Wire.PKG+".SUGGESTION_ENVIRONMENT";
    static final long MAX_AGE_MS=2500;
    // Android's per-user UID range; same primary-user projection as WindowBackendEligibility.
    static int userId(){return android.os.Process.myUid()/100000;}

    static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        try{
            Class<?> wms=Class.forName("com.android.server.wm.WindowManagerService",false,loader);
            module.hook(method(wms,"systemReady")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{connect(module,new Access(chain.getThisObject()),log);}
                catch(Exception error){log.accept("SUGGESTIONS_ENVIRONMENT_UNAVAILABLE "+error.getClass().getSimpleName());}
                return result;
            });
        }catch(Exception error){log.accept("SUGGESTIONS_ENVIRONMENT_UNAVAILABLE "+error.getClass().getSimpleName());}
    }
    private static void connect(XposedModule module,Access a,Consumer<String> log)throws Exception{
        Handler main=new Handler(Looper.getMainLooper());
        SuggestionInput input;
        try{input=new SuggestionInput(a);log.accept("SUGGESTIONS_INPUT_READY");}
        catch(Exception error){input=null;log.accept("SUGGESTIONS_INPUT_UNAVAILABLE "+error.getClass().getSimpleName());}
        SuggestionInput executor=input;
        Runnable publish=()->{
            Intent facts=new Intent(RESULT).setPackage(BackBridge.PACKAGE).putExtra("protocol",Wire.PROTOCOL);
            a.snapshot(facts);
            a.context.sendBroadcast(facts,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        };
        a.context.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context ignored,Intent request){
                if(!authorized(a.context,getSentFromUid())||request.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
                String op=request.getStringExtra("op");
                if("STATUS".equals(op))publish.run();
                else if("OTP_INPUT".equals(op)){
                    if(executor!=null)executor.start(request);
                    else {ResultReceiver reply=request.getParcelableExtra("inputReply",ResultReceiver.class);if(reply!=null)reply.send(0,null);}
                }
            }
        },new IntentFilter(Wire.COMMAND),null,main,Context.RECEIVER_EXPORTED);
        // Reuse the existing STATUS heartbeat; native changes only enqueue one fresh read on the main thread.
        Runnable changed=()->{main.removeCallbacks(publish);main.post(publish);};
        for(Method event:new Method[]{a.focusChanged,a.overlayChanged})try{
            module.hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean hiding=event==a.overlayChanged&&((List<?>)a.hidingWindows.get(a.service)).contains(chain.getArg(0));
                Object result=chain.proceed();
                if(event==a.focusChanged?Boolean.TRUE.equals(result):hiding!=((List<?>)a.hidingWindows.get(a.service)).contains(chain.getArg(0)))changed.run();
                return result;
            });
        }catch(Exception unavailable){log.accept("SUGGESTIONS_ENVIRONMENT_EVENT_UNAVAILABLE "+event.getName());}
        publish.run();log.accept("SUGGESTIONS_ENVIRONMENT_READY");
    }
    static boolean authorized(Context context,int uid){
        if(uid<0||!UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle()))return false;
        try{return uid==context.getPackageManager().getPackageUid(Wire.PKG,0)
            ||uid==context.getPackageManager().getPackageUid(BackBridge.PACKAGE,0);}
        catch(Exception unavailable){return false;}
    }
    static boolean fresh(Intent facts,long now,int user){
        if(facts==null||facts.hasExtra("environmentError")||!facts.hasExtra("foreground")
            ||!facts.hasExtra("fullscreenApp")||!facts.hasExtra("systemOverlay")||!facts.hasExtra("systemOverlayTitle")
            ||!facts.hasExtra("imeVisible")||!facts.hasExtra("imeTop")
            ||facts.getIntExtra("environmentUser",-1)!=user||facts.getIntExtra("environmentDisplay",-1)!=0)return false;
        long at=facts.getLongExtra("environmentAt",-1),age=now-at;
        return at>=0&&age>=0&&age<=MAX_AGE_MS;
    }
    static Method method(Class<?> owner,String name,Class<?>... args)throws NoSuchMethodException{
        for(Class<?> type=owner;type!=null;type=type.getSuperclass())try{
            Method member=type.getDeclaredMethod(name,args);member.setAccessible(true);return member;
        }catch(NoSuchMethodException missing){}
        throw new NoSuchMethodException(owner.getName()+"."+name);
    }
    private static Field field(Class<?> owner,String name)throws NoSuchFieldException{
        for(Class<?> type=owner;type!=null;type=type.getSuperclass())try{
            Field member=type.getDeclaredField(name);member.setAccessible(true);return member;
        }catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(owner.getName()+"."+name);
    }

    /** Exact, suggestion-local WMS ABI. No freeform, transition or surface preflight. */
    static final class Access {
        final Context context;
        final Object service,root,lock;
        final Field currentFocus,attrs,displayId,taskUser,packageName,hidingWindows,currentUser;
        final Method defaultDisplay,focusedTask,imeWindow,visible,owningUid,windowDisplay;
        final Method topActivity,taskDisplay,windowingMode,activityType,leaf,taskVisible,allTasks;
        final Method bounds,insetsController,rawInsets,calculateInsets,focusChanged,overlayChanged;
        Access(Object wms)throws Exception{
            service=wms;Class<?> type=wms.getClass();
            context=(Context)field(type,"mContext").get(wms);root=field(type,"mRoot").get(wms);lock=field(type,"mGlobalLock").get(wms);
            defaultDisplay=method(root.getClass(),"getDefaultDisplay");focusedTask=method(root.getClass(),"getTopDisplayFocusedLeafTask");
            Class<?> display=defaultDisplay.getReturnType(),task=focusedTask.getReturnType();
            currentFocus=field(display,"mCurrentFocus");displayId=field(display,"mDisplayId");
            Class<?> window=currentFocus.getType();attrs=field(window,"mAttrs");
            imeWindow=method(display,"getImeWindow");visible=method(window,"isVisible");owningUid=method(window,"getOwningUid");windowDisplay=method(window,"getDisplayContent");
            topActivity=method(task,"topRunningActivity");taskDisplay=method(task,"getDisplayContent");taskUser=field(task,"mUserId");
            packageName=field(topActivity.getReturnType(),"packageName");windowingMode=method(task,"getWindowingMode");activityType=method(task,"getActivityType");
            leaf=method(task,"isLeafTask");taskVisible=method(task,"isVisibleRequested");allTasks=method(display,"forAllTasks",Consumer.class);
            bounds=method(display,"getBounds");insetsController=method(display,"getInsetsStateController");rawInsets=method(insetsController.getReturnType(),"getRawInsetsState");
            calculateInsets=method(rawInsets.getReturnType(),"calculateInsets",Rect.class,Rect.class,int.class,boolean.class);
            hidingWindows=field(type,"mHidingNonSystemOverlayWindows");currentUser=field(type,"mCurrentUserId");
            focusChanged=method(type,"updateFocusedWindowLocked",int.class,boolean.class);
            overlayChanged=method(type,"updateNonSystemOverlayWindowsVisibilityIfNeeded",window,boolean.class);
            if(!List.class.isAssignableFrom(hidingWindows.getType())||visible.getReturnType()!=boolean.class
                ||owningUid.getReturnType()!=int.class||displayId.getType()!=int.class||taskUser.getType()!=int.class||currentUser.getType()!=int.class
                ||calculateInsets.getReturnType()!=Insets.class||focusChanged.getReturnType()!=boolean.class
                ||overlayChanged.getReturnType()!=void.class)throw new NoSuchMethodException("suggestion environment ABI");
        }
        Object display()throws Exception{
            Object display=defaultDisplay.invoke(root);
            if(display==null||displayId.getInt(display)!=0||currentUser.getInt(service)!=userId())
                throw new IllegalStateException("default display/current user unavailable");
            return display;
        }
        boolean visible(Object window)throws Exception{return window!=null&&(Boolean)visible.invoke(window);}
        boolean currentUser(int uid){return uid>=0&&UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle());}
        boolean editor(Object display,Object focus,int uid)throws Exception{
            Object task=focusedTask.invoke(root),ime=imeWindow.invoke(display);
            if(task==null||taskDisplay.invoke(task)!=display||taskUser.getInt(task)!=userId()
                ||focus==null||currentFocus.get(display)!=focus||!android.os.Process.isApplicationUid(uid)||!currentUser(uid)||windowDisplay.invoke(focus)!=display||!visible(focus)
                ||(Integer)owningUid.invoke(focus)!=uid||!visible(ime)||windowDisplay.invoke(ime)!=display)return false;
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)attrs.get(focus);
            return p.type>=1&&p.type<WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW;
        }
        void snapshot(Intent out){
            synchronized(lock){try{
                Object display=display(),focus=currentFocus.get(display),task=focusedTask.invoke(root);
                if(focus==null||windowDisplay.invoke(focus)!=display||!currentUser((Integer)owningUid.invoke(focus)))throw new IllegalStateException("unknown focus user/display");
                if(task!=null&&(taskUser.getInt(task)!=userId()||taskDisplay.invoke(task)!=display))throw new IllegalStateException("unknown task user/display");
                String foreground=task==null?"":app(task),fullscreen="";
                Object[] full={null};boolean[] done={false};
                allTasks.invoke(display,(Consumer<Object>)candidate->{
                    if(done[0])return;
                    try{
                        if(!(Boolean)leaf.invoke(candidate)||!(Boolean)taskVisible.invoke(candidate)||(Integer)windowingMode.invoke(candidate)!=1)return;
                        done[0]=true;
                        if(taskUser.getInt(candidate)==userId()
                            &&taskDisplay.invoke(candidate)==display&&(Integer)activityType.invoke(candidate)==1)full[0]=candidate;
                    }catch(Exception error){throw new IllegalStateException(error);}
                });
                if(full[0]!=null)fullscreen=app(full[0]);
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)attrs.get(focus);String title=String.valueOf(p.getTitle());
                int systemUi=context.getPackageManager().getPackageUid(BackBridge.PACKAGE,0);
                boolean ownOverlay=(Integer)owningUid.invoke(focus)==systemUi&&BackBridge.PACKAGE.equals(p.packageName)
                    &&("NativeComplements-suggestions".equals(title)||"NativeComplements-controls".equals(title)
                        ||"NativeComplements-dismiss".equals(title)||"NativeComplements-suggestion-dismiss".equals(title));
                boolean system=(p.type>=WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW||BackBridge.PACKAGE.equals(p.packageName))&&!ownOverlay;
                for(Object hiding:(List<?>)hidingWindows.get(service))if(windowDisplay.invoke(hiding)==display&&visible(hiding)){system=true;break;}
                boolean keyboard=visible(imeWindow.invoke(display));Rect rect=(Rect)bounds.invoke(display);
                Object state=rawInsets.invoke(insetsController.invoke(display));
                Insets insets=(Insets)calculateInsets.invoke(state,rect,rect,WindowInsets.Type.ime(),false);
                if(!context.getSystemService(PowerManager.class).isInteractive()||context.getSystemService(KeyguardManager.class).isKeyguardLocked())system=true;
                out.putExtra("foreground",foreground).putExtra("fullscreenApp",fullscreen).putExtra("systemOverlay",system).putExtra("systemOverlayTitle",title)
                    .putExtra("imeVisible",keyboard).putExtra("imeTop",keyboard&&insets.bottom>0?rect.bottom-insets.bottom:Integer.MAX_VALUE)
                    .putExtra("environmentAt",SystemClock.elapsedRealtime()).putExtra("environmentDisplay",0)
                    .putExtra("environmentUser",userId());
            }catch(Exception error){out.putExtra("environmentError",error.getClass().getSimpleName());}}
        }
        private String app(Object task)throws Exception{Object top=topActivity.invoke(task);return top==null?"":(String)packageName.get(top);}
    }
}
