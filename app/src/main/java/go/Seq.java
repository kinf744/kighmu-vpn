package go;

public class Seq {
    static {
        System.loadLibrary("gojni");
        init();
    }
    
    public static void touch() {}
    private static native void init();
    
    public static class Ref {
        public final int refnum;
        public Object obj;
        public Ref(int refnum, Object obj) {
            this.refnum = refnum;
            this.obj = obj;
        }
    }
    
    public interface Proxy {}
}
