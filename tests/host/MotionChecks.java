package dev.local.sunwindowdemo;

public final class MotionChecks {
    static void near(float a,float b){if(Math.abs(a-b)>.001f)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        // Visual radius remains the approved normal size; mini radius follows relative scale.
        near(MotionStyle.radius(.75f,.75f),24);
        near(MotionStyle.radius(.28f,.75f),8.96f);
        near(MotionStyle.radius(.28f,.51f),24*.28f/.51f);
        MotionStyle.Bar bar=MotionStyle.bar(.75f);
        near(bar.length(),85.5f);near(bar.thickness(),3.6f);near(bar.margin(),12);
        for(boolean appLandscape:new boolean[]{false,true}){
            Geometry.Placement normal=Geometry.place(1200,appLandscape,80,120,2670,1150,32);
            near(normal.x(),112); // include a cutout safe inset for content, unlike corner hotzones
            Geometry.Placement mini=new Geometry.Placement(normal.width(),normal.height(),.28f,1900,160);
            near(MotionStyle.interpolate(normal,mini,0).x(),normal.x());
            near(MotionStyle.interpolate(normal,mini,1).scale(),mini.scale());
            near(MotionStyle.interpolate(mini,normal,1).x(),112);
            // Mode transitions settle at the endpoint; pinned-size overshoot is a different operation.
            near(MotionStyle.interpolate(mini,normal,1.08f).scale(),normal.scale());
            near(MotionStyle.interpolate(normal,mini,1.08f).scale(),mini.scale());
            float last=normal.x();
            for(int i=1;i<=120;i++){
                Geometry.Placement p=MotionStyle.interpolate(normal,mini,i/120f);
                if(p.x()<last||!Float.isFinite(p.scale())||p.scale()<=0)throw new AssertionError("noncontinuous geometry");
                last=p.x();
            }
        }
        Geometry.Placement portrait=Geometry.place(1200,false,0,100,1200,2600,32);
        near(portrait.x()+portrait.width()*portrait.scale()/2,600);
        for(Geometry.Placement target:new Geometry.Placement[]{portrait,
            new Geometry.Placement(1200,2133,.28f,1800,12)}){
            float cx=target.x()+target.width()*target.scale()/2;
            float cy=target.y()+target.height()*target.scale()/2;
            float previous=0,previousExit=2;
            for(int step=0;step<=120;step++){
                float progress=step/120f;
                MotionStyle.Visibility enter=MotionStyle.visibility(target,progress);
                MotionStyle.Visibility exit=MotionStyle.visibility(target,1-progress);
                Geometry.Placement p=enter.placement();
                near(p.x()+p.width()*p.scale()/2,cx);near(p.y()+p.height()*p.scale()/2,cy);
                if(enter.alpha()<previous||exit.alpha()>previousExit)throw new AssertionError("visibility reversed");
                previous=enter.alpha();previousExit=exit.alpha();
            }
            near(MotionStyle.visibility(target,0).placement().scale(),target.scale()*.94f);
            near(MotionStyle.visibility(target,1).placement().scale(),target.scale());
            near(MotionStyle.visibility(target,0).alpha(),0);near(MotionStyle.visibility(target,1).alpha(),1);
        }
        try{MotionStyle.visibility(portrait,Float.NaN);throw new AssertionError("NaN visibility accepted");}catch(IllegalArgumentException expected){}
        for(float bad:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}){
            try{MotionStyle.radius(bad,.75f);throw new AssertionError("invalid radius accepted");}catch(IllegalArgumentException expected){}
            try{MotionStyle.bar(bad);throw new AssertionError("invalid bar accepted");}catch(IllegalArgumentException expected){}
        }
        System.out.println("PASS motion: SunOS bar scaling, radii, placement, reversible morph and centered entry/exit opacity/scale");
    }
}
