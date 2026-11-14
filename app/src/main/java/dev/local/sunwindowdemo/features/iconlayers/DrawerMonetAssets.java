package dev.local.sunwindowdemo.features.iconlayers;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.UserHandle;
import java.lang.reflect.*;

/** Builds a private BitmapInfo copy with native monochrome material, never the shared icon cache. */
final class DrawerMonetAssets {
    private final Class<?> bitmapClass;
    private final Constructor<?> themedConstructor, bitmapConstructor;
    private final Object colorProvider;
    private final Method component;
    private final Field user;

    DrawerMonetAssets(ClassLoader loader) throws ReflectiveOperationException {
        bitmapClass = Class.forName("com.android.launcher3.icons.BitmapInfo", false, loader);
        Class<?> themed = Class.forName("com.android.launcher3.icons.ThemedBitmap", false, loader);
        Class<?> mono = Class.forName("com.android.launcher3.icons.mono.MonoThemedBitmap", false, loader);
        Class<?> controller = Class.forName("com.android.launcher3.graphics.theme.MonoIconThemeFactory", false, loader);
        Object nativeController = controller.getField("MONO_THEME_CONTROLLER").get(null);
        colorProvider = nativeController.getClass().getField("colorProvider").get(nativeController);
        Class<?> provider = nativeController.getClass().getField("colorProvider").getType();
        themedConstructor = mono.getConstructor(Bitmap.class, provider, Double.class);
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
        Drawable icon = activity.getIcon(context.getResources().getDisplayMetrics().densityDpi);
        if (android.os.Process.myUserHandle().equals(targetUser)) {
            Drawable adapted = IconAssets.desktop(context, target.getPackageName(), icon);
            if (adapted != null) icon = adapted;
        }
        Bitmap mono = monochrome(icon, ((Bitmap) value(bitmap, "icon")).getWidth());
        if (mono == null) return null;
        boolean transferred = false;
        try {
            Object themed = themedConstructor.newInstance(mono, colorProvider, null);
            Object result = bitmapConstructor.newInstance(value(bitmap, "icon"), value(bitmap, "color"), value(bitmap, "flags"),
                    value(bitmap, "defaultIconShape"), themed, value(bitmap, "badgeInfo"),
                    value(bitmap, "delegateFactory"), value(bitmap, "badgeProvider"));
            transferred = true;
            return result;
        } finally { if (!transferred) mono.recycle(); }
    }

    private Object value(Object bitmap, String name) throws ReflectiveOperationException {
        return bitmapClass.getField(name).get(bitmap);
    }

    static Bitmap monochrome(Drawable icon, int size) {
        if (!(icon instanceof AdaptiveIconDrawable adaptive) || adaptive.getMonochrome() == null
                || size < 1 || size > 512) return null;
        Drawable.ConstantState state = adaptive.getMonochrome().getConstantState();
        if (state == null) return null;
        Drawable mono = new InsetDrawable(state.newDrawable().mutate(), -AdaptiveIconDrawable.getExtraInsetFraction());
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        try {
            mono.setBounds(0, 0, size, size);
            mono.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException | Error failure) { bitmap.recycle(); throw failure; }
    }
}
