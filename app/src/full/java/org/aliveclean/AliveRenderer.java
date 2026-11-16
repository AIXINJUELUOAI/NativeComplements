/* Copyright (c) 2026 Maga-King and OpenAlive contributors. MIT License; see LICENSE.
 * Photo-only adaptation of OpenAlive RenderLoop, commit 2d54c72a573b2f66e20b558283bef58cdb0ada60. */
package org.aliveclean;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.os.*;
import android.view.Choreographer;
import android.view.Surface;
import dev.local.sunwindowdemo.features.alive.AliveAssets;
import dev.local.sunwindowdemo.features.alive.AliveSettings;
import java.io.*;
import java.nio.ByteBuffer;

/** All EGL, animation, native handles and textures belong to one bounded worker. */
public final class AliveRenderer implements AutoCloseable {
    private final Context context;
    private AliveSettings.Snapshot settings;
    private final boolean preview,reportAppliedStatus;
    private final int[] ground=new int[2];
    private long lensUntil;
    private boolean animateMode=true;
    private final HandlerThread thread=new HandlerThread("AliveGL");
    private final Handler handler;
    private EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private EGLContext egl=EGL14.EGL_NO_CONTEXT;
    private EGLSurface window=EGL14.EGL_NO_SURFACE;
    private Surface surface;
    private Choreographer choreographer;
    private long scene,frameTime;
    private int width,height,photo,mask,decorator,mode=1,seed=-1;
    private boolean seedAnimate,deferredSleep;
    private Runnable sleepPresented;
    private float aspect;
    private boolean visible,holding,framePosted,failed,firstFrame,endpointPending;
    private volatile boolean closed;
    private volatile String error="";
    private String reportedError;
    private SceneMotion motion;
    private TextureMotion textures;
    private FrameMotion frameMotion;
    private MaskFrames masks;
    private final FrameSample sample=new FrameSample();
    private final Runnable draw=()->render(frameTime);
    private final Choreographer.FrameCallback frame=this::onVsync;
    private void onVsync(long time){framePosted=false;frameTime=time;handler.post(draw);}

    public AliveRenderer(Context context){this(context,false);}
    public AliveRenderer(Context context,boolean preview){this(context,null,preview);}
    public AliveRenderer(Context context,AliveSettings.Snapshot snapshot){this(context,snapshot,false);}
    private AliveRenderer(Context context,AliveSettings.Snapshot snapshot,boolean preview){
        this.context=context.getApplicationContext();settings=snapshot;this.preview=preview;reportAppliedStatus=snapshot==null&&!preview;
        thread.start();handler=new Handler(thread.getLooper());
    }
    private AliveSettings.Snapshot readSettings(){return preview?AliveSettings.readDraft(context):AliveSettings.readApplied(context);}
    public String error(){return error;}
    /** Bounded editor-only idle demonstration; never re-arms a real suspended Engine. */
    public void previewLens(){post(()->{
        if(reportAppliedStatus||mode!=0)return;
        animateMode=true;lensUntil=System.nanoTime()+3_000_000_000L;schedule();
    });}
    public void attach(Surface next,int width,int height){post(()->{
        release();surface=next;this.width=width;this.height=height;failed=false;
        if(visible)start();
    });}
    /** Scene 0=AOD, 1=keyguard, 2=home. Styles are independent preference values. */
    public void mode(int next,boolean animate){
        if(next<0||next>2)throw new IllegalArgumentException("scene");
        post(()->changeMode(next,animate));
    }
    /** Called once by the Service when a real visible sleep episode can actually start. */
    public void sleep(int lastAwakePhase,boolean animate,Runnable presented){
        post(()->{
            boolean absent=scene==0,known=lastAwakePhase==1||lastAwakePhase==2;
            if(animate&&(!absent||known)){
                // Present a fully decoded starting frame before starting any collapse clock.
                mode=0;animateMode=true;deferredSleep=true;sleepPresented=presented;
                seed=absent?lastAwakePhase:-1;seedAnimate=absent;
                sample.clear();endpointPending=true;lensUntil=0;
                if(!absent)schedule();
                android.util.Log.i("NativeComplements","ALIVE_RENDER sleep-seed="+seed+" waiting-first-frame=true");
            }else{
                changeMode(0,false);
                if(presented!=null)presented.run();
            }
        });
    }
    private void changeMode(int next,boolean animate){
            if(next!=mode||!animate){deferredSleep=false;sleepPresented=null;}
            if(mode==next){
                if(!animate){
                    if(scene!=0&&(textures.active()||motion.transitionActive()||frameMotion!=null&&frameMotion.active()))
                        android.util.Log.i("NativeComplements","ALIVE_RENDER force-endpoint mode="+next);
                    endpointPending=true;animateMode=false;lensUntil=0;
                    seed=-1;seedAnimate=false;
                    if(scene!=0){
                        textures.change(next,false);
                        if(frameMotion!=null){frameMotion.change(next,motion);frameMotion.finish();}
                        motion.change(next);motion.finish();sample.clear();schedule();
                    }
                }
                return;
            }
            int previous=mode;mode=next;animateMode=animate;sample.clear();endpointPending=true;
            lensUntil=next==0&&animate?System.nanoTime()+3_000_000_000L:0;
            android.util.Log.i("NativeComplements","ALIVE_RENDER mode="+previous+"->"+next+" animate="+animate+" scene="+(scene!=0)+" visible="+visible);
            if(scene==0){seed=-1;seedAnimate=false;if(next!=0&&previous==0&&animate){seed=0;seedAnimate=true;}}
            if(scene!=0){
                textures.change(next,animate);
                if(frameMotion!=null){frameMotion.change(next,motion);if(!animate)frameMotion.finish();}
                motion.change(next);if(!animate)motion.finish();schedule();
            }
    }
    /** Static handoff may retain a presented scene, never decode/recreate one just to bridge native ON→DOZE. */
    public void hold(boolean value){post(()->{
        visible=value;holding=true;
        if(scene==0)return;
        stopFrames();motion.finish();textures.change(0,false);
        if(frameMotion!=null)frameMotion.finish();sample.clear();lensUntil=0;animateMode=false;
        endpointPending=true;if(value)schedule();
    });}
    public void visible(boolean value){visible(value,false);}
    /** A bounded sleep interruption may retain an existing scene, but must never draw or init hidden. */
    public void visible(boolean value,boolean retainScene){post(()->{
        boolean changed=visible!=value||holding;visible=value;holding=false;
        if(!value){
            if(retainScene&&scene!=0){
                if(changed){motion.pause(true);textures.pause(true);if(frameMotion!=null)frameMotion.pause(true);}
                stopFrames();sample.clear();if(changed)android.util.Log.i("NativeComplements","ALIVE_RENDER hidden-retained mode="+mode);
            }
            else release();
        }else if(!failed){
            if(scene==0)start();else if(changed){
                motion.pause(false);textures.pause(false);if(frameMotion!=null)frameMotion.pause(false);
                endpointPending=true;schedule();
            }
        }
    });}
    public void reload(){post(()->reloadSettings(readSettings()));}
    public void reload(AliveSettings.Snapshot snapshot){
        if(snapshot==null)throw new IllegalArgumentException("snapshot");
        post(()->reloadSettings(snapshot));
    }
    private void reloadSettings(AliveSettings.Snapshot next){
        if(settings!=null&&settings.aod==next.aod&&settings.lock==next.lock&&settings.home==next.home
                &&java.util.Objects.equals(settings.photo,next.photo)&&!failed)return;
        settings=next;release();failed=false;if(visible)start();
    }
    public void detach(){post(()->{release();surface=null;});}
    private void post(Runnable task){if(!closed)handler.post(()->{if(!closed)task.run();});}
    @Override public void close(){close(null);}
    /** Completion runs on the GL worker only after EGL has disconnected its native window. */
    public void close(Runnable afterRelease){
        if(closed)return;closed=true;
        handler.post(()->{try{release();surface=null;}finally{try{if(afterRelease!=null)afterRelease.run();}finally{thread.quitSafely();}}});
    }
    private void start(){
        if(holding||surface==null||!surface.isValid()||width<1||height<1||scene!=0)return;
        try{init();schedule();}catch(Exception|LinkageError failure){fail(failure);}
    }
    private void fail(Throwable failure){
        reportError(failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));failed=true;
        android.util.Log.e("NativeComplements","ALIVE_RENDER_UNAVAILABLE",failure);release();
    }
    private void reportError(String value){
        error=value;if(!reportAppliedStatus||value.equals(reportedError))return;reportedError=value;
        var prefs=context.getSharedPreferences("alive_wallpaper",0);
        if(!value.equals(prefs.getString("backend_error","")))prefs.edit().putString("backend_error",value).apply();
    }
    private void init()throws IOException {
        if(settings==null)settings=readSettings();
        int style=settings.aod,lock=settings.lock,home=settings.home;
        if((style!=0&&!PhotoStyle.supported(style))||lock<0||lock>3||home<6||home>9)
            throw new IOException("Unsupported photo effect selection");
        File file=settings.photoFile(context);if(!file.isFile())throw new IOException("Select a photo first");
        NativeScene.load(AliveAssets.library(context));
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IOException("eglInitialize");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        int[] attrs={EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,
            EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
        if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IOException("No GLES3 EGL config");
        egl=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        window=EGL14.eglCreateWindowSurface(display,configs[0],surface,new int[]{EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,window,window,egl))throw new IOException("eglMakeCurrent");
        EGL14.eglSwapInterval(display,1);
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float ratio=Math.min(1f,2048f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,(int)(w*ratio)),Math.max(1,(int)(h*ratio)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        try{aspect=(float)bitmap.getWidth()/bitmap.getHeight();photo=upload(bitmap);}finally{bitmap.recycle();}
        // The fixed native render ABI requires a positive decorator texture for every photo style.
        try(InputStream in=AliveAssets.open(context,"shader/photo/aod/lensPhoto/lens_decorator.png")){
            Bitmap decor=BitmapFactory.decodeStream(in);
            if(decor==null)throw new IOException("Invalid lens decorator");
            try{decorator=upload(decor);}finally{decor.recycle();}
        }
        int initial=seed>=0?seed:mode;boolean animate=seedAnimate;seed=-1;seedAnimate=false;
        scene=NativeScene.create(width,height,initial,style,lock,home);
        if(scene==0)throw new IOException("Native photo scene creation failed");
        motion=new SceneMotion(width,height,initial,style==0?0:-1,lock,home,false);
        textures=new TextureMotion(width,height,initial,lock,home);
        if(PhotoStyle.supported(style)){
            PhotoStyle spec=PhotoStyle.get(style);frameMotion=new FrameMotion(width,height,initial,false,spec);
            int[] id=new int[1];GLES30.glGenTextures(1,id,0);mask=id[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,mask);
            textureParameters();GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGB,1,1,0,GLES30.GL_RGB,GLES30.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(3));
            NativeScene.texture(scene,0,mask,spec.size,spec.size);
            masks=new MaskFrames(context,handler,()->{if(visible&&scene!=0)render(frameTime);},spec);
        }
        if(initial!=mode&&!deferredSleep){
            textures.change(mode,animate);if(frameMotion!=null)frameMotion.change(mode,motion);motion.change(mode);
            if(!animate){if(frameMotion!=null)frameMotion.finish();motion.finish();}
        }
        if(lock==3||home==9){
            loadGround(0,"shader/photo/lock/groundGlass/gray.png",false);
            loadGround(1,"shader/photo/lock/groundGlass/mask.png",true);
        }
        if(style==0&&mode==0&&animateMode&&lensUntil==0)lensUntil=System.nanoTime()+3_000_000_000L;
        NativeScene.tint(scene,0xff264552);
        choreographer=Choreographer.getInstance();firstFrame=true;endpointPending=true;
        android.util.Log.i("NativeComplements","ALIVE_RENDER init initial="+initial+" target="+mode+" animate="+animate+" size="+width+"x"+height);
    }
    private void loadGround(int index,String path,boolean repeat)throws IOException{
        try(InputStream input=AliveAssets.open(context,path)){
            Bitmap bitmap=BitmapFactory.decodeStream(input);
            if(bitmap==null)throw new IOException("Invalid ground-glass texture");
            try{
                ground[index]=upload(bitmap);
                if(repeat){GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_REPEAT);
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_REPEAT);}
                NativeScene.texture(scene,index+1,ground[index],bitmap.getWidth(),bitmap.getHeight());
            }finally{bitmap.recycle();}
        }
    }
    private static void textureParameters(){
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
    }
    private static int upload(Bitmap bitmap){
        int[] ids=new int[1];GLES30.glGenTextures(1,ids,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0]);textureParameters();
        if(bitmap.hasAlpha()&&bitmap.isPremultiplied()){
            int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            ByteBuffer rgba=ByteBuffer.allocateDirect(pixels.length*4);
            for(int p:pixels)rgba.put((byte)(p>>16)).put((byte)(p>>8)).put((byte)p).put((byte)(p>>24));
            rgba.flip();GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,rgba);
        }else GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0);
        return ids[0];
    }
    private void schedule(){if(visible&&scene!=0&&choreographer!=null&&!framePosted){framePosted=true;choreographer.postFrameCallback(frame);}}
    private void render(long time){
        if(!visible||scene==0)return;
        try{
            boolean resumedSample=frameMotion!=null&&sample.pending;
            float lensSpeed=mode!=0?1f:Math.max(0f,Math.min(1f,(lensUntil-time)/3_000_000_000f));
            motion.advance(time,lensSpeed);
            if(frameMotion!=null){
                if(!sample.pending)sample.captureTextures(textures);
                sample.capture(frameMotion,motion,SystemClock.uptimeMillis());
                if(!masks.upload(sample.mask,mask)){
                    if(masks.failed())throw new IOException("Photo mask decode failed");
                    // Decoder posts a callback for this exact sample; no busy frame loop while waiting.
                    return;
                }
                if(!NativeScene.frame(scene,sample.effect))throw new IOException("Invalid native photo frame");
            }
            if(!NativeScene.effects(scene,frameMotion==null?textures.packet():sample.textures)
                ||!NativeScene.render(scene,photo,decorator,aspect,frameMotion==null?motion.packet():sample.base))
                throw new IOException("Invalid native photo packet");
            if(!EGL14.eglSwapBuffers(display,window))throw new IOException("EGL swap failed");
            reportError("");sample.clear();
            if(deferredSleep){
                deferredSleep=false;lensUntil=System.nanoTime()+3_000_000_000L;
                textures.change(0,true);if(frameMotion!=null)frameMotion.change(0,motion);motion.change(0);
                Runnable presented=sleepPresented;sleepPresented=null;if(presented!=null)presented.run();
                android.util.Log.i("NativeComplements","ALIVE_RENDER sleep-presented target=0");
            }
            if(firstFrame){android.util.Log.i("NativeComplements","ALIVE_RENDER first-frame mode="+mode);firstFrame=false;}
            boolean active=textures.active()||motion.transitionActive()||(frameMotion!=null?frameMotion.active():mode==0&&time<lensUntil);
            if(!active&&!resumedSample&&endpointPending){android.util.Log.i("NativeComplements","ALIVE_RENDER endpoint mode="+mode+" ratios="+motion.values[0]+","+motion.values[1]+","+motion.values[2]+" textures="+settings.lock+"/"+settings.home);endpointPending=false;}
            if(active||resumedSample)schedule();
        }catch(Exception|LinkageError failure){fail(failure);}
    }
    private void stopFrames(){
        if(choreographer!=null)choreographer.removeFrameCallback(frame);framePosted=false;handler.removeCallbacks(draw);
    }
    private void release(){
        if(scene!=0||display!=EGL14.EGL_NO_DISPLAY)android.util.Log.i("NativeComplements","ALIVE_RENDER release mode="+mode+" visible="+visible);
        stopFrames();
        sample.clear();if(textures!=null){textures.close();textures=null;}
        if(frameMotion!=null){frameMotion.close();frameMotion=null;}if(motion!=null){motion.close();motion=null;}
        if(masks!=null){masks.close();masks=null;}
        if(display!=EGL14.EGL_NO_DISPLAY){
            if(scene!=0){NativeScene.destroy(scene);scene=0;}
            GLES30.glDeleteTextures(5,new int[]{photo,mask,decorator,ground[0],ground[1]},0);photo=mask=decorator=0;ground[0]=ground[1]=0;
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);
            if(egl!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,egl);
            // ponytail: EGL_DEFAULT_DISPLAY is process-shared; never terminate another wallpaper engine's display.
            // Own contexts/surfaces are released above; the OS reclaims the shared display on process exit.
            EGL14.eglReleaseThread();
        }
        display=EGL14.EGL_NO_DISPLAY;egl=EGL14.EGL_NO_CONTEXT;window=EGL14.EGL_NO_SURFACE;
    }
}
