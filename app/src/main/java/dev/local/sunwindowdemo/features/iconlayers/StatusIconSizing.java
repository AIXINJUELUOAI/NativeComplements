package dev.local.sunwindowdemo.features.iconlayers;

import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

/** Reads the host's data-type View as the visual ruler, without changing its layout or artwork. */
final class StatusIconSizing {
    private StatusIconSizing() {}
    private static int referenceId,configuration;
    private static float referenceFraction;
    private static int referenceWidth,referenceHeight;
    private static float referenceFraction(Drawable reference,int id,int config){
        if(referenceId!=id||configuration!=config){
            referenceFraction=StatusIconDrawable.visibleHeightFraction(reference);
            referenceWidth=reference.getIntrinsicWidth();referenceHeight=reference.getIntrinsicHeight();
            referenceId=id;configuration=config;
        }
        return referenceFraction;
    }
    static float referenceHeight(ImageView icon) {
        var resources=icon.getResources();
        int id=resources.getIdentifier("mobile_type","id",icon.getContext().getPackageName());
        View candidate=id==0?null:icon.getRootView().findViewById(id);
        if(!(candidate instanceof ImageView mobile)||!mobile.isShown())return 0;
        Drawable drawable=mobile.getDrawable();
        if(drawable==null||drawable.getBounds().isEmpty())return 0;
        RectF visible=new RectF(0,0,drawable.getBounds().width(),drawable.getBounds().height());
        mobile.getImageMatrix().mapRect(visible);
        // The ruler remains 5G even when the currently connected network changes to 4G.
        int fiveG=resources.getIdentifier("ic_5g_mobiledata_updated","drawable",icon.getContext().getPackageName());
        if(fiveG==0)return 0;
        int config=resources.getConfiguration().hashCode();
        if(referenceId!=fiveG||configuration!=config)
            referenceFraction(resources.getDrawable(fiveG,icon.getContext().getTheme()),fiveG,config);
        float fraction=referenceFraction;
        float viewportHeight=visible.height();
        if(referenceWidth>0&&referenceHeight>0){
            int availableWidth=mobile.getWidth()-mobile.getPaddingLeft()-mobile.getPaddingRight();
            int availableHeight=mobile.getHeight()-mobile.getPaddingTop()-mobile.getPaddingBottom();
            if(mobile.getScaleType()==ImageView.ScaleType.CENTER)viewportHeight=referenceHeight;
            else if(mobile.getScaleType()==ImageView.ScaleType.FIT_CENTER || mobile.getScaleType()==ImageView.ScaleType.FIT_START || mobile.getScaleType()==ImageView.ScaleType.FIT_END)
                viewportHeight=referenceHeight*Math.min(availableWidth/(float)referenceWidth,availableHeight/(float)referenceHeight);
        }
        float height=viewportHeight*fraction;
        return height>0&&height<=mobile.getHeight()?height:0;
    }
    static float scale(float target,int referenceWidth,int reference,int width,int height,float nativeScale) {
        if(!Float.isFinite(target)||target<=0||reference<=0)return nativeScale;
        float available=target;
        if(width>0&&referenceWidth>0)available=Math.min(available,width*(float)reference/referenceWidth);
        if(height>0)available=Math.min(available,height);
        return available/reference;
    }
}
