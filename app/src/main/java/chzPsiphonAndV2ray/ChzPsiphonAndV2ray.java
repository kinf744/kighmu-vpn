package chzPsiphonAndV2ray;

import android.content.Context;
import android.net.VpnService;
import android.provider.Settings;

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

    public static void initEnv(Context context) {
        try {
            String assetPath = context.getFilesDir().getAbsolutePath();
            String deviceId = Settings.Secure.getString(
                context.getContentResolver(), 
                Settings.Secure.ANDROID_ID
            );
            initV2Env(assetPath, deviceId);
        } catch (Throwable e) {
            // ignore
        }
    }

    public static native void initV2Env(String assetPath, String deviceId);
    public static native V2RayPoint newV2RayPoint(V2RayVPNServiceSupportsSet supports, boolean adVpn);
    public static native void touch();

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
