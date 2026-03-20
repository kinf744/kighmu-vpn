package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }
    private Universe() {}
    public static native void _init();
    public static void touch() {}

    static final class proxyerror extends java.lang.Exception {
        private final int refnum;
        proxyerror(int refnum) { this.refnum = refnum; }
        public native String error();
    }
}
