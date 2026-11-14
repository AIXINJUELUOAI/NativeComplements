package dev.local.sunwindowdemo.features.iconlayers;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.UserHandle;
import java.lang.reflect.*;

/** Private BitmapInfo copies; module artwork first, native theme generation/normalization second. */
final class DrawerMonetAssets {
    private static final ThreadLocal<Boolean> originalRead = new ThreadLocal<>();
    static boolean readingOriginal() { return Boolean.TRUE.equals(originalRead.get()); }

    private final Class<?> bitmapClass;
    private final Constructor<?> bitmapConstructor, factoryConstructor;
    private final Object controller, unsupported;
    private final Method createThemed, badgeProvider, factoryClose, component;
    private final Field user;

    DrawerMonetAssets(ClassLoader loader) throws ReflectiveOperationException {
        bitmapClass = Class.forName("com.android.launcher3.icons.BitmapInfo", false, loader);
        Class<?> themed = Class.forName("com.android.launcher3.icons.ThemedBitmap", false, loader);
        Class<?> factory = Class.forName("com.android.launcher3.icons.BaseIconFactory", false, loader);
        Class<?> hint = Class.forName("com.android.launcher3.icons.SourceHint", false, loader);
        Class<?> themeFactory = Class.forName("com.android.launcher3.graphics.theme.MonoIconThemeFactory", false, loader);
        controller = themeFactory.getField("MONO_THEME_CONTROLLER").get(null);
        createThemed = controller.getClass().getMethod("createThemedBitmap", AdaptiveIconDrawable.class, bitmapClass, factory, hint);
        badgeProvider = controller.getClass().getMethod("getBadgeProvider");
        unsupported = themed.getField("NOT_SUPPORTED").get(null);
        factoryConstructor = factory.getConstructor(Context.class, int.class, int.class);
        factoryClose = factory.getMethod("close");
        bitmapConstructor = bitmapClass.getConstructor(Bitmap.class, int.class, int.class,
                Class.forName("com.android.launcher3.icons.IconShape", false, loader), themed, bitmapClass,
                Class.forName("com.android.launcher3.icons.FastBitmapDrawableDelegate$DelegateFactory", false, loader),
                Class.forName("com.android.launcher3.BadgeProvider", false, loader));
        Class<?> item = Class.forName("com.android.launcher3.model.data.ItemInfo", false, loader);
        component = item.getDeclaredMethod("getTargetComponent");
        user = item.getField("user");
    }

    Object build(Context context, Object item, Object bitmap) throws ReflectiveOperationException {
        UserHandle targetUser = (UserHandle) user.get(item);
        ComponentName target = (ComponentName) component.invoke(item);
        if (target == null) return null;
        android.content.pm.LauncherApps apps = context.getSystemService(android.content.pm.LauncherApps.class);
        var activity = apps == null ? null : apps.resolveActivity(new android.content.Intent().setComponent(target), targetUser);
        if (activity == null) return null;
        Drawable icon;
        Boolean previous = originalRead.get();
        originalRead.set(true);
        try { icon = activity.getIcon(context.getResources().getDisplayMetrics().densityDpi); }
        finally { if (previous == null) originalRead.remove(); else originalRead.set(previous); }
        Drawable adapted = android.os.Process.myUserHandle().equals(targetUser)
                ? IconAssets.drawerMonochrome(context, target.getPackageName(), icon) : null;
        if (adapted != null) icon = adapted;
        int size = ((Bitmap) value(bitmap, "icon")).getWidth();
        if (size < 1 || size > 512) return null;
        AdaptiveIconDrawable adaptive = safeMonochrome(context, adaptiveSource(icon), size);
        if (adaptive == null) return null;
        // Explicit module mono must also override the native clock delegate's embedded mono source.
        Object sourceBitmap = adapted == null ? bitmap : bitmapConstructor.newInstance(
                value(bitmap, "icon"), value(bitmap, "color"), value(bitmap, "flags"),
                value(bitmap, "defaultIconShape"), value(bitmap, "themedBitmap"), value(bitmap, "badgeInfo"),
                null, value(bitmap, "badgeProvider"));
        Object factory = factoryConstructor.newInstance(context, context.getResources().getDisplayMetrics().densityDpi, size);
        Object themed;
        try { themed = createThemed.invoke(controller, adaptive, sourceBitmap, factory, null); }
        finally { factoryClose.invoke(factory); }
        if (themed == null || themed == unsupported) return null;
        return bitmapConstructor.newInstance(value(bitmap, "icon"), value(bitmap, "color"), value(bitmap, "flags"),
                value(bitmap, "defaultIconShape"), themed, value(bitmap, "badgeInfo"),
                value(bitmap, "delegateFactory"), badgeProvider.invoke(controller));
    }

    private Object value(Object bitmap, String name) throws ReflectiveOperationException {
        return bitmapClass.getField(name).get(bitmap);
    }

    /** Some supplied mono layers occupy the whole expanded canvas and get clipped by the visible mask. */
    static AdaptiveIconDrawable safeMonochrome(Context context, AdaptiveIconDrawable adaptive, int size) {
        if (adaptive == null || adaptive.getClass() != AdaptiveIconDrawable.class && !(adaptive instanceof ImportedIcon)
                || adaptive.getMonochrome() == null) return adaptive;
        Bitmap source = IconImages.render(adaptive.getMonochrome(), size);
        try {
            Rect content = IconImages.contentBounds(source);
            // Existing correctly padded artwork stays byte-for-byte on the native rendering path.
            int edge = Math.round(size * .19f);
            if (content.left >= edge && content.top >= edge && content.right <= size - edge
                    && content.bottom <= size - edge) return adaptive;
            Bitmap padded = IconImages.fit(source, size, .60f);
            BitmapDrawable mono = new BitmapDrawable(context.getResources(), padded);
            mono.setGravity(android.view.Gravity.FILL);
            mono.setFilterBitmap(true);
            return new AdaptiveIconDrawable(adaptive.getBackground(), adaptive.getForeground(), mono);
        } finally { source.recycle(); }
    }

    static AdaptiveIconDrawable adaptiveSource(Drawable source) {
        if (source instanceof AdaptiveIconDrawable adaptive) return adaptive;
        if (source == null || source.getConstantState() == null) return null;
        // Native forced monochrome generation owns normalization; no negative-inset rasterization here.
        float extra = AdaptiveIconDrawable.getExtraInsetFraction();
        Drawable layer = new InsetDrawable(source.getConstantState().newDrawable().mutate(), extra / (1 + 2 * extra));
        return new AdaptiveIconDrawable(new ColorDrawable(Color.TRANSPARENT), layer);
    }
}
