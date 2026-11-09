package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.*;
import android.view.*;
import java.lang.reflect.Method;

/** Explicit user-triggered digit input. No Activity or app-process hook, no global text injection. */
final class SuggestionInput {
    private final FrameworkAccess a;
    private final Context context;
    private final Object root,lock;
    private final Handler handler;
    private final InputManager input;
    private final Method inject;
    private boolean running;
    private long sequence,deadline;
    private Object focus;
    private int targetUid;
    private ResultReceiver reply;

    SuggestionInput(FrameworkAccess access,Context c,Object root,Object lock,Handler handler)throws Exception{
        a=access;context=c;this.root=root;this.lock=lock;
        input=c.getSystemService(InputManager.class);
        inject=InputManager.class.getDeclaredMethod("injectInputEvent",InputEvent.class,int.class,int.class);
        if(inject.getReturnType()!=boolean.class)throw new NoSuchMethodException("targeted injection");inject.setAccessible(true);
        Class<?> window=Class.forName("com.android.server.wm.WindowState",false,root.getClass().getClassLoader());
        a.method(window,"getOwningUid");a.field(window,"mAttrs");a.method(window,"isVisible");
        HandlerThread thread=new HandlerThread("NativeSuggestionInput");thread.start();this.handler=new Handler(thread.getLooper());
    }
    void start(android.content.Intent request){
        handler.post(()->begin(request));
    }
    private void begin(android.content.Intent request){
        ResultReceiver receiver=request.getParcelableExtra("inputReply",ResultReceiver.class);
        String digits=request.getStringExtra("digits");int delay=request.getIntExtra("delay",100);
        if(receiver==null)return;
        if(running||digits==null||!digits.matches("[0-9]{4,8}")||delay<50||delay>500
                ||!Wire.validToken(request.getStringExtra("token"))){receiver.send(0,null);return;}
        try{synchronized(lock){
            Object display=a.call(root,"getDefaultDisplay"),current=a.field(display,"mCurrentFocus");
            Object ime=a.call(display,"getImeWindow");
            if(current==null||ime==null||!a.bool(ime,"isVisible")||!a.bool(current,"isVisible"))throw new IllegalStateException("no editor window");
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)a.field(current,"mAttrs");
            if(attrs.type<1||attrs.type>=WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW)throw new IllegalStateException("not an application");
            targetUid=a.integer(current,"getOwningUid");if(targetUid<10000)throw new IllegalStateException("system input");
            focus=current;
        }
            reply=receiver;running=true;deadline=SystemClock.uptimeMillis()+6000L+delay*digits.length();sendDigit(++sequence,digits,delay,0);
        }catch(Exception e){receiver.send(0,null);}
    }
    private void sendDigit(long generation,String digits,int delay,int position){
        if(!running||generation!=sequence)return;
        if(SystemClock.uptimeMillis()>deadline){finish(false);return;}
        try{
            synchronized(lock){
                Object display=a.call(root,"getDefaultDisplay"),ime=a.call(display,"getImeWindow");
                if(!context.getSystemService(PowerManager.class).isInteractive()
                        ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                        ||a.field(display,"mCurrentFocus")!=focus||!a.bool(focus,"isVisible")
                        ||a.integer(focus,"getOwningUid")!=targetUid||ime==null||!a.bool(ime,"isVisible")){
                    finish(false);return;
                }
            }
            long when=SystemClock.uptimeMillis();int key=KeyEvent.KEYCODE_0+digits.charAt(position)-'0';
            KeyEvent down=new KeyEvent(when,when,KeyEvent.ACTION_DOWN,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
            boolean pressed=(Boolean)inject.invoke(input,down,2,targetUid);
            // Always pair a submitted DOWN, even when the focused-window check changes after dispatch.
            KeyEvent up=new KeyEvent(when,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
            boolean released=(Boolean)inject.invoke(input,up,2,targetUid);
            if(!pressed||!released){finish(false);return;}
            if(position+1==digits.length()){finish(true);return;}
            handler.postDelayed(()->sendDigit(generation,digits,delay,position+1),delay);
        }catch(Exception e){finish(false);}
    }
    private void finish(boolean success){running=false;focus=null;ResultReceiver receiver=reply;reply=null;if(receiver!=null)receiver.send(success?1:0,null);}
}
