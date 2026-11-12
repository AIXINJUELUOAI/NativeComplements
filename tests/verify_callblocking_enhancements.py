"""Standalone JVM rules check; no Gradle, device, or shared build-cache access."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"))
javac = shutil.which("javac") or str(java_home / "bin" / "javac.exe")
java = shutil.which("java") or str(java_home / "bin" / "java.exe")
source = root / "app/src/main/java/dev/local/sunwindowdemo/features/callblocking"
with tempfile.TemporaryDirectory(prefix="callblocking-check-") as output:
    subprocess.run([javac, "-encoding", "UTF-8", "-d", output,
                    str(source / "CallBlockingRules.java"),
                    str(root / "tests/CallBlockingEnhancementsCheck.java")], check=True)
    subprocess.run([java, "-cp", output, "CallBlockingEnhancementsCheck"], check=True)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    android = Path(sdk) / "platforms/android-37.0/android.jar" if sdk else None
    if android and android.is_file():
        stub = Path(output) / "BuildConfig.java"
        stub.write_text('package dev.local.sunwindowdemo; public final class BuildConfig { public static final String VERSION_NAME="check",APPLICATION_ID="dev.nativecomplements.module"; public static final int VERSION_CODE=0; }', encoding="utf-8")
        classpath = os.pathsep.join([str(android), str(root / "app/libs/libxposed-api-101.0.1.jar")])
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", output,
                        str(stub), *map(str, source.glob("*.java")),
                        *map(str, (source.parent / "diagnostics").glob("*.java"))], check=True)
        print("Call blocking Android Java compilation: PASS")

# Integration invariants that can be checked without Android or the Xposed runtime.
module = (source / "CallBlockingModule.java").read_text(encoding="utf-8")
provider = (source / "CallBlockingProvider.java").read_text(encoding="utf-8")
store = (source / "CallBlockingStore.java").read_text(encoding="utf-8")
decide = module.split("private Decision decide(", 1)[1].split("private record Decision", 1)[0]
assert "getContentResolver" not in decide, "No synchronous provider/contacts I/O under Telecom lock"
assert "nativeAllows.getBoolean(original)?decide" in module
assert 'presentation.invoke(call)==android.telecom.TelecomManager.PRESENTATION_ALLOWED' in decide
assert 'repeats.observe(call,sub,recognized' in decide
assert 'READ_CONTACTS,-1,book.uid' in decide
assert 'Binder.clearCallingIdentity()' in provider and 'Binder.restoreCallingIdentity(identity)' in provider
assert 'checkSelfPermission(android.Manifest.permission.READ_CONTACTS)' in provider
assert 'catch(RuntimeException unavailable){return Bundle.EMPTY;}' in provider
assert 'p.getBoolean(key(slot, "reject_strangers"), false)' in store
assert 'p.getBoolean(key(slot, "allow_repeat"), false)' in store
print("Call blocking integration invariants: PASS")
