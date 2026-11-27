# Conservative test: module entry points are read from META-INF/xposed/java_init.list.
# Keep every module class/member, including synthetic callbacks and reflection targets.
# No allowoptimization/allowobfuscation: do not inline or merge module Hook code.
-keep class dev.local.sunwindowdemo.** { *; }
# These libraries also exist in the host. Class.forName(name, false, hostLoader)
# must keep the host ABI, not adapt names to the module's obfuscated dependencies.
# Allow unused dependencies to be removed, but preserve names and member signatures.
-keepnames class kotlin.** { *; }
-keepnames class androidx.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
