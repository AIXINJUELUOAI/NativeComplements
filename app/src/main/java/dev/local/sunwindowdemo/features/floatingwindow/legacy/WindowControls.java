package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.*;

import android.view.*;
import android.widget.TextView;
import java.util.*;

/** Trusted accessibility overlays cover only outside/handle areas, or the mini preview. */
final class WindowControls {
    private final Context c;
    private final WindowManager wm;
    private final List<View> views=new ArrayList<>();
    private final List<WindowManager.LayoutParams> params=new ArrayList<>();
    private String token,frame="";
    private float x,y,w,h,scale,normalScale=.75f;
    private boolean appLandscape,systemBlocked;
    private String scaleZone="normal";
    private int logicalW,logicalH;
    private boolean landscape,dragging;
    private long sequence=-1;
    private boolean motionPosted,transitioning,fullTransition,nativeHandle;
    private String motionOp;
    private float motionScale,motionX,motionY,barAlpha=1;
    private final Choreographer frames=Choreographer.getInstance();
    private final Choreographer.FrameCallback motionFrame=time->{motionPosted=false;flushMotion();};
    private int imeTop=Integer.MAX_VALUE;
    private WindowMetrics layoutMetrics;
    private TextView dismiss;
    /** Clear each touch-only buffer explicitly; it must never become a visible backdrop. */
    private class ClearView extends View {
        ClearView(){super(c);setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);}
    }
    WindowControls(Context context){c=context;wm=c.getSystemService(WindowManager.class);}
    private int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    void update(Intent i){
        if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        long serial=i.getLongExtra("sequence",0);if(serial<=sequence)return;sequence=serial;
        if(!"active".equals(i.getStringExtra("state"))){hide();return;}
        if(!i.getBooleanExtra("presented",false)){hide();return;}
        nativeHandle=i.getBooleanExtra("nativeHandle",false);
        String next=i.getStringExtra("frame"),key=i.getStringExtra("token");
        if(!Wire.validToken(key))return;
        float nx=i.getFloatExtra("x",0),ny=i.getFloatExtra("y",0),nw=i.getFloatExtra("width",0),nh=i.getFloatExtra("height",0);
        if(!Float.isFinite(nx)||!Float.isFinite(ny)||!Float.isFinite(nw)||!Float.isFinite(nh)||nw<=0||nh<=0)return;
        transitioning=i.getBooleanExtra("transitioning",false);
        fullTransition=i.getBooleanExtra("fullTransition",false);
        barAlpha=i.getFloatExtra("barAlpha",1);
        // Keep the normal handle visible through normal/mini animation, fading with progress.
        if(transitioning&&i.getBooleanExtra("transitionHandle",false))next="normal";
        boolean rebuild=!Objects.equals(token,key)||views.isEmpty();
        if(rebuild){hide();token=key;}
        frame=next;
        normalScale=i.getFloatExtra("normalScale",.75f);appLandscape=i.getBooleanExtra("appLandscape",false);
        if(!dragging||transitioning){x=nx;y=ny;w=nw;h=nh;scale=i.getFloatExtra("scale",1);}
        logicalW=i.getIntExtra("logicalW",1200);logicalH=i.getIntExtra("logicalH",2133);
        Rect display=wm.getCurrentWindowMetrics().getBounds();boolean orientation=display.width()>display.height();
        landscape=orientation;
        if(rebuild)build();
        if(transitioning||systemBlocked){for(View v:views)v.setVisibility(View.GONE);changed();}
        else if(!dragging)layout();
    }
    private void build(){
        {
            final String boundToken=token;
            View mini=new ClearView();mini.setContentDescription("迷你窗：单击还原，双击全屏，拖到顶部关闭");
            GestureDetector taps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame))send("NORMAL",0,0,0);return true;}
                @Override public boolean onDoubleTap(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame))send("FULL",0,0,0);return true;}
            });
            mini.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY,baseX,baseY,endX,endY;boolean moving;
                public boolean onTouch(View v,MotionEvent e){
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){moving=false;dragging=false;downX=e.getRawX();downY=e.getRawY();baseX=x;baseY=y;endX=x;endY=y;taps.onTouchEvent(e);return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                        if(!moving&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>ViewConfiguration.get(c).getScaledTouchSlop()){
                            moving=true;dragging=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);taps.onTouchEvent(cancel);cancel.recycle();showDismiss();
                        }
                        if(moving){endX=baseX+e.getRawX()-downX;endY=baseY+e.getRawY()-downY;sendMove("MOVE",endX,endY);highlight(e.getRawX(),e.getRawY());}else taps.onTouchEvent(e);
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        if(moving){boolean close=e.getActionMasked()==MotionEvent.ACTION_UP&&inDismiss(e.getRawX(),e.getRawY());hideDismiss();dragging=false;send(close?"CLOSE":"DOCK",0,endX,endY);}else taps.onTouchEvent(e);
                        return true;
                    }
                    return true;
                }
            });add(mini);
        }
        {
            for(int n=0;n<4;n++){
                View outside=new ClearView();outside.setContentDescription("点击空白处关闭小窗");
                GestureDetector closeTaps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
                    @Override public boolean onDown(MotionEvent e){return true;}
                    @Override public boolean onSingleTapUp(MotionEvent e){if(!new Config(c).doubleClose())send("CLOSE",0,0,0);return true;}
                    @Override public boolean onDoubleTap(MotionEvent e){if(new Config(c).doubleClose())send("CLOSE",0,0,0);return true;}
                });
                outside.setContentDescription("空白处关闭小窗，方式可在设置选择");
                outside.setOnTouchListener((v,e)->{closeTaps.onTouchEvent(e);return true;});add(outside);
            }
            View bar=new ClearView(){final Paint p=new Paint(3);@Override protected void onDraw(Canvas cv){
                super.onDraw(cv);p.setColor(0xfff0f2f2);p.setAlpha(Math.round(255*Math.max(0,Math.min(1,barAlpha))));
                if(nativeHandle)return;
                MotionStyle.Bar b=MotionStyle.bar(scale);float length=dp(b.length()),thickness=dp(b.thickness());
                float gap=dp(b.margin());float radius=thickness/2;
                if(landscape)cv.drawRoundRect(gap,(getHeight()-length)/2,gap+thickness,(getHeight()+length)/2,radius,radius,p);
                else cv.drawRoundRect((getWidth()-length)/2,gap,(getWidth()+length)/2,gap+thickness,radius,radius,p);
            }};
            bar.setContentDescription("小窗缩放白条：向内推缩小，向外拉全屏");
            bar.setOnTouchListener(new View.OnTouchListener(){
                float start,startScale;int axis;
                public boolean onTouch(View v,MotionEvent e){
                    if(fullTransition||transitioning)return true;
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){dragging=true;start=landscape?e.getRawX():e.getRawY();startScale=scale;scaleZone=GestureMath.release(scale,normalScale,appLandscape,landscape);axis=landscape?logicalW:logicalH;return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float delta=(landscape?e.getRawX():e.getRawY())-start;sendMoveScale(GestureMath.clampScale(GestureMath.dragScale(startScale,delta,axis),normalScale,appLandscape,landscape));return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_UP){
                        float delta=(landscape?e.getRawX():e.getRawY())-start;
                        float end=GestureMath.clampScale(GestureMath.dragScale(startScale,delta,axis),normalScale,appLandscape,landscape);
                        frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;dragging=false;
                        // One final sample + commit, rather than stale MOVE, RESIZE and RELEASE messages.
                        if(token!=null)Wire.control(c,"END_RESIZE",token,end,0,0);return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;send("NORMAL",0,0,0);return true;}
                    return true;
                }
            });add(bar);
        }
    }
    private void scheduleMotion(String op,float value,float px,float py){
        motionOp=op;motionScale=value;motionX=px;motionY=py;
        if(!motionPosted){motionPosted=true;frames.postFrameCallback(motionFrame);}
    }
    private void flushMotion(){
        if(motionOp==null)return;String op=motionOp;motionOp=null;
        if(token!=null)Wire.control(c,op,token,motionScale,motionX,motionY);
    }
    private void sendMove(String op,float px,float py){scheduleMotion(op,0,px,py);}
    private void sendMoveScale(float s){
        // Track the drag locally for zone feedback; the native transaction draws the bar.
        float cx=x+w/2,cy=y+h/2;scale=s;w=logicalW*s;h=logicalH*s;x=cx-w/2;y=cy-h/2;
        String next=GestureMath.release(s,normalScale,appLandscape,landscape);
        if(!next.equals(scaleZone)){
            scaleZone=next;if(!"normal".equals(next)&&views.size()>5)views.get(5).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        // The touch stream remains captured; the visible bar moves in the task transaction.
        barAlpha=1;scheduleMotion("RESIZE",s,0,0);
    }
    private void send(String op,float value,float x,float y){flushMotion();if(token!=null)Wire.control(c,op,token,value,x,y);}
    private void add(View v){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("SunWindowDemo8.2-controls");
        p.dimAmount=0;p.windowAnimations=0;
        wm.addView(v,p);views.add(v);params.add(p);
    }
    private void rect(int index,float x,float y,float w,float h){
        if(index>=views.size())return;WindowManager.LayoutParams p=params.get(index);
        WindowMetrics metrics=layoutMetrics==null?wm.getCurrentWindowMetrics():layoutMetrics;
        int avoid=WindowInsets.Type.displayCutout();
        // Normal app input is native. In landscape its mini/handle controls follow
        // camera-only geometry too; outside-dismiss overlays still leave bars usable.
        if(!landscape||(index!=0&&index!=5))avoid|=WindowInsets.Type.systemBars();
        Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(avoid);
        float right=Math.min(x+w,metrics.getBounds().width()-bars.right);
        float bottom=Math.min(y+h,Math.min(imeTop,metrics.getBounds().height()-bars.bottom));
        x=Math.max(x,bars.left);y=Math.max(y,bars.top);w=Math.max(0,right-x);h=Math.max(0,bottom-y);
        // Outside-dismiss overlays leave the keyboard, status bar and navigation bar usable.
        int nx=Math.round(x),ny=Math.round(y),nw=Math.max(1,Math.round(w)),nh=Math.max(1,Math.round(h));
        View view=views.get(index);view.setVisibility(w>0&&h>0?View.VISIBLE:View.GONE);
        if(p.x!=nx||p.y!=ny||p.width!=nw||p.height!=nh){
            p.x=nx;p.y=ny;p.width=nw;p.height=nh;wm.updateViewLayout(view,p);
        }
    }
    private void layout(){
        if(systemBlocked){for(View v:views)v.setVisibility(View.GONE);changed();return;}
        layoutMetrics=wm.getCurrentWindowMetrics();
        Rect d=layoutMetrics.getBounds();
        if("mini".equals(frame)){
            for(int n=1;n<views.size();n++)views.get(n).setVisibility(View.GONE);
            rect(0,x,y,w,h);changed();return;
        }
        if(!views.isEmpty())views.get(0).setVisibility(View.GONE);
        rect(1,0,0,d.width(),Math.max(0,y));rect(2,0,y,Math.max(0,x),h);
        rect(3,x+w,y,Math.max(0,d.width()-x-w),h);rect(4,0,y+h,d.width(),Math.max(0,d.height()-y-h));
        MotionStyle.Bar b=MotionStyle.bar(scale);
        float length=dp(b.length()),thickness=dp(b.thickness()),margin=dp(b.margin());
        float touchLength=Math.max(dp(120),length+dp(64)),touchDepth=dp(72);
        if(landscape)rect(5,x+w-dp(12),y+h/2-touchLength/2,touchDepth,touchLength);
        else rect(5,x+w/2-touchLength/2,y+h-dp(12),touchLength,touchDepth);
        if(!nativeHandle&&views.size()>5)views.get(5).invalidate();
        changed();
    }
    int[] protectedRects(){
        if(systemBlocked||transitioning||views.size()<6||views.get(5).getVisibility()!=View.VISIBLE)return new int[0];
        WindowManager.LayoutParams p=params.get(5);return new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
    }
    private void changed(){if(c instanceof CornerService service)service.controlsChanged();}
    void systemOverlay(boolean blocked){
        if(systemBlocked==blocked)return;systemBlocked=blocked;
        if(blocked){
            frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;
            if(dragging&&token!=null)Wire.control(c,"NORMAL",token,0,0,0);
            dragging=false;hideDismiss();for(View v:views)v.setVisibility(View.GONE);changed();
        }else if(!views.isEmpty()&&!transitioning)layout();
    }
    private boolean inDismiss(float px,float py){Rect d=wm.getCurrentWindowMetrics().getBounds();return px>d.width()/2-dp(110)&&px<d.width()/2+dp(110)&&py>=0&&py<dp(125);}
    private void showDismiss(){if(dismiss!=null)return;dismiss=new TextView(c);dismiss.setText("拖到这里松手关闭");dismiss.setTextColor(Color.WHITE);dismiss.setTextSize(16);dismiss.setGravity(Gravity.CENTER);dismiss.setBackgroundColor(0xee34303a);WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(220),dp(64),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(35);p.setFitInsetsTypes(0);wm.addView(dismiss,p);}
    private void highlight(float px,float py){if(dismiss!=null)dismiss.setBackgroundColor(inDismiss(px,py)?0xff8b3d46:0xee34303a);}
    private void hideDismiss(){if(dismiss!=null){try{wm.removeViewImmediate(dismiss);}catch(RuntimeException ignored){}dismiss=null;}}
    private void hideViews(){for(View v:views)try{wm.removeViewImmediate(v);}catch(RuntimeException ignored){}views.clear();params.clear();hideDismiss();}
    void hide(){frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;hideViews();frame="";token=null;dragging=false;changed();}
    void close(){send("CLOSE",0,0,0);hide();}
    void imeTop(int top){if(imeTop==top)return;imeTop=top;if(!views.isEmpty()&&!transitioning&&!dragging)layout();}
}
