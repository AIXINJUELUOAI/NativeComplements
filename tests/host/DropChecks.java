package dev.local.sunwindowdemo;
public final class DropChecks {
    private static void check(boolean yes){if(!yes)throw new AssertionError();}
    public static void main(String[] args){
        check(BackSwipe.threshold(1200,2670)==600);
        check(Math.abs(BackSwipe.threshold(2670,1200)-747.6f)<.01f);
        for(float threshold:new float[]{600,747.6f}){
            check(!BackSwipe.armed(threshold-1,threshold,false,36));
            check(BackSwipe.armed(threshold,threshold,false,36));
            check(BackSwipe.armed(threshold-35,threshold,true,36));
            check(!BackSwipe.armed(threshold-37,threshold,true,36));
            check(!BackSwipe.armed(0,threshold,false,36));
            check(!BackSwipe.armed(-600,threshold,false,36));
            check(!BackSwipe.armed(Float.NaN,threshold,true,36));
            check(!BackSwipe.armed(threshold,threshold,true,Float.NaN));
        }
        try{BackSwipe.threshold(0,1200);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("PASS back-long: SunOS portrait/landscape defaults, entry, withdrawal hysteresis, short/outward swipes, invalid input");
    }
}
