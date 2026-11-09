package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.UUID;

final class Wire {
    static final String PKG = "dev.local.sunwindowdemo";
    static final String COMMAND = PKG + ".COMMAND", RESULT = PKG + ".RESULT";
    static final String PERMISSION = PKG + ".CONTROL";
    static final int PROTOCOL = 16;
    static final Binder LEASE = new Binder();
    static final String CHANNEL = PKG + ".frames.v16";
    static final int MESSAGE = IBinder.FIRST_CALL_TRANSACTION;
    private static volatile IBinder endpoint;
    private static java.util.function.Consumer<Intent> listener;
    private static Intent pendingFrame;
    private static boolean framePosted;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Binder FRAMES = new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code!=MESSAGE)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=1000)return false;
            data.enforceInterface(CHANNEL);
            Intent frame=Intent.CREATOR.createFromParcel(data);data.enforceNoDataAvail();
            if(frame.getIntExtra("protocol",0)!=PROTOCOL)return false;
            synchronized(Wire.class){
                pendingFrame=frame;
                if(!framePosted){framePosted=true;main.post(Wire::deliverFrame);}
            }
            return true;
        }
    };
    private static void deliverFrame(){
        Intent frame;
        synchronized(Wire.class){frame=pendingFrame;pendingFrame=null;framePosted=false;}
        if(listener!=null&&frame!=null)listener.accept(frame);
    }
    static void listen(java.util.function.Consumer<Intent> sink){listener=sink;if(sink==null){endpoint=null;synchronized(Wire.class){pendingFrame=null;}}}
    // Called only after the RESULT sender UID and protocol have been checked.
    static void acceptBackend(Intent i){Bundle b=i.getExtras();if(b!=null)endpoint=b.getBinder("controlChannel");}
    static boolean transmit(IBinder target,Intent i){
        if(target==null)return false;
        Parcel data=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);i.writeToParcel(data,0);return target.transact(MESSAGE,data,null,IBinder.FLAG_ONEWAY);}
        catch(RemoteException e){return false;}finally{data.recycle();}
    }
    static String token() { return "sunwindow5:" + UUID.randomUUID(); }
    static boolean validToken(String value) {
        return value != null && value.matches("sunwindow5:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
    static boolean allowedPackage(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }
    static void send(Context context, String op, String pkg, int taskId, String token, boolean landscape) {
        Intent i = new Intent(COMMAND).setPackage("android").putExtra("protocol", PROTOCOL)
            .putExtra("op", op).putExtra("package", pkg).putExtra("taskId", taskId)
            .putExtra("token", token).putExtra("landscape", landscape).putExtra("hideRecents",new Config(context).hideRecents());
        sendRequest(context,i);
    }
    static void openShortcut(Context context,ShortcutTarget target,PendingIntent launch){
        if(!target.isShortcut()||!launch.isActivity())throw new IllegalArgumentException("shortcut activity");
        Intent i=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",target.pkg).putExtra("taskId",-1).putExtra("token",token())
            .putExtra("hideRecents",new Config(context).hideRecents()).putExtra("shortcutLaunch",launch).putExtra("shortcutUser",target.user);
        sendRequest(context,i);
    }
    private static void sendRequest(Context context,Intent i){
        if(BackBridge.PACKAGE.equals(context.getPackageName()))i.putExtra("hostReady",SystemUiHost.live!=null&&SystemUiHost.live.ready()).putExtra("lastTouch",SystemUiHost.lastTouch);
        Bundle b = new Bundle(); b.putBinder("lease", LEASE); if(listener!=null)b.putBinder("frameChannel",FRAMES); i.putExtras(b);
        context.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static void send(Context c, String op) { send(c, op, PKG, -1, null, false); }
    static void control(Context c,String op,String token,float value,float x,float y) {
        Intent i=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL)
            .putExtra("op",op).putExtra("token",token).putExtra("scale",value).putExtra("x",x).putExtra("y",y);
        if(transmit(endpoint,i))return;
        endpoint=null;
        c.sendBroadcast(i,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static void notification(Context c, boolean active) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!active) { nm.cancel(5); return; }
        nm.createNotificationChannel(new NotificationChannel("test", "小窗测试恢复", NotificationManager.IMPORTANCE_LOW));
        Intent i = new Intent(c, MainActivity.class).setAction("recover")
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent p = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            nm.notify(5, new Notification.Builder(c, "test")
                .setSmallIcon(android.R.drawable.ic_menu_revert).setContentTitle("小窗承载测试进行中")
                .setContentText("点击恢复原窗口；小窗无定时退出")
                .setContentIntent(p).setOngoing(true).build());
        } catch (SecurityException ignored) { /* The launcher entry still provides recovery. */ }
    }
}
