package dev.local.sunwindowdemo.features.motion;

import java.util.*;

/** UID is the platform sensor isolation boundary. Never reset a foreground window on a page change. */
public final class MotionPolicy {
    public static final int ALLOW=0, FIRST_SCREEN=1, ALWAYS=2;
    public static final long WINDOW=7000;
    public record App(int uid,String packageName,boolean persistent) {}
    public record Targets(Map<Integer,Integer> rules,Map<Integer,String> packages) {}

    /** Package identities are values; native PM enumeration and later UID revalidation stay in the adapter. */
    public static Targets targets(List<App> installed,int fallback,Map<String,Integer> overrides){
        Set<Integer> excluded=new HashSet<>();
        for(App app:installed)if(app.uid<10000||app.persistent
                ||Set.of("dev.nativecomplements.module","com.android.systemui","com.google.android.apps.nexuslauncher")
                    .contains(app.packageName))excluded.add(app.uid);
        Map<Integer,Integer> rules=new HashMap<>();Map<Integer,String> packages=new HashMap<>();
        for(App app:installed){
            if(excluded.contains(app.uid))continue;
            int mode=resolve(fallback,overrides.get(app.packageName));
            if(mode==ALLOW)continue;
            // Shared UIDs cannot be isolated by package; strongest rule wins, preserving iteration's command target.
            rules.merge(app.uid,mode,Math::max);packages.put(app.uid,app.packageName);
        }
        return new Targets(Map.copyOf(rules),Map.copyOf(packages));
    }
    private Map<Integer,Integer> rules=Map.of();
    private final Map<Integer,Long> until=new HashMap<>();
    private int foreground=-1,pid=-1;
    public static int resolve(int fallback,Integer override){
        int mode=override==null?fallback:override;
        return mode>=ALLOW&&mode<=ALWAYS?mode:ALLOW;
    }
    public void configure(Map<Integer,Integer> next,long now){
        for(var e:next.entrySet())if(e.getValue()==FIRST_SCREEN&&foreground==e.getKey()
                &&rules.getOrDefault(e.getKey(),ALLOW)!=FIRST_SCREEN)until.put(e.getKey(),now+WINDOW);
        rules=Map.copyOf(next);until.keySet().removeIf(uid->rules.getOrDefault(uid,ALLOW)!=FIRST_SCREEN);
    }
    public void foreground(int uid,int process,long now){
        if(uid==foreground&&process==pid)return;
        foreground=uid;pid=process;
        if(rules.getOrDefault(uid,ALLOW)==FIRST_SCREEN)until.put(uid,now+WINDOW);
    }
    public boolean blocked(int uid,long now){
        int mode=rules.getOrDefault(uid,ALLOW);
        return uid>=10000&&(mode==ALWAYS||mode==FIRST_SCREEN&&now<until.getOrDefault(uid,0L));
    }
    public long nextExpiry(long now){
        return until.values().stream().filter(t->t>now).min(Long::compare).orElse(0L);
    }
}
