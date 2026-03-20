package chzPsiphonAndV2ray;

import android.net.VpnService;

public class ChzPsiphonAndV2ray {
    
    static boolean loaded = false;
    
    public static boolean tryLoad() {
        if (loaded) return true;
        try {
            System.loadLibrary("gojni");
            loaded = true;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static native V2RayPoint newV2RayPoint(V2RayVPNServiceSupportsSet supports, boolean adVpn);
    public static native void touch();

    // Inner class avec nom exact pour JNI
    public static class proxyV2RayVPNServiceSupportsSet implements V2RayVPNServiceSupportsSet {
        private final VpnService vpnService;
        
        public proxyV2RayVPNServiceSupportsSet(VpnService vpnService) {
            this.vpnService = vpnService;
        }
        
        @Override
        public boolean protect(long fd) {
            try { return vpnService.protect((int) fd); }
            catch (Exception e) { return false; }
        }
        
        @Override public long prepare() { return 0L; }
        @Override public long setup(String conf) { return 0L; }
        @Override public long shutdown() { return 0L; }
        @Override public long onEmitStatus(long l, String s) { return 0L; }
    }
}
