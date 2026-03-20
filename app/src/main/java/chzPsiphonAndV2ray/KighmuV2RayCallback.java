package chzPsiphonAndV2ray;

import android.net.VpnService;

/**
 * Notre implémentation de V2RayVPNServiceSupportsSet
 * Nom différent de proxyV2RayVPNServiceSupportsSet pour éviter conflits avec JAR
 */
public class KighmuV2RayCallback implements V2RayVPNServiceSupportsSet {
    private final VpnService vpnService;
    
    public KighmuV2RayCallback(VpnService vpnService) {
        this.vpnService = vpnService;
    }
    
    @Override
    public boolean protect(long fd) {
        try { 
            boolean result = vpnService.protect((int) fd);
            android.util.Log.i("KighmuVPN", "protect() fd=" + fd + " result=" + result);
            return result;
        }
        catch (Exception e) { 
            android.util.Log.e("KighmuVPN", "protect() error: " + e.getMessage());
            return false; 
        }
    }
    
    @Override public long prepare() { return 0L; }
    @Override public long setup(String conf) { return 0L; }
    @Override public long shutdown() { return 0L; }
    @Override public long onEmitStatus(long l, String s) { return 0L; }
}
