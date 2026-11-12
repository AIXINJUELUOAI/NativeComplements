package dev.local.sunwindowdemo.features.shade;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalDisplayTest {
    @Test public void thresholdsAndAdjacentDbmAreExact(){
        int[] input={-156,-121,-120,-111,-110,-101,-100,-91,-90,-31};
        int[] expected={0,0,1,1,2,2,3,3,4,4};
        for(int i=0;i<input.length;i++)assertEquals("dBm="+input[i],expected[i],SignalDisplayPolicy.level(1,true,true,true,input[i]));
    }
    @Test public void encodedSignalKeepsNativeFlagsAndInflation(){
        assertEquals((5<<8)|3,SignalDisplayPolicy.encode((5<<8)|1,3));
        assertEquals((6<<8)|4,SignalDisplayPolicy.encode((6<<8)|2,3));
        assertEquals((2<<16)|(5<<8)|3,SignalDisplayPolicy.encode((2<<16)|(5<<8)|1,3));
        for(int state:new int[]{1,3})assertEquals((state<<16)|(5<<8),SignalDisplayPolicy.encode((state<<16)|(5<<8),4));
        assertEquals((7<<8)|1,SignalDisplayPolicy.encode((7<<8)|1,4));
    }
    @Test public void disabledUnregisteredNonNrAndInvalidMeasurementsKeepNative(){
        assertEquals(1,SignalDisplayPolicy.level(1,false,true,true,-96));
        assertEquals(0,SignalDisplayPolicy.level(0,true,false,true,-96));
        assertEquals(2,SignalDisplayPolicy.level(2,true,true,false,-96));
        for(int value:new int[]{Integer.MAX_VALUE,Integer.MIN_VALUE,0,-157,-30})
            assertEquals(1,SignalDisplayPolicy.level(1,true,true,true,value));
    }
    @Test public void realWorldCasesMatchExpectations(){
        // User's actual signal: -96 dBm should show 3 bars
        assertEquals(3,SignalDisplayPolicy.level(1,true,true,true,-96));
        // Common NR signal ranges
        assertEquals(4,SignalDisplayPolicy.level(1,true,true,true,-85));  // Excellent
        assertEquals(3,SignalDisplayPolicy.level(1,true,true,true,-95));  // Good
        assertEquals(2,SignalDisplayPolicy.level(1,true,true,true,-105)); // Fair
        assertEquals(1,SignalDisplayPolicy.level(1,true,true,true,-115)); // Poor
    }
}
