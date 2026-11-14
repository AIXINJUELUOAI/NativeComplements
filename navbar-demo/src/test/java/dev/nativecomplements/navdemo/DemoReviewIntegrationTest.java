package dev.nativecomplements.navdemo;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36, manifest=Config.NONE)
public class DemoReviewIntegrationTest {
    private Bundle initial() {
        Bundle result=new Bundle(); result.putString("decision","EXTEND");
        result.putString("initialDecision","EXTEND"); result.putString("reason","dominant_color");
        result.putDouble("dominantRatio",0.78); result.putLong("id",7); return result;
    }
    @Test public void reviewCanCorrectWithoutRewritingInitialEvidence() {
        Bundle result=initial(); result.putString("reviewDecision","NATIVE"); result.putString("reviewReason","repeated_two_dimensional_layout");
        DemoCapture.applyReview(result);
        assertEquals("NATIVE",result.getString("decision")); assertEquals("EXTEND",result.getString("initialDecision"));
        assertEquals(0.78,result.getDouble("dominantRatio"),0); assertEquals(7,result.getLong("id"));
        assertTrue(result.getString("reason").contains("repeated_two_dimensional_layout"));
    }
    @Test public void inconclusiveOrFailedReviewNeverOverridesInitialVerdict() {
        for (String review:new String[]{"INCONCLUSIVE","failed",""}) {
            Bundle result=initial(); result.putString("reviewDecision",review); DemoCapture.applyReview(result);
            assertEquals("EXTEND",result.getString("decision")); assertEquals("dominant_color",result.getString("reason"));
        }
    }
}
