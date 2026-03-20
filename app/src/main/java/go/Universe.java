package go;

public abstract class Universe {
    static {
        Seq.touch();
        _init();
    }
    
    private Universe() {}
    private static native void _init();
    public static void touch() {}
}
