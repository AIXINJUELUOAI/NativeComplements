package dev.local.sunwindowdemo.features.alive;

/** Separate module-authorized AOD component; the ordinary wallpaper remains an independent fallback. */
public final class AliveAmbientWallpaperService extends AliveWallpaperService {
    @Override protected boolean supportsOwnAmbient(){return true;}
}
