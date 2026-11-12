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
        if(ssRsrp>=-95)return 4;
        if(ssRsrp>=-105)return 3;
        if(ssRsrp>=-115)return 2;
        if(ssRsrp>=-125)return 1;
        return 0;
    }
}
