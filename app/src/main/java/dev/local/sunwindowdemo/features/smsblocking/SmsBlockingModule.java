package dev.local.sunwindowdemo.features.smsblocking;

import android.content.Context;
import android.database.ContentObserver;
import android.os.*;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import dev.local.sunwindowdemo.BuildConfig;
import dev.local.sunwindowdemo.core.HostSnapshot;
import dev.local.sunwindowdemo.features.diagnostics.Diagnostics;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Android 17 SMS filtering inside the phone stack. The decision is handed to the existing native
 * blocked-number path, so the row is permanently dropped and nothing is broadcast to the default
 * SMS app; this module never writes the Telephony provider by itself.
 */
public final class SmsBlockingModule extends XposedModule {
    private static final String PHONE = "com.android.phone";
    /** BlockedNumberContract.STATUS_BLOCKED_IN_LIST; any non zero status means "blocked". */
    private static final int STATUS_BLOCKED_IN_LIST = 1;
    private boolean eligible, attempted;
    private volatile Bundle config = Bundle.EMPTY;
    private Context context;
    private Handler worker;
    private record Contacts(Set<String> numbers, long expires, int uid) {}
    private volatile Contacts contacts = new Contacts(null, 0, -1);
    private record Blocked(Bundle entry) {}
    private static final class Pending {
        final Object handler, tracker;
        final Blocked decision;
        boolean consulted, nativeConsumed, recorded;
        Pending(Object handler, Object tracker, Blocked decision) {
            this.handler = handler; this.tracker = tracker; this.decision = decision;
        }
    }
    /** No message decisions survive the native processing call. */
    private final ThreadLocal<Pending> pending = new ThreadLocal<>();
    private final ThreadLocal<Pending> deleting = new ThreadLocal<>();
    private Method trackerAddress, trackerPdu, trackerFormat, trackerSubId, trackerCount, trackerDeleteWhere, trackerDeleteArgs;
    private Field handlerResolver, permanentDeleteUri;
    private Field handlerContext;
    private HostSnapshot snapshot;
    private long lastFailure;

    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        String name = p.getProcessName();
        eligible = !p.isSystemServer() && PHONE.equals(name);
    }

    @Override public void onPackageLoaded(PackageLoadedParam p) {
        if (!eligible || attempted || !PHONE.equals(p.getPackageName())) return;
        attempted = true;
        install(p.getDefaultClassLoader());
    }

    // The phone stack is platform signed, so its own internal classes stay reachable; every member is
    // still validated here and a missing one installs nothing.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void install(ClassLoader loader) {
        List<XposedInterface.HookHandle> hooks = new ArrayList<>();
        try {
            Class<?> handler = Class.forName("com.android.internal.telephony.InboundSmsHandler", false, loader);
            Class<?> tracker = Class.forName("com.android.internal.telephony.InboundSmsTracker", false, loader);
            Class<?> checker = Class.forName("com.android.internal.telephony.BlockChecker", false, loader);
            handlerContext = handler.getDeclaredField("mContext");
            handlerContext.setAccessible(true);
            if (handlerContext.getType() != Context.class) throw new NoSuchFieldException("InboundSmsHandler.mContext");
            Method part = handler.getDeclaredMethod("processMessagePart", tracker);
            part.setAccessible(true);
            Method status = checker.getDeclaredMethod("getBlockStatus", Context.class, String.class, Bundle.class);
            status.setAccessible(true);
            if (part.getReturnType() != boolean.class || status.getReturnType() != int.class)
                throw new NoSuchMethodException("InboundSmsHandler/BlockChecker signatures");
            trackerAddress = method(tracker, "getDisplayAddress", String.class);
            trackerPdu = method(tracker, "getPdu", byte[].class);
            trackerFormat = method(tracker, "getFormat", String.class);
            trackerSubId = method(tracker, "getSubId", int.class);
            trackerCount = method(tracker, "getMessageCount", int.class);
            trackerDeleteWhere = method(tracker, "getDeleteWhere", String.class);
            trackerDeleteArgs = method(tracker, "getDeleteWhereArgs", String[].class);
            handlerResolver = handler.getDeclaredField("mResolver");
            permanentDeleteUri = handler.getDeclaredField("sRawUriPermanentDelete");
            handlerResolver.setAccessible(true);
            permanentDeleteUri.setAccessible(true);
            if (handlerResolver.getType() != android.content.ContentResolver.class
                    || permanentDeleteUri.getType() != android.net.Uri.class
                    || !Modifier.isStatic(permanentDeleteUri.getModifiers()))
                throw new NoSuchFieldException("native SMS deletion fields");
            Class<?> receiver = Class.forName(handler.getName() + "$SmsBroadcastReceiver", false, loader);
            Method filter = handler.getDeclaredMethod("filterSms", byte[][].class, int.class, tracker, receiver, boolean.class, boolean.class);
            if (filter.getReturnType() != boolean.class) throw new NoSuchMethodException("native SMS filter signature");
            Method delete = handler.getDeclaredMethod("deleteFromRawTable", String.class, String[].class, int.class);
            delete.setAccessible(true);
            Method resolverDelete = android.content.ContentResolver.class.getDeclaredMethod("delete",
                    android.net.Uri.class, String.class, String[].class);
            if (delete.getReturnType() != void.class || resolverDelete.getReturnType() != int.class)
                throw new NoSuchMethodException("native SMS deletion signatures");
            Method blocked = checker.getDeclaredMethod("isBlocked", Context.class, String.class, Bundle.class);
            Class<?> delivering = Class.forName(handler.getName() + "$DeliveringState", false, loader);
            Method process = delivering.getDeclaredMethod("processMessage", Message.class);
            if (blocked.getReturnType() != boolean.class || process.getReturnType() != boolean.class
                    || !deoptimize(process) || !deoptimize(part) || !deoptimize(blocked) || !deoptimize(delete))
                throw new IllegalStateException("SMS native callers deoptimize");
            for (Method caller : handler.getDeclaredMethods()) {
                if (caller.getName().equals("-$$Nest$mprocessMessagePart") && !deoptimize(caller))
                    throw new IllegalStateException("SMS native bridge deoptimize");
            }
            // All reflection is resolved before installing anything. A missing API disables only this feature.
            // The handler is created once per phone at startup: connecting here starts the worker before
            // the first message instead of waiting for a message that would already have been allowed.
            for (Constructor<?> constructor : handler.getDeclaredConstructors()) {
                try {
                    hooks.add(hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        Object out = chain.proceed();
                        try {
                            Object value = handlerContext.get(chain.getThisObject());
                            if (value instanceof Context) connect((Context) value);
                        } catch (Throwable e) { failure(e); }
                        return out;
                    }));
                    // Optional: evaluate() also connects lazily, so one unhookable constructor must not
                    // disable blocking altogether.
                } catch (Throwable ignored) { }
            }
            hooks.add(hook(part).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Pending previous = pending.get();
                Blocked decision = null;
                try { decision = evaluate(chain.getThisObject(), chain.getArg(0)); }
                catch (Throwable e) { failure(e); }
                pending.set(new Pending(chain.getThisObject(), chain.getArg(0), decision));
                try { return chain.proceed(); }
                finally { if (previous == null) pending.remove(); else pending.set(previous); }
            }));
            hooks.add(hook(status).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Pending current = pending.get();
                Object nativeStatus = chain.proceed();
                if (current == null || current.decision == null || !Integer.valueOf(0).equals(nativeStatus)) return nativeStatus;
                try {
                    if (!Objects.equals(trackerAddress.invoke(current.tracker), chain.getArg(1))
                            || handlerContext.get(current.handler) != chain.getArg(0)) return nativeStatus;
                } catch (Throwable e) { failure(e); return nativeStatus; }
                current.consulted = true;
                return STATUS_BLOCKED_IN_LIST;
            }));
            hooks.add(hook(filter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Pending current = pending.get();
                Object out = chain.proceed();
                if (current != null && current.decision != null && current.consulted
                        && current.handler == chain.getThisObject() && current.tracker == chain.getArg(2)
                        && Boolean.TRUE.equals(chain.getArg(4)) && Boolean.TRUE.equals(chain.getArg(5))
                        && Boolean.FALSE.equals(out)) current.nativeConsumed = true;
                return out;
            }));
            hooks.add(hook(delete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Pending previous = deleting.get();
                Pending current = pending.get();
                boolean matches = false;
                try {
                    matches = current != null && current.decision != null && current.consulted && current.nativeConsumed && !current.recorded
                            && current.handler == chain.getThisObject() && Integer.valueOf(1).equals(chain.getArg(2))
                            && Objects.equals(trackerDeleteWhere.invoke(current.tracker), chain.getArg(0))
                            && Arrays.equals((String[]) trackerDeleteArgs.invoke(current.tracker), (String[]) chain.getArg(1));
                } catch (Throwable e) { failure(e); }
                if (matches) deleting.set(current); else deleting.remove();
                try { return chain.proceed(); }
                finally { if (previous == null) deleting.remove(); else deleting.set(previous); }
            }));
            hooks.add(hook(resolverDelete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Pending current = deleting.get();
                Object out = chain.proceed();
                // Only an exact synchronous native permanent deletion with affected rows is evidence.
                // Async carrier filters intentionally produce no record: consulted alone proves nothing.
                try {
                    if (current != null && !current.recorded && out instanceof Integer && (Integer) out > 0
                            && chain.getThisObject() == handlerResolver.get(current.handler)
                            && Objects.equals(permanentDeleteUri.get(null), chain.getArg(0))
                            && Objects.equals(trackerDeleteWhere.invoke(current.tracker), chain.getArg(1))
                            && Arrays.equals((String[]) trackerDeleteArgs.invoke(current.tracker), (String[]) chain.getArg(2))) {
                        current.recorded = true;
                        Bundle entry = current.decision.entry;
                        worker.post(() -> record(entry));
                        Diagnostics.event(context, Diagnostics.PHONE, "sms_blocked");
                        note("SMS_BLOCKED sim=" + (entry.getInt("slot_index") + 1) + " reason=" + entry.getString("reason"));
                    }
                } catch (Throwable e) { failure(e); }
                return out;
            }));
            note("SMS_BLOCKING_HOOK_READY hooks=" + hooks.size() + " version=" + BuildConfig.VERSION_NAME);
        } catch (Throwable e) {
            for (XposedInterface.HookHandle h : hooks) try { h.unhook(); } catch (Throwable ignored) {}
            note("SMS_BLOCKING_DISABLED " + e.getClass().getSimpleName());
        }
    }

    /**
     * Called on the inbound handler thread for every segment. Only in-memory rules run here; contact
     * snapshots and records come from the worker.
     */
    private Blocked evaluate(Object handler, Object tracker) throws Exception {
        Context host = context;
        if (host == null) {
            Object value = handlerContext.get(handler);
            if (!(value instanceof Context)) return null;
            connect((Context) value);
            host = context;
        }
        if (host == null || worker == null) return null;
        Bundle state = config;
        if (!state.getBoolean("enabled")) return null;   // not read yet, or switched off: allow
        int count = (Integer) trackerCount.invoke(tracker);
        String address = (String) trackerAddress.invoke(tracker);
        if (count < 1) return null;
        long identity = Binder.clearCallingIdentity();
        try {
            int sub = (Integer) trackerSubId.invoke(tracker);
            SubscriptionManager manager = host.getSystemService(SubscriptionManager.class);
            SubscriptionInfo info;
            try { info = manager == null ? null : manager.getActiveSubscriptionInfo(sub); }
            catch (SecurityException unavailable) { note("SIM_LOOKUP_UNAVAILABLE"); return null; }
            if (info == null) return null;
            int slot = info.getSimSlotIndex();
            if (slot < 0 || slot > 1) return null;
            // BlockChecker is reached only when native multipart assembly is ready, but this hook
            // has no complete assembled body. Never treat the arriving segment as the whole message.
            String body = count == 1 ? body(tracker) : null;
            SmsBlockingRules.Decision explicit = SmsBlockingRules.decide(true, slot,
                    state.getBoolean("sim1_reject_all"), state.getStringArray("sim1_patterns"),
                    state.getStringArray("sim1_keywords"), state.getBoolean("sim2_follow_sim1"),
                    state.getBoolean("sim2_reject_all"), state.getStringArray("sim2_patterns"),
                    state.getStringArray("sim2_keywords"), address, body);
            String prefix = slot == 0 || state.getBoolean("sim2_follow_sim1") ? "sim1_" : "sim2_";
            SmsBlockingRules.Decision verdict = explicit;
            if (!verdict.blocked && state.getBoolean(prefix + "reject_strangers")) {
                Contacts book = contacts;
                boolean ready = book.numbers != null && SystemClock.elapsedRealtime() < book.expires && book.uid >= 0
                        && host.checkPermission(android.Manifest.permission.READ_CONTACTS, -1, book.uid)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                verdict = SmsBlockingRules.stranger(explicit, true, ready,
                        ready && book.numbers.contains(SmsBlockingRules.normalizeSender(address)),
                        state.getBoolean(prefix + "allow_verification"),
                        SmsBlockingRules.looksLikeVerification(body), body != null, address);
            }
            String reason = verdict.blocked ? verdict.reason : null;
            String pattern = verdict.matchedPattern;
            if (reason == null) return null;
            Bundle entry = new Bundle();
            entry.putLong("time", System.currentTimeMillis());
            entry.putString("sender", address == null ? "" : address);
            entry.putInt("slot_index", slot);
            entry.putInt("subscription_id", sub);
            entry.putString("sim_label", String.valueOf(info.getDisplayName()));
            entry.putString("reason", reason);
            entry.putString("matched_pattern", pattern);
            return new Blocked(entry);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    /** Only a single-part message can provide a complete body here; decode failures are unknown. */
    private String body(Object tracker) {
        try {
            byte[] pdu = (byte[]) trackerPdu.invoke(tracker);
            String format = (String) trackerFormat.invoke(tracker);
            if (pdu == null) return null;
            SmsMessage message = SmsMessage.createFromPdu(pdu, format);
            return message == null ? null : message.getMessageBody();
        } catch (Throwable e) { return null; }
    }

    private synchronized void connect(Context c) {
        if (worker != null) return;
        context = c;
        HandlerThread thread = new HandlerThread("NativeComplements-Sms", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        Diagnostics.initialize(context, Diagnostics.PHONE);
        worker.post(() -> {
            try {
                context.getContentResolver().registerContentObserver(SmsBlockingProvider.CONFIG, true,
                        new ContentObserver(worker) { @Override public void onChange(boolean self) { reload(); } });
            } catch (Throwable e) { failure(e); }
            try {
                context.getContentResolver().registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true,
                        new ContentObserver(worker) { @Override public void onChange(boolean self) { contacts = new Contacts(null, 0, -1); reloadContacts(); } });
            } catch (Throwable e) { failure(e); }
            poll();
        });
    }

    private void poll() {
        reload();
        worker.postDelayed(this::poll, 60_000);
    }

    private void reload() {
        long identity = Binder.clearCallingIdentity();
        try {
            // A stopped module process must not silently stop blocking: keep the last complete snapshot.
            if (snapshot == null) snapshot = HostSnapshot.open(context, "smsblocking", "backend_seen_at", "backend_version", "backend_error");
            HostSnapshot.Result read = snapshot.read(() -> context.getContentResolver().call(SmsBlockingProvider.CONFIG, "read", null, null));
            if (read.report()) note((read.live() ? "SMS_BLOCKING_CONFIG_RESTORED " : "SMS_BLOCKING_CONFIG_CACHED ") + "cached=" + (read.config() != null)
                    + " version=" + BuildConfig.VERSION_NAME + " elapsed=" + SystemClock.elapsedRealtime());
            Bundle value = read.config();
            if (value == null) { config = Bundle.EMPTY; return; }
            Bundle previous = config;
            config = new Bundle(value);
            // Contacts live in the module process; while it is unreachable the cached set expires and
            // the stranger rule fails open (allows the message).
            if (read.live()) reloadContacts();
            if (previous == Bundle.EMPTY || previous.getLong("updated_at") != value.getLong("updated_at")
                    || previous.getBoolean("enabled") != value.getBoolean("enabled")) {
                note("SMS_BLOCKING_CONFIG version=" + BuildConfig.VERSION_NAME + " enabled=" + value.getBoolean("enabled")
                        + " updatedAt=" + value.getLong("updated_at") + " sim1RejectAll=" + value.getBoolean("sim1_reject_all")
                        + " sim2RejectAll=" + value.getBoolean("sim2_reject_all") + " sim2Follow=" + value.getBoolean("sim2_follow_sim1")
                        + " sim1Rules=" + ruleCount(value, "sim1_patterns") + " sim2Rules=" + ruleCount(value, "sim2_patterns")
                        + " sim1Keywords=" + ruleCount(value, "sim1_keywords") + " sim2Keywords=" + ruleCount(value, "sim2_keywords")
                        + " wall=" + System.currentTimeMillis() + " elapsed=" + SystemClock.elapsedRealtime()
                        + " thread=" + Thread.currentThread().getName() + " live=" + read.live());
            }
            if (!read.live()) return;
            Bundle status = new Bundle();
            status.putString("error", "");
            context.getContentResolver().call(SmsBlockingProvider.CONFIG, "backend", BuildConfig.VERSION_NAME, status);
        } catch (Throwable e) { failure(e); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    /** Called only on the existing worker, never while the inbound handler holds its own locks. */
    private void reloadContacts() {
        Bundle state = config;
        if (!state.getBoolean("enabled") || !(state.getBoolean("sim1_reject_strangers")
                || (!state.getBoolean("sim2_follow_sim1") && state.getBoolean("sim2_reject_strangers")))) {
            contacts = new Contacts(null, 0, -1);
            return;
        }
        try {
            Bundle out = context.getContentResolver().call(SmsBlockingProvider.CONFIG, "contacts", null, null);
            String[] numbers = out == null ? null : out.getStringArray("numbers");
            if (numbers == null) Diagnostics.event(context, Diagnostics.PHONE, "contacts_unavailable");
            contacts = new Contacts(numbers == null ? null : Collections.unmodifiableSet(new HashSet<>(Arrays.asList(numbers))),
                    SystemClock.elapsedRealtime() + 65_000, out == null ? -1 : out.getInt("permission_uid", -1));
        } catch (Throwable e) { contacts = new Contacts(null, 0, -1); failure(e); }
    }

    private static int ruleCount(Bundle value, String key) {
        String[] rules = value.getStringArray(key);
        return rules == null ? 0 : rules.length;
    }

    private void record(Bundle entry) {
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle out = context.getContentResolver().call(SmsBlockingProvider.CONFIG, "record", null, entry);
            if (out == null || !out.getBoolean("stored")) throw new IllegalStateException("record not stored");
        } catch (Throwable e) { failure(e); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private static Method method(Class<?> c, String name, Class<?> result) throws Exception {
        Method m = c.getDeclaredMethod(name);
        m.setAccessible(true);
        if (m.getReturnType() != result) throw new NoSuchMethodException(c.getSimpleName() + "." + name);
        return m;
    }

    private synchronized void failure(Throwable e) {
        long now = SystemClock.elapsedRealtime();
        if (lastFailure != 0 && now - lastFailure < 60_000) return;
        lastFailure = now;
        Throwable root = e;
        while (root instanceof InvocationTargetException && root.getCause() != null) root = root.getCause();
        StackTraceElement[] trace = root.getStackTrace();
        String where = trace.length == 0 ? "unknown" : trace[0].getClassName() + "." + trace[0].getMethodName() + ":" + trace[0].getLineNumber();
        // Do not log exception messages: framework errors can include a sender address or body.
        note("SMS_BLOCKING_ERROR type=" + e.getClass().getSimpleName() + " cause=" + root.getClass().getSimpleName() + " at=" + where
                + " elapsed=" + now + " thread=" + Thread.currentThread().getName());
    }

    private void note(String text) {
        try { log(android.util.Log.INFO, "NativeComplements", text); } catch (Throwable ignored) {}
    }
}
