package android.view;
public final class SurfaceControl {
    public boolean valid=true;
    public float alpha=1;
    public static boolean failApply;
    public boolean isValid(){return valid;}
    public static final class Transaction {
        java.util.Map<SurfaceControl,Float> writes=new java.util.LinkedHashMap<>();
        public Transaction setAlpha(SurfaceControl s,float a){writes.put(s,a);return this;}
        public Transaction merge(Transaction other){writes.putAll(other.writes);other.writes.clear();return this;}
        public void apply(){if(failApply)throw new IllegalStateException("injected apply failure");writes.forEach((s,a)->s.alpha=a);writes.clear();}
        public void close(){}
    }
}
