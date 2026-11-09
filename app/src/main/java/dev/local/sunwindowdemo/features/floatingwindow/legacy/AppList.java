package dev.local.sunwindowdemo;

import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A View card inside the existing SystemUI overlay. Never starts an Activity or owns a task. */
final class AppList {
    static View create(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,Consumer<List<ShortcutTarget>> save,Runnable close) {
        return new Panel(c,all,selected,launch,save,close);
    }

    private static final class Panel extends LinearLayout {
        final Context c;
        final boolean compact;
        final List<AppCatalog.App> apps;
        final ArrayList<ShortcutTarget> appRows=new ArrayList<>(), chosen=new ArrayList<>(), shortcuts=new ArrayList<>();
        final ArrayList<ShortcutTarget> visible=new ArrayList<>();
        final Consumer<ShortcutTarget> launch;
        final Consumer<List<ShortcutTarget>> save;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Handler main=new Handler(Looper.getMainLooper());
        final int surface,container,text,secondary,accent,tonal;
        final TextView manage,selectedTitle,help,empty,bubble;
        final TextView[] tabs=new TextView[2];
        final LinearLayout selectedRow;
        final GridView grid;
        final FrameLayout body;
        final IndexBar index;
        final BaseAdapter adapter;
        boolean editing,shortcutRequested,shortcutLoading,disposed;
        int tab,hover=-1;
        String shortcutMessage="正在读取应用快捷功能…";

        Panel(Context context,List<AppCatalog.App> all,List<ShortcutTarget> initial,
                Consumer<ShortcutTarget> launch,Consumer<List<ShortcutTarget>> save,Runnable close) {
            super(context);c=context;apps=List.copyOf(all);this.launch=launch;this.save=save;
            compact=context.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
            surface=color(android.R.attr.colorBackground,0xfff8f7fc);
            text=color(android.R.attr.textColorPrimary,0xff1d1b20);
            secondary=color(android.R.attr.textColorSecondary,0xff625f68);
            accent=color(android.R.attr.colorAccent,0xff6750a4);
            container=blend(surface,text,.045f);tonal=blend(surface,accent,.13f);
            setOrientation(VERTICAL);setPadding(dp(16),dp(compact?6:10),dp(16),dp(compact?6:14));setBackground(round(container,28));
            setClipToOutline(true);setOnClickListener(v->{});
            long serial=ShortcutTarget.serial(c,Process.myUserHandle());
            for(AppCatalog.App app:apps) appRows.add(new ShortcutTarget(app.pkg,null,Process.myUserHandle(),serial,app.label,app.label,app.icon,app.section));
            for(ShortcutTarget item:initial) if(item!=null&&ShortcutTarget.validKey(item.key())&&selectedIndex(item.key())<0&&chosen.size()<5) chosen.add(item);

            LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title=label("更多应用",compact?20:22,text);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            header.addView(title,new LinearLayout.LayoutParams(0,dp(compact?44:52),1));
            manage=button("管理",()->toggleEditing());header.addView(manage,new LinearLayout.LayoutParams(dp(64),dp(compact?40:48)));
            TextView dismiss=button("×",close);dismiss.setTextSize(26);dismiss.setContentDescription("关闭更多应用");
            header.addView(dismiss,new LinearLayout.LayoutParams(dp(44),dp(compact?40:48)));addView(header);

            LinearLayout tabRow=new LinearLayout(c);tabRow.setPadding(0,dp(compact?2:6),0,dp(compact?6:10));
            for(int i=0;i<2;i++) {
                final int target=i;tabs[i]=button(i==0?"应用":"快捷功能",()->setTab(target));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(compact?34:40),1);if(i==0)p.rightMargin=dp(6);
                tabRow.addView(tabs[i],p);
            }
            addView(tabRow);
            selectedTitle=label("",compact?12:13,accent);selectedTitle.setPadding(dp(4),dp(compact?2:4),0,dp(compact?2:4));addView(selectedTitle);
            selectedRow=new LinearLayout(c);selectedRow.setGravity(Gravity.CENTER_VERTICAL);
            addView(selectedRow,new LinearLayout.LayoutParams(-1,dp(compact?64:86)));
            help=label("长按已选项拖动排序，点击 + 添加、− 移除；完成后保存",11,secondary);
            help.setPadding(dp(4),0,dp(4),dp(8));help.setMaxLines(2);addView(help);
            selectedRow.setOnDragListener((view,event)->drag(event));

            body=new FrameLayout(c);addView(body,new LinearLayout.LayoutParams(-1,0,1));
            grid=new GridView(c);grid.setNumColumns(4);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(4));grid.setVerticalSpacing(dp(4));grid.setClipToPadding(false);
            grid.setPadding(0,dp(4),0,dp(4));grid.setVerticalScrollBarEnabled(false);
            grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,-1);gp.rightMargin=dp(26);body.addView(grid,gp);
            adapter=new BaseAdapter() {
                public int getCount(){return visible.size();}
                public Object getItem(int position){return visible.get(position);}
                public long getItemId(int position){return position;}
                public View getView(int position,View recycled,ViewGroup parent){return tile(visible.get(position),false);}
            };
            grid.setAdapter(adapter);grid.setOnItemClickListener((p,v,position,id)->{
                ShortcutTarget target=visible.get(position);if(editing)toggle(target);else launch.accept(target);
            });
            empty=label("",14,secondary);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),0,dp(16),0);
            body.addView(empty,new FrameLayout.LayoutParams(-1,-1));
            bubble=label("",22,accent);bubble.setGravity(Gravity.CENTER);bubble.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            bubble.setBackground(round(tonal,20));bubble.setVisibility(GONE);bubble.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP|Gravity.RIGHT);bp.rightMargin=dp(32);body.addView(bubble,bp);
            index=new IndexBar(c);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(26),-1,Gravity.RIGHT);body.addView(index,ip);
            renderSelected();setTab(0);
        }
        int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
        int color(int attr,int fallback){
            TypedValue value=new TypedValue();
            try {if(c.getTheme().resolveAttribute(attr,value,true))return value.resourceId!=0?c.getColor(value.resourceId):value.data;}
            catch(RuntimeException ignored){}
            return fallback;
        }
        static int blend(int a,int b,float amount){return Color.rgb(
                Math.round(Color.red(a)*(1-amount)+Color.red(b)*amount),
                Math.round(Color.green(a)*(1-amount)+Color.green(b)*amount),
                Math.round(Color.blue(a)*(1-amount)+Color.blue(b)*amount));}
        GradientDrawable round(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
        Drawable ripple(int background){return new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x26000000),round(background,22),round(Color.WHITE,22));}
        TextView label(String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
        TextView button(String value,Runnable action){TextView t=label(value,14,accent);t.setGravity(Gravity.CENTER);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setBackground(ripple(Color.TRANSPARENT));t.setOnClickListener(v->action.run());return t;}
        int selectedIndex(String key){for(int i=0;i<chosen.size();i++)if(chosen.get(i).key().equals(key))return i;return -1;}

        void toggleEditing(){
            if(editing) {
                try {save.accept(List.copyOf(chosen));}
                catch(RuntimeException failure){Toast.makeText(c,"保存失败，请重试",Toast.LENGTH_SHORT).show();return;}
            }
            editing=!editing;manage.setText(editing?"完成":"管理");renderSelected();adapter.notifyDataSetChanged();
        }
        void toggle(ShortcutTarget target){
            int position=selectedIndex(target.key());
            if(position>=0)chosen.remove(position);
            else if(chosen.size()<5)chosen.add(target);
            else {Toast.makeText(c,"最多选择 5 个应用或快捷功能",Toast.LENGTH_SHORT).show();return;}
            renderSelected();adapter.notifyDataSetChanged();
        }
        void renderSelected(){
            selectedTitle.setText("快捷入口 · "+chosen.size()+" / 5");
            selectedTitle.setVisibility(editing?VISIBLE:GONE);selectedRow.setVisibility(editing?VISIBLE:GONE);help.setVisibility(editing&&!compact?VISIBLE:GONE);
            selectedRow.removeAllViews();
            if(!editing)return;
            for(int i=0;i<5;i++) {
                View child;
                if(i<chosen.size()) {
                    ShortcutTarget target=chosen.get(i);child=tile(target,true);
                    child.setOnClickListener(v->toggle(target));
                    child.setOnLongClickListener(v->v.startDragAndDrop(ClipData.newPlainText("shortcut-target",target.key()),new View.DragShadowBuilder(v),target.key(),0));
                    child.setContentDescription(target.label+"，点击移除，长按拖动调整顺序");
                } else {
                    TextView add=label("+\n添加",13,secondary);add.setGravity(Gravity.CENTER);add.setBackground(ripple(surface));add.setOnClickListener(v->setTab(0));child=add;
                }
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);selectedRow.addView(child,p);
            }
        }
        boolean drag(DragEvent event){
            if(!editing||!(event.getLocalState() instanceof String)||selectedIndex((String)event.getLocalState())<0)return false;
            int action=event.getAction();
            if(action==DragEvent.ACTION_DRAG_LOCATION||action==DragEvent.ACTION_DROP) {
                hover=Math.max(0,Math.min(chosen.size()-1,(int)(event.getX()/Math.max(1,selectedRow.getWidth())*5)));
                for(int i=0;i<selectedRow.getChildCount();i++)selectedRow.getChildAt(i).setAlpha(i==hover?.55f:1);
            }
            if(action==DragEvent.ACTION_DROP) {
                int from=selectedIndex((String)event.getLocalState());ShortcutTarget item=chosen.remove(from);chosen.add(hover,item);renderSelected();return true;
            }
            if(action==DragEvent.ACTION_DRAG_ENDED||action==DragEvent.ACTION_DRAG_EXITED) {
                hover=-1;for(int i=0;i<selectedRow.getChildCount();i++)selectedRow.getChildAt(i).setAlpha(1);
            }
            return true;
        }
        View tile(ShortcutTarget target,boolean selected){
            boolean dense=selected&&compact;
            LinearLayout cell=new LinearLayout(c);cell.setOrientation(VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2),dp(dense?2:4),dp(2),dp(2));cell.setBackground(ripple(selected?tonal:Color.TRANSPARENT));
            FrameLayout artwork=new FrameLayout(c);cell.addView(artwork,new LinearLayout.LayoutParams(-1,dp(dense?38:48)));
            ImageView image=new ImageView(c);Drawable icon=target.icon;
            if(icon!=null&&icon.getConstantState()!=null)icon=icon.getConstantState().newDrawable(c.getResources()).mutate();
            image.setImageDrawable(icon);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize=dp(dense?32:selected?36:42);artwork.addView(image,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
            if(editing) {
                TextView badge=label(selectedIndex(target.key())>=0?"−":"+",16,accent);badge.setGravity(Gravity.CENTER);badge.setBackground(round(tonal,12));badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                FrameLayout.LayoutParams b=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.RIGHT);artwork.addView(badge,b);
            }
            TextView title=label(target.label,12,text);title.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);title.setMaxLines(dense||target.isShortcut()&&!selected?1:2);title.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(title,new LinearLayout.LayoutParams(-1,dense?dp(18):target.isShortcut()&&!selected?dp(17):dp(32)));
            if(target.isShortcut()&&!selected){TextView owner=label(target.appLabel,10,secondary);owner.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);owner.setSingleLine(true);owner.setEllipsize(TextUtils.TruncateAt.END);cell.addView(owner,new LinearLayout.LayoutParams(-1,dp(15)));}
            cell.setContentDescription(target.label+(target.isShortcut()?"，"+target.appLabel:"")+(editing?(selectedIndex(target.key())>=0?"，已选，点击移除":"，点击添加"):""));
            return cell;
        }
        void setTab(int next){
            tab=next;for(int i=0;i<tabs.length;i++)tabs[i].setBackground(ripple(i==tab?tonal:Color.TRANSPARENT));
            if(tab==1&&!shortcutRequested)loadShortcuts();refreshRows();
        }
        void refreshRows(){
            visible.clear();visible.addAll(tab==0?appRows:shortcuts);adapter.notifyDataSetChanged();
            boolean noRows=visible.isEmpty();empty.setText(tab==1?(shortcutLoading?"正在读取应用快捷功能…":shortcutMessage):"暂无可启动的应用");
            empty.setVisibility(noRows?VISIBLE:GONE);grid.setVisibility(noRows?GONE:VISIBLE);index.setVisibility(noRows?GONE:VISIBLE);bubble.setVisibility(GONE);
        }
        void loadShortcuts(){
            shortcutRequested=true;shortcutLoading=true;
            worker.execute(()->{
                AppCatalog.Shortcuts result=AppCatalog.loadShortcuts(c,apps);
                main.post(()->{
                    if(disposed)return;shortcutLoading=false;shortcuts.clear();shortcuts.addAll(result.items);shortcutMessage=result.message;
                    for(int i=0;i<chosen.size();i++)for(ShortcutTarget real:shortcuts)if(chosen.get(i).key().equals(real.key())){chosen.set(i,real);break;}
                    renderSelected();refreshRows();
                });
            });
        }
        @Override protected void onDetachedFromWindow(){disposed=true;worker.shutdownNow();super.onDetachedFromWindow();}

        final class IndexBar extends View {
            final String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            int active=-1;
            IndexBar(Context context){super(context);setContentDescription("按字母滑动定位，中文应用按拼音分组");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);float row=getHeight()/(float)alphabet.length();
                paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                paint.setTextSize(Math.min(dp(11),Math.max(dp(6),row*.8f)));
                int step=Math.max(1,(int)Math.ceil(dp(10)/Math.max(1,row)));
                for(int i=0;i<alphabet.length();i++)if(i%step==0||i==alphabet.length()-1||i==active){
                    paint.setColor(i==active?accent:secondary);Paint.FontMetrics fm=paint.getFontMetrics();
                    canvas.drawText(alphabet.substring(i,i+1),getWidth()/2f,row*(i+.5f)-(fm.ascent+fm.descent)/2,paint);
                }
            }
            @Override public boolean onTouchEvent(MotionEvent event){
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE){
                    getParent().requestDisallowInterceptTouchEvent(true);
                    active=Math.max(0,Math.min(26,(int)(event.getY()/Math.max(1,getHeight())*27)));
                    String letter=alphabet.substring(active,active+1);int position=-1;
                    for(int i=0;i<visible.size();i++)if(alphabet.indexOf(visible.get(i).section)>=active){position=i;break;}
                    if(position<0)position=Math.max(0,visible.size()-1);grid.setSelection(position);
                    bubble.setText(letter);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)bubble.getLayoutParams();p.topMargin=Math.max(0,Math.min(body.getHeight()-dp(40),(int)event.getY()-dp(20)));bubble.setLayoutParams(p);bubble.setVisibility(VISIBLE);invalidate();return true;
                }
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){active=-1;bubble.setVisibility(GONE);invalidate();return true;}
                return true;
            }
        }
    }
}
