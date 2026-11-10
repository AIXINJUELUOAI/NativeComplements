package dev.local.sunwindowdemo;
import dev.local.sunwindowdemo.features.motion.MotionPolicy;
import java.util.Map;
public final class MotionPolicyChecks {
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    public static void main(String[] args){
        MotionPolicy p=new MotionPolicy();p.configure(Map.of(10001,1,10002,2),100);
        check(!p.blocked(10001,100),"inactive first screen");check(p.blocked(10002,100),"always");
        p.foreground(10001,15,1000);check(p.blocked(10001,7999),"first 7 seconds");
        p.foreground(10001,15,4000);check(!p.blocked(10001,8000),"same process page must not extend");
        p.foreground(10002,16,9000);p.foreground(10001,15,10000);check(p.blocked(10001,10001),"new entry");
        p.foreground(10001,25,12000);check(p.blocked(10001,18999),"new process");
        check(p.nextExpiry(12000)==19000,"expiry deadline");
        p.configure(Map.of(10001,1,10002,2),13000);check(!p.blocked(10001,19000),"reload must not extend");
        p.configure(Map.of(),13000);check(!p.blocked(10002,13000)&&!p.blocked(10001,13000),"disable releases all");
        p.configure(Map.of(1000,2),14000);check(!p.blocked(1000,14000),"system UID exempt");
        System.out.println("PASS: sensor rules, expiry, cold restart, same-UID page switches and disable");
    }
}
