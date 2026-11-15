package dev.local.sunwindowdemo;

import android.app.BroadcastOptions;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.*;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/** A non-owner never creates an engine. It only answers authenticated status requests. */
final class WindowBackendConflict {
    static void install(XposedModule module,ClassLoader loader,String owner,boolean delegated,Consumer<String> log)throws Exception {
        Class<?> wms=Class.forName("com.android.server.wm.WindowManagerService",false,loader);
        Field context=wms.getDeclaredField("mContext");context.setAccessible(true);
        module.hook(wms.getDeclaredMethod("systemReady")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object result=chain.proceed();
            Context host=(Context)context.get(chain.getThisObject());
            Handler main=new Handler(Looper.getMainLooper());
            int manager=host.getPackageManager().getPackageUid(Wire.PKG,0);
            int systemUi=host.getPackageManager().getPackageUid(BackBridge.PACKAGE,0);
            host.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context ignored,Intent request){
                    int uid=getSentFromUid();
                    if(uid!=manager&&uid!=systemUi)return;
                    if(request.getIntExtra("protocol",0)!=Wire.PROTOCOL||!"STATUS".equals(request.getStringExtra("op")))return;
                    Intent status=new Intent(Wire.RESULT).addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                        .putExtra("event","STATUS").putExtra("protocol",Wire.PROTOCOL).putExtra("state","conflict")
                        .putExtra("backendOwner",owner).putExtra("ownershipConflict",true)
                        .putExtra("delegatedToStandalone",delegated)
                        .putExtra("capabilitiesKnown",false).putExtra("preflightReady",false)
                        .putExtra("supportsMultiWindow",false).putExtra("supportsFreeform",false)
                        .putExtra("hostConnected",false).putExtra("hasJournal",false)
                        .putExtra("report",(delegated?"本次启动已选择独立小窗：":"本版本未取得小窗接管权，归属：")+owner+
                            "。请使用对应版本的小窗设置；主版的小窗后端选择仅下次完整重启生效。本版本未创建小窗引擎，不会迁移或覆盖另一版本的恢复记录。");
                    String requestId=request.getStringExtra("requestId");
                    if(requestId!=null&&!requestId.isEmpty()&&requestId.length()<=128)status.putExtra("requestId",requestId);
                    var options=BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                    host.sendBroadcast(new Intent(status).setPackage(Wire.PKG),Wire.PERMISSION,options);
                    host.sendBroadcast(new Intent(status).setPackage(BackBridge.PACKAGE),null,options);
                }
            },new IntentFilter(Wire.COMMAND),null,main,Context.RECEIVER_EXPORTED);
            log.accept("WINDOW_BACKEND_CONFLICT owner="+owner+" delegatedToStandalone="+delegated);
            return result;
        });
    }
}
