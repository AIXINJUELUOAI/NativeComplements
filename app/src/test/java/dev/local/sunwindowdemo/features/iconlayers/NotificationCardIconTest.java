package dev.local.sunwindowdemo.features.iconlayers;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.widget.ImageView;
import android.widget.RemoteViews;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NotificationCardIconTest {
    private static final String PKG="example.card";
    private Context context;
    private NotificationCardIcons cards;
    private CardIcon view;
    private Header header;
    private Row row;
    private Drawable original;
    private IconAssets.Snapshot previous;

    @Before public void setup()throws Exception{
        previous=IconAssets.snapshot();
        context=RuntimeEnvironment.getApplication();
        cards=new NotificationCardIcons(Header.class,Row.class,Entry.class,CardIcon.class,Set.of(Header.class,Template.class));
        view=new CardIcon(context);view.setPadding(4,6,4,6);view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bitmap=Bitmap.createBitmap(12,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);
        original=new BitmapDrawable(context.getResources(),bitmap);view.setImageDrawable(original);
        view.measure(exact(72),exact(96));view.layout(0,0,72,96);
        row=new Row();row.mEntryAdapter=new Entry(notice(PKG,new Notification(),android.os.Process.myUserHandle(),android.os.Process.myUid()));
        header=new Header();header.mRow=row;header.mIcon=view;cards.updated(header,row);
        snapshot(Color.GREEN);
    }
    @After public void cleanup()throws Exception{
        Field field=IconAssets.class.getDeclaredField("snapshot");field.setAccessible(true);field.set(null,previous);
    }
    private static int exact(int size){return android.view.View.MeasureSpec.makeMeasureSpec(size,android.view.View.MeasureSpec.EXACTLY);}
    private void snapshot(int color)throws Exception{
        IconAssets.Entry art=new IconAssets.Entry(IconImages.solid(color,32),IconImages.solid(Color.TRANSPARENT,32),null,null,true,false,false);
        setSnapshot(Map.of(PKG,art));
    }
    private static void setSnapshot(Map<String,IconAssets.Entry> entries)throws Exception{
        Field field=IconAssets.class.getDeclaredField("snapshot");field.setAccessible(true);
        field.set(null,new IconAssets.Snapshot(entries,Map.of(),"",0,0,Set.of()));
    }
    private static StatusBarNotification notice(String pkg,Notification n,UserHandle user,int uid){
        return new StatusBarNotification(pkg,pkg,1,null,uid,0,0,n,user,0);
    }
    private int pixel()throws Throwable{
        Bitmap bitmap=Bitmap.createBitmap(72,96,Bitmap.Config.ARGB_8888);
        cards.draw(view,()->{view.draw(new Canvas(bitmap));return null;});
        assertSame(original,view.getDrawable());return bitmap.getPixel(36,48);
    }

    @Test public void coloredCardUsesNativeBoundsMatrixAndRestoresTintAndDrawable()throws Throwable{
        view.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        Rect nativeBounds=new Rect(original.getBounds());Matrix matrix=new Matrix(view.getImageMatrix());
        Drawable replacement=IconAssets.colored(context,PKG);replacement.setBounds(1,2,17,19);replacement.setAlpha(97);
        Rect replacementBounds=new Rect(replacement.getBounds());
        Bitmap bitmap=Bitmap.createBitmap(72,96,Bitmap.Config.ARGB_8888);
        cards.drawWith(view,replacement,()->{
            assertSame(replacement,view.getDrawable());assertEquals(nativeBounds,replacement.getBounds());
            assertEquals(original.getAlpha(),replacement.getAlpha());
            view.draw(new Canvas(bitmap));return null;
        });
        assertEquals(Color.GREEN,bitmap.getPixel(36,48));assertSame(original,view.getDrawable());
        assertEquals(nativeBounds,original.getBounds());assertEquals(replacementBounds,replacement.getBounds());assertEquals(97,replacement.getAlpha());
        assertEquals(Color.WHITE,view.getImageTintList().getDefaultColor());assertEquals(matrix,view.getImageMatrix());
        assertEquals(ImageView.ScaleType.FIT_CENTER,view.getScaleType());assertEquals(4,view.getPaddingLeft());assertEquals(6,view.getPaddingTop());
        Bitmap nativeImage=Bitmap.createBitmap(72,96,Bitmap.Config.ARGB_8888);view.draw(new Canvas(nativeImage));
        assertEquals(Color.WHITE,nativeImage.getPixel(36,48));
    }
    @Test public void failingNativeDrawRunsOnceAndAlwaysRestores()throws Throwable{
        Drawable replacement=IconAssets.colored(context,PKG);replacement.setBounds(2,3,4,5);replacement.setAlpha(91);
        int[] calls={0};RuntimeException failure=new RuntimeException("native draw");
        try{cards.drawWith(view,replacement,()->{calls[0]++;throw failure;});fail("exception lost");}
        catch(RuntimeException actual){assertSame(failure,actual);}
        assertEquals(1,calls[0]);assertSame(original,view.getDrawable());assertEquals(new Rect(2,3,4,5),replacement.getBounds());assertEquals(91,replacement.getAlpha());
    }
    @Test public void importUpdateDisableAndOriginalChoiceNeedNoNativeRebind()throws Throwable{
        assertNull("status-bar mask is independently unchanged",IconAssets.notification(context,PKG));
        assertEquals(Color.GREEN,pixel());snapshot(Color.BLUE);cards.refresh();assertEquals(Color.BLUE,pixel());
        Bundle disabled=new Bundle();disabled.putBoolean("enabled",false);disabled.putBoolean("notifications",true);
        assertTrue(IconAssets.load(context,disabled,()->false));cards.refresh();assertEquals(Color.RED,pixel());
        snapshot(Color.GREEN);assertEquals(Color.GREEN,pixel());
        // Selecting native colour leaves no adapted colour entry (even with a notification mask).
        setSnapshot(Map.of(PKG,new IconAssets.Entry(null,null,null,IconImages.solid(Color.WHITE,32),false,false,true)));
        assertNotNull(IconAssets.notification(context,PKG));assertEquals(Color.RED,pixel());
    }
    @Test public void nativeAsyncImageWriteAndLatestEntryWinWithoutStaleRestoration()throws Throwable{
        assertEquals(Color.GREEN,pixel());
        original=new BitmapDrawable(context.getResources(),IconImages.solid(Color.YELLOW,16));
        view.setImageDrawable(original); // Native async RemoteViews completion after our previous draw.
        assertEquals(Color.GREEN,pixel());assertSame(original,view.getDrawable());
        row.mEntryAdapter=new Entry(notice("example.other",new Notification(),android.os.Process.myUserHandle(),android.os.Process.myUid()));
        assertEquals(Color.YELLOW,pixel());
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),android.os.Process.myUserHandle(),android.os.Process.myUid()));
        assertEquals(Color.GREEN,pixel());
        header.mIcon=new CardIcon(context);assertEquals(Color.YELLOW,pixel());
    }
    @Test public void messagingConversationCustomCrossUserAndUnknownWrapperStayNative()throws Throwable{
        Notification n=new Notification();n.extras.putString(Notification.EXTRA_TEMPLATE,"android.app.Notification$MessagingStyle");
        row.mEntryAdapter=new Entry(notice(PKG,n,android.os.Process.myUserHandle(),android.os.Process.myUid()));assertEquals(Color.RED,pixel());
        n=new Notification.Builder(context,"test").setShortcutId("conversation").build();
        row.mEntryAdapter=new Entry(notice(PKG,n,android.os.Process.myUserHandle(),android.os.Process.myUid()));assertEquals(Color.RED,pixel());
        n=new Notification();n.contentView=new RemoteViews(context.getPackageName(),android.R.layout.simple_list_item_1);
        row.mEntryAdapter=new Entry(notice(PKG,n,android.os.Process.myUserHandle(),android.os.Process.myUid()));assertEquals(Color.RED,pixel());
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),UserHandle.getUserHandleForUid(1_010_000),1_010_000));assertEquals(Color.RED,pixel());
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),android.os.Process.myUserHandle(),1_010_000));assertEquals(Color.RED,pixel());
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),android.os.Process.myUserHandle(),android.os.Process.myUid()));
        Unknown unknown=new Unknown();unknown.mIcon=view;unknown.mRow=row;cards.updated(unknown,row);assertEquals(Color.RED,pixel());
        Template template=new Template();template.mIcon=view;template.mRow=row;cards.updated(template,row);assertEquals(Color.GREEN,pixel());
        template.mRow=new Row();assertEquals(Color.RED,pixel());
    }
    @Test public void unregisteredContentImagesAndOffMainDrawAreNeverChanged()throws Throwable{
        CardIcon unregistered=new CardIcon(context);unregistered.setImageDrawable(original);
        cards.draw(unregistered,()->{assertSame(original,unregistered.getDrawable());return null;});
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{try{cards.draw(view,()->{assertSame(original,view.getDrawable());return null;});}catch(Throwable t){failure.set(t);}});
        worker.start();worker.join();assertNull(failure.get());
    }
    @Test public void redesignedRowIconUsesColoredArtAndKeepsNativeCacheForRestoration()throws Throwable{
        cards=new NotificationCardIcons(Header.class,Row.class,Entry.class,CardIcon.class,Set.of(Header.class,Template.class),RowIcon.class);
        RowIcon redesigned=new RowIcon(context);
        redesigned.setAppIcon(original);view=redesigned;
        view.measure(exact(72),exact(96));view.layout(0,0,72,96);
        header.mIcon=view;cards.updated(header,row);
        assertNull(IconAssets.notification(context,PKG));
        assertEquals(Color.GREEN,pixel());assertSame(original,redesigned.mAppIcon);
        snapshot(Color.BLUE);cards.refresh();assertEquals(Color.BLUE,pixel());
        original=new BitmapDrawable(context.getResources(),IconImages.solid(Color.YELLOW,16));
        redesigned.setAppIcon(original);assertEquals(Color.BLUE,pixel());assertSame(original,redesigned.mAppIcon);
        setSnapshot(Map.of());cards.refresh();assertEquals(Color.YELLOW,pixel());
        snapshot(Color.GREEN);
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),UserHandle.getUserHandleForUid(1_010_000),1_010_000));
        assertEquals(Color.YELLOW,pixel());
        row.mEntryAdapter=new Entry(notice(PKG,new Notification(),android.os.Process.myUserHandle(),android.os.Process.myUid()));
        UnknownRowIcon unknown=new UnknownRowIcon(context);unknown.setImageDrawable(original);
        unknown.measure(exact(72),exact(96));unknown.layout(0,0,72,96);
        view=unknown;header.mIcon=view;cards.updated(header,row);assertEquals(Color.YELLOW,pixel());
    }
    @Test public void redesignedDrawOverrideIsRejectedBeforeAnyHook()throws Exception{
        try{new NotificationCardIcons(Header.class,Row.class,Entry.class,CardIcon.class,Set.of(Header.class),OverridingRowIcon.class);fail("row draw ABI accepted");}
        catch(NoSuchMethodException expected){}
    }
    @Test public void incompatibleCachingDrawOverrideFailsPreflight()throws Exception{
        try{new NotificationCardIcons(Header.class,Row.class,Entry.class,OverridingIcon.class,Set.of(Header.class));fail("unknown draw ABI accepted");}
        catch(NoSuchMethodException expected){}
    }

    // The helper is exercised against the same exact field/method shapes, without SystemUI on the test classpath.
    public static class Base {public Row mRow;}
    public static class Header extends Base {public CardIcon mIcon;public void onContentUpdated(Row row){}}
    public static class Template extends Header {}
    public static class Unknown extends Header {}
    public static class Row {public Entry mEntryAdapter;}
    public static class Entry {private final StatusBarNotification sbn;Entry(StatusBarNotification sbn){this.sbn=sbn;}public StatusBarNotification getSbn(){return sbn;}}
    public static class CardIcon extends ImageView {public CardIcon(Context context){super(context);}}
    public static class RowIcon extends CardIcon {private Drawable mAppIcon;public RowIcon(Context context){super(context);}void setAppIcon(Drawable icon){mAppIcon=icon;setImageDrawable(icon);}}
    public static class UnknownRowIcon extends RowIcon {public UnknownRowIcon(Context context){super(context);}}
    public static class OverridingRowIcon extends CardIcon {public OverridingRowIcon(Context context){super(context);}@Override protected void onDraw(Canvas canvas){super.onDraw(canvas);}}
    public static class OverridingIcon extends ImageView {public OverridingIcon(Context context){super(context);}@Override protected void onDraw(Canvas canvas){super.onDraw(canvas);}}
}
