package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.os.*;
import android.view.View;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.function.Consumer;

/** Launcher owns a separate native rotation controller; only its action crosses to SystemUI. */
final class LauncherRotationSuggestion {
    static final String ACTION=Wire.PKG+".LAUNCHER_ROTATION", CHANNEL=ACTION+".v1";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static Proposal active;
    private static boolean restoring,remoteReady,connected;
    private static IBinder hostLease;
    private static Context application;
    static final String CONFIG=ACTION+".CONFIG";
    private static String hookStatus="尚未安装";
    static void connect(Context context){
        application=context.getApplicationContext();
        dev.local.sunwindowdemo.features.diagnostics.Diagnostics.initialize(application,dev.local.sunwindowdemo.features.diagnostics.Diagnostics.LAUNCHER);
        if(!connected){
            connected=true;
            application.registerReceiver(new android.content.BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent i){try{
                    if(getSentFromUid()!=c.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)return;
                    remoteReady=i.getBooleanExtra("enabled",false);
                    IBinder next=i.getExtras()==null?null:i.getExtras().getBinder("lease");
                    if(next!=hostLease){
                        hostLease=next;
                        if(next!=null)next.linkToDeath(()->main.post(()->{
                            if(hostLease==next){remoteReady=false;hostLease=null;if(active!=null)active.restore();}
                        }),0);
                    }
                    if(!remoteReady&&active!=null)active.restore();
                }catch(Exception ignored){} }
            },new android.content.IntentFilter(CONFIG),null,main,Context.RECEIVER_EXPORTED);
        }
        application.sendBroadcast(new Intent(ACTION).setPackage(BackBridge.PACKAGE).putExtra("hook",hookStatus),null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private static final class Proposal extends Binder {
        final Context context;final Object owner,button;final View view;final Method hide,state;
        final Field target;final int rotation;final int systemUiUid;
        boolean accepted;
        Proposal(Context c,Object o,Object b,View v,Method h,Method s,Field t)throws Exception {
            context=c;owner=o;button=b;view=v;hide=h;state=s;target=t;rotation=t.getInt(o);
            systemUiUid=c.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid;
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(Binder.getCallingUid()!=systemUiUid||code<1||code>3)return false;
            data.enforceInterface(CHANNEL);data.enforceNoDataAvail();
            main.post(()->{
                if(active!=this)return;
                try {
                    if(code==1){accepted=true;hide.invoke(button);}
                    else if(code==2){active=null;if(target.getInt(owner)==rotation)view.performClick();}
                    else restore();
                }catch(Exception ignored){active=null;}
            });return true;
        }
        void restore(){
            if(active!=this)return;active=null;restoring=true;
            try{state.invoke(owner,true,false);}catch(Exception ignored){}finally{restoring=false;}
            publish(false);
        }
        void publish(boolean shown){
            Bundle extras=new Bundle();extras.putBinder("proposal",this);
            context.sendBroadcast(new Intent(ACTION).setPackage(BackBridge.PACKAGE).putExtras(extras).putExtra("shown",shown),null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        }
    }
    static boolean send(IBinder endpoint,int code){
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(CHANNEL);return endpoint.transact(code,data,null,IBinder.FLAG_ONEWAY);}
        catch(RemoteException failure){return false;}finally{data.recycle();}
    }
    static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        try {
            Class<?> controller=Class.forName("com.android.systemui.shared.rotation.RotationButtonController",false,loader);
            Field button=controller.getDeclaredField("mRotationButton"),context=controller.getDeclaredField("mContext"),target=controller.getDeclaredField("mLastRotationSuggestion");
            Method state=controller.getDeclaredMethod("setRotateSuggestionButtonState",boolean.class,boolean.class);
            Method current=button.getType().getMethod("getCurrentView"),hide=button.getType().getMethod("hide");
            Field pending=controller.getDeclaredField("mPendingRotationSuggestion"),handler=controller.getDeclaredField("mUiHandler"),cancel=controller.getDeclaredField("mCancelPendingRotationProposal");
            pending.setAccessible(true);handler.setAccessible(true);cancel.setAccessible(true);
            button.setAccessible(true);context.setAccessible(true);target.setAccessible(true);state.setAccessible(true);
            module.hook(state).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(restoring||Looper.myLooper()!=Looper.getMainLooper())return chain.proceed();
                Object owner=chain.getThisObject();
                if(!(Boolean)chain.getArg(0)){
                    if(active!=null&&active.owner==owner){Proposal old=active;active=null;old.publish(false);}
                    return chain.proceed();
                }
                Context c=(Context)context.get(owner);if(!connected)connect(c);
                if(!remoteReady||hostLease==null||!hostLease.isBinderAlive())return chain.proceed();
                Object nativeButton=button.get(owner);
                View view=nativeButton==null?null:(View)current.invoke(nativeButton);
                if(view==null||!view.hasOnClickListeners())return chain.proceed();
                if(active!=null)active.publish(false);
                Proposal proposal=new Proposal(c,owner,nativeButton,view,hide,state,target);
                active=proposal;
                // Config is pre-authorized by SystemUI; require a per-proposal acknowledgement too.
                pending.setBoolean(owner,false);
                ((Handler)handler.get(owner)).removeCallbacks((Runnable)cancel.get(owner));
                Object result=chain.proceed(new Object[]{false,true});
                try{proposal.publish(true);}catch(RuntimeException failure){proposal.restore();return result;}
                main.postDelayed(()->{if(active==proposal&&!proposal.accepted)proposal.restore();},1000);
                return result;
            });
            Method destroy=controller.getDeclaredMethod("onDestroy");
            module.hook(destroy).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(active!=null&&active.owner==chain.getThisObject()){Proposal old=active;active=null;old.publish(false);}
                return chain.proceed();
            });
            // Preserve cancellation through native watcher/task/timeout callers after ART compilation.
            for(Method method:controller.getDeclaredMethods())if(!method.equals(state))module.deoptimize(method);
            for(String name:new String[]{"$TaskStackListenerImpl","$$ExternalSyntheticLambda0","$TaskStackListenerImpl$$ExternalSyntheticLambda0"}){
                try{for(Method method:Class.forName(controller.getName()+name,false,loader).getDeclaredMethods())module.deoptimize(method);}
                catch(Throwable unavailable){log.accept("LAUNCHER_ROTATION_DEOPT_UNAVAILABLE "+name+" "+unavailable);}
            }
            hookStatus="Launcher旋转Hook已安装";log.accept("LAUNCHER_ROTATION_READY");
        }catch(Throwable failure){hookStatus="Launcher安装失败: "+failure.getClass().getSimpleName();log.accept("LAUNCHER_ROTATION_UNAVAILABLE "+failure);}
    }
}
