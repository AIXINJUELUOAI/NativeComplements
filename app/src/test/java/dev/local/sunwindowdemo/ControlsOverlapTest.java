package dev.local.sunwindowdemo;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ControlsOverlapTest {
    private Intent frame(long serial,String mode){
        return new Intent().putExtra("protocol",Wire.PROTOCOL).putExtra("sequence",serial)
            .putExtra("state","active").putExtra("presented",true).putExtra("taskId",10)
            .putExtra("token","sunwindow5:11111111-1111-1111-1111-111111111111")
            .putExtra("frame",mode).putExtra("x",100f).putExtra("y",200f).putExtra("width",200f).putExtra("height",300f)
            .putExtra("scale",.5f).putExtra("logicalW",400).putExtra("logicalH",600);
    }
    private Context context(Activity activity){return new ContextWrapper(activity){
        @Override public Context createWindowContext(int type,Bundle options){return this;}
    };}
    private void layout(View root){
        root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,1000,1800);
    }
    private MotionEvent touch(int action,float x,float y){return MotionEvent.obtain(0,10,action,x,y,0);}
    @Test public void fallbackDifferenceHandlesFourEdgesFullCoverAndDisjoint(){
        Rect mini=new Rect(0,0,100,100);
        assertEquals(new Rect(0,40,100,100),WindowControls.largestUncovered(mini,new Region(0,0,100,40)));
        assertEquals(new Rect(0,0,100,60),WindowControls.largestUncovered(mini,new Region(0,60,100,100)));
        assertEquals(new Rect(40,0,100,100),WindowControls.largestUncovered(mini,new Region(0,0,40,100)));
        assertEquals(new Rect(0,0,60,100),WindowControls.largestUncovered(mini,new Region(60,0,100,100)));
        assertTrue(WindowControls.largestUncovered(mini,new Region(mini)).isEmpty());
        assertEquals(mini,WindowControls.largestUncovered(mini,new Region(200,200,300,300)));
    }
    @Test public void singleRootExcludesContentButKeepsAllFourUncoveredSidesAndRestores(){
        var activity=Robolectric.buildActivity(Activity.class).setup();WindowControls mini=new WindowControls(context(activity.get()));
        try{
            mini.update(frame(1,"mini"));FrameLayout root=ReflectionHelpers.getField(mini,"controlsRoot");assertNotNull(root);layout(root);
            mini.normalOcclusion(new Region(150,250,250,350));Region region=new Region();mini.fillTouchRegion(region);
            assertFalse(region.contains(200,300));assertTrue(region.contains(120,300));assertTrue(region.contains(280,300));
            assertTrue(region.contains(200,220));assertTrue(region.contains(200,380));
            mini.normalOcclusion(new Region());mini.fillTouchRegion(region);assertTrue(region.contains(200,300));
            mini.normalOcclusion(new Region(0,0,1000,1800));mini.fillTouchRegion(region);assertTrue(region.isEmpty());
        }finally{mini.hide();activity.pause().stop().destroy();}
    }
    @Test public void fallbackUsesNonoverlapGeometryAndNotTouchableWhenFullyCovered(){
        var activity=Robolectric.buildActivity(Activity.class).setup();WindowControls mini=new WindowControls(context(activity.get()));
        ReflectionHelpers.setField(mini,"separateWindows",true);
        try{
            mini.normalOcclusion(new Region(100,200,200,500));mini.update(frame(1,"mini"));
            List<WindowManager.LayoutParams> params=ReflectionHelpers.getField(mini,"params");WindowManager.LayoutParams p=params.get(0);
            assertEquals(200,p.x);assertEquals(100,p.width);assertTrue(p.height>0);
            mini.normalOcclusion(new Region(0,0,1000,1800));assertTrue((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
            List<View> views=ReflectionHelpers.getField(mini,"views");assertEquals(View.GONE,views.get(0).getVisibility());
            mini.normalOcclusion(new Region());assertEquals(100,p.x);assertEquals(200,p.width);assertEquals(View.VISIBLE,views.get(0).getVisibility());
            assertEquals(0,p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }finally{mini.hide();activity.pause().stop().destroy();}
    }
    @Test public void rejectedDownNeverReachesMiniTapListenerAndAcceptedStreamStaysCaptured(){
        var activity=Robolectric.buildActivity(Activity.class).setup();WindowControls mini=new WindowControls(context(activity.get()));
        try{
            mini.update(frame(1,"mini"));FrameLayout root=ReflectionHelpers.getField(mini,"controlsRoot");layout(root);
            View hit=root.getChildAt(0);final int[] calls={0};hit.setOnTouchListener((view,event)->{calls[0]++;return true;});
            mini.normalOcclusion(new Region(100,200,300,500));
            MotionEvent down=touch(MotionEvent.ACTION_DOWN,150,250),up=touch(MotionEvent.ACTION_UP,150,250);
            root.dispatchTouchEvent(down);root.dispatchTouchEvent(up);assertEquals(0,calls[0]);down.recycle();up.recycle();
            mini.normalOcclusion(new Region());down=touch(MotionEvent.ACTION_DOWN,150,250);root.dispatchTouchEvent(down);
            assertTrue(calls[0]>0);mini.normalOcclusion(new Region(100,200,300,500));Region region=new Region();mini.fillTouchRegion(region);
            assertTrue(region.contains(150,250));up=touch(MotionEvent.ACTION_UP,150,250);root.dispatchTouchEvent(up);
            mini.fillTouchRegion(region);assertFalse(region.contains(150,250));down.recycle();up.recycle();
        }finally{mini.hide();activity.pause().stop().destroy();}
    }
    @Test public void fallbackKeepsCapturedRawStreamAndExportsOnlyVisibleEdgeProtection(){
        var activity=Robolectric.buildActivity(Activity.class).setup();WindowControls mini=new WindowControls(context(activity.get()));
        ReflectionHelpers.setField(mini,"separateWindows",true);
        try{
            mini.update(frame(1,"mini").putExtra("edgeHidden",true).putExtra("edgeSide",-1).putExtra("edgeCenterY",300f));
            int[] original=mini.protectedRects();assertEquals(4,original.length);
            List<View> views=ReflectionHelpers.getField(mini,"views");View hit=views.get(0);hit.setOnTouchListener((view,event)->true);
            List<WindowManager.LayoutParams> params=ReflectionHelpers.getField(mini,"params");WindowManager.LayoutParams p=params.get(0);
            Rect before=new Rect(p.x,p.y,p.x+p.width,p.y+p.height);
            MotionEvent down=touch(MotionEvent.ACTION_DOWN,before.exactCenterX(),before.exactCenterY());hit.dispatchTouchEvent(down);
            mini.normalOcclusion(new Region(before));assertEquals(before.left,p.x);assertEquals(before.width(),p.width);
            assertEquals(0,mini.protectedRects().length);
            MotionEvent up=touch(MotionEvent.ACTION_UP,before.exactCenterX(),before.exactCenterY());hit.dispatchTouchEvent(up);
            // Finishing the stream posts the deferred native window layout update.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
            mini.normalOcclusion(new Region());assertArrayEquals(original,mini.protectedRects());
            down.recycle();up.recycle();
        }finally{mini.hide();activity.pause().stop().destroy();}
    }
    @Test public void normalHiddenClearsContentAndHandleOcclusionNotOutsideDismiss(){
        var activity=Robolectric.buildActivity(Activity.class).setup();WindowControls normal=new WindowControls(context(activity.get()));
        try{
            normal.update(frame(1,"normal"));Region region=normal.contentOcclusion();assertTrue(region.contains(150,250));assertFalse(region.contains(10,100));
            normal.hide();assertTrue(normal.contentOcclusion().isEmpty());
        }finally{normal.hide();activity.pause().stop().destroy();}
    }
}
