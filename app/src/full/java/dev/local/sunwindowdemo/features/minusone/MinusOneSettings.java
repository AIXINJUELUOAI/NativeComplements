package dev.local.sunwindowdemo.features.minusone;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import dev.local.sunwindowdemo.core.LauncherTargets;

/** Exposes the feed selection to the installed Pixel Launcher. */
public final class MinusOneSettings extends ContentProvider {
    private static final Uri URI = Uri.parse("content://dev.nativecomplements.module.minusone");
    private static final String ENABLED = "enabled";
    private static final String WIDGET_GRID = "widget_grid";
    private static final String WIDGET_PICKER = "widget_picker";
    static final Uri WIDGET_GRID_URI = URI.buildUpon().appendPath(WIDGET_GRID).build();
    public static final Uri PICKER_REQUEST_URI = URI.buildUpon().appendPath("picker_request").build();
    static final Uri PICKER_READY_URI = URI.buildUpon().appendPath(WIDGET_PICKER).build();
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static volatile PendingIntent widgetPicker;

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("minus_one", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, false); }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    }

    public static boolean enabledForLauncher(Context context) {
        return readFlag(context, ENABLED);
    }

    static synchronized PendingIntent takeWidgetPicker() {
        var picker = widgetPicker;
        widgetPicker = null;
        return picker;
    }

    static void requestWidgetPicker(Context context) {
        context.getContentResolver().notifyChange(PICKER_REQUEST_URI, null);
    }

    public static void publishWidgetPicker(Context context, PendingIntent picker) {
        var extras = new Bundle();
        extras.putParcelable(WIDGET_PICKER, picker);
        context.getContentResolver().call(URI, WIDGET_PICKER, null, extras);
    }

    private void updateWidgetPicker(Bundle extras) {
        var picker = extras == null ? null : extras.getParcelable(WIDGET_PICKER, PendingIntent.class);
        if (picker == null || !picker.isActivity() || !picker.isImmutable()
                || picker.getCreatorUid() != Binder.getCallingUid()
                || !LAUNCHER.equals(picker.getCreatorPackage())) return;
        widgetPicker = picker;
        getContext().getContentResolver().notifyChange(PICKER_READY_URI, null);
    }

    public static void publishWidgetGrid(Context context, WidgetGrid grid) {
        context.getContentResolver().call(URI, WIDGET_GRID, null, grid.toBundle());
    }

    static WidgetGrid widgetGrid(Context context) {
        var saved = preferences(context);
        return new WidgetGrid(saved.getInt("grid_columns", 0), saved.getInt("grid_rows", 0),
                saved.getInt("grid_cell_width", 0), saved.getInt("grid_cell_height", 0),
                saved.getInt("grid_gap_x", 0), saved.getInt("grid_gap_y", 0));
    }

    private synchronized void updateGrid(Bundle values) {
        if (values == null) return;
        var context = getContext();
        var grid = WidgetGrid.fromBundle(values);
        if (!grid.valid() || grid.equals(widgetGrid(context))) return;
        preferences(context).edit().putInt("grid_columns", grid.columns()).putInt("grid_rows", grid.rows())
                .putInt("grid_cell_width", grid.cellWidth()).putInt("grid_cell_height", grid.cellHeight())
                .putInt("grid_gap_x", grid.gapX()).putInt("grid_gap_y", grid.gapY()).apply();
        context.getContentResolver().notifyChange(WIDGET_GRID_URI, null);
    }

    private static boolean readFlag(Context context, String flag) {
        try {
            var result = context.getContentResolver().call(URI, flag, null, null);
            return result != null && result.getBoolean(flag);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (!LAUNCHER.equals(getCallingPackage())
                || !LauncherTargets.isLauncherUid(getContext(), Binder.getCallingUid())) {
            throw new SecurityException("Verified Pixel Launcher only");
        }
        var result = new Bundle();
        switch (method) {
            case ENABLED -> result.putBoolean(ENABLED, enabled(getContext()));
            case WIDGET_GRID -> updateGrid(extras);
            case WIDGET_PICKER -> updateWidgetPicker(extras);
            default -> throw new UnsupportedOperationException(method);
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
