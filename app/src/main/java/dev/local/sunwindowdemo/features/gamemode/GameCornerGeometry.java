package dev.local.sunwindowdemo.features.gamemode;

/** The same four quarter-circle hit test is used by the input filter and scaled preview. */
public final class GameCornerGeometry {
    public static final int MIN_PERCENT=4,MAX_PERCENT=24,DEFAULT_PERCENT=10;
    private GameCornerGeometry(){}
    public static int clamp(int percent){return Math.max(MIN_PERCENT,Math.min(MAX_PERCENT,percent));}
    public static float radius(int width,int height,int percent){
        return width>0&&height>0?Math.min(width,height)*clamp(percent)/100f:0;
    }
    public static boolean inside(float x,float y,int width,int height,int percent){
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=width||y>=height)return false;
        float dx=Math.min(x,width-x),dy=Math.min(y,height-y),r=radius(width,height,percent);
        return r>0&&dx*dx+dy*dy<=r*r;
    }
}
