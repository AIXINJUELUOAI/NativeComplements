# Keep live Hook code/names unchanged, but allow genuinely unreachable product code to disappear.
-keep,allowshrinking class dev.local.sunwindowdemo.** { *; }
-keep class dev.local.sunwindowdemo.SunWindowModule {
    <init>(...);
    public void onModuleLoaded(...);
    public void onSystemServerStarting(...);
}
-keep class dev.local.sunwindowdemo.LauncherGuard {
    <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
}
-keep class dev.local.sunwindowdemo.BackBridge {
    <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
}
-keep class dev.local.sunwindowdemo.features.iconlayers.LauncherThemeModule {
    <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
}
# Keep native host-loader ABI literals unchanged through dependency optimization.
-keepnames class kotlin.** { *; }
-keepnames class androidx.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
