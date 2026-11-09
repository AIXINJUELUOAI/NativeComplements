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
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A View card inside the existing SystemUI overlay. Never starts an Activity or owns a task. */
final class AppList {
    interface SaveAction { void save(List<ShortcutTarget> targets,Consumer<Boolean> done); }

    static View create(Context c,List<AppCatalog.App> all,List<ShortcutTarget> selected,
            Consumer<ShortcutTarget> launch,SaveAction save,Runnable close) {
        return new Panel(c,all,selected,launch,save);
    }

    private static final class Panel extends LinearLayout {
        static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
        final Context c;
        final boolean compact;
        final List<AppCatalog.App> apps;
        final ArrayList<ShortcutTarget> appRows=new ArrayList<>(),chosen=new ArrayList<>(),shortcuts=new ArrayList<>(),visible=new ArrayList<>();
        final ArrayList<AppRow> rows=new ArrayList<>();
        final int[] sectionPositions=new int[ALPHABET.length()];
        final Consumer<ShortcutTarget> launch;
        final SaveAction save;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Handler main=new Handler(Looper.getMainLooper());
        final int surface,container,text,secondary,accent,tonal;
        final TextView manage,selectedTitle,help,empty,bubble;
        final TextView[] tabs=new TextView[2];
        final ScrollView selectedScroll;
        final LinearLayout selectedRows;
        final ListView list;
        final FrameLayout body;
        final IndexBar index;
        final BaseAdapter adapter;
        final Runnable dragScroll=this::scrollDrag;
        boolean editing,saving,shortcutRequested,shortcutLoading,disposed;
        int tab,hover=-1;
        float dragX,dragY;
        String shortcutMessage="正在读取应用快捷功能…";

        static final class AppRow {
            final String section;
            final List<ShortcutTarget> items;
            AppRow(String section,List<ShortcutTarget> items){this.section=section;this.items=items;}
        }

        Panel(Context context,List<AppCatalog.App> all,List<ShortcutTarget> initial,
                Consumer<ShortcutTarget> launch,SaveAction save) {
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
            for(AppCatalog.App app:apps)appRows.add(new ShortcutTarget(app.pkg,null,Process.myUserHandle(),serial,app.label,app.label,app.icon,app.section));
            for(ShortcutTarget item:initial)if(item!=null&&ShortcutTarget.validKey(item.key())&&selectedIndex(item.key())<0&&chosen.size()<Config.MAX_TARGETS)chosen.add(item);
            AppCatalog.Shortcuts cached=AppCatalog.cachedShortcuts();
            if(cached!=null){shortcutRequested=true;applyShortcuts(cached);}

            LinearLayout header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title=label("更多应用",14,text);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            header.addView(title,new LinearLayout.LayoutParams(0,dp(compact?36:42),1));
            manage=button("管理",this::toggleEditing);manage.setBackground(ripple(tonal));
            header.addView(manage,new LinearLayout.LayoutParams(dp(72),dp(compact?32:36)));addView(header);

            selectedTitle=label("",12,secondary);selectedTitle.setPadding(dp(4),dp(2),0,dp(2));addView(selectedTitle);
            selectedScroll=new ScrollView(c);selectedScroll.setFillViewport(false);selectedScroll.setClipToPadding(false);selectedScroll.setVerticalScrollBarEnabled(false);
            selectedRows=new LinearLayout(c);selectedRows.setOrientation(VERTICAL);selectedScroll.addView(selectedRows,new ScrollView.LayoutParams(-1,-2));
            addView(selectedScroll,new LinearLayout.LayoutParams(-1,dp(selectedTileHeight())));
            selectedScroll.setOnDragListener((view,event)->drag(event));
            help=label("长按快捷入口拖动排序；点击下方 + 添加、− 移除，完成后保存",11,secondary);
            help.setPadding(dp(4),dp(2),dp(4),dp(4));help.setMaxLines(2);addView(help);
            View divider=new View(c);divider.setBackgroundColor(blend(container,text,.12f));
            LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMargins(0,dp(6),0,dp(6));addView(divider,line);

            LinearLayout tabRow=new LinearLayout(c);tabRow.setPadding(0,0,0,dp(compact?4:6));
            for(int i=0;i<2;i++){
                final int target=i;tabs[i]=button(i==0?"应用":"快捷功能",()->setTab(target));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(compact?32:36),1);if(i==0)p.rightMargin=dp(6);
                tabRow.addView(tabs[i],p);
            }
            addView(tabRow);
            body=new FrameLayout(c);addView(body,new LinearLayout.LayoutParams(-1,0,1));
            list=new ListView(c);list.setDivider(null);list.setDividerHeight(0);list.setClipToPadding(false);list.setPadding(0,dp(2),0,dp(2));
            list.setVerticalScrollBarEnabled(false);list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);lp.rightMargin=dp(26);body.addView(list,lp);
            adapter=new BaseAdapter(){
                public int getCount(){return rows.size();}
                public Object getItem(int position){return rows.get(position);}
                public long getItemId(int position){return position;}
                public int getViewTypeCount(){return 2;}
                public int getItemViewType(int position){return rows.get(position).section!=null?0:1;}
                public boolean areAllItemsEnabled(){return false;}
                public boolean isEnabled(int position){return rows.get(position).section==null;}
                public View getView(int position,View recycled,ViewGroup parent){
                    AppRow entry=rows.get(position);
                    if(entry.section!=null){
                        TextView heading=recycled instanceof TextView?(TextView)recycled:label("",14,accent);
                        heading.setText(entry.section);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setPadding(dp(6),0,0,0);
                        heading.setLayoutParams(new AbsListView.LayoutParams(-1,dp(sectionHeight())));return heading;
                    }
                    LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(c);row.removeAllViews();
                    row.setLayoutParams(new AbsListView.LayoutParams(-1,dp(appTileHeight())));
                    for(int column=0;column<4;column++){
                        View child;
                        if(column<entry.items.size()){
                            ShortcutTarget target=entry.items.get(column);child=tile(target,false);
                            child.setOnClickListener(v->{if(!saving){if(editing)toggle(target);else launch.accept(target);}});
                        }else child=new View(c);
                        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(child,p);
                    }
                    return row;
                }
            };
            list.setAdapter(adapter);
            empty=label("",14,secondary);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),0,dp(16),0);body.addView(empty,new FrameLayout.LayoutParams(-1,-1));
            bubble=label("",22,accent);bubble.setGravity(Gravity.CENTER);bubble.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            bubble.setBackground(round(tonal,20));bubble.setVisibility(GONE);bubble.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.TOP|Gravity.RIGHT);bp.rightMargin=dp(32);body.addView(bubble,bp);
            index=new IndexBar(c);body.addView(index,new FrameLayout.LayoutParams(dp(26),-1,Gravity.RIGHT));
            renderSelected();setTab(0);
        }
        int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
        int selectedTileHeight(){return compact?64:86;}
        int appTileHeight(){return compact?78:92;}
        int sectionHeight(){return compact?22:26;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            if(selectedScroll!=null&&MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED){
                int fixed=getPaddingTop()+getPaddingBottom();
                for(int i=0;i<getChildCount();i++){
                    View child=getChildAt(i);if(child==selectedScroll||child==body||child.getVisibility()==GONE)continue;
                    measureChildWithMargins(child,widthSpec,0,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),0);
                    LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();fixed+=child.getMeasuredHeight()+p.topMargin+p.bottomMargin;
                }
                int room=Math.max(0,MeasureSpec.getSize(heightSpec)-fixed-dp(sectionHeight())-dp(appTileHeight())-dp(4));
                int rowHeight=dp(selectedTileHeight()),count=Math.max(1,(chosen.size()+3)/4);
                int cap=Math.min(room,dp(compact?132:260));
                int height=Math.min(count*rowHeight,cap<rowHeight?cap:cap/rowHeight*rowHeight);
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)selectedScroll.getLayoutParams();p.height=height;
            }
            super.onMeasure(widthSpec,heightSpec);
        }
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
            if(saving)return;
            if(!editing){editing=true;renderSelected();adapter.notifyDataSetChanged();return;}
            saving=true;renderSelected();adapter.notifyDataSetChanged();
            try{save.save(List.copyOf(chosen),success->main.post(()->finishSaving(Boolean.TRUE.equals(success))));}
            catch(RuntimeException failure){finishSaving(false);}
        }
        void finishSaving(boolean success){
            if(disposed||!saving)return;saving=false;
            if(success)editing=false;else Toast.makeText(c,"保存失败，请重试",Toast.LENGTH_SHORT).show();
            renderSelected();adapter.notifyDataSetChanged();
        }
        void toggle(ShortcutTarget target){
            if(saving)return;
            int position=selectedIndex(target.key());
            if(position>=0)chosen.remove(position);
            else if(chosen.size()<Config.MAX_TARGETS)chosen.add(target);
            else{Toast.makeText(c,"快捷入口已达容量上限",Toast.LENGTH_SHORT).show();return;}
            renderSelected();adapter.notifyDataSetChanged();
        }
        void renderSelected(){
            main.removeCallbacks(dragScroll);hover=-1;
            manage.setText(saving?"保存中…":editing?"完成":"管理");manage.setEnabled(!saving);
            selectedTitle.setText("快捷入口 · "+chosen.size());help.setVisibility(editing&&!compact?VISIBLE:GONE);
            int scrollY=selectedScroll.getScrollY();selectedRows.removeAllViews();
            if(chosen.isEmpty()){
                TextView hint=label(editing?"点击下方应用或快捷功能添加":"点管理添加常用应用和快捷功能",12,secondary);hint.setGravity(Gravity.CENTER);
                selectedRows.addView(hint,new LinearLayout.LayoutParams(-1,dp(selectedTileHeight())));
            }
            for(int start=0;start<chosen.size();start+=4){
                LinearLayout row=new LinearLayout(c);selectedRows.addView(row,new LinearLayout.LayoutParams(-1,dp(selectedTileHeight())));
                for(int column=0;column<4;column++){
                    int position=start+column;View child;
                    if(position<chosen.size()){
                        ShortcutTarget target=chosen.get(position);child=tile(target,true);child.setTag(position);
                        child.setOnClickListener(v->{if(!saving){if(editing)toggle(target);else launch.accept(target);}});
                        child.setOnLongClickListener(v->editing&&!saving&&v.startDragAndDrop(ClipData.newPlainText("shortcut-target",target.key()),new View.DragShadowBuilder(v),target.key(),0));
                        child.setContentDescription(target.label+(target.isShortcut()?"，"+target.appLabel:"")+(editing?"，点击移除，长按拖动调整顺序":"，点击打开"));
                    }else child=new View(c);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),0,dp(2),0);row.addView(child,p);
                }
            }
            selectedScroll.post(()->{if(!disposed)selectedScroll.scrollTo(0,scrollY);});requestLayout();
        }
        int dropIndex(float px,float py){
            float x=px+selectedScroll.getScrollX()-selectedRows.getLeft(),y=py+selectedScroll.getScrollY()-selectedRows.getTop();
            float best=Float.MAX_VALUE;int position=-1;
            for(int r=0;r<selectedRows.getChildCount();r++){
                View candidate=selectedRows.getChildAt(r);if(!(candidate instanceof LinearLayout))continue;
                LinearLayout row=(LinearLayout)candidate;
                for(int column=0;column<row.getChildCount();column++){
                    View child=row.getChildAt(column);if(!(child.getTag() instanceof Integer))continue;
                    float dx=x-row.getLeft()-child.getLeft()-child.getWidth()/2f,dy=y-row.getTop()-child.getTop()-child.getHeight()/2f;
                    float distance=dx*dx+dy*dy;
                    if(distance<best){best=distance;position=(Integer)child.getTag();}
                }
            }
            return position;
        }
        void highlightDrop(){
            hover=dropIndex(dragX,dragY);
            for(int r=0;r<selectedRows.getChildCount();r++)if(selectedRows.getChildAt(r) instanceof LinearLayout row)
                for(int column=0;column<row.getChildCount();column++){
                    View child=row.getChildAt(column);child.setAlpha(child.getTag() instanceof Integer&&(Integer)child.getTag()==hover?.55f:1);
                }
        }
        void clearDrag(){
            main.removeCallbacks(dragScroll);hover=-1;
            for(int r=0;r<selectedRows.getChildCount();r++)if(selectedRows.getChildAt(r) instanceof LinearLayout row)
                for(int column=0;column<row.getChildCount();column++)row.getChildAt(column).setAlpha(1);
        }
        void scrollDrag(){
            if(!editing||saving||hover<0||disposed)return;
            int edge=Math.min(dp(24),selectedScroll.getHeight()/3);
            int direction=dragY<edge?-1:dragY>selectedScroll.getHeight()-edge?1:0;
            if(direction==0)return;
            int old=selectedScroll.getScrollY();selectedScroll.scrollBy(0,direction*dp(10));
            if(selectedScroll.getScrollY()!=old){highlightDrop();main.postDelayed(dragScroll,16);}
        }
        boolean drag(DragEvent event){
            if(!editing||saving||!(event.getLocalState() instanceof String)||selectedIndex((String)event.getLocalState())<0)return false;
            int action=event.getAction();
            if(action==DragEvent.ACTION_DRAG_LOCATION||action==DragEvent.ACTION_DROP){
                dragX=event.getX();dragY=event.getY();highlightDrop();main.removeCallbacks(dragScroll);
                if(action==DragEvent.ACTION_DRAG_LOCATION)main.post(dragScroll);
            }
            if(action==DragEvent.ACTION_DROP){
                int from=selectedIndex((String)event.getLocalState());
                if(hover>=0){ShortcutTarget item=chosen.remove(from);chosen.add(hover,item);renderSelected();}
                clearDrag();return true;
            }
            if(action==DragEvent.ACTION_DRAG_ENDED||action==DragEvent.ACTION_DRAG_EXITED)clearDrag();
            return true;
        }
        View tile(ShortcutTarget target,boolean selected){
            boolean dense=compact;
            LinearLayout cell=new LinearLayout(c);cell.setOrientation(VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2),dp(dense?2:4),dp(2),dp(2));cell.setBackground(ripple(selected?tonal:Color.TRANSPARENT));
            FrameLayout artwork=new FrameLayout(c);cell.addView(artwork,new LinearLayout.LayoutParams(-1,dp(dense?38:48)));
            ImageView image=new ImageView(c);Drawable icon=target.icon;
            if(icon!=null&&icon.getConstantState()!=null)icon=icon.getConstantState().newDrawable(c.getResources()).mutate();
            image.setImageDrawable(icon);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int iconSize=dp(dense?(selected?32:36):selected?36:42);artwork.addView(image,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));
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
            if(tab==1&&!shortcutRequested)loadShortcuts();refreshRows();list.setSelection(0);
        }
        int sectionIndex(String section){int position=section==null||section.length()!=1?-1:ALPHABET.indexOf(section);return position>=0?position:26;}
        void refreshRows(){
            visible.clear();visible.addAll(tab==0?appRows:shortcuts);rows.clear();Arrays.fill(sectionPositions,-1);
            for(int letter=0;letter<ALPHABET.length();letter++){
                ArrayList<ShortcutTarget> group=new ArrayList<>();for(ShortcutTarget target:visible)if(sectionIndex(target.section)==letter)group.add(target);
                if(group.isEmpty())continue;
                sectionPositions[letter]=rows.size();rows.add(new AppRow(ALPHABET.substring(letter,letter+1),List.of()));
                for(int start=0;start<group.size();start+=4)rows.add(new AppRow(null,List.copyOf(group.subList(start,Math.min(start+4,group.size())))));
            }
            adapter.notifyDataSetChanged();boolean noRows=rows.isEmpty();
            empty.setText(tab==1?(shortcutLoading?"正在读取应用快捷功能…":shortcutMessage):"暂无可启动的应用");
            empty.setVisibility(noRows?VISIBLE:GONE);list.setVisibility(noRows?GONE:VISIBLE);index.setVisibility(noRows?GONE:VISIBLE);bubble.setVisibility(GONE);
        }
        void applyShortcuts(AppCatalog.Shortcuts result){
            shortcuts.clear();shortcuts.addAll(result.items);shortcutMessage=result.message;
            for(int i=0;i<chosen.size();i++)for(ShortcutTarget real:shortcuts)if(chosen.get(i).key().equals(real.key())){chosen.set(i,real);break;}
        }
        void loadShortcuts(){
            shortcutRequested=true;shortcutLoading=true;
            worker.execute(()->{
                AppCatalog.Shortcuts result=AppCatalog.loadShortcuts(c);
                main.post(()->{if(disposed)return;shortcutLoading=false;if(!result.isCurrent()){loadShortcuts();return;}applyShortcuts(result);renderSelected();refreshRows();});
            });
        }
        @Override protected void onDetachedFromWindow(){disposed=true;main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDetachedFromWindow();}

        final class IndexBar extends View {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            int active=-1;
            IndexBar(Context context){super(context);setContentDescription("按字母滑动定位，中文应用按拼音分组");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);float row=getHeight()/(float)ALPHABET.length();
                paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(Math.min(dp(11),Math.max(dp(6),row*.8f)));
                int step=Math.max(1,(int)Math.ceil(dp(10)/Math.max(1,row)));
                for(int i=0;i<ALPHABET.length();i++)if(i%step==0||i==ALPHABET.length()-1||i==active){
                    paint.setColor(i==active?accent:secondary);Paint.FontMetrics fm=paint.getFontMetrics();
                    canvas.drawText(ALPHABET.substring(i,i+1),getWidth()/2f,row*(i+.5f)-(fm.ascent+fm.descent)/2,paint);
                }
            }
            @Override public boolean onTouchEvent(MotionEvent event){
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE){
                    getParent().requestDisallowInterceptTouchEvent(true);active=Math.max(0,Math.min(26,(int)(event.getY()/Math.max(1,getHeight())*27)));
                    int position=-1;for(int letter=active;letter<sectionPositions.length;letter++)if(sectionPositions[letter]>=0){position=sectionPositions[letter];break;}
                    if(position<0)for(int letter=active-1;letter>=0;letter--)if(sectionPositions[letter]>=0){position=sectionPositions[letter];break;}
                    if(position>=0)list.setSelectionFromTop(position,0);
                    bubble.setText(ALPHABET.substring(active,active+1));FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)bubble.getLayoutParams();p.topMargin=Math.max(0,Math.min(body.getHeight()-dp(40),(int)event.getY()-dp(20)));bubble.setLayoutParams(p);bubble.setVisibility(VISIBLE);invalidate();return true;
                }
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);active=-1;bubble.setVisibility(GONE);invalidate();return true;}
                return true;
            }
        }
    }
}
