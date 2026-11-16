package org.aliveclean;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** One interrupted-wake regression; no native renderer, imported assets or device operations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class SceneMotionTest {
    @Test public void redirectedWakeUsesVisibleAodInsteadOfTheUnreachedLockTarget(){
        SceneMotion motion=new SceneMotion(1200,2670,0,0,0,6,false);
        motion.prepare(1); // Logical LOCK has changed, but its delayed expansion has not run.
        AnimatorSet redirected=motion.prepare(2);
        assertEquals(33,duration(redirected,"sceneValue6")); // Lens decorator must leave on the wake track.
        motion.close();
        SceneMotion settled=new SceneMotion(1200,2670,1,0,0,6,false);
        assertEquals(1000,duration(settled.prepare(2),"sceneValue6")); // Real LOCK→HOME is unchanged.
        settled.close();
    }
    private static long duration(AnimatorSet set,String property){
        for(Animator animator:set.getChildAnimations())
            if(animator instanceof ObjectAnimator object&&property.equals(object.getPropertyName()))return object.getDuration();
        throw new AssertionError("Missing animation: "+property);
    }
}
