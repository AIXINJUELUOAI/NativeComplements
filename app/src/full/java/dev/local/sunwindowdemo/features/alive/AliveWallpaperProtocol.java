package dev.local.sunwindowdemo.features.alive;

/** Explicit module-to-system_server live-wallpaper request, never a foreign component API. */
public final class AliveWallpaperProtocol {
    public static final String ACTION = "dev.nativecomplements.module.alive.APPLY";
    public static final String CONTROL = "dev.nativecomplements.module.CONTROL";
    public static final String EXTRA_COMPONENT = "component";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_MESSAGE = "message";
    private AliveWallpaperProtocol() {}
}
