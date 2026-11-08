package dev.local.sunwindowdemo;

public final class GeometryChecks {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        Geometry.Placement wider=Geometry.place(1200,true,0,0,5000,3000,32);
        check(Math.abs(wider.width()*wider.scale()-(5000-64))<.01f,"wide canvas fits width when height would overflow");
        Geometry.Placement taller=Geometry.place(1200,false,0,0,5000,3000,32);
        check(Math.abs(taller.height()*taller.scale()-(3000-64))<.01f,"portrait canvas fills landscape safe height");
        for(boolean app:new boolean[]{false,true}){
            Geometry.Placement cameraOnly=Geometry.place(1200,app,140,0,2670,1200,12);
            check(cameraOnly.x()==152,"camera-side cutout plus margin");
            check(Math.abs(cameraOnly.height()*cameraOnly.scale()-1176)<.01f,"camera-only landscape uses 1176px");
            check(cameraOnly.y()==12,"no hidden status-bar top reservation");
            check(cameraOnly.y()+cameraOnly.height()*cameraOnly.scale()>1127,"no hidden navigation-bar reservation");
        }
        for(boolean app:new boolean[]{false,true}){
            Geometry.Placement actual=Geometry.place(1200,app,140,126,2670,1127,12);
            check(actual.x()==152,"landscape anchored at safe left");
            check(Math.abs(actual.height()*actual.scale()-977)<.01f,"977px of 1001px safe height");
            check(actual.y()>=138&&actual.y()+actual.height()*actual.scale()<=1115.01f,"status/navigation safe margins");
        }
        for(boolean app:new boolean[]{false,true})for(boolean display:new boolean[]{false,true}){
            int right=display?2670:1200,bottom=display?1200:2670;
            Geometry.Placement p=Geometry.place(1200,app,80,110,right,bottom,32);
            Geometry.Placement mini=Geometry.miniTopRight(p,p.scale(),80,110,right,bottom,32);
            check(Math.abs(mini.x()+mini.width()*mini.scale()-(right-32))<.01,"mini right margin");
            check(mini.y()==142,"mini top margin");
            check(mini.x()>=80&&mini.y()+mini.height()*mini.scale()<=bottom-32,"mini visible");
            check(mini.scale()<=p.scale(),"mini cannot grow");
        }
        Geometry.Placement tiny=Geometry.miniTopRight(new Geometry.Placement(1200,2133,.75f,0,0),.75f,0,0,300,300,32);
        check(tiny.y()+tiny.height()*tiny.scale()<=268.01f,"mini fits constrained area");
        check(Rules.angle(0,1)==45,"one item");
        check(Rules.fanSelection(100,0,200,44,6,90)==-1,"halfway no longer selects by default");
        check(Rules.fanSelection(179,0,200,44,6,90)==-1,"wait until near the icon");
        check(Rules.fanSelection(180,0,200,44,6,90)==0,"90 percent selection boundary");
        check(Rules.fanSelection(100,0,200,44,6,50)==0,"50 percent preserves earlier distance");
        check(Rules.fanSelection(199,0,200,44,6,100)==-1,"100 percent waits for icon center radius");
        check(Rules.fanSelection(200,0,200,44,6,100)==0,"icon center radius is reachable");
        for(int count=1;count<=6;count++)for(float arc:new float[]{120,180,200,176})for(int i=0;i<count;i++){
            double angle=Math.toRadians(Rules.angle(i,count));
            float dx=(float)((arc+.5)*Math.cos(angle)),dy=(float)((arc+.5)*Math.sin(angle));
            check(Rules.fanSelection(dx,dy,arc,44,count,100)==i,"all icons/list reachable with fitted or animated radius");
            check(Rules.fanSelection(dx*.8f,dy*.8f,arc,44,count,90)==-1,"all sectors honor the default dead zone");
        }
        check(Rules.fanSelection(-180,20,200,44,6,90)==-1,"outward selection rejected");
        check(Rules.fanSelection(180,-20,200,44,6,90)==-1,"below arc selection rejected");
        check(Rules.fanSelection(245,0,200,44,6,90)==-1,"overshoot still cancels");
        check(Rules.fanSelection(180,0,0,44,6,90)==-1,"unlaid-out fan has no selection");
        check(Rules.fanSelection(Float.NaN,180,200,44,6,90)==-1,"invalid pointer rejected");
        check(Rules.angle(0,2)==22.5&&Rules.angle(1,2)==67.5,"two items");
        for(int n=1;n<=6;n++){
            double last=-1;
            for(int i=0;i<n;i++){
                double a=Rules.angle(i,n);check(a>last&&a>0&&a<90,"ordered interior centers");last=a;
                check(Math.abs(a+Rules.angle(n-1-i,n)-90)<1e-9,"mirror symmetry");
            }
            if(n>1){double radius=Rules.radius(120,56,n);check(2*radius*Math.sin(Math.PI/(4*n))>=64-1e-8,"no icon overlap");}
        }
        check(Rules.triggered(35,-35,false,42),"left inward");
        check(Rules.triggered(-35,-35,true,42),"right inward");
        check(!Rules.triggered(-35,-35,false,42),"left outward");
        check(!Rules.triggered(35,35,false,42),"downward");
        check(!Rules.triggered(80,-1,false,42),"horizontal rejection");
        check(!Rules.triggered(1,-80,false,42),"vertical rejection");
        check(!Rules.triggered(5,-5,false,42),"short drag");
        check(Rules.validPackage("com.android.chrome"),"valid package");
        for(String s:new String[]{"", "chrome", "../com.chrome", "com.chrome/Activity", "com.chrome;id"})check(!Rules.validPackage(s),"reject invalid package");
        check(!Rules.validPackage(null),"null package");
        for(int bits=0;bits<64;bits++){
            boolean locked=(bits&1)!=0,landscape=(bits&2)!=0,landscapeOff=(bits&4)!=0;
            boolean keyboard=(bits&8)!=0,keyboardOff=(bits&16)!=0,excluded=(bits&32)!=0;
            boolean expected=locked||excluded;
            if(landscapeOff&&landscape)expected=true;
            if(keyboardOff&&keyboard)expected=true;
            check(Rules.blocked(locked,landscape,landscapeOff,keyboard,keyboardOff,excluded)==expected,"blocking toggles");
        }
        System.out.println("PASS: fan geometry 1..6, symmetry, icon spacing, diagonal threshold, package validation, blocking combinations");
    }
}
