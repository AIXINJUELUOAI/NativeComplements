package dev.local.sunwindowdemo.features.iconlayers;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.FileNotFoundException;
import java.util.*;

/** Standalone read-only native-icon projection. No imported assets or generation entrypoint. */
public final class WindowIconProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private boolean checkCaller(){
        int uid=Binder.getCallingUid();
        boolean owner=uid==getContext().getApplicationInfo().uid;
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        boolean sameUser=UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle());
        boolean launcher=sameUser&&packages!=null&&Arrays.asList(packages).contains(IconLayerStore.LAUNCHER);
        boolean systemUi=sameUser&&packages!=null&&Arrays.asList(packages).contains(IconLayerStore.SYSTEM_UI);
        if(!owner&&!launcher&&!systemUi&&uid!=android.os.Process.SYSTEM_UID)throw new SecurityException("window icon bridge");
        return launcher;
    }
    @Override public Bundle call(String method,String arg,Bundle extras){
        boolean launcher=checkCaller();
        if("read".equals(method)){
            Bundle result=new Bundle();
            result.putBoolean("enabled",false);result.putBoolean("notifications",false);result.putInt("mode",0);
            result.putString("generation","");result.putString("policies","{}");
            result.putStringArrayList("packages",new ArrayList<>());
            result.putStringArrayList("originals",new ArrayList<>());result.putStringArrayList("restore",new ArrayList<>());
            Set<String> installed=new HashSet<>();
            for(var activity:getContext().getSystemService(android.content.pm.LauncherApps.class)
                    .getActivityList(null,android.os.Process.myUserHandle())){
                String pkg=activity.getComponentName().getPackageName();
                if(pkg!=null&&pkg.length()<=240&&pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))installed.add(pkg);
            }
            result.putStringArrayList("targets",new ArrayList<>(installed));
            result.putBoolean("systemThemed",IconLayerStore.systemThemed(getContext()));
            result.putBoolean("coverMonet",dev.local.sunwindowdemo.features.shade.ShadeStore.coverMonet(getContext()));
            result.putBoolean("windowMonet",dev.local.sunwindowdemo.features.shade.ShadeStore.windowMonet(getContext()));
            return result;
        }
        if("report".equals(method)){
            if(!launcher)throw new SecurityException("window theme reporter");
            if(extras==null||extras.size()!=1||!(extras.get("systemThemed") instanceof Boolean))
                throw new IllegalArgumentException("theme boolean only");
            IconLayerStore.report(getContext(),extras);return Bundle.EMPTY;
        }
        if("started".equals(method)){
            if(Binder.getCallingUid()!=android.os.Process.SYSTEM_UID)throw new SecurityException("launch observer");
            return Bundle.EMPTY;
        }
        throw new IllegalArgumentException("method");
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        checkCaller();throw new FileNotFoundException("standalone has no imported icon assets");
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
