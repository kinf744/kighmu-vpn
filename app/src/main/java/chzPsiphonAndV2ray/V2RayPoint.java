package chzPsiphonAndV2ray;

public class V2RayPoint {
    private final int refnum;
    
    public V2RayPoint(int refnum) { this.refnum = refnum; }
    
    public native String getDomainName();
    public native void setDomainName(String name);
    public native String getConfigureFileContent();
    public native void setConfigureFileContent(String content);
    public native boolean getAsyncResolve();
    public native void setAsyncResolve(boolean resolve);
    public native boolean getIsRunning();
    public native void setIsRunning(boolean running);
    public native V2RayVPNServiceSupportsSet getSupportSet();
    public native void setSupportSet(V2RayVPNServiceSupportsSet supportSet);
    public native long runLoop(boolean testOnly);
    public native long stopLoop();
    public native long measureDelay();
    public native String queryStats(String tag, String direct);
}
