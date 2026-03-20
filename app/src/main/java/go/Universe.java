package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }
    private Universe() {}
    public static native void _init();
    public static void touch() {}
    
    // proxyerror inner class
    static final class proxyerror implements java.lang.Error {
        private final int refnum;
        proxyerror(int refnum) { this.refnum = refnum; }
        public native String error();
    }
}
