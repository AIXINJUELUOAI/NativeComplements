package dev.local.sunwindowdemo.features.shade;

/** Display preference, not radio quality or an AOSP threshold correction. */
final class SignalDisplayPolicy {
    private SignalDisplayPolicy() {}
    static int encode(int nativeState,int level){
        int count=(nativeState>>>8)&255,state=(nativeState>>>16)&255;
        if((count!=5&&count!=6)||(state!=0&&state!=2))return nativeState;
        return (nativeState&~255)|(level+(count==6?1:0));
    }
    static int level(int original,boolean enabled,boolean inService,boolean terrestrialNr,int ssRsrp){
        if(!enabled||!inService||!terrestrialNr||ssRsrp < -156||ssRsrp > -31)return original;
        // Adjusted thresholds based on real-world NR SS-RSRP distribution
        if(ssRsrp>=-90)return 4;  // Excellent: ≥-90
        if(ssRsrp>=-100)return 3; // Good: ≥-100
        if(ssRsrp>=-110)return 2; // Fair: ≥-110
        if(ssRsrp>=-120)return 1; // Poor: ≥-120
        return 0;                  // Very poor: <-120
    }
}
