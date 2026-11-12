package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import dev.local.sunwindowdemo.features.suggestions.SuggestionRules;
import dev.local.sunwindowdemo.features.suggestions.SuggestionStore;
import java.util.*;
import java.util.concurrent.*;

/** Event-driven independent SystemUI host. Private content stays in bounded, short-lived memory. */
// Permissions belong to the already privileged SystemUI host (archived manifest checked).
// The bright lock intentionally lasts until SCREEN_OFF/lock/disable, rather than a fixed timeout.
@android.annotation.SuppressLint({"MissingPermission","WakelockTimeout"})
final class SuggestionHost implements ComponentCallbacks,AutoCloseable {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Context windowContext,moduleContext;
    private WindowManager wm;
    private ClipboardManager clipboard;
    private CameraManager cameras;
    private AudioManager audio;
    private PowerManager.WakeLock awakeLock;
    private Bundle settings=new Bundle();
    private View view;
    private SuggestionDrag drag;
    private final Set<String> litCameras=new HashSet<>();
    private boolean registered,closed,systemOverlay,audioConnected,bluetoothUsed,inputRunning,chooser;
    private boolean torchDismissed,opening;
    private int openGeneration;
    private String lastTorchCamera,requestedTorchCamera;
    private Boolean requestedTorchMode;
    private int imeTop=Integer.MAX_VALUE,renderGeneration,inputGeneration;
    private int clipboardHash;
    private long clipboardTimestamp;
    private String code,smsBody,mapText,displayedSmsBody;
    private long displayedSmsUntil;
    private long codeUntil,contentUntil,torchUntil,bluetoothUntil,awakeUntil;
    private SuggestionRules.Link link;
    private List<String> maps=List.of();
    private final Map<String,Notice> notices=new LinkedHashMap<>();
    private record Notice(NotificationListenerService source,String key,String body,long received) {}
    private final Runnable expire=()->{prune();render();};
    private final Runnable retry=this::loadSettings;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener=this::clipboardChanged;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            String action=intent.getAction();
            if(SuggestionStore.CHANGED.equals(action)||SuggestionStore.SMS.equals(action)){
                try{if(getSentFromUid()!=context.getPackageManager().getApplicationInfo(Wire.PKG,0).uid)return;}catch(Exception e){return;}
                if(SuggestionStore.CHANGED.equals(action))loadSettings();else sms(intent.getStringExtra("body"));
            }else if(SuggestionStore.AWAKE.equals(action)){
                long time=intent.getLongExtra("time",0);
                if(getSentFromUid()==1000&&enabled("awake")&&!locked()&&SystemClock.uptimeMillis()-time>=0
                        &&SystemClock.uptimeMillis()-time<2000&&(awakeLock==null||!awakeLock.isHeld())){awakeUntil=offerUntil();render();}
            }else if(Intent.ACTION_SCREEN_OFF.equals(action)){awakeUntil=0;releaseAwake();removeView();chooser=false;}
            else render();
        }
    };
    private final CameraManager.TorchCallback torchCallback=new CameraManager.TorchCallback(){
        @Override public void onTorchModeChanged(String camera,boolean enabled){
            boolean changed=litCameras.contains(camera)!=enabled;
            if(enabled){litCameras.add(camera);lastTorchCamera=camera;}else litCameras.remove(camera);
            if(camera.equals(requestedTorchCamera)&&requestedTorchMode!=null&&requestedTorchMode==enabled){requestedTorchCamera=null;requestedTorchMode=null;}
            if(changed){torchDismissed=false;torchUntil=enabled?0:offerUntil();}
            render();
        }
        @Override public void onTorchModeUnavailable(String camera){litCameras.remove(camera);render();}
    };
    private final AudioDeviceCallback audioCallback=new AudioDeviceCallback(){
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){updateAudio();}
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){updateAudio();}
    };

    SuggestionHost(Context c){context=c.getApplicationContext();main.post(this::initialize);}
    private void initialize(){
        if(closed)return;
        try{
            Context display=context.createDisplayContext(context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY));
            windowContext=new android.view.ContextThemeWrapper(display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),android.R.style.Theme_DeviceDefault_DayNight);
            moduleContext=context.createPackageContext(Wire.PKG,0);wm=windowContext.getSystemService(WindowManager.class);
            drag=new SuggestionDrag(windowContext,wm);
            SystemUiHost.trust(new WindowManager.LayoutParams());
            clipboard=context.getSystemService(ClipboardManager.class);cameras=context.getSystemService(CameraManager.class);audio=context.getSystemService(AudioManager.class);
            awakeLock=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"NativeComplements:SuggestionAwake");awakeLock.setReferenceCounted(false);
            IntentFilter filter=new IntentFilter(SuggestionStore.CHANGED);filter.addAction(SuggestionStore.SMS);
            filter.addAction(SuggestionStore.AWAKE);
            filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
            context.registerReceiver(receiver,filter,null,main,Context.RECEIVER_EXPORTED);registered=true;
            context.registerComponentCallbacks(this);
            clipboard.addPrimaryClipChangedListener(clipListener);
            try{cameras.registerTorchCallback(torchCallback,main);}catch(RuntimeException e){status("TORCH_UNAVAILABLE");}
            try{audio.registerAudioDeviceCallback(audioCallback,main);updateAudio();}catch(RuntimeException e){status("AUDIO_UNAVAILABLE");}
            loadSettings();
        }catch(Exception e){status("INIT_UNAVAILABLE");removeView();}
    }
    private void status(String value){android.util.Log.i("NativeComplements","SUGGESTIONS_"+value);}
    private boolean enabled(String feature){return settings.getBoolean("enabled")&&settings.getBoolean(feature);}
    private void loadSettings(){
        if(closed)return;main.removeCallbacks(retry);
        worker.execute(()->{
            try{
                Bundle next=context.getContentResolver().call(Uri.parse("content://"+SuggestionStore.AUTHORITY),"read",null,null);
                if(next==null)throw new IllegalStateException();
                main.post(()->{
                    if(closed)return;
                    long delta=(next.getInt("duration",60)-settings.getInt("duration",60))*1000L;
                    long time=now();
                    if(codeUntil>time)codeUntil+=delta;if(contentUntil>time)contentUntil+=delta;
                    if(torchUntil>time)torchUntil+=delta;if(bluetoothUntil>time)bluetoothUntil+=delta;if(awakeUntil>time)awakeUntil+=delta;
                    boolean offerBluetooth=next.getBoolean("enabled")&&next.getBoolean("bluetooth")&&(!enabled("bluetooth")
                        ||!Objects.equals(settings.getString("bluetooth_app"),next.getString("bluetooth_app")));
                    settings=next;
                    if(offerBluetooth&&audioConnected){bluetoothUsed=false;bluetoothUntil=offerUntil();}
                    if(!enabled("otp")){code=null;smsBody=null;displayedSmsBody=null;notices.clear();}
                    if(!enabled("awake")){awakeUntil=0;releaseAwake();}
                    if(!settings.getBoolean("enabled")){link=null;mapText=null;clipboardTimestamp=0;}
                    if(!enabled("links"))link=null;if(!enabled("maps"))mapText=null;
                    render();
                });
            }catch(Exception e){main.post(()->{if(!closed){settings=new Bundle();removeView();main.postDelayed(retry,3000);}});}
        });
    }
    void environment(boolean overlay,int keyboardTop){
        if(closed)return;
        if(systemOverlay==overlay&&imeTop==keyboardTop)return;
        systemOverlay=overlay;imeTop=keyboardTop;render();
    }
    private void updateAudio(){
        boolean connected=false;
        try{for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int type=device.getType();
            if(type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    ||type==AudioDeviceInfo.TYPE_BLE_HEADSET||type==AudioDeviceInfo.TYPE_BLE_SPEAKER
                    ||type==AudioDeviceInfo.TYPE_HEARING_AID)connected=true;
        }}catch(RuntimeException ignored){}
        if(connected!=audioConnected){audioConnected=connected;bluetoothUsed=false;bluetoothUntil=connected?offerUntil():0;}render();
    }
    private void clipboardChanged(){
        if(closed||!settings.getBoolean("enabled")||locked())return;
        try{
            ClipData clip=clipboard.getPrimaryClip();if(clip==null||clip.getItemCount()!=1)return;
            ClipData.Item item=clip.getItemAt(0);CharSequence text=item.getText();
            String value=SuggestionRules.bounded(text!=null?text.toString():item.getUri()!=null?item.getUri().toString():"");
            long timestamp=clip.getDescription().getTimestamp();
            if(value.isEmpty()||timestamp==clipboardTimestamp&&value.hashCode()==clipboardHash)return;
            clipboardTimestamp=timestamp;clipboardHash=value.hashCode();
            String extracted=enabled("otp")?SuggestionRules.otp(value,true):null;
            if(extracted!=null){if(!extracted.equals(code))smsBody=null;code=extracted;codeUntil=offerUntil();}
            else if(smsBody==null){code=null;codeUntil=0;}
            link=enabled("links")?SuggestionRules.link(value):null;
            mapText=enabled("maps")&&link==null&&extracted==null?SuggestionRules.address(value):null;
            contentUntil=offerUntil();chooser=false;render();
        }catch(RuntimeException e){status("CLIPBOARD_UNAVAILABLE");}
    }
    private static long now(){return SystemClock.elapsedRealtime();}
    private long offerUntil(){return now()+Math.max(5,Math.min(300,settings.getInt("duration",60)))*1000L;}
    private boolean locked(){return !context.getSystemService(PowerManager.class).isInteractive()||context.getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private void sms(String body){
        if(!enabled("otp"))return;
        String value=SuggestionRules.bounded(body),extracted=SuggestionRules.otp(value,false);
        if(extracted==null)return;code=extracted;smsBody=value;codeUntil=offerUntil();render();
    }
    void notification(NotificationListenerService source,StatusBarNotification sbn){
        if(closed||!enabled("otp")||!enabled("hide_sms")||sbn==null)return;
        try{
            if(!sbn.getUser().equals(android.os.Process.myUserHandle())
                    ||!Objects.equals(sbn.getPackageName(),Telephony.Sms.getDefaultSmsPackage(context)))return;
            Notification n=sbn.getNotification();if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
            Parcelable[] messages=n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            CharSequence text;
            if(messages!=null){
                if(messages.length!=1)return;
                if(!(messages[0] instanceof Bundle message))return;
                text=message.getCharSequence("text");
            }else{
                if(n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!=null)return;
                text=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if(text==null)text=n.extras.getCharSequence(Notification.EXTRA_TEXT);
            }
            String body=SuggestionRules.bounded(text==null?null:text.toString());
            if(SuggestionRules.otp(body,false)==null)return;
            notices.put(sbn.getKey(),new Notice(source,sbn.getKey(),body,now()));
            while(notices.size()>12)notices.remove(notices.keySet().iterator().next());
            hideMatchedNotice();main.postDelayed(expire,60000);
        }catch(RuntimeException ignored){/* Keep the original notification on every uncertain path. */}
    }
    void notificationRemoved(String key){notices.remove(key);}
    private static String canonical(String text){return text==null?"":text.replaceAll("\\s+","");}
    private void hideMatchedNotice(){
        if(!enabled("hide_sms")||displayedSmsBody==null||displayedSmsUntil<=now())return;
        for(Notice notice:new ArrayList<>(notices.values())){
            if(now()-notice.received>60000){notices.remove(notice.key);continue;}
            // Exact whole-message association, never just a matching code or a clipboard candidate.
            if(canonical(displayedSmsBody).equals(canonical(notice.body))){
                try{notice.source.cancelNotification(notice.key);notices.remove(notice.key);}catch(RuntimeException ignored){}
            }
        }
    }
    private void prune(){
        if(codeUntil<=now()){code=null;smsBody=null;}
        if(displayedSmsUntil<=now())displayedSmsBody=null;
        if(contentUntil<=now()){link=null;mapText=null;chooser=false;}
        notices.values().removeIf(n->now()-n.received>60000);
    }
    private int dp(float value){return Math.round(value*windowContext.getResources().getDisplayMetrics().density);}
    private int color(int attr,int fallback){
        android.util.TypedValue v=new android.util.TypedValue();
        try{if(windowContext.getTheme().resolveAttribute(attr,v,true))return v.resourceId!=0?windowContext.getColor(v.resourceId):v.data;}catch(RuntimeException ignored){}return fallback;
    }
    private Drawable circle(int color){GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(color);return bg;}
    private Drawable background(){GradientDrawable bg=new GradientDrawable();bg.setColor(color(android.R.attr.colorBackground,0xfff6f3fa));bg.setCornerRadius(dp(28));return bg;}
    private Drawable icon(String pkg){
        try{return AppCatalog.normalized(windowContext,context.getPackageManager().getApplicationIcon(pkg));}catch(Exception e){return null;}
    }
    private Drawable glyph(String name){
        try{int id=moduleContext.getResources().getIdentifier(name,"drawable",Wire.PKG);Drawable d=moduleContext.getDrawable(id).mutate();d.setTint(switch(name){case "ic_suggestion_torch","ic_suggestion_awake"->0xff302600;case "ic_suggestion_torch_off"->Color.WHITE;case "ic_suggestion_otp"->Color.WHITE;default->color(android.R.attr.colorAccent,0xff6750a4);});return d;}catch(RuntimeException e){return context.getDrawable(android.R.drawable.ic_menu_compass);}
    }
    private boolean installed(String pkg){try{return context.getPackageManager().getLaunchIntentForPackage(pkg)!=null;}catch(RuntimeException e){return false;}}
    private List<String> mapApps(){
        ArrayList<String> result=new ArrayList<>();
        for(String pkg:new String[]{"com.autonavi.minimap","com.baidu.BaiduMap","com.tencent.map","com.google.android.apps.maps"})if(installed(pkg))result.add(pkg);
        Intent geo=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q=place"));
        for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(geo,0)){
            String pkg=info.activityInfo.packageName;if(!result.contains(pkg)&&installed(pkg))result.add(pkg);
        }
        return List.copyOf(result);
    }
    private int buttonSize(){return Math.max(32,Math.min(80,settings.getInt("size",48)));}
    private int buttonPadding(float base){int size=buttonSize();return dp((Math.max(48,size)-size)/2f+base*size/48f);}
    private Drawable buttonBackground(Drawable drawable){int inset=dp((Math.max(48,buttonSize())-buttonSize())/2f);return inset==0?drawable:new InsetDrawable(drawable,inset);}
    private ImageView addButton(LinearLayout row,Drawable drawable,String description,Runnable click,Runnable dismiss){
        ImageView button=new ImageView(windowContext);button.setImageDrawable(drawable);button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad=buttonPadding(9);button.setPadding(pad,pad,pad,pad);button.setBackground(buttonBackground(background()));button.setElevation(dp(4));
        button.setContentDescription(description);button.setFocusable(false);button.setClickable(true);
        button.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);click.run();});
        if(drag!=null)drag.attach(button,()->{dismiss.run();render();});
        int touch=dp(Math.max(48,buttonSize()));
        WindowMetrics metrics=wm.getCurrentWindowMetrics();
        android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
        int columns=Math.max(1,(metrics.getBounds().width()-bars.left-bars.right-dp(30))/(touch+dp(10)));
        LinearLayout line=row.getChildCount()==0?null:(LinearLayout)row.getChildAt(row.getChildCount()-1);
        if(line==null||line.getChildCount()>=columns){line=new LinearLayout(windowContext);row.addView(line);}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(touch,touch);p.setMargins(dp(5),dp(5),dp(5),dp(5));line.addView(button,p);return button;
    }
    private ImageView addAppButton(LinearLayout row,Drawable drawable,String description,Runnable click,Runnable dismiss){
        ImageView button=addButton(row,drawable,description,click,dismiss);
        int pad=buttonPadding(4);button.setBackground(null);button.setElevation(0);button.setPadding(pad,pad,pad,pad);return button;
    }
    private void render(){
        try{renderContent();}catch(RuntimeException failure){removeView();status("RENDER_UNAVAILABLE");}
    }
    private void renderContent(){
        if(closed||wm==null)return;prune();main.removeCallbacks(expire);
        if(code!=null)main.postDelayed(expire,Math.max(1,codeUntil-now()));
        if(link!=null||mapText!=null)main.postDelayed(expire,Math.max(1,contentUntil-now()));
        if(displayedSmsUntil>now())main.postDelayed(expire,displayedSmsUntil-now());
        for(Notice notice:notices.values())main.postDelayed(expire,Math.max(1,notice.received+60000-now()));
        if(torchUntil>now())main.postDelayed(expire,torchUntil-now());
        if(bluetoothUntil>now())main.postDelayed(expire,bluetoothUntil-now());
        if(awakeUntil>now())main.postDelayed(expire,awakeUntil-now());
        removeView();if(locked()){awakeUntil=0;releaseAwake();return;}
        if(!settings.getBoolean("enabled")||systemOverlay)return;
        LinearLayout row=new LinearLayout(windowContext);row.setOrientation(LinearLayout.VERTICAL);row.setGravity(settings.getBoolean("right")?Gravity.RIGHT:Gravity.LEFT);row.setPadding(dp(3),dp(3),dp(3),dp(3));
        if(chooser&&mapText!=null){
            row.setOrientation(LinearLayout.VERTICAL);row.setBackground(background());
            for(String pkg:maps){
                LinearLayout item=new LinearLayout(windowContext);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(14),dp(10),dp(14),dp(10));
                ImageView image=new ImageView(windowContext);image.setImageDrawable(icon(pkg));item.addView(image,new LinearLayout.LayoutParams(dp(30),dp(30)));
                TextView label=new TextView(windowContext);try{label.setText(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0)));}catch(Exception e){label.setText(pkg);}
                label.setTextColor(color(android.R.attr.textColorPrimary,Color.BLACK));label.setTextSize(14);label.setPadding(dp(12),0,0,0);item.addView(label);
                item.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);openMap(pkg);});row.addView(item,new LinearLayout.LayoutParams(dp(228),dp(50)));
            }
        }else{
            boolean torchOn=!litCameras.isEmpty();
            if(enabled("torch")&&(torchOn?!torchDismissed:torchUntil>now())){
                ImageView torch=addButton(row,glyph(torchOn?"ic_suggestion_torch":"ic_suggestion_torch_off"),torchOn?"关闭手电筒":"打开手电筒",this::toggleTorch,()->{torchDismissed=true;torchUntil=0;});
                torch.setBackground(buttonBackground(circle(torchOn?0xffc59618:0xff73777f)));
                torch.setEnabled(requestedTorchMode==null);torch.setAlpha(requestedTorchMode==null?1:.5f);
            }
            if(enabled("awake")&&awakeUntil>now()){
                ImageView awake=addButton(row,glyph("ic_suggestion_awake"),"本次保持屏幕常亮",()->{
                if(locked())return;
                try{awakeLock.acquire();awakeUntil=0;render();}catch(RuntimeException e){Toast.makeText(context,"常亮暂不可用",Toast.LENGTH_SHORT).show();}
                },()->awakeUntil=0);
                awake.setBackground(buttonBackground(circle(0xfff2d87d)));
            }
            String bluetooth=settings.getString("bluetooth_app","");
            if(enabled("bluetooth")&&audioConnected&&!bluetoothUsed&&bluetoothUntil>now()&&installed(bluetooth))addAppButton(row,icon(bluetooth),"打开蓝牙目标应用",()->{
                Intent launch=context.getPackageManager().getLaunchIntentForPackage(bluetooth);open(launch,bluetooth,()->{bluetoothUsed=true;render();});
            },()->bluetoothUsed=true);
            if(enabled("otp")&&code!=null&&!inputRunning){
                ImageView otp=addButton(row,glyph("ic_suggestion_otp"),inputRunning?"正在逐位填入验证码":"逐位填入验证码",this::input,()->{code=null;smsBody=null;displayedSmsBody=null;displayedSmsUntil=0;});
                otp.setBackground(buttonBackground(circle(0xff2866c7)));
                otp.setEnabled(!inputRunning);otp.setAlpha(inputRunning?.5f:1);
                int generation=renderGeneration;
                row.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener(){
                    boolean scheduled;
                    public void onDraw(){if(scheduled)return;scheduled=true;main.post(()->{
                        if(row.getViewTreeObserver().isAlive())row.getViewTreeObserver().removeOnDrawListener(this);
                        if(generation==renderGeneration&&view==row&&otp.isShown()){
                            if(smsBody!=null){displayedSmsBody=smsBody;displayedSmsUntil=Math.min(codeUntil,now()+60000);main.postDelayed(expire,Math.max(1,displayedSmsUntil-now()));}
                            hideMatchedNotice();
                        }
                    });}
                });
            }
            if(enabled("links")&&link!=null&&installed(link.packageName())){
                SuggestionRules.Link target=link;
                addAppButton(row,icon(target.packageName()),target.token()?"打开应用识别分享口令":"打开分享链接",()->{
                    Intent launch=target.token()?context.getPackageManager().getLaunchIntentForPackage(target.packageName()):new Intent(Intent.ACTION_VIEW,Uri.parse(target.value())).setPackage(target.packageName());
                    open(launch,target.packageName(),()->{if(Objects.equals(link,target))link=null;render();});
                },()->link=null);
            }
            if(enabled("maps")&&mapText!=null){
                maps=mapApps();if(!maps.isEmpty()){ImageView map=addButton(row,maps.size()==1?icon(maps.get(0)):glyph("ic_suggestion_navigation"),"用地图搜索地点",()->{
                    if(maps.size()==1)openMap(maps.get(0));else{chooser=true;render();}
                },()->{mapText=null;chooser=false;});if(maps.size()==1){int pad=buttonPadding(4);map.setBackground(null);map.setElevation(0);map.setPadding(pad,pad,pad,pad);}}
            }
        }
        if(row.getChildCount()==0)return;
        row.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE&&chooser){chooser=false;render();}return false;});
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.BOTTOM|(settings.getBoolean("right")?Gravity.RIGHT:Gravity.LEFT);p.setFitInsetsTypes(0);p.windowAnimations=0;p.setTitle("NativeComplements-suggestions");SystemUiHost.trust(p);
        WindowMetrics metrics=wm.getCurrentWindowMetrics();android.graphics.Rect bounds=metrics.getBounds();
        android.graphics.Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
        p.x=(settings.getBoolean("right")?bars.right:bars.left)+dp(12);
        p.y=Math.max(bars.bottom,imeTop==Integer.MAX_VALUE?0:Math.max(0,bounds.bottom-imeTop))+dp(12);
        try{view=row;wm.addView(row,p);}catch(RuntimeException e){view=null;status("OVERLAY_UNAVAILABLE");}
    }
    private void open(Intent intent,String pkg,Runnable completed){
        if(intent==null||locked()||opening)return;
        try{
            Intent launch=new Intent(intent).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(launch.resolveActivity(context.getPackageManager())==null)throw new IllegalStateException();
            if(settings.getBoolean("window")){
                if(!new Config(context).enabled()){
                    Toast.makeText(context,"请开启小窗控制，或关闭建议中的小窗打开",Toast.LENGTH_SHORT).show();return;
                }
                opening=true;int generation=++openGeneration;
                ResultReceiver reply=new ResultReceiver(main){
                    @Override protected void onReceiveResult(int result,Bundle data){
                        if(closed||generation!=openGeneration||!opening)return;
                        opening=false;
                        if(result==1){chooser=false;completed.run();}
                        else {Toast.makeText(context,"打开未完成，请重试",Toast.LENGTH_SHORT).show();render();}
                    }
                };
                Wire.openIntent(context,pkg,launch,reply);
                main.postDelayed(()->{if(!closed&&opening&&generation==openGeneration){opening=false;openGeneration++;Toast.makeText(context,"打开超时，请重试",Toast.LENGTH_SHORT).show();render();}},15000);
            }else {context.startActivity(launch);chooser=false;completed.run();}
        }catch(Exception e){opening=false;openGeneration++;Toast.makeText(context,"目标应用暂不支持此入口",Toast.LENGTH_SHORT).show();}
    }
    private void openMap(String pkg){
        if(mapText==null)return;
        String text=mapText,owner=SuggestionRules.mapOwner(text),url=SuggestionRules.firstUrl(text);
        Intent target;
        if(url!=null&&owner!=null){
            // Preserve short links and coordinates for the map app which understands them.
            target=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(pkg);
            if(!owner.equals(pkg)&&target.resolveActivity(context.getPackageManager())==null){
                Toast.makeText(context,"请使用分享链接对应的地图应用",Toast.LENGTH_SHORT).show();return;
            }
        }else target=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="+Uri.encode(text))).setPackage(pkg);
        open(target,pkg,()->{if(Objects.equals(mapText,text))mapText=null;chooser=false;render();});
    }
    private void input(){
        if(inputRunning||code==null||codeUntil<=now()||locked())return;
        inputRunning=true;String digits=code;int generation=++inputGeneration;render();
        ResultReceiver reply=new ResultReceiver(main){
            @Override protected void onReceiveResult(int result,Bundle data){
                if(closed||generation!=inputGeneration||!inputRunning)return;inputRunning=false;
                if(result==1&&Objects.equals(code,digits)){code=null;smsBody=null;}
                else if(result!=1)Toast.makeText(context,"输入已停止，请确认目标输入框与键盘",Toast.LENGTH_SHORT).show();render();
            }
        };
        Wire.inputDigits(context,digits,settings.getInt("delay",100),reply);
        main.postDelayed(()->{if(!closed&&inputRunning&&generation==inputGeneration){inputRunning=false;inputGeneration++;render();Toast.makeText(context,"输入服务未响应，请检查模块后端",Toast.LENGTH_SHORT).show();}},12000);
    }
    private void toggleTorch(){
        if(requestedTorchMode!=null)return;
        boolean on=litCameras.isEmpty();String camera=on?lastTorchCamera:litCameras.iterator().next();if(camera==null)return;
        try{
            requestedTorchCamera=camera;requestedTorchMode=on;torchDismissed=false;
            cameras.setTorchMode(camera,on);render();
            main.postDelayed(()->{if(camera.equals(requestedTorchCamera)){requestedTorchCamera=null;requestedTorchMode=null;render();}},1500);
        }catch(Exception e){requestedTorchCamera=null;requestedTorchMode=null;Toast.makeText(context,"手电筒暂不可操作",Toast.LENGTH_SHORT).show();render();}
    }
    private void releaseAwake(){if(awakeLock!=null&&awakeLock.isHeld())try{awakeLock.release();}catch(RuntimeException ignored){}}
    private void removeView(){
        if(drag!=null)drag.cancel();
        renderGeneration++;
        if(view!=null){try{wm.removeViewImmediate(view);}catch(RuntimeException ignored){}view=null;}
    }
    @Override public void onConfigurationChanged(Configuration config){render();}
    @Override public void onLowMemory(){}
    @Override public void close(){
        closed=true;releaseAwake();removeView();main.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(registered){context.unregisterReceiver(receiver);context.unregisterComponentCallbacks(this);}
        if(clipboard!=null)clipboard.removePrimaryClipChangedListener(clipListener);
        if(cameras!=null)cameras.unregisterTorchCallback(torchCallback);
        if(audio!=null)audio.unregisterAudioDeviceCallback(audioCallback);
        code=null;smsBody=null;displayedSmsBody=null;link=null;mapText=null;notices.clear();
    }
}
