package dev.local.sunwindowdemo;

import java.util.Arrays;

public final class HistoryChecks {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        WindowHistory h=new WindowHistory();
        h.windowed(new WindowHistory.Entry(457,0,"com.example.clock","com.example.clock/.Main",null));
        // Closing and switching preserve history even after the internal mode is restored.
        check(!h.fullscreenReady(457,false,false,true,true,false),"background restore stays hidden");
        h.windowed(new WindowHistory.Entry(458,0,"com.example.calc","com.example.calc/.Main",null));
        check(Arrays.equals(h.ids(),new int[]{457,458}),"switch keeps both task identities");
        check(!h.fullscreenReady(458,true,true,true,true,false),"active floating task stays hidden");
        check(!h.fullscreenReady(457,false,true,false,true,false),"wait for fullscreen draw");
        check(!h.fullscreenReady(457,false,true,true,false,false),"stale freeform client config stays hidden");
        check(!h.fullscreenReady(457,false,true,true,true,true),"wait for native transition");
        check(h.fullscreenReady(457,false,true,true,true,false),"normal fullscreen launch releases this task");
        check(Arrays.equals(h.ids(),new int[]{458}),"fullscreen leaves other floated task hidden");
        WindowHistory loaded=new WindowHistory();for(var e:h.entries())loaded.windowed(e);
        check(Arrays.equals(loaded.ids(),h.ids()),"history survives serialization/reload entries");
        int[] ids=h.ids();ids[0]=999;check(h.get(458)!=null&&h.get(999)==null,"published ids do not mutate history");
        loaded.removed(458);check(loaded.ids().length==0,"removed task is pruned");
        check(h.get(458)!=null,"copies independent");
        System.out.println("PASS history: multi-task close/switch persistence, fullscreen draw/transition gating, reload and task removal");
    }
}
