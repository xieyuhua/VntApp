package com.rustvnt.vntapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.vnt.RegisterResult;
import com.vnt.VntApi;
import com.vnt.VntManager;
import com.vnt.VntNetwork;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 组网前台服务。
 *
 * <p>1.x 的架构要点：核心注册成功后通过 {@link VntNetwork.TunHost#generateTunFd}
 * 回调本服务，由这里建立 Android VPN 并把 fd 交回核心。因此 VPN 接口是在
 * <b>核心线程</b>上建立的，必须保证该回调不会因为并发访问而损坏状态。
 */
public final class VntVpnService extends VpnService implements VntNetwork.TunHost {
    static final String ACTION_STATE = "com.rustvnt.vntapp.STATE";
    private static final String ACTION_START = "com.rustvnt.vntapp.START";
    private static final String ACTION_STOP = "com.rustvnt.vntapp.STOP";
    private static final String CHANNEL_ID = "vnt_connection";
    private static final int NOTIFICATION_ID = 1207;
    private static final String ACTIVE_PREFS = "vnt_active_connection";

    private static volatile VntState state = VntState.stopped();
    private static volatile boolean uiVisible;
    private static volatile WeakReference<VntVpnService> instance = new WeakReference<>(null);

    private ScheduledExecutorService worker;
    private ScheduledFuture<?> refreshTask;
    private VntNetwork network;
    private VntApi api;
    private String activeProfileName;
    private String activeConfigJson;
    private String activeIp;
    private int activePrefixLen;
    private List<String> activeInRoutes = Collections.emptyList();
    private List<String> activeOutRoutes = Collections.emptyList();
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean cancellationRequested;

    static VntState state() { return state; }

    static void setUiVisible(boolean visible) {
        uiVisible = visible;
        VntVpnService service = instance.get();
        if (service == null || service.worker == null || service.worker.isShutdown()) return;
        try {
            service.worker.execute(() -> {
                if (visible) {
                    service.refresh();
                    service.startRefreshing();
                } else {
                    service.stopRefreshing();
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    static void start(Context context, VntConfigStore.Profile profile) {
        Intent intent = new Intent(context, VntVpnService.class)
                .setAction(ACTION_START)
                .putExtra("id", profile.id)
                .putExtra("name", profile.name)
                .putExtra("json", profile.json);
        ContextCompat.startForegroundService(context, intent);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, VntVpnService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = new WeakReference<>(this);
        createNotificationChannel();
        worker = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * 保持 CPU 与 Wi-Fi 在后台可用。
     *
     * <p>VNT 的 P2P 依赖每 3 秒一次的 UDP 心跳以及对端可达；息屏后系统若让
     * CPU 或 Wi-Fi 休眠，心跳会中断，P2P 路由随之过期并回落到中继。
     */
    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "VNT::connection");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            VntLog.info("服务", "已获取 CPU 唤醒锁");
        }
        if (wifiLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VNT::wifi");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            try {
                wifiLock.acquire();
                VntLog.info("服务", "已获取 Wi-Fi 高性能锁");
            } catch (Throwable error) {
                // 部分系统在无 Wi-Fi 时抛出异常，不影响蜂窝网络下的运行。
                VntLog.warn("服务", "获取 Wi-Fi 锁失败：" + rootMessage(error));
            }
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) { }
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Throwable ignored) { }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (intent == null) {
            SharedPreferences active = getSharedPreferences(ACTIVE_PREFS, MODE_PRIVATE);
            String json = active.getString("json", null);
            if (json != null && network == null) {
                String id = active.getString("id", "");
                String name = active.getString("name", "VNT");
                startForeground(NOTIFICATION_ID, notification("正在恢复虚拟网络…", false));
                worker.execute(() -> connect(id, name, json));
            }
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            cancellationRequested = true;
            VntLog.info("服务", "收到停止请求");
            publish(new VntState(VntState.Status.STOPPING, state.profileId, state.profileName,
                    state.ip, "正在停止，请稍候…", null, null, null, null, null));
            worker.execute(this::shutdown);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String id = intent.getStringExtra("id");
            String name = intent.getStringExtra("name");
            String json = intent.getStringExtra("json");
            cancellationRequested = false;
            getSharedPreferences(ACTIVE_PREFS, MODE_PRIVATE).edit()
                    .putString("id", id).putString("name", name).putString("json", json).apply();
            VntLog.info("服务", "收到启动请求：配置 " + name);
            startForeground(NOTIFICATION_ID, notification("正在建立虚拟网络…", false));
            worker.execute(() -> connect(id, name, json));
        }
        return START_STICKY;
    }

    private void connect(String id, String name, String json) {
        cleanupNative();
        activeProfileName = name;
        activeConfigJson = json;
        try {
            JSONObject config = new JSONObject(json);
            activeInRoutes = arrayStrings(config.optJSONArray("in_ips"));
            activeOutRoutes = arrayStrings(config.optJSONArray("out_ips"));
        } catch (Exception ignored) {
            activeInRoutes = Collections.emptyList();
            activeOutRoutes = Collections.emptyList();
        }
        publish(new VntState(VntState.Status.STARTING, id, name, null,
                "正在连接服务器并注册网络…", null, null, null, null, null));
        try {
            VntLog.info("核心", "初始化 Rust 核心");
            if (!VntManager.init()) throw new IllegalStateException("Rust 核心初始化失败");

            VntLog.info("核心", "连接服务器并注册网络…");
            VntLog.info("核心", "服务器：" + describeServer(json));
            // 1.x 由核心在注册成功后回调 generateTunFd，VPN 接口在那时建立。
            network = VntManager.createNetwork(json, this);
            if (network == null) throw new IllegalStateException("Rust 核心无法创建网络实例");

            RegisterResult registration = network.register();
            if (cancellationRequested) { shutdown(); return; }
            if (registration == null) throw new IllegalStateException("注册失败");
            activeIp = registration.getIp();
            activePrefixLen = registration.getPrefixLen();
            VntLog.info("核心", "注册成功，虚拟 IP " + registration.toCidr()
                    + "，网关 " + registration.getGateway());

            api = network.getApi();
            if (cancellationRequested) { shutdown(); return; }
            VntState running = readState(id, name, registration.getIp());
            publish(running);
            startForeground(NOTIFICATION_ID, notification("已连接 · " + registration.getIp(), true));
            startRefreshing();
            acquireLocks();
            VntLog.info("服务", "组网已连接");
        } catch (Throwable error) {
            if (cancellationRequested) { shutdown(); return; }
            cleanupNative();
            clearActiveConnection();
            String message = rootMessage(error);
            VntLog.error("服务", "连接失败：" + message);
            publish(new VntState(VntState.Status.ERROR, id, name, null,
                    "连接失败：" + message, null, null, null, null, null));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /**
     * Rust 核心回调：注册成功后索取 TUN fd。
     *
     * <p>在核心线程调用，因此这里建立 VPN 后直接 detachFd 把所有权交给核心。
     */
    @Override public int generateTunFd(String virtualIp, String netmask, String gateway) {
        // 核心可能在断线重连后再次调用。Android 同一时间只能有一个 VPN，
        // 新建接口会自动替换旧的，因此这里直接建立新接口。
        try {
            int prefixLen = netmaskPrefix(netmask);
            VntLog.info("安卓", "核心请求建立 VPN：IP " + virtualIp + "/" + prefixLen
                    + "，网关 " + gateway);
            if (activeConfigJson == null) {
                VntLog.error("安卓", "配置为空，无法建立 VPN");
                return -1;
            }
            ParcelFileDescriptor descriptor = establishVpn(
                    activeProfileName, activeConfigJson, virtualIp, prefixLen);
            if (descriptor == null) {
                VntLog.error("安卓", "Android 未能建立 VPN 接口");
                return -1;
            }
            // fd 所有权交给核心，Java 侧不能再持有同一个对象。
            int fd = descriptor.detachFd();
            activeIp = virtualIp;
            activePrefixLen = prefixLen;
            VntLog.info("安卓", "VPN 接口已就绪，fd=" + fd);
            return fd;
        } catch (Throwable error) {
            VntLog.error("安卓", "建立 VPN 接口失败", error);
            return -1;
        }
    }

    private ParcelFileDescriptor establishVpn(String name, String json, String ip, int prefixLen)
            throws Exception {
        JSONObject config = new JSONObject(json);
        int mtu = Math.max(576, Math.min(9000, config.optInt("mtu", 1400)));
        Builder builder = new Builder()
                .setSession("VNT · " + name)
                .setMtu(mtu)
                .addAddress(ip, prefixLen);
        builder.addDisallowedApplication(getPackageName());

        // 1.x：in_ips 是"目标网段,出口节点IP"，out_ips 是本机允许转发的网段。
        // Android 侧需要把两者都加进 VPN 路由，才能让流量进入隧道。
        for (VpnRouteSet.Route route : VpnRouteSet.build(ip, prefixLen,
                activeInRoutes, activeOutRoutes)) {
            builder.addRoute(route.address(), route.prefix());
        }
        return builder.establish();
    }

    private void refresh() {
        VntState current = state;
        if (!uiVisible || current.status != VntState.Status.RUNNING || api == null) return;
        try {
            VntState next = readState(current.profileId, current.profileName, current.ip);
            if (!uiVisible) return;
            publish(next);
        } catch (Throwable ignored) { }
    }

    private void startRefreshing() {
        if (!uiVisible || api == null || state.status != VntState.Status.RUNNING) return;
        if (refreshTask == null || refreshTask.isCancelled() || refreshTask.isDone()) {
            refreshTask = worker.scheduleWithFixedDelay(this::refresh, 3, 3, TimeUnit.SECONDS);
        }
    }

    private void stopRefreshing() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    private VntState readState(String id, String name, String ip) throws Exception {
        List<VntApi.ClientInfo> clients = api.getClientList();
        List<VntApi.ServerInfo> servers = api.getServerList();
        List<VntApi.RouteInfo> routes = api.getRouteTable();
        VntApi.NatInfo nat = api.getNatInfo();
        VntApi.NetworkInfo networkInfo = api.getNetwork();
        return new VntState(VntState.Status.RUNNING, id, name, ip,
                "", clients, servers, routes, nat, networkInfo);
    }

    private void shutdown() {
        VntLog.info("服务", "组网已停止");
        cleanupNative();
        clearActiveConnection();
        publish(VntState.stopped());
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanupNative() {
        releaseLocks();
        stopRefreshing();
        api = null;
        if (network != null) {
            try { network.stop(); } catch (Throwable ignored) { }
            network = null;
        }
        try { VntManager.destroy(); } catch (Throwable ignored) { }
        activeProfileName = null;
        activeConfigJson = null;
        activeIp = null;
        activePrefixLen = 0;
        activeInRoutes = Collections.emptyList();
        activeOutRoutes = Collections.emptyList();
    }

    private void publish(VntState next) {
        state = next;
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private void clearActiveConnection() {
        getSharedPreferences(ACTIVE_PREFS, MODE_PRIVATE).edit().clear().apply();
    }

    private Notification notification(String text, boolean connected) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pendingStop = PendingIntent.getService(this, 1,
                new Intent(this, VntVpnService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vnt)
                .setContentTitle(connected ? "VNT 虚拟网络已连接" : "VNT 正在启动")
                .setContentText(text)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "停止", pendingStop)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "VNT 连接状态",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持 VNT 虚拟网络在后台运行");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private static List<String> arrayStrings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i).trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    /** 把点分十进制掩码转成前缀长度。 */
    private static int netmaskPrefix(String netmask) {
        if (netmask == null) return 24;
        String[] parts = netmask.split("\\.");
        if (parts.length != 4) return 24;
        long value = 0;
        try {
            for (String part : parts) value = (value << 8) | (Integer.parseInt(part.trim()) & 0xFF);
        } catch (NumberFormatException error) {
            return 24;
        }
        return Long.bitCount(value);
    }

    /**
     * 把服务器地址转成便于排查的文本。
     *
     * <p>1.x 用协议前缀决定传输方式：不带前缀默认 UDP；tcp:// 为 TCP 上的 TLS；
     * ws:// / wss:// 为 WebSocket。
     */
    private static String describeServer(String json) {
        try {
            String raw = new JSONObject(json).optString("server_address", "").trim();
            if (raw.isEmpty()) return "未配置";
            return raw + "（" + protocolOf(raw) + "）";
        } catch (Exception error) {
            return "无法解析";
        }
    }

    private static String protocolOf(String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        if (lower.startsWith("wss://")) return "WebSocket over TLS";
        if (lower.startsWith("ws://")) return "WebSocket";
        if (lower.startsWith("tcp://")) return "TCP（内建 TLS）";
        return "UDP（默认）";
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    @Override public void onRevoke() {
        VntLog.warn("服务", "VPN 权限被系统或其他 VPN 应用收回");
        cancellationRequested = true;
        if (worker != null && !worker.isShutdown()) {
            try {
                worker.execute(this::shutdown);
                return;
            } catch (RejectedExecutionException ignored) { }
        }
        shutdown();
    }

    @Override public void onDestroy() {
        cleanupNative();
        if (instance.get() == this) instance.clear();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}
