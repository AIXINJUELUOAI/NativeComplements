package dev.local.sunwindowdemo.features.smsblocking;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class SmsBlockingHookTest {
    private final Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
    private SmsBlockingModule module;
    private final Context context = RuntimeEnvironment.getApplication();
    private Object host, tracker;
    private Class<?> hostType, trackerType;
    // Robolectric's Android classloader otherwise wins over com.android.internal test fixtures.
    private ClassLoader fixtures() {
        String own = getClass().getName().replace('.', '/') + ".class";
        String location = getClass().getResource("/" + own).toExternalForm();
        String root = location.substring(0, location.length() - own.length());
        return new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.android.internal.telephony.")) return super.loadClass(name, resolve);
                synchronized (this) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (java.io.InputStream in = new java.net.URL(root + name.replace('.', '/') + ".class").openStream()) {
                            byte[] bytes = in.readAllBytes(); type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
    }
    private android.net.Uri permanentUri() { return android.net.Uri.parse("content://sms/raw/permanentDelete"); }
    private int posted;

    private void install() throws Exception {
        ClassLoader loader = fixtures();
        hostType = loader.loadClass("com.android.internal.telephony.InboundSmsHandler");
        trackerType = loader.loadClass("com.android.internal.telephony.InboundSmsTracker");
        host = hostType.getConstructor(Context.class).newInstance(context);
        tracker = trackerType.getConstructor().newInstance();
        module = new SmsBlockingModule();
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{XposedInterface.class}, (p, m, a) -> {
            if (m.getName().equals("deoptimize")) return true;
            if (m.getName().equals("log")) return null;
            if (m.getName().equals("hook")) {
                Executable executable = (Executable) a[0];
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{XposedInterface.HookBuilder.class}, (bp, bm, ba) -> {
                    if (bm.getName().equals("setExceptionMode")) return bp;
                    if (bm.getName().equals("intercept")) {
                        hooks.put(executable.getName(), (XposedInterface.Hooker) ba[0]);
                        return new XposedInterface.HookHandle() {
                            public Executable getExecutable() { return executable; }
                            public void unhook() { hooks.remove(executable.getName()); }
                        };
                    }
                    throw new AssertionError(bm);
                });
            }
            throw new AssertionError(m);
        }));
        Method install = SmsBlockingModule.class.getDeclaredMethod("install", ClassLoader.class);
        install.setAccessible(true); install.invoke(module, loader);
        assertTrue(hooks.containsKey("deleteFromRawTable"));
        field("context").set(module, context);
        field("worker").set(module, new Handler(Looper.getMainLooper()) {
            @Override public boolean sendMessageAtTime(android.os.Message message, long time) { posted++; return true; }
        });
    }
    private Field field(String name) throws Exception { Field f = SmsBlockingModule.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private Object pending(boolean decision) throws Exception {
        Class<?> blocked = Class.forName(SmsBlockingModule.class.getName() + "$Blocked");
        Constructor<?> b = blocked.getDeclaredConstructors()[0]; b.setAccessible(true);
        Constructor<?> p = Class.forName(SmsBlockingModule.class.getName() + "$Pending").getDeclaredConstructors()[0]; p.setAccessible(true);
        return p.newInstance(host, tracker, decision ? b.newInstance(new Bundle()) : null);
    }
    @SuppressWarnings("unchecked") private ThreadLocal<Object> local(String name) throws Exception { return (ThreadLocal<Object>) field(name).get(module); }
    interface Native { Object run() throws Throwable; }
    private Object call(String hook, Object self, Object[] args, Native body) throws Throwable {
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{XposedInterface.Chain.class}, (p,m,a) -> {
            if (m.getName().equals("getThisObject")) return self;
            if (m.getName().equals("getArg")) return args[(Integer) a[0]];
            if (m.getName().equals("proceed")) return body.run();
            throw new AssertionError(m);
        });
        return hooks.get(hook).intercept(chain);
    }
    private Object status(String sender, int nativeValue) throws Throwable {
        return call("getBlockStatus", null, new Object[]{context, sender, null}, () -> nativeValue);
    }
    private void delete(int rows) throws Throwable {
        call("deleteFromRawTable", host, new Object[]{"_id=?", new String[]{"42"}, 1}, () ->
                call("delete", context.getContentResolver(), new Object[]{permanentUri(), "_id=?", new String[]{"42"}}, () -> rows));
    }

    @Test public void nativeAndOtherSendersCannotBeClaimedAndRowsMustBePositiveOnce() throws Throwable {
        install(); Object current = pending(true); local("pending").set(current);
        assertEquals(0, status("other", 0));
        assertEquals(2, status("sender", 2));
        delete(1); assertEquals(0, posted);
        assertEquals(1, status("sender", 0));
        delete(1); assertEquals(0, posted); // consulted without the normal blocked branch is not evidence
        call("filterSms", host, new Object[]{new byte[0][], -1, tracker, null, true, true}, () -> false);
        delete(0); assertEquals(0, posted);
        delete(1); assertEquals(1, posted);
        delete(1); assertEquals(1, posted);
        assertNull(local("deleting").get());
    }

    @Test public void bodyIsAttachedOnlyToExactConfirmedDeletionAndNeverLeaksToAnotherMessage() throws Throwable {
        install(); Object current = pending(true); local("pending").set(current);
        Field text = current.getClass().getDeclaredField("text"); text.setAccessible(true);
        Field decision = current.getClass().getDeclaredField("decision"); decision.setAccessible(true);
        Object blocked = decision.get(current);
        Method entryMethod = blocked.getClass().getDeclaredMethod("entry"); entryMethod.setAccessible(true);
        Bundle entry = (Bundle) entryMethod.invoke(blocked);
        byte[][] pdus = {SmsRecordBodyTest.pdu("被拦正文")};
        status("sender", 0);
        call("filterSms", host, new Object[]{pdus, -1, trackerType.getConstructor().newInstance(), null, true, true}, () -> false);
        assertNull(text.get(current));
        call("filterSms", host, new Object[]{new byte[][]{pdus[0], pdus[0]}, -1, tracker, null, true, true}, () -> false);
        assertNull(text.get(current)); // native count mismatch cannot be treated as assembled text
        call("filterSms", host, new Object[]{pdus, -1, tracker, null, false, true}, () -> true);
        assertNull(text.get(current)); // locked/unconfirmed filtering is not a record source
        call("filterSms", host, new Object[]{pdus, -1, tracker, null, true, true}, () -> false);
        assertEquals("被拦正文", text.get(current)); assertFalse(entry.containsKey("body"));
        delete(0); assertFalse(entry.containsKey("body")); assertEquals(0, posted);
        delete(1); assertEquals("被拦正文", entry.getString("body")); assertEquals(1, posted);
        Object next = pending(true); local("pending").set(next); status("sender", 0);
        call("filterSms", host, new Object[]{pdus, 9200, tracker, null, true, true}, () -> false);
        assertNull(text.get(next));
        Object nextBlocked = decision.get(next);
        Bundle nextEntry = (Bundle) entryMethod.invoke(nextBlocked);
        delete(1); assertFalse(nextEntry.containsKey("body")); assertEquals(2, posted);
    }

    @Test public void nestedInboundAndThrowingNativeRestoreThreadState() throws Throwable {
        install(); Object outer = pending(true); local("pending").set(outer);
        RuntimeException failure = new IllegalStateException("native");
        try {
            call("processMessagePart", host, new Object[]{tracker}, () -> {
                assertNotSame(outer, local("pending").get());
                Object middle = local("pending").get();
                call("processMessagePart", host, new Object[]{tracker}, () -> false);
                assertSame(middle, local("pending").get());
                throw failure;
            });
            fail("exception swallowed");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertSame(outer, local("pending").get());
        status("sender", 0);
        Object priorDelete = pending(false); local("deleting").set(priorDelete);
        try {
            call("deleteFromRawTable", host, new Object[]{"_id=?", new String[]{"42"}, 1}, () -> { throw failure; });
            fail("exception swallowed");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertSame(priorDelete, local("deleting").get());
        assertEquals(0, posted);
    }
}
