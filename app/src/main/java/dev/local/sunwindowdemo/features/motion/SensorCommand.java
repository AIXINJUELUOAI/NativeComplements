package dev.local.sunwindowdemo.features.motion;

import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Calls the platform SensorService UID policy, never a shell process or an application Binder payload. */
final class SensorCommand {
    private final Method service,shell;
    SensorCommand()throws Exception{
        service=Class.forName("android.os.ServiceManager").getDeclaredMethod("getService",String.class);service.setAccessible(true);
        shell=IBinder.class.getDeclaredMethod("shellCommand",FileDescriptor.class,FileDescriptor.class,FileDescriptor.class,
            String[].class,Class.forName("android.os.ShellCallback"),ResultReceiver.class);shell.setAccessible(true);
        if(service.getReturnType()!=IBinder.class||shell.getReturnType()!=void.class)throw new NoSuchMethodException("sensor command signature");
    }
    void execute(String... args)throws Exception{
        long identity=Binder.clearCallingIdentity();
        try(ParcelFileDescriptor sink=ParcelFileDescriptor.open(new File("/dev/null"),ParcelFileDescriptor.MODE_READ_WRITE)){
            Object binder=service.invoke(null,"sensorservice");if(binder==null)throw new IllegalStateException("sensorservice missing");
            AtomicInteger result=new AtomicInteger(Integer.MIN_VALUE);ConditionVariable done=new ConditionVariable();
            ResultReceiver receiver=new ResultReceiver(null){@Override protected void onReceiveResult(int code,Bundle data){result.set(code);done.open();}};
            FileDescriptor fd=sink.getFileDescriptor();shell.invoke(binder,fd,fd,fd,args,null,receiver);
            if(!done.block(1500))throw new IllegalStateException("sensorservice command timeout");
            if(result.get()!=0)throw new IllegalStateException("sensorservice status="+result.get());
        }finally{Binder.restoreCallingIdentity(identity);}
    }
    void restrict(String pkg)throws Exception{execute("set-uid-state",pkg,"idle","--user","0");}
    void restore(String pkg)throws Exception{execute("reset-uid-state",pkg,"--user","0");}
}
