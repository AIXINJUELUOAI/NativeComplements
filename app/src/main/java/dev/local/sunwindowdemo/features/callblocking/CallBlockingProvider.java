package dev.local.sunwindowdemo.features.callblocking;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Separate from window settings: only the owner and the system Telecom may use this bridge. */
public final class CallBlockingProvider extends ContentProvider {
    public static final String AUTHORITY="dev.local.sunwindowdemo.callblocking";
    public static final Uri CONFIG=Uri.parse("content://"+AUTHORITY+"/config");
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        if(uid!=1000 && uid!=getContext().getApplicationInfo().uid)
            throw new SecurityException("call blocking bridge");
        switch(method){
            case "read": return CallBlockingStore.readConfig(getContext());
            case "record": {
                if(extras==null)throw new IllegalArgumentException("record");
                Bundle out=new Bundle();
                out.putBoolean("stored",CallBlockingStore.recordBlocked(getContext(),extras));
                return out;
            }
            case "backend": {
                if(uid!=1000)throw new SecurityException("system backend");
                CallBlockingStore.updateBackend(getContext(),arg,extras==null?"":extras.getString("error",""));
                return Bundle.EMPTY;
            }
            default: throw new IllegalArgumentException("method");
        }
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
