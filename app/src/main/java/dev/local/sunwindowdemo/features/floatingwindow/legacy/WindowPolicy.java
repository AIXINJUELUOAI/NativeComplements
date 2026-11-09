package dev.local.sunwindowdemo;

/** Shared, framework-independent geometry and orientation decisions. */
final class WindowPolicy {
    static boolean steadyMiniTransition(boolean liveMini,boolean busy,int changeMode,boolean sameGeometry,
            boolean sameRotation,boolean dependentChanges,boolean snapshot){
        return liveMini&&!busy&&changeMode==6&&sameGeometry&&sameRotation&&!dependentChanges&&!snapshot;
    }
    static boolean sameWindowCaller(int callingUid,int realCallingUid,int ownerUid,String callingPackage,String ownerPackage){
        return ownerUid>=10000&&callingUid==ownerUid&&(realCallingUid<0||realCallingUid==ownerUid)
            &&ownerPackage!=null&&!ownerPackage.isEmpty()&&ownerPackage.equals(callingPackage);
    }
    static boolean refreshRootLayout(boolean launcher,boolean attached,boolean relaunching,boolean finishing,boolean editing,String lifecycle){
        return launcher&&attached&&!relaunching&&!finishing&&!editing
            &&("RESUMED".equals(lifecycle)||"PAUSED".equals(lifecycle)||"STOPPED".equals(lifecycle));
    }
    static boolean landscape(int requested,boolean previous){
        return switch(requested){case 0,6,8,11 -> true;case 1,7,9,12 -> false;default -> previous;};
    }
    static float logicalRaw(float raw,float offset,float scale){
        if(!Float.isFinite(scale)||scale<=0||!Float.isFinite(offset))return raw;
        return (raw-offset)/scale;
    }
    static boolean sameLiveTask(Object task,Object owned,int id,int expectedId,int user,int expectedUser){
        return task!=null&&task==owned&&id==expectedId&&user==expectedUser;
    }
}
