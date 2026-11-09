package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.*;

import android.view.*;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import java.util.*;

/** Trusted app overlays stay below native notification, status bar and IME windows. */
final class WindowControls {
    private final Context c,host;
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
    private View menu;
    private LinearLayout menuActions;
    private Context moduleContext;
    private MiniAction sizeAction,muteAction;
    private boolean miniLarge,muted,edgeHidden;
    private int edgeSide;
    private float edgeCenterY;
    /** Clear each touch-only buffer explicitly; it must never become a visible backdrop. */
    private class ClearView extends FrameLayout {
        ClearView(){super(c);setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);}
    }
    WindowControls(Context context){
        host=context;
        // SunOS uses APPLICATION_OVERLAY for pinned controls. A matching window
        // context keeps these touch regions below status/heads-up/shade windows.
        c=context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        wm=c.getSystemService(WindowManager.class);
    }
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
        miniLarge=i.getBooleanExtra("miniLarge",false);muted=i.getBooleanExtra("muted",false);
        edgeHidden=i.getBooleanExtra("edgeHidden",false);edgeSide=i.getIntExtra("edgeSide",1);
        edgeCenterY=i.getFloatExtra("edgeCenterY",ny+nh/2);
        if(!"mini".equals(frame)||edgeHidden||transitioning||systemBlocked)hideMenu();
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
            View mini=new ClearView(){
                final Paint edgePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv){
                    super.onDraw(cv);
                    if(edgeHidden){
                        edgePaint.setColor(0xfff0f2f2);
                        float left=edgeSide<0?dp(12):getWidth()-dp(16),top=(getHeight()-dp(48))/2f;
                        cv.drawRoundRect(left,top,left+dp(4),top+dp(48),dp(2),dp(2),edgePaint);
                    }
                }
            };mini.setContentDescription("迷你窗：单击还原，双击全屏，长按调整大小或静音，拖出侧边隐藏；侧边白条点击或向内拖动还原");
            GestureDetector taps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame)){if(menu!=null)hideMenu();else send("NORMAL",0,0,0);}return true;}
                @Override public boolean onDoubleTap(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame)){hideMenu();send("FULL",0,0,0);}return true;}
                @Override public void onLongPress(MotionEvent e){
                    if(Objects.equals(boundToken,token)&&"mini".equals(frame)&&!dragging&&!transitioning&&!systemBlocked){
                        mini.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        if(menu==null)showMenu();else hideMenu();
                    }
                }
            });
            mini.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY,baseX,baseY,endX,endY;boolean moving,edgeTouch,edgeRestored;
                public boolean onTouch(View v,MotionEvent e){
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                        edgeTouch=edgeHidden;edgeRestored=false;
                        if(edgeTouch){downX=e.getRawX();downY=e.getRawY();return true;}
                    }
                    if(edgeTouch){
                        float inward=(e.getRawX()-downX)*(edgeSide<0?1:-1);
                        if(!edgeRestored&&(e.getActionMasked()==MotionEvent.ACTION_MOVE&&inward>dp(20)
                            ||e.getActionMasked()==MotionEvent.ACTION_UP&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)<dp(20))){
                            edgeRestored=true;send("RESTORE_MINI",0,0,0);
                        }
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){hideMenu();return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){moving=false;dragging=false;downX=e.getRawX();downY=e.getRawY();baseX=x;baseY=y;endX=x;endY=y;taps.onTouchEvent(e);return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                        if(!moving&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>ViewConfiguration.get(c).getScaledTouchSlop()){
                            moving=true;dragging=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);taps.onTouchEvent(cancel);cancel.recycle();hideMenu();showDismiss();
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
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("NativeComplements-controls");
        p.dimAmount=0;p.windowAnimations=0;
        if(views.isEmpty())p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        SystemUiHost.trust(p);
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
            if(edgeHidden){
                Insets insets=layoutMetrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                int width=dp(48),height=dp(104);
                rect(0,edgeSide<0?insets.left:d.width()-insets.right-width,
                    Math.max(insets.top,Math.min(edgeCenterY-height/2,d.height()-insets.bottom-height)),width,height);
                views.get(0).setSystemGestureExclusionRects(List.of(new Rect(0,0,width,height)));
                views.get(0).invalidate();
            }else{views.get(0).setSystemGestureExclusionRects(List.of());rect(0,x,y,w,h);views.get(0).invalidate();layoutMenu();}
            changed();return;
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
        if(systemBlocked||transitioning)return new int[0];
        // Keep the existing one-rectangle GestureGate contract. Protect the
        // compact buttons, rather than the potentially wide landscape preview.
        if(edgeHidden&&!params.isEmpty()&&views.get(0).getVisibility()==View.VISIBLE){
            WindowManager.LayoutParams p=params.get(0);return new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
        }
        if(menuActions!=null&&!params.isEmpty()){
            WindowManager.LayoutParams p=params.get(0);
            int width=menuActions.getLayoutParams().width,height=menuActions.getLayoutParams().height;
            int left=p.x+(p.width-width)/2,top=p.y+(p.height-height)/2;
            return new int[]{left,top,left+width,top+height};
        }
        WindowManager.LayoutParams p=views.size()>5&&views.get(5).getVisibility()==View.VISIBLE?params.get(5):null;
        return p==null?new int[0]:new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
    }
    // SunOS pinned_window_overlay.xml: 42dp light circular actions, 18dp
    // original vector icons, 20dp spacing; mute first, then scale.
    private class MiniAction extends ImageButton {
        final boolean sound;
        MiniAction(boolean sound){
            super(c);this.sound=sound;setPadding(0,0,0,0);setMinimumWidth(0);setMinimumHeight(0);
            setScaleType(ImageView.ScaleType.CENTER);setImageTintList(null);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x1f06080a),
                moduleContext.getDrawable(R.drawable.nc_pinned_window_button_shape),null));
            refresh();
        }
        void refresh(){
            boolean selected=sound?muted:miniLarge;
            int icon=sound?(selected?R.drawable.nc_pinned_window_unmute:R.drawable.nc_pinned_window_mute)
                :(selected?R.drawable.nc_pinned_window_scale_small:R.drawable.nc_pinned_window_scale_large);
            // These IDs belong to the module APK, not the SystemUI host resources.
            setImageDrawable(moduleContext.getDrawable(icon));
            setContentDescription(sound?(selected?"取消静音":"静音"):(selected?"缩小迷你窗":"放大迷你窗"));
        }
    }
    private void showMenu(){
        if(token==null||!"mini".equals(frame)||transitioning||systemBlocked||menu!=null||views.isEmpty())return;
        try{if(moduleContext==null)moduleContext=c.createPackageContext(Wire.PKG,0);}
        catch(android.content.pm.PackageManager.NameNotFoundException e){android.util.Log.e("NativeComplements","MINI_MENU_RESOURCES_UNAVAILABLE",e);return;}
        final String bound=token;
        FrameLayout cover=new FrameLayout(c);
        cover.setBackground(moduleContext.getDrawable(R.drawable.nc_pinned_window_menu_shape));
        cover.setClipToOutline(true);cover.setOnClickListener(v->hideMenu());
        menuActions=new LinearLayout(c);menuActions.setGravity(Gravity.CENTER);
        sizeAction=new MiniAction(false);muteAction=new MiniAction(true);
        menuActions.addView(muteAction);menuActions.addView(sizeAction);
        cover.addView(menuActions,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        sizeAction.setOnClickListener(v->{if(Objects.equals(bound,token)){v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);hideMenu();send("MINI_SIZE",0,0,0);}});
        muteAction.setOnClickListener(v->{if(Objects.equals(bound,token)){v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);hideMenu();send("MUTE",0,0,0);}});
        menu=cover;
        ((FrameLayout)views.get(0)).addView(cover,new FrameLayout.LayoutParams(-1,-1));
        layoutMenu();changed();
    }
    private void layoutMenu(){
        if(menu==null||menuActions==null||params.isEmpty())return;
        WindowManager.LayoutParams p=params.get(0);
        boolean horizontal=p.width>p.height;
        Rect display=wm.getCurrentWindowMetrics().getBounds();
        int gap=dp(20);
        int major=Math.min(horizontal?p.width:p.height,(horizontal?display.width():display.height())/2);
        int minor=Math.min(horizontal?p.height:p.width,(horizontal?display.height():display.width())/2);
        int side=Math.max(1,Math.min(dp(42),Math.min(minor,(major-gap)/2)));
        menuActions.setOrientation(horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        for(int i=0;i<2;i++){
            LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(side,side);
            if(i==0){if(horizontal)action.rightMargin=gap;else action.bottomMargin=gap;}
            menuActions.getChildAt(i).setLayoutParams(action);
        }
        menuActions.setLayoutParams(new FrameLayout.LayoutParams(horizontal?side*2+gap:side,horizontal?side:side*2+gap,Gravity.CENTER));
        sizeAction.refresh();muteAction.refresh();
    }
    private void hideMenu(){
        if(menu==null)return;View old=menu;menu=null;menuActions=null;sizeAction=null;muteAction=null;
        if(old.getParent() instanceof android.view.ViewGroup parent)parent.removeView(old);
        changed();
    }
    private void changed(){if(host instanceof SystemUiHost service)service.controlsChanged();}
    void systemOverlay(boolean blocked){
        if(systemBlocked==blocked)return;systemBlocked=blocked;
        if(blocked){
            frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;
            if(dragging&&token!=null)Wire.control(c,"NORMAL",token,0,0,0);
            dragging=false;hideMenu();hideDismiss();for(View v:views)v.setVisibility(View.GONE);changed();
        }else if(!views.isEmpty()&&!transitioning)layout();
    }
    private boolean inDismiss(float px,float py){Rect d=wm.getCurrentWindowMetrics().getBounds();return px>d.width()/2-dp(110)&&px<d.width()/2+dp(110)&&py>=0&&py<dp(125);}
    private void showDismiss(){
        if(dismiss!=null)return;
        dismiss=new TextView(c);dismiss.setText("拖到这里松手关闭");dismiss.setTextColor(Color.WHITE);dismiss.setTextSize(16);dismiss.setGravity(Gravity.CENTER);dismiss.setBackgroundColor(0xee34303a);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(220),dp(64),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(35);p.setFitInsetsTypes(0);p.setTitle("NativeComplements-dismiss");
        try{SystemUiHost.trust(p);wm.addView(dismiss,p);}catch(RuntimeException e){dismiss=null;}
    }
    private void highlight(float px,float py){if(dismiss!=null)dismiss.setBackgroundColor(inDismiss(px,py)?0xff8b3d46:0xee34303a);}
    private void hideDismiss(){if(dismiss!=null){try{wm.removeViewImmediate(dismiss);}catch(RuntimeException ignored){}dismiss=null;}}
    private void hideViews(){hideMenu();for(View v:views)try{wm.removeViewImmediate(v);}catch(RuntimeException ignored){}views.clear();params.clear();hideDismiss();}
    void hide(){frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;hideViews();frame="";token=null;dragging=false;changed();}
    void close(){send("CLOSE",0,0,0);hide();}
    void imeTop(int top){if(imeTop==top)return;imeTop=top;if(!views.isEmpty()&&!transitioning&&!dragging)layout();}
}
