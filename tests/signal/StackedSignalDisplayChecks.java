package dev.local.sunwindowdemo.features.shade;

import java.util.List;
import java.util.function.IntBinaryOperator;

/** Android-free checks for the actual reflection adapter, including Compose restart inputs. */
public final class StackedSignalDisplayChecks {
    public static final class Cell {
        public final int level, numberOfLevels;
        public final boolean showExclamationMark, carrierNetworkChange;
        public Cell(int level,int count,boolean warning,boolean changing) {
            this.level=level;numberOfLevels=count;showExclamationMark=warning;carrierNetworkChange=changing;
        }
    }
    public record Pair(Object first,Object second) {}
    public static final class Dual {
        public int primarySubId,secondarySubId;
        public Cell primary,secondary;
    }
    public static Dual parse(List<Pair> pairs) {
        Dual dual=new Dual();dual.primarySubId=(Integer)pairs.get(0).first();dual.secondarySubId=(Integer)pairs.get(1).first();
        dual.primary=(Cell)pairs.get(0).second();dual.secondary=(Cell)pairs.get(1).second();return dual;
    }
    private static void check(boolean value,String why) {if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        StackedSignalDisplay adapter=new StackedSignalDisplay(Dual.class,Cell.class,Pair.class,
            StackedSignalDisplayChecks.class.getMethod("parse",List.class));
        Cell first=new Cell(1,5,true,false),second=new Cell(2,6,false,false);
        Dual original=parse(List.of(new Pair(2,first),new Pair(1,second)));
        IntBinaryOperator adjust=(state,sub)->SignalDisplayPolicy.encode(state,sub==2?4:3);
        Dual changed=(Dual)adapter.adjust(original,adjust);
        check(changed!=original&&changed.primary.level==4&&changed.secondary.level==4,"subscription isolation and inflation");
        check(changed.primarySubId==2&&changed.secondarySubId==1&&changed.primary.showExclamationMark,"identity and flags");
        check(original.primary==first&&first.level==1&&second.level==2,"native objects unchanged");
        check(adapter.adjust(changed,(state,sub)->state)==original,"disable restores original even from restart copy");
        Dual refreshed=(Dual)adapter.adjust(changed,(state,sub)->SignalDisplayPolicy.encode(state,2));
        check(refreshed.primary.level==2&&refreshed.secondary.level==3,"restart recomputes from original");
        java.util.ArrayList<Object> retained=new java.util.ArrayList<>();
        for(int i=0;i<64;i++) {
            Dual sample=parse(List.of(new Pair(2,new Cell(1,5,false,false)),new Pair(1,new Cell(2,5,false,false))));
            Dual copy=(Dual)adapter.adjust(sample,adjust);retained.add(copy);
            check(copy.primary.level==4,"history must not disable adjustment");
            check(adapter.adjust(copy,(state,sub)->state)==sample,"retained restart copy restores original");
        }
        original.secondary=new Cell(2,5,false,true);
        Dual guarded=(Dual)adapter.adjust(original,adjust);
        check(guarded.secondary==original.secondary,"carrier change preserved");
        original.primarySubId=original.secondarySubId;
        check(adapter.adjust(original,adjust)==original,"ambiguous identities stay native");
        check(adapter.adjust(null,adjust)==null,"null preserved");
        System.out.println("PASS: stacked subscription mapping, inflation, flags, native immutability and restart/disable restoration");
    }
}
