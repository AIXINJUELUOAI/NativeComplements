/* Copyright (c) 2026 Maga-King and OpenAlive contributors. MIT License; see LICENSE.
 * Adapted from OpenAlive 2d54c72a573b2f66e20b558283bef58cdb0ada60. */
package org.aliveclean;

import java.io.File;
import java.io.IOException;

final class NativeScene {
    private static String loaded;
    /** Only an importer-verified immutable private file may be supplied by AliveAssets. */
    static synchronized boolean load(File library)throws IOException {
        String path=library.getCanonicalPath();
        if(loaded!=null){if(!loaded.equals(path))throw new IOException("Native generation changed; restart wallpaper process");return true;}
        if(!library.isFile()||library.canWrite())throw new IOException("Native library is missing or writable");
        System.load(path);loaded=path;return true;
    }
    static native long create(int width,int height,int mode,int aod,int lock,int home);
    static native void texture(long handle,int slot,int texture,int width,int height);
    static native void tint(long handle,int color);
    static native void frameCrop(long handle,float x,float y,float size,float angle);
    static native boolean frame(long handle,java.nio.ByteBuffer frame);
    static native boolean effects(long handle,java.nio.ByteBuffer effects);
    static native boolean render(long handle,int photo,int decorator,float photoAspect,java.nio.ByteBuffer frame);
    static native void destroy(long handle);
}
