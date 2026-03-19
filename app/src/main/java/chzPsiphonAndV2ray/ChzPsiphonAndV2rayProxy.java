package chzPsiphonAndV2ray;

import android.net.VpnService;

/**
 * Cette classe DOIT s'appeler exactement comme ça pour correspondre
 * au symbole JNI dans libgojni.so:
 * Java_chzPsiphonAndV2ray_ChzPsiphonAndV2ray_00024proxyV2RayVPNServiceSupportsSet_protect
 * 
 * En Java: ChzPsiphonAndV2ray$proxyV2RayVPNServiceSupportsSet
 */
public class ChzPsiphonAndV2rayProxy {
    
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
