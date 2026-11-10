package dev.local.sunwindowdemo;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.ImageView;

/** Keeps the original touch stream; the drag overlay itself never receives input. */
final class SuggestionDrag {
    private final Context context;
    private final WindowManager wm;
    private final RectF dismissBounds=new RectF();
    private View source,layer;
    private Bitmap shadow;
    private Runnable dismissed;
    private float rawX,rawY,offsetX,offsetY,sourceAlpha;
    private boolean entered;

    SuggestionDrag(Context context,WindowManager wm){this.context=context;this.wm=wm;}

    void attach(ImageView button,Runnable action){
        button.setOnTouchListener((v,event)->{
            int type=event.getActionMasked();
            if(type==MotionEvent.ACTION_DOWN){
                rawX=event.getRawX();rawY=event.getRawY();offsetX=event.getX();offsetY=event.getY();
            }
            if(source!=button)return false;
            if(type==MotionEvent.ACTION_CANCEL||event.getPointerCount()>1){cancel();return true;}
            rawX=event.getRawX();rawY=event.getRawY();updateTarget();
            if(type==MotionEvent.ACTION_UP){Runnable done=entered?dismissed:null;cancel();if(done!=null)done.run();}
            return true;
        });
        button.setOnLongClickListener(v->begin(button,action));
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){if(source==v)cancel();}
        });
    }

    private int dp(float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private int dimension(String name,int fallback){
        int id=context.getResources().getIdentifier(name,"dimen","com.android.systemui");
        return id==0?dp(fallback):context.getResources().getDimensionPixelSize(id);
    }
    private Drawable drawable(String name){
        int id=context.getResources().getIdentifier(name,"drawable","com.android.systemui");
        return id==0?null:context.getDrawable(id).mutate();
    }
    private boolean begin(View button,Runnable action){
        cancel();if(!button.isAttachedToWindow()||button.getWidth()==0||button.getHeight()==0)return false;
        try{
            shadow=Bitmap.createBitmap(button.getWidth(),button.getHeight(),Bitmap.Config.ARGB_8888);
            button.draw(new Canvas(shadow));source=button;sourceAlpha=button.getAlpha();dismissed=action;
            int size=dimension("floating_dismiss_background_size",96),iconSize=dimension("floating_dismiss_icon_size",32);
            Drawable background=drawable("floating_dismiss_background"),icon=drawable("floating_dismiss_ic_close");
            WindowMetrics metrics=wm.getCurrentWindowMetrics();Rect bounds=metrics.getBounds();
            Insets nav=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
            float cx=bounds.centerX(),cy=bounds.bottom-nav.bottom-dimension("floating_dismiss_bottom_margin",50)-size/2f;
            dismissBounds.set(cx-size/2f,cy-size/2f,cx+size/2f,cy+size/2f);
            layer=new View(context){
                final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                final int[] origin=new int[2];
                @Override protected void onDraw(Canvas canvas){
                    getLocationOnScreen(origin);canvas.save();canvas.translate(-origin[0],-origin[1]);
                    if(background!=null){background.setBounds(Math.round(dismissBounds.left),Math.round(dismissBounds.top),Math.round(dismissBounds.right),Math.round(dismissBounds.bottom));background.draw(canvas);}
                    else{paint.setColor(0xee34303a);canvas.drawCircle(cx,cy,size/2f,paint);}
                    if(entered){paint.setColor(0x558b3d46);canvas.drawCircle(cx,cy,size/2f,paint);}
                    if(icon!=null){int x=Math.round(cx-iconSize/2f),y=Math.round(cy-iconSize/2f);icon.setBounds(x,y,x+iconSize,y+iconSize);icon.draw(canvas);}
                    else{
                        float half=iconSize*7f/24;paint.setColor(Color.WHITE);paint.setStrokeWidth(iconSize*2f/24);
                        canvas.drawLine(cx-half,cy-half,cx+half,cy+half,paint);canvas.drawLine(cx-half,cy+half,cx+half,cy-half,paint);
                    }
                    if(shadow!=null){paint.setAlpha(entered?128:255);canvas.drawBitmap(shadow,rawX-offsetX,rawY-offsetY,paint);paint.setAlpha(255);}
                    canvas.restore();
                }
            };
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.windowAnimations=0;
            p.setTitle("NativeComplements-suggestion-dismiss");SystemUiHost.trust(p);wm.addView(layer,p);
            // Alpha preserves pointer ownership while the original remains laid out.
            button.setAlpha(0);button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);updateTarget();return true;
        }catch(RuntimeException failure){cancel();return false;}
    }

    private void updateTarget(){
        boolean inside=MiniMagnet.contains(Math.round(dismissBounds.left),Math.round(dismissBounds.top),Math.round(dismissBounds.right),Math.round(dismissBounds.bottom),rawX,rawY,1);
        if(inside&&!entered)source.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
        entered=inside;layer.invalidate();
    }

    void cancel(){
        View old=source,oldLayer=layer;source=null;layer=null;dismissed=null;entered=false;
        if(old!=null){old.setAlpha(sourceAlpha);old.setPressed(false);old.cancelLongPress();}
        if(oldLayer!=null)try{wm.removeViewImmediate(oldLayer);}catch(RuntimeException ignored){}
        shadow=null;
    }
}
