package dev.local.sunwindowdemo.features.motion;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Read-only rules for system_server; status writes are restricted to the system UID. */
public final class MotionProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        if(uid!=1000&&uid!=getContext().getApplicationInfo().uid)throw new SecurityException("motion bridge");
        if("read".equals(method))return MotionStore.read(getContext());
        if("backend".equals(method)&&uid==1000){MotionStore.backend(getContext(),arg,extras==null?"":extras.getString("error",""));return Bundle.EMPTY;}
        throw new IllegalArgumentException("method");
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
