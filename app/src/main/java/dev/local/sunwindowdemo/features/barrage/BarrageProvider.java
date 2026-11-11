package dev.local.sunwindowdemo.features.barrage;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Read-only configuration snapshot for the module and the existing SystemUI UID. */
public final class BarrageProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        try{if(uid!=getContext().getApplicationInfo().uid&&uid!=getContext().getPackageManager().getApplicationInfo("com.android.systemui",0).uid)throw new SecurityException("barrage reader");}
        catch(android.content.pm.PackageManager.NameNotFoundException e){throw new SecurityException(e);}
        if(!"read".equals(method))throw new IllegalArgumentException("method");
        return BarrageStore.read(getContext());
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
