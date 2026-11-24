package org.aliveclean;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Motion endpoints and interrupted wake; no native renderer, imported assets or device operations. */
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
    @Test public void ambientAnchorMovesDownWithoutMovingLockOrHomeEndpoints(){
        for(int[] size:new int[][]{{1200,2670},{1080,1920}}){
            SceneMotion lens=new SceneMotion(size[0],size[1],0,0,0,6,false);
            assertLensCenter(lens,.45f);
            for(int awake:new int[]{1,2}){
                finish(lens.prepare(awake));
                assertLensCenter(lens,.5f);
                assertEquals(0,lens.values[SceneMotion.LENS+5],.00001f);
                assertEquals(0,lens.values[SceneMotion.LENS+6],.00001f);
                finish(lens.prepare(0));
                assertLensCenter(lens,.45f);
            }
            lens.close();
            for(int style=1;style<=5;style++){
                FrameMotion frame=new FrameMotion(size[0],size[1],0,false,PhotoStyle.get(style));
                assertFrameCenter(frame,.45f);
                for(int awake:new int[]{1,2}){
                    finish(frame.prepare(awake,400));
                    assertFrameCenter(frame,.5f);
                    assertEquals(2.35f,frame.values[2],.00001f);
                    assertEquals(0,frame.values[4],.00001f);
                    assertEquals(0,frame.values[5],.00001f);
                    finish(frame.prepare(0,400));
                    assertFrameCenter(frame,.45f);
                }
                frame.close();
            }
        }
    }
    private static void finish(AnimatorSet animation){
        // AnimatorSet play time excludes its own start delay; total duration includes it.
        animation.start();
        animation.setCurrentPlayTime(animation.getTotalDuration()-animation.getStartDelay());
        animation.end();
    }
    private static void assertLensCenter(SceneMotion motion,float expectedY){
        float[] inverse=matrix(motion.packet(),SceneMotion.DECOR_MATRIX),model=new float[16],center=new float[4];
        assertTrue(Matrix.invertM(model,0,inverse,0));
        Matrix.multiplyMV(center,0,model,0,new float[]{.5f,.5f,0,1},0);
        assertEquals(.5f,(center[0]/center[3]+1)/2,.00001f);
        assertEquals(expectedY,(1-center[1]/center[3])/2,.00001f);
    }
    private static void assertFrameCenter(FrameMotion motion,float expectedY){
        float[] model=matrix(motion.packet(),0),center=new float[4];
        Matrix.multiplyMV(center,0,model,0,new float[]{0,0,0,1},0);
        assertEquals(.5f,(center[0]/center[3]+1)/2,.00001f);
        assertEquals(expectedY,(1-center[1]/center[3])/2,.00001f);
    }
    private static float[] matrix(ByteBuffer packet,int offset){
        float[] result=new float[16];
        for(int i=0;i<16;i++)result[i]=packet.getFloat((offset+i)*4);
        return result;
    }
    private static long duration(AnimatorSet set,String property){
        for(Animator animator:set.getChildAnimations())
            if(animator instanceof ObjectAnimator object&&property.equals(object.getPropertyName()))return object.getDuration();
        throw new AssertionError("Missing animation: "+property);
    }
}
