package dev.local.sunwindowdemo.features.edgetouch;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs inside SystemUI. Small trusted split-touch windows consume edge contacts, not app input. */
public final class EdgeTouchHost implements ComponentCallbacks, AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> windows = new ArrayList<>();
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showRegions;
    private volatile int[] activeRects = new int[0];
    private volatile boolean closed;
    private volatile String state = "starting";
    private int[] protectedRects = new int[0];
    private Context windowContext;
    private WindowManager wm;
    private Method trustOverlay;
    private Bundle settings;
    private boolean initialized, registered, environmentReady, systemOverlay, imeVisible;
    private boolean inputBackend;
    private long backendSeen;
    private final Runnable heartbeat=new Runnable(){
        @Override public void run(){
            if(closed)return;
            if(inputBackend&&android.os.SystemClock.uptimeMillis()-backendSeen>3000){inputBackend=false;removeWindows();rebuild();}
            publishRegions();
            if(settings!=null&&settings.getBoolean("enabled"))main.postDelayed(this,1000);
        }
    };

    private void publishRegions(){
        context.sendBroadcast(new Intent(EdgeTouchStore.REGIONS).setPackage("android").putExtra("rectangles",activeRects),null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private final Runnable retrySettings = this::loadSettings;

    public EdgeTouchHost(Context systemUiContext) {
        context = systemUiContext.getApplicationContext();
        main.post(this::initialize);
    }

    /** Existing SystemUI focus/IME observation is shared, while activation remains independent. */
    public void setEnvironment(boolean hasSystemOverlay, boolean keyboardVisible) {
        main.post(() -> {
            if (closed) return;
            boolean changed = !environmentReady || systemOverlay != hasSystemOverlay || imeVisible != keyboardVisible;
            environmentReady = true; systemOverlay = hasSystemOverlay; imeVisible = keyboardVisible;
            if (changed) rebuild();
        });
    }

    /** Actual display coordinates, e.g. mini actions, white handle and corner launch hotspots. */
    public void setProtectedRects(int[] rectangles) {
        int[] copy = rectangles == null ? new int[0] : rectangles.clone();
        main.post(() -> {
            if (closed || Arrays.equals(protectedRects, copy)) return;
            protectedRects = copy;
            rebuild();
        });
    }

    /** For a native InputMonitor admission guard, since monitors still see consumed contacts. */
    public boolean blocks(float x, float y) {
        int[] rectangles = activeRects;
        for (int i = 0; i < rectangles.length; i += 4)
            if (x >= rectangles[i] && y >= rectangles[i + 1] && x < rectangles[i + 2] && y < rectangles[i + 3]) return true;
        return false;
    }

    public int[] blockingRects() { return activeRects.clone(); }
    public String state() { return state; }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if(EdgeTouchStore.BACKEND.equals(intent.getAction())){
                if(getSentFromUid()!=android.os.Process.SYSTEM_UID)return;
                backendSeen=android.os.SystemClock.uptimeMillis();
                boolean active=intent.getBooleanExtra("active",false);
                if(inputBackend!=active){inputBackend=active;removeWindows();rebuild();}
            } else if (EdgeTouchStore.CHANGED.equals(intent.getAction())) {
                try {
                    if (getSentFromUid() == context.getPackageManager().getApplicationInfo("dev.nativecomplements.module", 0).uid) loadSettings();
                } catch (Exception ignored) { }
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                removeWindows(); publishRegions();state = "screen_off";
            } else rebuild();
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { if (id == Display.DEFAULT_DISPLAY) removeWindows(); }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) { removeWindows(); main.post(EdgeTouchHost.this::rebuild); }
        }
    };

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void initialize() {
        if (closed) return;
        try {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("default display missing");
            windowContext = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            wm = windowContext.getSystemService(WindowManager.class);
            trustOverlay = WindowManager.LayoutParams.class.getDeclaredMethod("setTrustedOverlay");
            trustOverlay.invoke(new WindowManager.LayoutParams());
            IntentFilter filter = new IntentFilter(EdgeTouchStore.CHANGED);
            filter.addAction(EdgeTouchStore.BACKEND);
            filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(receiver, filter, null, main, Context.RECEIVER_EXPORTED);
            registered = true;
            context.registerComponentCallbacks(this);
            context.getSystemService(DisplayManager.class).registerDisplayListener(displayListener, main);
            initialized = true;
            loadSettings();
        } catch (Exception failure) {
            state = "unavailable"; removeWindows();
            android.util.Log.e("NativeComplements", "EDGE_TOUCH_INIT_FAILED", failure);
        }
    }

    private void loadSettings() {
        if (closed) return;
        main.removeCallbacks(retrySettings);
        worker.execute(() -> {
            try {
                Bundle loaded = context.getContentResolver().call(Uri.parse("content://" + EdgeTouchStore.AUTHORITY), "read", null, null);
                if (loaded == null) throw new IllegalStateException("empty edge touch settings");
                main.post(() -> { if (!closed) { settings = loaded; rebuild();main.removeCallbacks(heartbeat);heartbeat.run(); } });
            } catch (Exception failure) {
                main.post(() -> {
                    if (closed) return;
                    settings = null; removeWindows();publishRegions();state = "settings_unavailable";
                    main.postDelayed(retrySettings, 3000);
                });
                android.util.Log.w("NativeComplements", "EDGE_TOUCH_SETTINGS_FAILED", failure);
            }
        });
    }

    private void rebuild() {
        if (closed || !initialized) return;
        String suspended = null;
        if (settings == null) suspended = "settings_unavailable";
        else if (!settings.getBoolean("enabled", false)) suspended = "disabled";
        else if (!environmentReady) suspended = "waiting_environment";
        else if (!context.getSystemService(PowerManager.class).isInteractive()) suspended = "screen_off";
        else if (context.getSystemService(KeyguardManager.class).isKeyguardLocked()) suspended = "locked";
        else if (systemOverlay) suspended = "system_overlay";
        else if (imeVisible) suspended = "keyboard";
        if (suspended != null) { removeWindows();publishRegions();state = suspended; return; }
        try {
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean nextShowRegions = settings.getBoolean("show_regions", false);
            if (showRegions != nextShowRegions) {
                showRegions = nextShowRegions;
                if(inputBackend)removeWindows();
                // Appearance changes do not remove windows or cancel an owned pointer stream.
                for (View view : windows) view.invalidate();
            }
            int[] rectangles = EdgeTouchGeometry.buildSuppression(bounds.width(), bounds.height(), insets.top, insets.bottom,
                    windowContext.getResources().getDisplayMetrics().density,
                    settings.getBoolean("left_enabled", true), settings.getBoolean("right_enabled", true),
                    settings.getInt("left_width_dp", 8), settings.getInt("right_width_dp", 8),
                    settings.getInt("height_percent", 80), settings.getInt("position_percent", 50), protectedRects,
                    windowContext.getDisplay().getRotation(),settings.getInt("corner_dp",24));
            if (Arrays.equals(activeRects, rectangles)) { state = rectangles.length == 0 ? "no_regions" : inputBackend?"input_filter":"active"; return; }
            removeWindows();
            for (int i = 0; i < rectangles.length; i += 4) {
                if(inputBackend&&!showRegions)break;
                int left = rectangles[i], top = rectangles[i + 1], width = rectangles[i + 2] - left, height = rectangles[i + 3] - top;
                View touch = new View(windowContext) {
                    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
                    @Override protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        if (!showRegions) return;
                        // Fixed Material tones: SystemUI must not resolve application theme resources.
                        regionPaint.setStyle(Paint.Style.FILL);
                        regionPaint.setColor(0x336750A4);
                        canvas.drawRect(0, 0, getWidth(), getHeight(), regionPaint);
                        float stroke = Math.min(getWidth(), Math.max(1f, getResources().getDisplayMetrics().density));
                        float inset = stroke / 2f;
                        regionPaint.setStyle(Paint.Style.STROKE);
                        regionPaint.setStrokeWidth(stroke);
                        regionPaint.setColor(0xB39A82DB);
                        canvas.drawRect(inset, inset, getWidth() - inset, getHeight() - inset, regionPaint);
                    }
                };
                touch.setWillNotDraw(false);
                touch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                touch.setFilterTouchesWhenObscured(false);
                if(!inputBackend)touch.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                        v.setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t))));
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.LEFT; p.x = left; p.y = top;
                if(inputBackend)p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                p.setFitInsetsTypes(0); p.windowAnimations = 0;
                p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                p.setTitle("NativeComplements-edge-touch-" + i / 4);
                trustOverlay.invoke(p);
                wm.addView(touch, p); windows.add(touch);
            }
            activeRects = rectangles;
            publishRegions();state = rectangles.length == 0 ? "no_regions" : inputBackend?"input_filter":"active";
        } catch (Exception failure) {
            removeWindows(); state = "unavailable";
            android.util.Log.e("NativeComplements", "EDGE_TOUCH_WINDOWS_FAILED", failure);
        }
    }

    private void removeWindows() {
        activeRects = new int[0];
        if (wm != null) for (View view : windows) try { wm.removeViewImmediate(view); } catch (RuntimeException ignored) { }
        windows.clear();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        main.post(() -> { removeWindows(); rebuild(); });
    }
    @Override public void onLowMemory() { }
    @Override public void close() {
        closed = true;
        main.post(() -> {
            main.removeCallbacks(retrySettings);
            main.removeCallbacks(heartbeat);removeWindows();publishRegions();
            if (registered) { try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { } registered = false; }
            context.unregisterComponentCallbacks(this);
            context.getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
            worker.shutdownNow(); state = "closed";
        });
    }
}
