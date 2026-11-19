package dev.local.sunwindowdemo;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.core.LauncherTargets;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class DesktopSleepHostTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final Intent homeIntent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);

    @After public void clearRoutingCache(){LauncherTargets.invalidate();}

    @Test public void onlyFreshDefaultDisplayUnlockedInteractiveTrustedRequestsPass(){
        assertTrue(DesktopSleepHost.requestAllowed(true,true,0,1000,1000,true,false));
        assertTrue(DesktopSleepHost.requestAllowed(true,true,0,0,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,0,0,1001,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,0,1001,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,0,-1,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,1,1000,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,-1,1000,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(false,true,0,1000,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,false,0,1000,1000,true,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,0,1000,1000,false,false));
        assertFalse(DesktopSleepHost.requestAllowed(true,true,0,1000,1000,true,true));
    }

    @Test public void missingFailedAndCachedSettingsCannotAuthorizeSleep(){
        assertFalse(DesktopSleepHost.configEnabled(null));
        assertFalse(DesktopSleepHost.configEnabled(new HostSnapshot.Result(null,true,false,"")));
        Bundle config=new Bundle();
        assertFalse(DesktopSleepHost.configEnabled(new HostSnapshot.Result(config,true,false,"")));
        config.putBoolean("desktopDoubleTapSleep",true);
        assertTrue(DesktopSleepHost.configEnabled(new HostSnapshot.Result(config,true,false,"")));
        assertFalse(DesktopSleepHost.configEnabled(new HostSnapshot.Result(config,false,true,"cached")));
        config.putBoolean("desktopDoubleTapSleep",false);
        assertFalse(DesktopSleepHost.configEnabled(new HostSnapshot.Result(config,true,false,"")));
    }

    private void home(String name,int uid,boolean system){
        PackageInfo pkg=new PackageInfo();pkg.packageName=name;
        pkg.applicationInfo=new ApplicationInfo();pkg.applicationInfo.packageName=name;
        pkg.applicationInfo.uid=uid;pkg.applicationInfo.flags=system?ApplicationInfo.FLAG_SYSTEM:0;
        var pm=Shadows.shadowOf(context.getPackageManager());pm.installPackage(pkg);
        ResolveInfo resolved=new ResolveInfo();resolved.activityInfo=new ActivityInfo();
        resolved.activityInfo.packageName=name;resolved.activityInfo.name=name+".Home";
        resolved.activityInfo.applicationInfo=pkg.applicationInfo;
        pm.setResolveInfosForIntent(homeIntent,List.of(resolved));
        LauncherTargets.invalidate();
    }
    private ActivityManager.RunningTaskInfo task(String pkg,String activity,int display,int user)throws Exception{
        ActivityManager.RunningTaskInfo task=new ActivityManager.RunningTaskInfo();
        task.topActivity=new ComponentName(pkg,activity);
        ActivityManager.RunningTaskInfo.class.getField("displayId").setInt(task,display);
        ActivityManager.RunningTaskInfo.class.getField("userId").setInt(task,user);
        return task;
    }

    @Test public void frontActivityMustBeExactCurrentSystemHomeOnSameUserAndDisplay()throws Exception{
        String name=LauncherTargets.PIXEL;home(name,10042,true);
        int user=android.os.Process.myUid()/100000;
        assertTrue(LauncherTargets.isLauncherUid(context,10042));
        assertTrue(DesktopSleepHost.foregroundHome(context,task(name,name+".Home",0,user)));
        assertFalse(DesktopSleepHost.foregroundHome(context,task(name,name+".Settings",0,user)));
        assertFalse(DesktopSleepHost.foregroundHome(context,task(name,name+".Home",1,user)));
        assertFalse(DesktopSleepHost.foregroundHome(context,task(name,name+".Home",0,user+1)));
        assertFalse(DesktopSleepHost.foregroundHome(context,task("example.app","example.app.Main",0,user)));
        assertFalse(DesktopSleepHost.foregroundHome(context,null));
        assertFalse(DesktopSleepHost.foregroundHome(context,new ActivityManager.RunningTaskInfo()));
        home(LauncherTargets.LINEAGE,10043,true);
        assertFalse(LauncherTargets.isLauncherUid(context,10042));
        assertFalse(DesktopSleepHost.foregroundHome(context,task(name,name+".Home",0,user)));
        assertTrue(DesktopSleepHost.foregroundHome(context,task(LauncherTargets.LINEAGE,LauncherTargets.LINEAGE+".Home",0,user)));
    }

    @Test public void thirdPartyAndNonSystemHomesAreNeverAuthorized()throws Exception{
        int user=android.os.Process.myUid()/100000;
        home("example.home",10044,true);
        assertFalse(LauncherTargets.isLauncherUid(context,10044));
        assertFalse(DesktopSleepHost.foregroundHome(context,task("example.home","example.home.Home",0,user)));
        home(LauncherTargets.PIXEL,10042,false);
        assertFalse(LauncherTargets.isLauncherUid(context,10042));
        assertFalse(DesktopSleepHost.foregroundHome(context,task(LauncherTargets.PIXEL,LauncherTargets.PIXEL+".Home",0,user)));
        Shadows.shadowOf(context.getPackageManager()).setResolveInfosForIntent(homeIntent,List.of());
        assertFalse(DesktopSleepHost.foregroundHome(context,task(LauncherTargets.PIXEL,LauncherTargets.PIXEL+".Home",0,user)));
    }
}
