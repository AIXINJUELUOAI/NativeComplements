/* Copyright (c) 2026 Maga-King and OpenAlive contributors. MIT License; see LICENSE.
 * Adapted from OpenAlive 2d54c72a573b2f66e20b558283bef58cdb0ada60. */
package org.aliveclean;

import android.content.Context;
import android.os.*;
import android.opengl.GLES30;
import dev.local.sunwindowdemo.features.alive.AliveAssets;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** One decoder worker and 2–4 reusable RGB buffers, matching the upstream presentation sample. */
final class MaskFrames implements AutoCloseable {
    private final Context context;
    private final HandlerThread thread=new HandlerThread("AliveMask");
    private final Handler worker,owner;
    private final Runnable ready;
    private final LinkedHashMap<Integer,ByteBuffer> cache=new LinkedHashMap<>(8,.75f,true);
    private final MaskDecoder decoder=new MaskDecoder();
    private final String[] paths;
    private final int size,bytes,end,capacity;
    private volatile boolean closed;
    private int desired,uploaded=-1,failures;
    private boolean queued;
    private final Runnable decode=this::decode;
    MaskFrames(Context context,Handler owner,Runnable ready,PhotoStyle style){
        this.context=context;this.owner=owner;this.ready=ready;size=style.size;bytes=size*size*3;
        int start=style.frames;end=start+1;desired=start;
        capacity=Math.max(2,Math.min(4,4*720*720*3/bytes));paths=new String[end+1];
        for(int i=0;i<paths.length;i++)paths[i]="masks/"+style.folder+"/"+(i==start?"start":i==end?"end":String.format(Locale.ROOT,"%03d",i))+".rgbz";
        thread.start();worker=new Handler(thread.getLooper());
    }
    synchronized boolean upload(int index,int texture){
        if(closed)return false;
        desired=index;if(uploaded==index)return true;
        ByteBuffer value=cache.get(index);
        if(value==null&&!queued&&failures<3){queued=true;worker.post(decode);}
        if(value==null)return false;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);value.position(0);
        int side=index==end?720:size;
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGB,side,side,0,GLES30.GL_RGB,GLES30.GL_UNSIGNED_BYTE,value);
        uploaded=index;return true;
    }
    synchronized boolean failed(){return failures>=3;}
    private void decode(){
        int target;ByteBuffer pixels;
        synchronized(this){if(closed){queued=false;return;}target=desired;
            if(cache.size()>=capacity){pixels=cache.remove(cache.keySet().iterator().next());pixels.clear();}
            else pixels=ByteBuffer.allocateDirect(bytes);
        }
        try{
            int side=target==end?720:size;
            try(InputStream in=AliveAssets.open(context,paths[target])){decoder.decode(in,pixels,side*side*3);}
            synchronized(this){if(!closed){cache.put(target,pixels);while(cache.size()>capacity)cache.remove(cache.keySet().iterator().next());}}
        }catch(IOException|RuntimeException e){synchronized(this){failures++;}}
        synchronized(this){queued=false;if(!closed&&failures<3&&desired!=target&&!cache.containsKey(desired)){queued=true;worker.post(decode);}}
        if(!closed)owner.post(ready);
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;worker.removeCallbacksAndMessages(null);cache.clear();
        worker.post(decoder::close);thread.quitSafely();
    }
}
