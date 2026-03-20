package go;

import android.content.Context;

public class Seq {
    static {
        System.loadLibrary("gojni");
        init();
    }
    
    public static void touch() {}
    public static native void init();
    public static native void setContext(Context ctx);
    public static native void destroyRef(int refnum);
    public static native void incGoRef(int refnum);
    
    public interface Proxy {}
    
    public static class Ref {
        public final int refnum;
        public Object obj;
        public Ref(int refnum, Object obj) {
            this.refnum = refnum;
            this.obj = obj;
        }
    }
}
