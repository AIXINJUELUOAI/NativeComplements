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
        // Portrait -> landscape -> portrait keeps a docked mini on the same
        // edge and at the same fraction of the usable vertical travel.
        Geometry.Placement portraitCanvas=Geometry.place(1200,false,0,100,1200,2600,32);
        Geometry.Placement landscapeCanvas=Geometry.place(1200,false,140,0,2670,1200,12);
        for(boolean large:new boolean[]{false,true})for(boolean left:new boolean[]{false,true})for(float ratio:new float[]{0,.5f,1}){
            Geometry.Placement seed=Geometry.miniTopRight(portraitCanvas,portraitCanvas.scale(),0,100,1200,2600,32,large);
            Geometry.Placement oldMini=new Geometry.Placement(seed.width(),seed.height(),seed.scale(),
                left?32:seed.x(),132+ratio*(2500-64-seed.height()*seed.scale()));
            Geometry.Placement rotated=Geometry.remapMini(oldMini,landscapeCanvas,landscapeCanvas.scale(),
                0,100,1200,2600,32,140,0,2670,1200,12,large);
            check(Math.abs((left?rotated.x()-152:rotated.x()+rotated.width()*rotated.scale()-2658))<.01,"rotation retains mini edge");
            float travel=1200-24-rotated.height()*rotated.scale();
            check(Math.abs(rotated.y()-(12+ratio*Math.max(0,travel)))<.02,"rotation retains mini vertical fraction");
            Geometry.Placement roundTrip=Geometry.remapMini(rotated,portraitCanvas,portraitCanvas.scale(),
                140,0,2670,1200,12,0,100,1200,2600,32,large);
            check(Math.abs(roundTrip.x()-oldMini.x())<.02&&Math.abs(roundTrip.y()-oldMini.y())<.02,"rotation round trip does not drift");
        }
        Geometry.Placement docked=Geometry.miniTopRight(portraitCanvas,portraitCanvas.scale(),0,100,1200,2600,32);
        check(Geometry.hideSide(docked,docked.x(),0,1200,32)==0,"resting at edge does not hide");
        check(Geometry.hideSide(docked,1200-docked.width()*docked.scale()/2,0,1200,32)==1,"push half beyond right hides");
        check(Geometry.hideSide(docked,-docked.width()*docked.scale()/2,0,1200,32)==-1,"push half beyond left hides");
        float miniWidth=docked.width()*docked.scale();
        check(Geometry.hideSide(docked,-miniWidth/3+.01f,1200)==0,"left under one-third remains visible");
        check(Geometry.hideSide(docked,-miniWidth/3,1200)==-1,"left exactly one-third hides");
        check(Geometry.hideSide(docked,1200-miniWidth*2/3-.01f,1200)==0,"right under one-third remains visible");
        check(Geometry.hideSide(docked,1200-miniWidth*2/3,1200)==1,"right exactly one-third hides");
        check(Geometry.hideSide(docked,docked.x(),1200)==0,"safe margin never acts as physical hide boundary");
        check(Geometry.offscreen(docked,-1,0,1200,12).x()+docked.width()*docked.scale()<0,"hidden left has no content on display");
        check(Geometry.offscreen(docked,1,0,1200,12).x()>1200,"hidden right has no content on display");
        Geometry.Placement appRotated=Geometry.place(1200,true,0,100,1200,2600,32);
        Geometry.Placement remapped=Geometry.remapMini(docked,appRotated,appRotated.scale(),0,100,1200,2600,32,0,100,1200,2600,32,false);
        check(remapped.width()==appRotated.width()&&remapped.height()==appRotated.height(),"app orientation uses the new logical canvas");
        // Fixed normal geometry is independent from the side/position of the last mini.
        check(portraitCanvas.equals(Geometry.place(1200,false,0,100,1200,2600,32)),"portrait normal always returns to its default");
        check(landscapeCanvas.x()==152,"landscape normal always uses the left edge");
        Geometry.Placement shifted=Geometry.avoidIme(portraitCanvas,1200,2670,800);
        check(shifted.width()==portraitCanvas.width()&&shifted.height()==portraitCanvas.height()&&shifted.scale()==portraitCanvas.scale()&&shifted.x()==portraitCanvas.x(),"IME does not resize or move X");
        check(Math.abs(shifted.y()+shifted.height()*shifted.scale()-1870)<.01,"IME moves only the overlap distance");
        check(Geometry.avoidIme(portraitCanvas,1200,2670,2200).y()==0,"oversized IME may use status bar area but never crosses physical top");
        check(Geometry.avoidIme(portraitCanvas,1200,2670,100).equals(portraitCanvas),"non-overlapping IME does not move normal");
        check(Geometry.avoidIme(portraitCanvas,1200,2670,0).equals(portraitCanvas),"hidden or floating IME restores fixed normal");
        check(Geometry.avoidIme(landscapeCanvas,2670,1200,600).equals(landscapeCanvas),"landscape ignores IME avoidance");
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
        System.out.println("PASS: fixed normal, mini mapping, IME translation; fan geometry, package validation, blocking combinations");
    }
}
