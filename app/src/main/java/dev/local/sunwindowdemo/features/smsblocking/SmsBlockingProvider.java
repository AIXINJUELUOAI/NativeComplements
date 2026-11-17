package dev.local.sunwindowdemo.features.smsblocking;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Separate from window settings: only the owner, system and the phone stack may use this bridge. */
public final class SmsBlockingProvider extends ContentProvider {
    public static final String AUTHORITY="dev.nativecomplements.module.smsblocking";
    public static final Uri CONFIG=Uri.parse("content://"+AUTHORITY+"/config");
    private static final int SYSTEM_UID=1000;
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        if(uid!=getContext().getApplicationInfo().uid && uid!=SYSTEM_UID && !phone(uid))
            throw new SecurityException("sms blocking bridge");
        switch(method){
            case "read": return SmsBlockingStore.readConfig(getContext());
            case "contacts": {
                if(uid!=SYSTEM_UID&&!phone(uid))throw new SecurityException("phone contacts snapshot");
                Bundle out=new Bundle();
                if(getContext().checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                        !=android.content.pm.PackageManager.PERMISSION_GRANTED)return out;
                long identity=Binder.clearCallingIdentity();
                java.util.HashSet<String> numbers=new java.util.HashSet<>();
                try(Cursor cursor=getContext().getContentResolver().query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,null)){
                    if(cursor==null)return out;
                    while(cursor.moveToNext()){
                        String number=dev.local.sunwindowdemo.features.callblocking.CallBlockingRules
                                .normalizeNumber(cursor.getString(0));
                        if(!number.isEmpty())numbers.add(number);
                        // ponytail: keep Binder snapshots bounded; oversized books disable only stranger blocking.
                        if(numbers.size()>4_000)return out;
                    }
                    out.putInt("permission_uid",getContext().getApplicationInfo().uid);
                    out.putStringArray("numbers",numbers.toArray(new String[0]));
                }catch(RuntimeException unavailable){return Bundle.EMPTY;}
                finally{Binder.restoreCallingIdentity(identity);}
                return out;
            }
            case "record": {
                if(extras==null)throw new IllegalArgumentException("record");
                Bundle out=new Bundle();
                out.putBoolean("stored",SmsBlockingStore.recordBlocked(getContext(),extras));
                return out;
            }
            case "backend": {
                if(uid!=SYSTEM_UID&&!phone(uid))throw new SecurityException("phone backend");
                SmsBlockingStore.updateBackend(getContext(),arg,extras==null?"":extras.getString("error",""));
                return Bundle.EMPTY;
            }
            default: throw new IllegalArgumentException("method");
        }
    }
    /** Resolve the system phone package in this provider's user; missing identity always denies. */
    private boolean phone(int uid){
        if(!UserHandle.getUserHandleForUid(uid).equals(android.os.Process.myUserHandle()))return false;
        try{
            android.content.pm.ApplicationInfo app=getContext().getPackageManager().getApplicationInfo("com.android.phone",0);
            return app.uid==uid && (app.flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
        }catch(Exception unavailable){return false;}
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
