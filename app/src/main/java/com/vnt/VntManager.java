package com.vnt;

public final class VntManager {
    static { System.loadLibrary("vnt_jni"); }

    private VntManager() {}

    public static boolean init() { return nativeInit(); }

    public static void destroy() { nativeDestroy(); }

    /** 不建立 Android VPN 的场景（例如仅测试注册）。 */
    public static VntNetwork createNetwork(String configJson) {
        return createNetwork(configJson, null);
    }

    /**
     * 创建网络实例。
     *
     * <p>1.x 核心在注册成功后回调 {@code host.generateTunFd(...)} 索取 TUN fd，
     * 因此 host 必须能在核心线程被调用。
     */
    public static VntNetwork createNetwork(String configJson, VntNetwork.TunHost host) {
        long handle = nativeCreateNetwork(configJson, host);
        return handle < 0 ? null : new VntNetwork(handle);
    }

    private static native boolean nativeInit();
    private static native void nativeDestroy();
    private static native long nativeCreateNetwork(String configJson, VntNetwork.TunHost host);
}
