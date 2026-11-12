import dev.local.sunwindowdemo.features.callblocking.CallBlockingRules;

public final class CallBlockingEnhancementsCheck {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static CallBlockingRules.Decision base(int slot, boolean follow, boolean all, String... patterns) {
        return CallBlockingRules.decide(true,slot,all,patterns,follow,false,new String[0],"13800138000");
    }
    public static void main(String[] args) {
        var clear=base(0,false,false);
        check(CallBlockingRules.stranger(clear,true,true,false,false,false,"13800138000").blocked);
        check(!CallBlockingRules.stranger(clear,true,false,false,false,false,"13800138000").blocked);
        check(!CallBlockingRules.stranger(clear,true,true,true,false,false,"13800138000").blocked);
        check(!CallBlockingRules.stranger(clear,false,true,false,false,false,"13800138000").blocked);
        check(!CallBlockingRules.stranger(clear,true,true,false,true,true,"13800138000").blocked);
        check(CallBlockingRules.stranger(clear,true,true,false,false,true,"13800138000").blocked);
        for (String hidden : new String[]{"", "anonymous", "private", "-1", "sip:user@example.com"}) {
            check(!CallBlockingRules.stranger(clear,true,true,false,false,false,hidden).blocked);
        }
        check(CallBlockingRules.stranger(base(0,false,true),true,true,false,true,true,"13800138000").reason.equals("all_calls"));
        check(CallBlockingRules.stranger(base(0,false,false,"138*"),true,true,false,true,true,"13800138000").reason.equals("pattern"));
        check(base(1,true,false,"138*").blocked);
        check(!base(1,false,false,"138*").blocked);
        check(CallBlockingRules.normalizeNumber("+86 13800138000").equals(CallBlockingRules.normalizeNumber("13800138000")));
        check(!CallBlockingRules.normalizeNumber("+86 212345678").equals(CallBlockingRules.normalizeNumber("212345678")));
        check(!CallBlockingRules.normalizeNumber("+1 212345678").equals(CallBlockingRules.normalizeNumber("+86 212345678")));
        check(CallBlockingRules.normalizeNumber("0033123456789").equals("+33123456789"));
        var repeats=new CallBlockingRules.Repeats();
        Object first=new Object();
        check(!repeats.observe(first,1,"+86 138-0013-8000",100));
        check(!repeats.observe(first,1,"13800138000",200));
        check(!repeats.observe(new Object(),2,"13800138000",300));
        Object second=new Object();
        check(repeats.observe(second,1,"008613800138000",180100));
        check(repeats.observe(second,1,"13800138000",400000)); // callback is stable, not a new call
        check(!repeats.observe(new Object(),1,"13800138000",360101));
        check(!repeats.observe(new Object(),1,"",400000));
        check(!repeats.observe(new Object(),1,"",400001));
        check(!repeats.observe(new Object(),-1,"13800138000",400002));
        check(!repeats.observe(new Object(),1,"13900139000",400003));
        check(!repeats.observe(new Object(),1,"+1212345678",400004));
        check(!repeats.observe(new Object(),1,"+86212345678",400005));
        check(!repeats.observe(new Object(),1,"212345678",400006));
        System.out.println("Call blocking enhancements: PASS");
    }
}
