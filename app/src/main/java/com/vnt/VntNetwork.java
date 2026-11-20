package com.vnt;

/**
 * VNT 1.x 网络实例。
 *
 * <p>1.x 的 TUN 由核心在注册成功后回调 {@link TunHost#generateTunFd} 索取，
 * 因此这里没有 startTun 之类的入口。
 */
public final class VntNetwork {
    /**
     * Rust 核心回调 Java 建立 Android VPN。
     *
     * <p>核心线程调用，实现方必须同步返回：-1 表示建立失败，核心会触发 error 回调。
     */
    public interface TunHost {
        /** @return TUN 文件描述符，失败返回 -1 */
        int generateTunFd(String virtualIp, String netmask, String gateway);
    }

    private final long nativeHandle;
    private boolean closed;

    VntNetwork(long nativeHandle) { this.nativeHandle = nativeHandle; }

    public synchronized RegisterResult register() throws VntException {
        checkOpen();
        return RegisterResult.fromJson(nativeRegister(nativeHandle));
    }

    public synchronized VntApi getApi() throws VntException {
        checkOpen();
        long apiHandle = nativeGetApi(nativeHandle);
        if (apiHandle < 0) throw new VntException("无法获取 VNT 状态接口");
        return new VntApi(apiHandle);
    }

    public synchronized void stop() {
        if (!closed) {
            nativeStop(nativeHandle);
            closed = true;
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("VNT 实例已经关闭");
    }

    private static native String nativeRegister(long handle);
    private static native long nativeGetApi(long handle);
    private static native boolean nativeStop(long handle);
}
