package dev.local.sunwindowdemo.features.shade;

/** Presentation choices over host-sampled values; no Resources, View, Drawable or host identity. */
final class ShadePolicy {
    private ShadePolicy() {}
    static boolean forceSplit(boolean enabled,boolean landscape,int widthDp){
        return enabled&&landscape&&widthDp>=600;
    }
    /** Null leaves the native resource result untouched. */
    static Boolean resourceOverride(int id,boolean scoped,boolean force,int split,int fullWidth){
        if(!scoped||!force)return null;
        if(id==split)return true;
        if(id==fullWidth)return false;
        return null;
    }
    static int offset(int dp){return Math.max(-32,Math.min(32,dp));}
    static int iconScale(int percent){return Math.max(80,Math.min(120,percent));}
    static boolean systemSlot(String slot){
        if(slot==null)return false;
        return switch(slot){
            case "alarm_clock","bluetooth","hotspot","volume","zen","cast","location","headset","tty","vpn","nfc","managed_profile","rotate","data_saver","sensors_off" -> true;
            default -> false;
        };
    }
    static float multiplier(int percent,float nativeWidth,float nativeHeight,int width,int height){
        float requested=iconScale(percent)/100f;
        if(requested<=1)return requested;
        if(!Float.isFinite(nativeWidth)||!Float.isFinite(nativeHeight)||nativeWidth<=0||nativeHeight<=0||width<=0||height<=0)return 1;
        float room=Math.min(width/nativeWidth,height/nativeHeight);
        // Never worsen an existing native clip; enlargement stops at the current content viewport.
        return Math.min(requested,Math.max(1,room));
    }
}
