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
source = root / "app/src/main/java/dev/local/sunwindowdemo/features/smsblocking"
with tempfile.TemporaryDirectory(prefix="smsblocking-check-") as output:
    subprocess.run([javac, "-encoding", "UTF-8", "-d", output,
                    str(source / "SmsBlockingRules.java"),
                    str(root / "app/src/main/java/dev/local/sunwindowdemo/features/callblocking/CallBlockingRules.java"),
                    str(root / "tests/SmsBlockingCheck.java")], check=True)
    subprocess.run([java, "-cp", output, "SmsBlockingCheck"], check=True)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    android = Path(sdk) / "platforms/android-37.0/android.jar" if sdk else None
    if android and android.is_file():
        stub = Path(output) / "BuildConfig.java"
        stub.write_text('package dev.local.sunwindowdemo; public final class BuildConfig { public static final String VERSION_NAME="check",APPLICATION_ID="dev.nativecomplements.module"; public static final int VERSION_CODE=0; }', encoding="utf-8")
        classpath = os.pathsep.join([str(android), str(root / "app/libs/libxposed-api-101.0.1.jar")])
        subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", output,
                        str(stub), *map(str, source.glob("*.java")),
                        *map(str, (source.parent / "diagnostics").glob("*.java")),
                        *map(str, (source.parent / "callblocking").glob("CallBlockingRules.java")),
                        str(source.parent.parent / "core/HostSnapshot.java"),
                        str(source.parent.parent / "core/ModuleIdentity.java"),
                        str(source.parent.parent / "core/LauncherTargets.java")], check=True)
        print("SMS blocking Android Java compilation: PASS")

# Integration invariants that can be checked without Android or the Xposed runtime.
module = (source / "SmsBlockingModule.java").read_text(encoding="utf-8")
provider = (source / "SmsBlockingProvider.java").read_text(encoding="utf-8")
store = (source / "SmsBlockingStore.java").read_text(encoding="utf-8")
rules = (source / "SmsBlockingRules.java").read_text(encoding="utf-8")
screen = (source / "SmsBlockingScreen.kt").read_text(encoding="utf-8")
evaluate = module.split("private Blocked evaluate(", 1)[1].split("private String body(", 1)[0]
# The list lookup must stay in memory; only the worker may touch the provider or contacts.
assert "getContentResolver" not in evaluate, "No synchronous provider/contacts I/O on the inbound thread"
assert 'checkPermission(android.Manifest.permission.READ_CONTACTS, -1, book.uid)' in evaluate
assert 'PHONE.equals(name)' in module and '!PHONE.equals(p.getPackageName())' in module
assert 'startsWith(PHONE)' not in module, "Only the exact phone main process/package may install"
assert 'InboundSmsHandler' in module and 'processMessagePart' in module
assert 'BlockChecker' in module and 'getBlockStatus' in module
assert 'STATUS_BLOCKED_IN_LIST = 1' in module
assert 'decided' not in module and 'trackerReference' not in module, "No cross-message/ref decision cache"
assert 'count == 1 ? body(tracker) : null' in module, "Multipart partial text is never treated as complete"
assert 'body != null, address' in module and '!bodyComplete || verificationLike' in rules
assert 'current.consulted = true' in module and 'current.consulted && current.nativeConsumed && !current.recorded' in module
assert 'current.handler == chain.getThisObject()' in module and 'trackerDeleteWhere.invoke(current.tracker)' in module
assert 'Arrays.equals((String[]) trackerDeleteArgs.invoke(current.tracker)' in module
assert 'permanentDeleteUri.get(null)' in module and '(Integer) out > 0' in module
assert 'Integer.valueOf(1).equals(chain.getArg(2))' in module and 'current.recorded = true' in module
assert 'trackerAddress.invoke(current.tracker), chain.getArg(1)' in module
assert 'Integer.valueOf(0).equals(nativeStatus)' in module, "Native blocks must not be claimed as module blocks"
assert 'pending.remove()' in module and 'pending.set(previous)' in module, "Inbound thread state is restored"
assert 'deleting.remove()' in module and 'deleting.set(previous)' in module, "Deletion scope is restored"
assert '!deoptimize(process)' in module and '!deoptimize(part)' in module
assert '!deoptimize(blocked)' in module and '!deoptimize(delete)' in module
# Blocking must reuse the native cleanup instead of writing the Telephony provider.
assert 'content://sms' not in module and 'Telephony.Sms' not in module
assert 'getMessageBody' in module and 'SmsMessage.createFromPdu' in module
assert 'Binder.clearCallingIdentity()' in provider and 'Binder.restoreCallingIdentity(identity)' in provider
assert 'checkSelfPermission(android.Manifest.permission.READ_CONTACTS)' in provider
assert 'catch(RuntimeException unavailable){return Bundle.EMPTY;}' in provider
assert 'phone(uid)' in provider and 'PHONE_UID' not in provider
assert 'UserHandle.getUserHandleForUid(uid)' in provider and 'Process.myUserHandle()' in provider
assert 'ApplicationInfo.FLAG_SYSTEM' in provider and 'ApplicationInfo.FLAG_UPDATED_SYSTEM_APP' in provider
assert 'catch(Exception unavailable){return false;}' in provider, "Phone lookup failure must deny"
assert 'p.getBoolean(key(slot, "reject_strangers"), false)' in store
assert 'p.getBoolean(key(slot, "allow_verification"), false)' in store
# Message bodies must never be persisted or logged.
assert 'first.put("sender"' in store and 'first.put("body", bounded(value, "body", 700))' in store
assert "body" not in module.split("private void note(", 1)[1], "Record text must not reach the log"
assert 'current.text = assembled((byte[][]) chain.getArg(0), (Integer) chain.getArg(1),' in module
assert 'destPort != -1 || format == null) return null' in module, "Port/WAP pdus are never decoded as SMS text"
assert 'matched_pattern' in store and 'bounded(value, "matched_pattern", 64)' in store
assert 'looksLikeVerification' in rules and 'VERIFICATION_CODE' in rules
assert 'SmsBlockingScreen' in screen and 'confirmAll' in screen

scopes = (root / "app/src/main/resources/META-INF/xposed/scope.list").read_text(encoding="utf-8").splitlines()
assert "com.android.phone" in scopes, scopes
entries = (root / "app/src/full/resources/META-INF/xposed/java_init.list").read_text(encoding="utf-8").splitlines()
assert "dev.local.sunwindowdemo.features.smsblocking.SmsBlockingModule" in entries, entries
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
assert '${applicationId}.smsblocking' in manifest
print("SMS blocking integration invariants: PASS")
