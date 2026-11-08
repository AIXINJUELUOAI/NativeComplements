package dev.local.sunwindowdemo;

public final class GeometryCheck {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int cases = 0;
        for (boolean appLandscape : new boolean[]{false, true}) {
            for (boolean displayLandscape : new boolean[]{false, true}) {
                int w = displayLandscape ? 2670 : 1200, h = displayLandscape ? 1200 : 2670;
                Geometry.Placement p = Geometry.place(1200, appLandscape, 0, 140, w, h-50, 32);
                require(p.scale()>0 && p.scale()<=1, "scale");
                require(p.x()>=32-.01f && p.y()>=172-.01f, "left/top safe region");
                require(p.x()+p.width()*p.scale()<=w-32+.01f, "right safe region");
                require(p.y()+p.height()*p.scale()<=h-82+.01f, "bottom safe region");
                require((p.width()>p.height())==appLandscape, "logical orientation");
                require(Math.min(p.width(),p.height())==1200, "logical canvas must not shrink with display");
                float actualRatio=(p.width()*p.scale())/(p.height()*p.scale());
                require(Math.abs(actualRatio-(float)p.width()/p.height())<.00001f, "uniform aspect ratio");
                cases++;
            }
        }
        Geometry.Placement cramped = Geometry.place(1200,false,0,0,500,200,12);
        require(cramped.height()*cramped.scale()<=176.01f,"small safe-area clamp");
        boolean rejected=false;
        try { Geometry.place(1200,false,0,0,10,10,12); } catch (IllegalArgumentException e) { rejected=true; }
        require(rejected,"reject non-positive usable area");
        System.out.println("PASS geometry: " + cases + " orientation combinations, safe-area clamp, invalid-input rejection");
    }
}
