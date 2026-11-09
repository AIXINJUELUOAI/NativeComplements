package dev.local.sunwindowdemo;

public final class MiniMagnetChecks {
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    private static void near(float a,float b,String why){check(Math.abs(a-b)<.0001f,why);}
    public static void main(String[] args){
        MiniMagnet m=new MiniMagnet();
        m.target(100,200,196,296,221,248);check(!m.latched(),"outside 1.5 radius does not latch");
        m.target(100,200,196,296,220,248);check(m.latched(),"actual dismiss radius x1.5 latches");
        m.advance(60_000_000);near(m.progress(),.5f,"half duration smoothstep");
        m.target(100,200,196,296,240,248);check(m.latched(),"hysteresis keeps a slightly retreated drag latched");
        m.advance(60_000_000);near(m.progress(),1,"settles after 120ms");check(!m.animating(),"latched animation stops scheduling");
        m.target(100,200,196,296,245,248);check(!m.latched(),"leaves beyond radius x2");
        m.advance(30_000_000);float retreat=m.progress();check(retreat>0&&retreat<1,"retreat stays continuous");
        m.target(100,200,196,296,148,248);m.advance(15_000_000);check(m.progress()>retreat,"reentry reverses from the live phase");
        m.target(0,0,0,0,148,248);check(!m.latched(),"removed target cannot close");
        m.advance(120_000_000);near(m.progress(),0,"leaving restores original size/opacity");check(!m.animating(),"restored animation stops scheduling");
        m.target(100,200,196,296,Float.NaN,248);check(!m.latched(),"invalid pointer cannot latch");
        m.target(100,200,196,296,148,248);m.advance(120_000_000);m.reset();check(!m.latched()&&!m.animating(),"cancel clears latch and pending animation");near(m.progress(),0,"cancel clears progress");
        near(MiniMagnet.scaleFactor(0),1,"original scale");near(MiniMagnet.scaleFactor(1),.35f,"dismiss scale");
        near(MiniMagnet.alphaFactor(0),1,"original alpha");near(MiniMagnet.alphaFactor(1),.25f,"dismiss alpha");
        near(MiniMagnet.scaleFactor(-1),1,"clamp underflow");near(MiniMagnet.alphaFactor(2),.25f,"clamp overflow");near(MiniMagnet.alphaFactor(Float.NaN),1,"invalid progress keeps task visible");
        Geometry.Placement base=new Geometry.Placement(1200,2133,.28f,800,132);
        for(int displayWidth:new int[]{1200,2670}){
            float width=base.width()*base.scale(),third=width/3;
            check(Geometry.hideSide(base,-third+.1f,displayWidth)==0,"before physical left one-third threshold");
            check(Geometry.hideSide(base,-third,displayWidth)==-1,"exact physical left one-third threshold");
            check(Geometry.hideSide(base,displayWidth-width+third-.1f,displayWidth)==0,"before physical right one-third threshold");
            check(Geometry.hideSide(base,displayWidth-width+third,displayWidth)==1,"exact physical right one-third threshold");
        }
        System.out.println("PASS: mini magnet hysteresis, reversible progress, baseline factors, cancel/reset, actual hide thresholds");
    }
}
