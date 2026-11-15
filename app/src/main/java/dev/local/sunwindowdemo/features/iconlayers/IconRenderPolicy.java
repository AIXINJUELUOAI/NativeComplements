package dev.local.sunwindowdemo.features.iconlayers;

/** Persisted channel choices to render decisions; no pixels, host objects or I/O. */
final class IconRenderPolicy {
    static final int AUTO=0, MANUAL=1, ALGORITHM=2, ORIGINAL=3;
    static final int FOLLOW=0, UPLOAD=1, UNCHANGED=2;
    enum ColorSource { NATIVE, MANUAL, LIBRARY, GENERATED }
    enum MaskSource { KEEP, UPLOAD, FOLLOW }
    record Plan(ColorSource color,MaskSource monochrome,MaskSource notification,boolean resolve) {}
    static Plan plan(int color,int monochrome,int notification,boolean library,boolean generatedPair){
        ColorSource source=color==MANUAL?ColorSource.MANUAL:color==AUTO&&library?ColorSource.LIBRARY
            :color==ALGORITHM&&generatedPair?ColorSource.GENERATED:ColorSource.NATIVE;
        boolean resolve=!(color==ORIGINAL&&monochrome==UNCHANGED&&notification==UNCHANGED)
            &&(source!=ColorSource.NATIVE||monochrome==UPLOAD||notification!=UNCHANGED);
        return new Plan(source,mask(monochrome),mask(notification),resolve);
    }
    static boolean themed(boolean userEnabled,boolean systemThemed){return userEnabled&&systemThemed;}
    private static MaskSource mask(int mode){return mode==UPLOAD?MaskSource.UPLOAD:mode==FOLLOW?MaskSource.FOLLOW:MaskSource.KEEP;}
}
