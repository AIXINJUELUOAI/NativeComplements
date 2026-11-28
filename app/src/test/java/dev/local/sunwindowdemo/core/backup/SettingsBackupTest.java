package dev.local.sunwindowdemo.core.backup;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class SettingsBackupTest {
    private static final String MODULE="dev.nativecomplements.module";
    private Context app;
    private Storage context;
    private SharedPreferences prefs(String name){
        return (name.equals("corner")?app:app.createDeviceProtectedStorageContext())
                .getSharedPreferences(name,Context.MODE_PRIVATE);
    }
    private final class Storage extends ContextWrapper {
        final List<String> commits=new ArrayList<>();
        String failOnce;boolean failNotification;int broadcasts;
        Storage(){super(app);}
        @Override public Context getApplicationContext(){return this;}
        @Override public String getPackageName(){return MODULE;}
        @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(app.getApplicationInfo());info.uid=Process.myUid();return info;}
        @Override public Context createDeviceProtectedStorageContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences real=prefs(name);
            return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                if(!method.getName().equals("edit"))try{return method.invoke(real,args);}
                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                SharedPreferences.Editor edit=real.edit();
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(p,m,a)->{
                    if(m.getName().equals("commit")){
                        commits.add(name);boolean saved=edit.commit();
                        // Real SharedPreferences can publish in memory before reporting a failed disk commit.
                        if(name.equals(failOnce)){failOnce=null;return false;}return saved;
                    }
                    Object result=m.invoke(edit,a);return result==edit?p:result;
                });
            });
        }
        @Override public void sendBroadcast(Intent intent,String permission,Bundle options){
            broadcasts++;if(failNotification)throw new IllegalStateException("host unavailable");
        }
    }
    @Before public void setUp(){
        app=RuntimeEnvironment.getApplication();context=new Storage();
        for(String name:List.of("corner","dark_aod","landscape_shade","navbar_immersive","edge_touch","game_mode","barrage",
                "suggestions","motion_access","background_management","icon_layers","call_blocking","sms_blocking",
                "nativecomplements_corner","alive_applied","icon_assets"))prefs(name).edit().clear().commit();
        prefs("corner").edit().putBoolean("enabled",false).putBoolean("longBackWindow",false).commit();
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] backup(String groups){return bytes("{\"version\":1,\"module\":\""+MODULE+"\",\"groups\":"+groups+"}");}
    private static byte[] value(String group,String key,String json){return backup("{\""+group+"\":{\""+key+"\":"+json+"}}");}
    private int observe(String suffix){
        AtomicInteger count=new AtomicInteger();
        app.getContentResolver().registerContentObserver(Uri.parse("content://"+MODULE+suffix),true,new ContentObserver(null){
            @Override public void onChange(boolean selfChange){count.incrementAndGet();}
        });
        observers.add(count);return observers.size()-1;
    }
    private final List<AtomicInteger> observers=new ArrayList<>();

    @Test public void savedPreferenceTypesRoundTripWithoutRuntimeReportsRecordsOrAssets()throws Exception{
        prefs("corner").edit().putBoolean("hideRecents",true).putInt("heat",48)
                .putStringSet("blocked",Set.of("example.one","example.two"))
                .putString("targets","[\"example.one\"]").putString("targetLabels","{\"example.one\":\"工具\"}")
                .putString("backend_error","private-backend").putString("log","private-log").commit();
        prefs("dark_aod").edit().putBoolean("enabled",true).putFloat("dark_lux",2.25f).commit();
        prefs("edge_touch").edit().putInt("left_width_dp",17).putBoolean("native_owned",true).putString("native_checked","private-fingerprint").commit();
        prefs("call_blocking").edit().putBoolean("enabled",true).putString("sim1_patterns","[\"138*\"]")
                .putString("records","private-records").putString("backend_version","private-version").commit();
        prefs("sms_blocking").edit().putString("sim1_keywords","[\"优惠\"]").putString("records","private-body").putLong("updated_at",123L).commit();
        prefs("nativecomplements_corner").edit().putString("host_cache","private-cache").commit();
        prefs("alive_applied").edit().putString("asset","private-wallpaper").commit();
        prefs("icon_assets").edit().putString("image","private-image").commit();
        byte[] exported=SettingsBackup.export(context);String text=new String(exported,StandardCharsets.UTF_8);
        JSONObject root=new JSONObject(text),groups=root.getJSONObject("groups");
        assertEquals(1,root.getInt("version"));assertEquals(MODULE,root.getString("module"));
        assertFalse(groups.getJSONObject("corner").has("enabled"));
        for(String excluded:List.of("private-backend","private-log","private-fingerprint","private-records","private-version","private-body","private-cache","private-wallpaper","private-image"))assertFalse(excluded,text.contains(excluded));
        assertFalse(groups.has("nativecomplements_corner"));assertFalse(groups.has("alive_applied"));assertFalse(groups.has("icon_assets"));
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(exported);
        assertEquals(groups.length(),snapshot.groupCount());int count=0;
        for(var keys=groups.keys();keys.hasNext();)count+=groups.getJSONObject(keys.next()).length();
        assertEquals(count,snapshot.settingCount());
        for(String group:List.of("corner","dark_aod","edge_touch","call_blocking","sms_blocking"))prefs(group).edit().clear().commit();
        prefs("corner").edit().putBoolean("enabled",false).commit();
        SettingsBackup.restore(context,snapshot);
        assertTrue(prefs("corner").getBoolean("hideRecents",false));assertEquals(48,prefs("corner").getInt("heat",0));
        assertEquals(Set.of("example.one","example.two"),prefs("corner").getStringSet("blocked",Set.of()));
        assertEquals("[\"example.one\"]",prefs("corner").getString("targets",""));
        assertEquals(2.25f,prefs("dark_aod").getFloat("dark_lux",0),0);assertTrue(prefs("dark_aod").getBoolean("enabled",false));
        assertEquals(17,prefs("edge_touch").getInt("left_width_dp",0));
        assertEquals("[\"138*\"]",prefs("call_blocking").getString("sim1_patterns",""));
        assertEquals("[\"优惠\"]",prefs("sms_blocking").getString("sim1_keywords",""));assertFalse(prefs("corner").getBoolean("enabled",true));
    }

    @Test public void structuredRulesOrderedListsLegacyNumbersAndMigrationRoundTrip(){
        String rules="{\"example.one\":{\"mode\":\"STRICT\",\"batch\":\"FREEZE\",\"processes\":{\"example.one:worker\":\"TRIM\"},\"keep_active\":true,\"protect_background\":true}}";
        String locks="{\"example.one\":{\"delay\":30,\"budget\":15,\"period\":60},\"example.two\":false}";
        prefs("background_management").edit().putString("rules",rules).putString("background_wake_locks",locks).commit();
        prefs("motion_access").edit().putInt("default_mode",1).putString("rules","{\"example.one\":2}").commit();
        prefs("game_mode").edit().putInt("corner_radius_percent",10).putString("quick","mute,dnd")
                .putString("quick_apps","example.two,example.one").putFloat("handle_position_portrait",.75f).commit();
        prefs("barrage").edit().putBoolean("migrated",true).putBoolean("enabled",true).putInt("lines",5).commit();
        prefs("landscape_shade").edit().putInt("notification_icon_scale",110).putBoolean("cover_monet",true).commit();
        prefs("navbar_immersive").edit().putBoolean("enabled",true).commit();
        prefs("suggestions").edit().putString("bluetooth_app","example.one").putInt("size",64).commit();
        prefs("icon_layers").edit().putInt("mode",2).putStringSet("originals",Set.of("example.one"))
                .putString("policies","local-image-reference").commit();
        byte[] exported=SettingsBackup.export(context);
        assertFalse(new String(exported,StandardCharsets.UTF_8).contains("local-image-reference"));
        prefs("barrage").edit().remove("migrated").putBoolean("enabled",false).commit();
        SettingsBackup.restore(context,SettingsBackup.parse(exported));
        assertEquals(rules,prefs("background_management").getString("rules",""));
        assertEquals(locks,prefs("background_management").getString("background_wake_locks",""));
        assertEquals("{\"example.one\":2}",prefs("motion_access").getString("rules",""));
        assertEquals(10f,prefs("game_mode").getFloat("corner_radius_percent",0),0);
        assertEquals("mute,dnd",prefs("game_mode").getString("quick",""));
        assertEquals("example.two,example.one",prefs("game_mode").getString("quick_apps",""));
        assertFalse(prefs("game_mode").contains("handle_position_landscape"));
        assertTrue(dev.local.sunwindowdemo.features.barrage.BarrageStore.read(context).getBoolean("enabled"));
        assertEquals(110,prefs("landscape_shade").getInt("notification_icon_scale",0));
        assertTrue(prefs("navbar_immersive").getBoolean("enabled",false));
        assertEquals("example.one",prefs("suggestions").getString("bluetooth_app",""));
        assertEquals("local-image-reference",prefs("icon_layers").getString("policies",""));
        assertEquals(Set.of("example.one"),prefs("icon_layers").getStringSet("originals",Set.of()));
    }

    @Test public void presentGroupsReplaceSupportedKeysButPreserveExcludedKeysAndAbsentGroups(){
        prefs("corner").edit().putBoolean("hideRecents",true).putInt("heat",50).putString("backend_error","keep").commit();
        prefs("dark_aod").edit().putFloat("dark_lux",4).commit();
        prefs("call_blocking").edit().putString("records","keep-records").putString("sim1_patterns","[\"138*\"]").commit();
        prefs("edge_touch").edit().putBoolean("native_owned",true).putInt("left_width_dp",17).commit();
        SettingsBackup.restore(context,SettingsBackup.parse(backup("{\"corner\":{\"heat\":60},\"call_blocking\":{},\"edge_touch\":{}}")));
        assertEquals(60,prefs("corner").getInt("heat",0));assertFalse(prefs("corner").contains("hideRecents"));
        assertTrue(prefs("corner").contains("longBackWindow"));assertFalse(prefs("corner").getBoolean("longBackWindow",true));assertFalse(prefs("corner").getBoolean("enabled",true));
        assertEquals("keep",prefs("corner").getString("backend_error",""));assertEquals(4f,prefs("dark_aod").getFloat("dark_lux",0),0);
        assertEquals("keep-records",prefs("call_blocking").getString("records",""));assertFalse(prefs("call_blocking").contains("sim1_patterns"));
        assertTrue(prefs("edge_touch").getBoolean("native_owned",false));assertFalse(prefs("edge_touch").contains("left_width_dp"));
        assertEquals(List.of("corner","call_blocking","edge_touch"),context.commits);
    }

    @Test public void importCannotCreateLegacyEvidenceOrReenableTheGesture(){
        prefs("corner").edit().clear().commit();
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(backup("{\"corner\":{\"heat\":60}}"));
        assertThrows(IllegalStateException.class,()->SettingsBackup.restore(context,snapshot));
        assertThrows(IllegalStateException.class,()->SettingsBackup.export(context));
        assertTrue(prefs("corner").getAll().isEmpty());assertTrue(context.commits.isEmpty());
        prefs("corner").edit().putBoolean("enabled",false).commit();
        SettingsBackup.restore(context,snapshot);
        new dev.local.sunwindowdemo.Config(context);
        assertFalse(prefs("corner").getBoolean("enabled",true));
        assertFalse(prefs("corner").getBoolean("longBackWindow",true));
    }

    @Test public void foreignLockedOrDeviceProtectedContextsCannotReadOrWrite(){
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(backup("{\"dark_aod\":{\"dark_lux\":2}}"));
        Context foreign=new ContextWrapper(context){@Override public String getPackageName(){return "foreign.module";}};
        assertThrows(SecurityException.class,()->SettingsBackup.restore(foreign,snapshot));
        assertThrows(SecurityException.class,()->SettingsBackup.export(app.createDeviceProtectedStorageContext()));
        android.os.UserManager users=app.getSystemService(android.os.UserManager.class);
        org.robolectric.Shadows.shadowOf(users).setUserUnlocked(false);
        try{assertThrows(IllegalStateException.class,()->SettingsBackup.restore(context,snapshot));}
        finally{org.robolectric.Shadows.shadowOf(users).setUserUnlocked(true);}
        assertTrue(context.commits.isEmpty());assertEquals(0,context.broadcasts);
    }

    @Test public void snapshotOwnsItsValidatedInputAndItsCountsStayStable(){
        byte[] source=backup("{\"corner\":{\"blocked\":[\"example.one\"]},\"dark_aod\":{\"dark_lux\":1.5}}");
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(source);Arrays.fill(source,(byte)'x');
        assertEquals(2,snapshot.groupCount());assertEquals(2,snapshot.settingCount());
        SettingsBackup.restore(context,snapshot);prefs("corner").edit().putStringSet("blocked",Set.of("example.changed")).commit();
        SettingsBackup.restore(context,snapshot);
        assertEquals(Set.of("example.one"),prefs("corner").getStringSet("blocked",Set.of()));
        assertEquals(1.5f,prefs("dark_aod").getFloat("dark_lux",0),0);assertEquals(2,snapshot.settingCount());
    }

    @Test public void invalidSchemaTypesRangesAndStructuredSettingsAreRejectedBeforeAnyWrite(){
        List<byte[]> bad=new ArrayList<>();
        bad.add(bytes("{\"version\":2,\"module\":\""+MODULE+"\",\"groups\":{}}"));
        bad.add(bytes("{\"version\":1,\"module\":\"foreign.module\",\"groups\":{}}"));
        bad.add(bytes("{\"version\":1,\"module\":\""+MODULE+"\",\"groups\":{},\"extra\":true}"));
        bad.add(backup("{\"unknown\":{}}"));bad.add(value("corner","unknown","true"));bad.add(value("corner","enabled","true"));
        bad.add(value("corner","heat","\"48\""));bad.add(value("corner","heat","48.0"));bad.add(value("corner","heat","73"));
        bad.add(value("corner","hideRecents","1"));bad.add(value("dark_aod","dark_lux","6"));
        bad.add(value("edge_touch","left_width_dp","0"));bad.add(value("corner","blocked","[\"not a package\"]"));
        bad.add(value("corner","blocked","[\"example.one\",\"example.one\"]"));
        bad.add(value("corner","targets","\"[\\\"not a package\\\"]\""));bad.add(value("corner","targets","\"not json\""));
        bad.add(value("corner","targetLabels","\"{\\\"example.one\\\":\\\"orphan\\\"}\""));
        bad.add(value("call_blocking","sim1_patterns","\"[\\\" 138*\\\"]\""));
        bad.add(value("sms_blocking","sim1_keywords","\"[{\\\"body\\\":\\\"x\\\"}]\""));
        bad.add(value("motion_access","rules","\"{\\\"example.one\\\":3}\""));
        for(byte[] input:bad)assertThrows(IllegalArgumentException.class,()->SettingsBackup.restore(context,SettingsBackup.parse(input)));
        assertTrue(context.commits.isEmpty());assertEquals(0,context.broadcasts);assertFalse(prefs("corner").getBoolean("enabled",true));
    }

    @Test public void ambiguousMalformedDeepOversizedTrailingAndNonUtf8DocumentsAreRejected(){
        List<byte[]> bad=new ArrayList<>();
        bad.add(bytes("{\"version\":1,\"version\":1,\"module\":\""+MODULE+"\",\"groups\":{}}"));
        bad.add(backup("{\"corner\":{\"heat\":48,\"h\\u0065at\":50}}"));
        bad.add(value("corner","targets","\"[\\\"example.one\\\",\\\"example.one\\\"]\""));
        bad.add(value("motion_access","rules","\"{\\\"example.one\\\":1,\\\"example.one\\\":2}\""));
        bad.add(backup("{\"corner\":{\"blocked\":"+"[".repeat(20)+"true"+"]".repeat(20)+"}}"));
        bad.add(bytes(new String(backup("{}"),StandardCharsets.UTF_8)+" {}"));bad.add(bytes("\ufeff"+new String(backup("{}"),StandardCharsets.UTF_8)));
        bad.add(value("corner","targets","\"\\ud800\""));bad.add(bytes("{\"version\":1,}"));bad.add(new byte[]{(byte)0xc3,0x28});
        bad.add(new byte[SettingsBackup.MAX_BYTES+1]);bad.add(new byte[0]);
        for(byte[] input:bad)assertThrows(IllegalArgumentException.class,()->SettingsBackup.restore(context,SettingsBackup.parse(input)));
        assertEquals(1024*1024,SettingsBackup.MAX_BYTES);assertTrue(context.commits.isEmpty());assertEquals(0,context.broadcasts);
    }

    @Test public void nonJsonLiteralsSeparatorsAndEscapesAreRejectedBeforeAnyWrite(){
        List<byte[]> bad=new ArrayList<>();
        for(String literal:List.of("TRUE","True","FALSE","False","NULL","Null","null"))
            bad.add(value("corner","hideRecents",literal));
        bad.add(backup("{\"corner\":{\"heat\":48;\"hideRecents\":true}}"));
        bad.add(bytes(new String(backup("{}"),StandardCharsets.UTF_8).replace("1,\"module\"","1;\"module\"")));
        bad.add(bytes(new String(backup("{}"),StandardCharsets.UTF_8).replace("module\",\"groups", "modu\\le\",\"groups")));
        bad.add(value("motion_access","rules",JSONObject.quote("{\"example.one\":1;\"example.two\":2}")));
        bad.add(value("background_management","rules",JSONObject.quote("{\"example.one\":{\"keep_active\":TRUE}}")));
        for(String escape:List.of("\\q","\\'","\\v","\\x41","\\u12G4","\\u+123","\\u123")) {
            bad.add(value("suggestions","bluetooth_app","\"example."+escape+"one\""));
            // Nested preference JSON must receive exactly the same lexical validation.
            String labels="{\"example.one\":\""+escape+"\"}";
            bad.add(backup("{\"corner\":{\"targets\":\"[\\\"example.one\\\"]\",\"targetLabels\":"+JSONObject.quote(labels)+"}}"));
        }
        bad.add(bytes(new String(backup("{}"),StandardCharsets.UTF_8)+"\u000b"));
        bad.add(value("suggestions","bluetooth_app","\"example.\none\""));
        bad.add(value("suggestions","bluetooth_app","\"example.one\\"));
        for(byte[] input:bad)assertThrows(new String(input,StandardCharsets.UTF_8),IllegalArgumentException.class,
                ()->SettingsBackup.restore(context,SettingsBackup.parse(input)));
        assertTrue(context.commits.isEmpty());assertEquals(0,context.broadcasts);
    }

    @Test public void standardEscapesUnicodeQuotesAndExponentNumbersStillRoundTrip()throws Exception{
        String labels="{\"example.one\":\"slash\\\\ quote\\\" solidus\\/ unicode\\u4E2D pair\\uD83D\\uDE00 controls\\b\\f\\n\\r\\t\"}";
        String groups="{\"corner\":{\"targets\":\"[\\\"example.one\\\"]\",\"targetLabels\":"+JSONObject.quote(labels)
                +"},\"dark_aod\":{\"dark_lux\":2.5E+0}}";
        String document=new String(backup(groups),StandardCharsets.UTF_8).replace("dev.nativecomplements.module","dev.nativecomplements.mod\\u0075le");
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(bytes(document));
        SettingsBackup.restore(context,snapshot);
        assertEquals(labels,prefs("corner").getString("targetLabels",""));
        assertEquals("slash\\ quote\" solidus/ unicode中 pair😀 controls\b\f\n\r\t",new JSONObject(labels).getString("example.one"));
        assertEquals(2.5f,prefs("dark_aod").getFloat("dark_lux",0),0);
        SettingsBackup.Snapshot roundTrip=SettingsBackup.parse(SettingsBackup.export(context));
        assertEquals(13,roundTrip.groupCount());assertTrue(roundTrip.settingCount()>=snapshot.settingCount());
    }

    @Test public void readBoundsItsInputAndDoesNotCloseTheCallerOwnedStream()throws Exception{
        class Stream extends ByteArrayInputStream {
            int consumed;boolean closed,zero=true;
            Stream(byte[] data){super(data);}
            @Override public synchronized int read(byte[] b,int off,int len){if(zero){zero=false;return 0;}int n=super.read(b,off,len);if(n>0)consumed+=n;return n;}
            @Override public synchronized int read(){int n=super.read();if(n>=0)consumed++;return n;}
            @Override public void close(){closed=true;}
        }
        Stream valid=new Stream(backup("{\"dark_aod\":{\"dark_lux\":2}}"));
        assertEquals(1,SettingsBackup.read(valid).settingCount());assertFalse(valid.closed);
        Stream huge=new Stream(new byte[SettingsBackup.MAX_BYTES+100]);
        assertThrows(IOException.class,()->SettingsBackup.read(huge));assertEquals(SettingsBackup.MAX_BYTES+1,huge.consumed);assertFalse(huge.closed);
        assertThrows(IllegalArgumentException.class,()->SettingsBackup.read(new ByteArrayInputStream(bytes("{} garbage"))));assertTrue(context.commits.isEmpty());
    }

    @Test public void failedSecondCommitRollsBackEvenPublishedMemoryWithoutHostNotifications(){
        prefs("corner").edit().putInt("heat",40).putString("backend_error","keep").commit();
        prefs("dark_aod").edit().putFloat("dark_lux",1).putString("backend_error","keep-dark").commit();
        int observer=observe(".darkaod");context.failOnce="dark_aod";
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(backup("{\"corner\":{\"heat\":60},\"dark_aod\":{\"dark_lux\":4}}"));
        assertThrows(IllegalStateException.class,()->SettingsBackup.restore(context,snapshot));
        assertEquals(List.of("corner","dark_aod","dark_aod","corner"),context.commits);
        assertEquals(40,prefs("corner").getInt("heat",0));assertFalse(prefs("corner").getBoolean("enabled",true));
        assertTrue(prefs("corner").contains("longBackWindow"));assertEquals("keep",prefs("corner").getString("backend_error",""));
        assertEquals(1f,prefs("dark_aod").getFloat("dark_lux",0),0);assertEquals("keep-dark",prefs("dark_aod").getString("backend_error",""));
        assertEquals(0,context.broadcasts);assertEquals(0,observers.get(observer).get());
    }

    @Test public void notificationFailureIsDistinctAndDoesNotUndoSuccessfulPersistence(){
        context.failNotification=true;
        SettingsBackup.Snapshot snapshot=SettingsBackup.parse(backup("{\"corner\":{\"heat\":60}}"));
        assertThrows(SettingsBackup.NotificationException.class,()->SettingsBackup.restore(context,snapshot));
        assertEquals(60,prefs("corner").getInt("heat",0));assertEquals(List.of("corner"),context.commits);assertEquals(1,context.broadcasts);
    }
}
