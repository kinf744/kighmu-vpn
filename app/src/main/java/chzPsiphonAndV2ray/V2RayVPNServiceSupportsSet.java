package chzPsiphonAndV2ray;

public interface V2RayVPNServiceSupportsSet {
    long onEmitStatus(long l, String s);
    long prepare();
    boolean protect(long fd);
    long setup(String conf);
    long shutdown();
}
