package com.rustvnt.vntapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.vnt.VntApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ThemedActivity {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/vnt-dev/VntApp/releases/latest";

    private DrawerLayout drawer;
    private ScrollView scroll;
    private LinearLayout pageHost;
    private TextView title;
    private TextView status;
    private View sidebarDot;
    private TextView sidebarStatus;
    private final List<TextView> navViews = new ArrayList<>();
    private final List<Page> navPages = new ArrayList<>();
    private VntConfigStore store;
    private VntConfigStore.Profile pendingProfile;
    private Page page = Page.DASHBOARD;
    private VntState.Status lastRenderedStatus;
    private long lastRenderedLogVersion = -1;
    private boolean stateReceiverRegistered;
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> vpnPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && pendingProfile != null) {
                    VntVpnService.start(this, pendingProfile);
                    pendingProfile = null;
                    promptBatteryOptimization();
                } else {
                    pendingProfile = null;
                    toast("需要 VPN 权限才能创建 VNT 虚拟网卡");
                }
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

    private final ActivityResultLauncher<ScanOptions> qrScanner = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() != null) handleNetworkQr(result.getContents());
            });

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render(true);
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new VntConfigStore(this);
        buildShell();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (!stateReceiverRegistered) {
            ContextCompat.registerReceiver(this, stateReceiver, new IntentFilter(VntVpnService.ACTION_STATE),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            stateReceiverRegistered = true;
        }
        VntVpnService.setUiVisible(true);
        render();
    }

    @Override protected void onStop() {
        VntVpnService.setUiVisible(false);
        if (stateReceiverRegistered) {
            unregisterReceiver(stateReceiver);
            stateReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        updateExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        // 状态栏与导航栏配色由 Theme.VntApp / values-night 统一控制，
        // 这里不再单独设置，避免两处颜色来源不同步。
        drawer = new DrawerLayout(this);

        LinearLayout main = column();
        main.setBackgroundColor(bgPage());
        drawer.addView(main, new DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(12), 0);
        header.setBackgroundColor(bgHeader());
        // 刘海与状态栏：padding 下移内容，同时把固定 64dp 的高度补上 inset，
        // 否则内容会被压缩。
        applySystemBarPadding(header, true, false, false, false, dp(64));
        main.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        Button menu = iconButton("☰");
        menu.setContentDescription("打开导航");
        menu.setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        header.addView(menu, size(44, 44));

        LinearLayout heading = column();
        heading.setPadding(dp(5), 0, 0, 0);
        title = text("网络总览", 18, true, textStrong());
        heading.addView(title);
        heading.addView(text("VNT 虚拟局域网管理", 11, false, textMuted()));
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        status = chip("未运行", textMuted(), bgChip());
        header.addView(status);
        Button theme = iconButton(dark ? "☀" : "☾");
        theme.setOnClickListener(v -> {
            dark = !dark;
            getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply();
            // setDefaultNightMode 触发配置变更后 Activity 会自行重建，
            // 不需要再调用 recreate()，否则会重建两次并闪屏。
            AppCompatDelegate.setDefaultNightMode(
                    dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        header.addView(theme, size(40, 40));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        pageHost = column();
        pageHost.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(pageHost);
        // 底部手势区：让最后一张卡片不被导航条盖住。
        applySystemBarPadding(scroll, false, false, false, true);
        main.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout navigation = column();
        navigation.setBackgroundColor(bgHeader());
        // 抽屉是独立浮层，横屏时刘海可能位于左侧，底部还要避开手势条。
        applySystemBarPadding(navigation, false, true, false, true);
        DrawerLayout.LayoutParams navParams = new DrawerLayout.LayoutParams(dp(288), ViewGroup.LayoutParams.MATCH_PARENT);
        navParams.gravity = GravityCompat.START;
        drawer.addView(navigation, navParams);
        buildNavigation(navigation);

        setContentView(drawer);
    }

    private void buildNavigation(LinearLayout navigation) {
        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(20), 0, dp(20), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.vnt_icon);
        logo.setContentDescription("VNT");
        brand.addView(logo, size(32, 32));
        LinearLayout brandText = column();
        brandText.setPadding(dp(12), 0, 0, 0);
        brandText.addView(text("VNT", 15, true, textStrong()));
        TextView brandSub = text("CONTROL CENTER", 9, true, textMuted());
        brandSub.setLetterSpacing(0.18f);
        brandText.addView(brandSub);
        LinearLayout.LayoutParams brandParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        // 纵向的刘海/状态栏 inset 已由抽屉容器承担，这里只需要跟随系统栏高度增长。
        navigation.addView(brand, brandParams);
        View brandDivider = new View(this);
        brandDivider.setBackgroundColor(border());
        navigation.addView(brandDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout statusCard = row();
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(12), 0, dp(12), 0);
        statusCard.setBackground(round(bgChip(), 12, border(), 1));
        sidebarDot = new View(this);
        LinearLayout.LayoutParams dotParams = size(10, 10);
        dotParams.rightMargin = dp(10);
        statusCard.addView(sidebarDot, dotParams);
        LinearLayout statusTexts = column();
        TextView statusLabel = text("虚拟网络", 9, true, textMuted());
        statusLabel.setLetterSpacing(0.1f);
        statusTexts.addView(statusLabel);
        sidebarStatus = text("未运行", 12, false, textBody());
        statusTexts.addView(sidebarStatus, top(3));
        statusCard.addView(statusTexts);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        statusParams.leftMargin = dp(12);
        statusParams.rightMargin = dp(12);
        statusParams.topMargin = dp(16);
        navigation.addView(statusCard, statusParams);

        navigation.addView(nav("▦", "网络总览", Page.DASHBOARD), navItemParams(dp(16)));
        navigation.addView(nav("♙", "在线设备", Page.PEERS), navItemParams(dp(4)));
        navigation.addView(nav("⇄", "路由表", Page.ROUTES), navItemParams(dp(4)));
        navigation.addView(nav("⚙", "组网配置", Page.CONFIG), navItemParams(dp(4)));
        navigation.addView(nav("?", "设置教程", Page.GUIDE), navItemParams(dp(4)));
        navigation.addView(nav("≡", "运行日志", Page.LOGS), navItemParams(dp(4)));
        navigation.addView(nav("ⓘ", "关于", Page.ABOUT), navItemParams(dp(4)));

        Space space = new Space(this);
        navigation.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
        View footerDivider = new View(this);
        footerDivider.setBackgroundColor(border());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.leftMargin = dp(12);
        dividerParams.rightMargin = dp(12);
        navigation.addView(footerDivider, dividerParams);
        LinearLayout footer = row();
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(18), dp(14), dp(18), dp(14));
        View coreDot = new View(this);
        coreDot.setBackground(round(GREEN, 3, 0, 0));
        LinearLayout.LayoutParams coreDotParams = size(6, 6);
        coreDotParams.rightMargin = dp(8);
        footer.addView(coreDot, coreDotParams);
        footer.addView(text("Rust 核心已就绪", 11, false, textMuted()), weighted());
        footer.addView(text(coreVersion(), 10, false, textMuted()));
        navigation.addView(footer);
    }

    private LinearLayout.LayoutParams navItemParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        params.topMargin = topMargin;
        return params;
    }

    private View nav(String icon, String label, Page target) {
        TextView item = text(icon + "    " + label, 14, false, textBody());
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), 0, dp(8), 0);
        item.setOnClickListener(v -> {
            page = target;
            drawer.closeDrawer(GravityCompat.START);
            render();
        });
        navViews.add(item);
        navPages.add(target);
        return item;
    }

    private void updateSidebar(VntState current) {
        if (sidebarStatus != null) {
            sidebarStatus.setText(switch (current.status) {
                case RUNNING -> "运行中";
                case STARTING -> "正在启动";
                case STOPPING -> "正在停止";
                case ERROR -> "连接失败";
                default -> "未运行";
            });
        }
        if (sidebarDot != null) {
            int color = current.status == VntState.Status.RUNNING ? GREEN :
                    current.status == VntState.Status.STARTING || current.status == VntState.Status.STOPPING ? AMBER :
                            neutralDot();
            sidebarDot.setBackground(round(color, 5, 0, 0));
        }
        for (int i = 0; i < navViews.size(); i++) {
            boolean active = navPages.get(i) == page;
            TextView item = navViews.get(i);
            item.setTextColor(active ? INDIGO : textBody());
            item.setTypeface(Typeface.create("sans", active ? Typeface.BOLD : Typeface.NORMAL));
            item.setBackground(round(active ? colorWithAlpha(INDIGO, 25) : Color.TRANSPARENT, 10, 0, 0));
        }
    }

    private void render() { render(true); }

    /**
     * @param force 页面自身触发的重绘（切换导航、保存配置、点击按钮）。
     *              状态广播传 false：只有状态真正变化时才重建，
     *              同状态下的周期性数据刷新不销毁滚动位置和用户输入。
     */
    private void render(boolean force) {
        VntState current = VntVpnService.state();
        title.setText(page.title);
        String statusText = switch (current.status) {
            case RUNNING -> "● " + (current.ip == null || current.ip.isEmpty() ? "获取中" : current.ip);
            case STARTING -> "● 正在启动";
            case STOPPING -> "● 正在停止";
            case ERROR -> "● 连接失败";
            default -> "● 未运行";
        };
        status.setText(statusText);
        status.setTextColor(current.status == VntState.Status.RUNNING ? GREEN :
                current.status == VntState.Status.ERROR ? RED :
                        current.status == VntState.Status.STARTING || current.status == VntState.Status.STOPPING ? AMBER : textMuted());
        updateSidebar(current);

        // 关于页、设置教程没有状态依赖；配置页的按钮只在状态切换时变化；
        // 运行日志按日志版本号判断；三个数据页跟随每次广播刷新实时数值。
        boolean statusChanged = current.status != lastRenderedStatus;
        if (!force) {
            if (page == Page.ABOUT || page == Page.GUIDE) return;
            if (page == Page.CONFIG && !statusChanged) return;
            if (page == Page.LOGS && VntLog.version() == lastRenderedLogVersion) return;
        }

        boolean keepScroll = statusChanged;
        lastRenderedStatus = current.status;
        lastRenderedLogVersion = VntLog.version();
        int scrollY = scroll == null ? 0 : scroll.getScrollY();
        pageHost.removeAllViews();
        switch (page) {
            case DASHBOARD -> dashboard(current);
            case PEERS -> peers(current);
            case ROUTES -> routes(current);
            case CONFIG -> configs(current);
            case GUIDE -> guide();
            case LOGS -> logs();
            case ABOUT -> about();
        }
        // 只有状态切换时才恢复滚动位置；周期性数据刷新不打断用户浏览。
        if (keepScroll && scroll != null) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void dashboard(VntState current) {
        List<VntConfigStore.Profile> profiles = store.all();
        int online = 0;
        for (VntApi.ClientInfo client : current.clients) if (client.online()) online++;
        LinearLayout stats = row();
        stats.addView(statCard("在线设备", String.valueOf(online)), weighted());
        int connected = 0;
        for (VntApi.ServerInfo server : current.servers) if (server.connected()) connected++;
        String serverText = current.status == VntState.Status.RUNNING ? connected + " / " + current.servers.size() : "-";
        stats.addView(statCard("服务器连接", serverText), weightedWithStart());
        pageHost.addView(stats);

        LinearLayout startCard = card();
        startCard.addView(text("启动组网", 16, true, textStrong()));
        if (profiles.isEmpty()) {
            startCard.addView(text("还没有任何配置，先创建一个组网配置吧。", 13, false, textMuted()), top(10));
            Button create = primary("去新建配置");
            create.setOnClickListener(v -> { page = Page.CONFIG; render(); });
            startCard.addView(create, top(14));
        } else if (current.status == VntState.Status.STOPPED || current.status == VntState.Status.ERROR) {
            startCard.addView(text("选择一个配置并建立虚拟网络。", 13, false, textMuted()), top(10));
            startCard.addView(text("选择配置", 12, true, textBody()), top(14));
            LinearLayout startRow = row();
            startRow.setGravity(Gravity.CENTER_VERTICAL);
            Spinner profileSelect = new Spinner(this);
            String[] names = new String[profiles.size()];
            for (int i = 0; i < profiles.size(); i++) names[i] = profiles.get(i).name;
            profileSelect.setAdapter(spinnerAdapter(names));
            startRow.addView(spinnerBox(profileSelect), weighted());
            Button start = primary("启动");
            start.setOnClickListener(v -> {
                int index = profileSelect.getSelectedItemPosition();
                if (index < 0 || index >= profiles.size()) { toast("请先选择一个配置"); return; }
                requestVpn(profiles.get(index));
            });
            LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            startParams.leftMargin = dp(12);
            startRow.addView(start, startParams);
            startCard.addView(startRow, top(6));
        } else if (current.status == VntState.Status.RUNNING || current.status == VntState.Status.STARTING) {
            startCard.addView(text(current.message, 13, false, textMuted()), top(10));
            Button stop = danger(current.status == VntState.Status.STARTING ? "取消连接" : "停止组网");
            stop.setOnClickListener(v -> confirmStop());
            startCard.addView(stop, top(14));
        }
        pageHost.addView(startCard, top(16));

        sectionTitle("实例概览");
        if (current.status == VntState.Status.STOPPED) empty("暂无运行中的组网，请在上方选择配置启动");
        else instanceCard(current);
        if (current.status == VntState.Status.RUNNING) {
            networkDetail(current);
            serverList(current);
        }
    }

    private void networkDetail(VntState current) {
        VntConfigStore.Profile profile = current.profileId == null ? null : store.find(current.profileId);
        JSONObject config = profile == null ? new JSONObject() : profile.config();
        LinearLayout card = card();
        card.addView(text("网络详情", 16, true, textStrong()));
        VntApi.NetworkInfo network = current.network;
        detailRow(card, "虚拟 IP / 掩码", network == null ? current.ip == null ? "-" : current.ip
                : network.ip() + " / " + network.prefixLen());
        detailRow(card, "网关", network == null ? "-" : network.gateway());
        detailRow(card, "Token", config.optString("token", "-"));
        detailRow(card, "MTU", String.valueOf(config.optInt("mtu", 1380)));
        detailRow(card, "NAT 类型", current.nat == null ? "-" : current.nat.type());
        detailRow(card, "Public IPv6", current.nat == null || current.nat.ipv6() == null ? "-" : current.nat.ipv6());
        detailRow(card, "设备名称", config.optString("device_name", "-"));
        detailRow(card, "设备 ID", config.optString("device_id", "-"));
        LinearLayout features1 = row();
        features1.addView(featureChip("加密", !config.optString("password").isEmpty()), weighted());
        features1.addView(featureChip("压缩", config.optBoolean("compress")), weightedWithStart());
        card.addView(features1, top(12));
        LinearLayout features2 = row();
        features2.addView(featureChip("FEC 纠错", config.optBoolean("fec")), weighted());
        features2.addView(featureChip("QUIC 传输", config.optBoolean("rtx")), weightedWithStart());
        card.addView(features2, top(8));
        card.addView(text("Public IPv4s", 12, false, textMuted()), top(14));
        LinearLayout ips = row();
        List<String> publicIps = current.nat == null ? Collections.emptyList() : current.nat.publicIps();
        if (publicIps.isEmpty()) {
            ips.addView(text("无", 12, false, textMuted()));
        } else {
            for (String publicIp : publicIps) {
                TextView ipChip = text(publicIp, 11, false, GREEN);
                ipChip.setTypeface(Typeface.MONOSPACE);
                ipChip.setTextIsSelectable(true);
                ipChip.setPadding(dp(8), dp(4), dp(8), dp(4));
                ipChip.setBackground(round(colorWithAlpha(GREEN, 22), 6, border(), 1));
                ips.addView(ipChip, end(6));
            }
        }
        card.addView(ips, top(8));
        pageHost.addView(card, top(16));
    }

    private void serverList(VntState current) {
        LinearLayout card = card();
        card.addView(text("服务器连接列表", 16, true, textStrong()));
        if (current.servers.isEmpty()) card.addView(text("暂无数据", 12, false, textMuted()), top(12));
        for (VntApi.ServerInfo server : current.servers) {
            LinearLayout rowLayout = row();
            rowLayout.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout info = column();
            TextView address = text(server.address(), 13, true, textStrong());
            address.setTypeface(Typeface.MONOSPACE);
            address.setTextIsSelectable(true);
            info.addView(address);
            String sub = "延迟 " + (server.rtt() == null ? "-" : server.rtt() + " ms")
                    + "    版本 " + (server.version() == null ? "-" : server.version());
            info.addView(text(sub, 11, false, textMuted()), top(4));
            rowLayout.addView(info, weighted());
            rowLayout.addView(chip(server.connected() ? "已连接" : "未连接",
                    server.connected() ? GREEN : RED,
                    colorWithAlpha(server.connected() ? GREEN : RED, 25)));
            card.addView(rowLayout, top(12));
        }
        pageHost.addView(card, top(16));
    }

    private void instanceCard(VntState current) {
        LinearLayout card = card();
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(current.profileName == null ? "VNT" : current.profileName, 16, true, textStrong()), weighted());
        int color = current.status == VntState.Status.RUNNING ? GREEN : current.status == VntState.Status.ERROR ? RED : AMBER;
        head.addView(chip(statusLabel(current.status), color, colorWithAlpha(color, 28)));
        card.addView(head);
        card.addView(labelValue("虚拟 IP", current.ip == null ? "-" : current.ip, INDIGO), top(12));
        int online = 0, direct = 0;
        for (VntApi.ClientInfo client : current.clients) {
            if (client.online()) online++;
            if (client.online() && client.direct()) direct++;
        }
        card.addView(text("在线  " + online + "       直连  " + direct + "       离线  " + (current.clients.size() - online), 12, false, textMuted()), top(12));
        card.addView(text(current.message, 12, false, textMuted()), top(10));
        pageHost.addView(card);
    }

    private void peers(VntState current) {
        if (current.status != VntState.Status.RUNNING) { empty("请先启动一个组网实例"); return; }
        int online = 0;
        for (VntApi.ClientInfo client : current.clients) if (client.online()) online++;
        pageHost.addView(text("在线 " + online + "    总计 " + current.clients.size(), 12, false, textMuted()));
        if (current.clients.isEmpty()) { empty("当前网络中暂无其他设备"); return; }
        for (VntApi.ClientInfo client : current.clients) {
            LinearLayout card = card();
            LinearLayout head = row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout identity = column();
            LinearLayout nameRow = row();
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            nameRow.addView(text(client.name().isEmpty() ? "未命名设备" : client.name(),
                    15, true, textStrong()));
            TextView type = chip(client.clientType(), textMuted(), bgChip());
            LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            typeParams.leftMargin = dp(7);
            nameRow.addView(type, typeParams);
            identity.addView(nameRow);
            TextView address = text(client.ip(), 12, true, INDIGO);
            address.setTypeface(Typeface.MONOSPACE);
            address.setTextIsSelectable(true);
            identity.addView(address, top(4));
            head.addView(identity, weighted());
            head.addView(chip(client.online() ? "在线" : "离线", client.online() ? GREEN : textMuted(),
                    client.online() ? colorWithAlpha(GREEN, 25) : bgChip()));
            card.addView(head);
            String mode = connectionMode(client);
            // 版本号：1.x 的 DeviceInfo 协议不携带对端版本，无从获取，直接不显示。
            int encryptionColor = client.keyEqual() == 1 ? GREEN
                    : client.keyEqual() == 2 ? AMBER
                    : client.keyEqual() == 0 ? textMuted() : RED;
            card.addView(labelValue("加密状态", encryptionLabel(client.keyEqual()), encryptionColor), top(10));
            int modeColor = !client.online() ? textMuted()
                    : client.direct() ? GREEN : client.routeMetric() != null ? INDIGO : AMBER;
            card.addView(labelValue("连接模式", mode, modeColor), top(8));
            card.addView(labelValue("延迟", client.rtt() == null ? "-" : client.rtt() + " ms", textBody()), top(8));
            // 丢包率：1.x 不统计，无数据时不显示该行。
            if (client.loss() != null) {
                int lossColor = client.loss().rate() > 10 ? RED
                        : client.loss().rate() > 5 ? AMBER : GREEN;
                card.addView(labelValue("丢包率",
                        String.format(Locale.US, "%.1f%%", client.loss().rate()), lossColor), top(8));
            }
            VntApi.Traffic traffic = client.traffic();
            if (traffic != null) {
                // 1.x 只累计总字节数，没有实时速率，因此不显示 "/s"。
                card.addView(labelValue("上传", bytes(traffic.txBytes()), GREEN), top(8));
                card.addView(labelValue("下载", bytes(traffic.rxBytes()), INDIGO), top(8));
            }
            pageHost.addView(card, top(12));
        }
    }

    private void routes(VntState current) {
        if (current.status != VntState.Status.RUNNING) { empty("暂无运行中的组网实例"); return; }
        sectionTitle("路由表");
        if (current.routes.isEmpty()) { empty("暂未发现可用路由"); return; }
        for (VntApi.RouteInfo group : current.routes) {
            LinearLayout card = card();
            card.addView(text(group.ip(), 15, true, INDIGO));
            if (group.routes().isEmpty()) card.addView(text("暂无路由", 12, false, textMuted()), top(8));
            for (VntApi.RouteDetail route : group.routes()) {
                card.addView(text(route.protocol() + "  " + route.key(), 12, true, textBody()), top(10));
                card.addView(text("Metric " + route.metric() + "    " + route.rtt() + " ms", 11, false, textMuted()), top(3));
            }
            pageHost.addView(card, top(12));
        }
    }

    private void configs(VntState current) {
        LinearLayout action = row();
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.addView(text("配置文件", 16, true, textStrong()), weighted());
        ImageButton scan = imageButton(R.drawable.ic_qr_code_scanner, "扫码加入网络");
        scan.setOnClickListener(v -> scanNetworkQr());
        action.addView(scan, end(8));
        Button add = primary("＋ 新建配置");
        add.setOnClickListener(v -> editProfile(null));
        action.addView(add);
        pageHost.addView(action);
        List<VntConfigStore.Profile> profiles = store.all();
        if (profiles.isEmpty()) { empty("暂无配置，请新建一个组网配置"); return; }
        boolean sessionActive = isSessionActive(current.status);
        for (VntConfigStore.Profile profile : profiles) {
            JSONObject config = profile.config();
            LinearLayout card = card();
            LinearLayout head = row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(profile.name, 16, true, textStrong()), weighted());
            boolean activeProfile = sessionActive && profile.id.equals(current.profileId);
            if (activeProfile) {
                int stateColor = current.status == VntState.Status.RUNNING ? GREEN : AMBER;
                head.addView(chip(statusLabel(current.status), stateColor,
                        colorWithAlpha(stateColor, 25)), end(8));
            }
            ImageButton qr = plainImageButton(R.drawable.ic_qr_code,
                    "显示加入网络二维码");
            qr.setOnClickListener(v -> showProfileQr(profile));
            head.addView(qr);
            card.addView(head);
            card.addView(labelValue("Token", config.optString("token", "-"), INDIGO), top(10));
            card.addView(labelValue("服务器", config.optString("server_address", "-"), textBody()), top(7));
            LinearLayout buttons = row();
            Button connection;
            if (activeProfile) {
                connection = current.status == VntState.Status.STOPPING
                        ? ghost("停止中")
                        : danger(current.status == VntState.Status.STARTING ? "取消启动" : "停止");
                connection.setEnabled(current.status != VntState.Status.STOPPING);
                if (connection.isEnabled()) connection.setOnClickListener(v -> confirmStop());
            } else {
                connection = primary("启动");
                connection.setEnabled(!sessionActive);
                if (sessionActive) {
                    connection.setTextColor(textMuted());
                    connection.setBackground(round(bgInput(), 9, border(), 1));
                } else {
                    connection.setOnClickListener(v -> requestVpn(profile));
                }
            }
            buttons.addView(connection, end(8));
            Button edit = ghost("编辑");
            edit.setOnClickListener(v -> editProfile(profile));
            buttons.addView(edit, end(8));
            Button delete = ghost("删除");
            delete.setEnabled(!activeProfile);
            delete.setTextColor(activeProfile ? textMuted() : RED);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("删除配置")
                    .setMessage("确定要删除 “" + profile.name + "” 吗？")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> { store.delete(profile.id); render(); }).show());
            buttons.addView(delete);
            card.addView(buttons, top(14));
            pageHost.addView(card, top(12));
        }
    }

    private void logs() {
        List<VntLog.Entry> entries = VntLog.snapshot();

        LinearLayout action = row();
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.addView(text("最近 " + entries.size() + " 条记录", 13, true, textBody()), weighted());
        Button copy = ghost("复制全部");
        copy.setEnabled(!entries.isEmpty());
        copy.setOnClickListener(v -> copyLogs());
        action.addView(copy, end(8));
        Button clear = ghost("清空");
        clear.setTextColor(entries.isEmpty() ? textMuted() : RED);
        clear.setEnabled(!entries.isEmpty());
        clear.setOnClickListener(v -> {
            VntLog.clear();
            render();
        });
        action.addView(clear);
        pageHost.addView(action);

        if (entries.isEmpty()) {
            empty("暂无运行日志。启动一次组网后，连接过程与错误会记录在这里。");
            return;
        }

        LinearLayout card = card();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        for (VntLog.Entry entry : entries) {
            card.addView(logRow(entry));
        }
        pageHost.addView(card, top(12));

        TextView hint = text("日志仅保存在本机内存中，最多保留 500 条，退出应用后清空。"
                + "反馈问题时请复制日志并检查是否包含敏感信息。", 11, false, textMuted());
        hint.setPadding(0, dp(10), 0, 0);
        pageHost.addView(hint);
    }

    private View logRow(VntLog.Entry entry) {
        int color = switch (entry.level()) {
            case ERROR -> RED;
            case WARN -> AMBER;
            default -> textBody();
        };
        LinearLayout rowLayout = row();
        rowLayout.setGravity(Gravity.TOP);
        rowLayout.setPadding(0, dp(4), 0, dp(4));

        TextView time = text(entry.time(), 11, false, textMuted());
        time.setTypeface(Typeface.MONOSPACE);
        rowLayout.addView(time, end(8));

        TextView tag = text(entry.tag(), 11, true, color);
        tag.setTypeface(Typeface.MONOSPACE);
        tag.setMinWidth(dp(44));
        rowLayout.addView(tag, end(8));

        TextView message = text(entry.message(), 11, false, color);
        message.setTypeface(Typeface.MONOSPACE);
        message.setTextIsSelectable(true);
        rowLayout.addView(message, weighted());
        return rowLayout;
    }

    private void copyLogs() {
        List<VntLog.Entry> entries = VntLog.snapshot();
        if (entries.isEmpty()) return;
        StringBuilder value = new StringBuilder();
        String version = currentVersion();
        value.append("VNT Android v").append(version)
                .append(" · 核心 ").append(coreVersion())
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(")\n");
        for (VntLog.Entry entry : entries) {
            value.append(entry.time()).append(" [").append(entry.level()).append("] ")
                    .append(entry.tag()).append("  ").append(entry.message()).append('\n');
        }
        copyToClipboard("VNT 运行日志", value.toString());
    }

    private void guide() {
        LinearLayout intro = card();
        intro.addView(text("第一次使用", 16, true, textStrong()));
        intro.addView(text("按下面四步即可把设备接入 VNT 虚拟网络。"
                + "需要先在服务端准备好服务器地址和网络编号。", 13, false, textMuted()), top(9));
        pageHost.addView(intro);

        step(1, "准备服务器", "从服务端管理员处获取 VNT 服务器地址，例如 quic://1.2.3.4:29872。\n"
                + "同一个人可以填写多个地址，用逗号或换行分隔，第一个不可用时会自动尝试下一个。");
        step(2, "新建组网配置", "进入「组网配置」→「新建配置」，填写服务器地址和网络编号。\n"
                + "如果网络设置了密码，在「安全配置」中填写组网加密密码，必须与其他设备完全一致。\n"
                + "设备名称可留空，默认使用手机型号。");
        step(3, "启动组网", "回到「网络总览」，选择配置后点击「启动」。\n"
                + "首次启动时系统会弹出 VPN 授权窗口，必须点「确定」才能创建虚拟网卡。\n"
                + "连接成功后通知栏会常驻显示，顶部会显示分配到的虚拟 IP。");
        step(4, "相互访问", "把对方的虚拟 IP 发给对方，即可像局域网一样访问。\n"
                + "在「在线设备」中查看谁在线、走直连还是中继，以及延迟和丢包情况。");

        LinearLayout quick = card();
        quick.addView(text("扫码快速加入", 15, true, textStrong()));
        quick.addView(text("如果对方有配置好的 VNT，可让他点「组网配置」里的二维码按钮，"
                + "你再点右上角扫码按钮直接加入，无需手动输入。", 13, false, textMuted()), top(9));
        pageHost.addView(quick, top(14));

        LinearLayout faq = card();
        faq.addView(text("常见问题", 15, true, textStrong()));
        faqItem(faq, "启动失败：需要 VPN 权限",
                "系统弹出授权时点了取消。再次点击启动，在弹窗中选择「确定」并勾选不再询问。");
        faqItem(faq, "连接不上服务器",
                "先确认服务器地址、端口和协议正确，再确认网络编号一致。"
                        + "如果开启了「服务端证书校验」，证书或指纹不匹配也会连接失败。");
        faqItem(faq, "报错 TLS handshake eof",
                "两种常见原因：\n"
                        + "1. 地址没写协议前缀。本客户端在没写协议时按 TLS over TCP 连接，"
                        + "而很多 VNT 服务端只监听 UDP（QUIC）。改写成 quic://地址:端口 即可。\n"
                        + "2. 服务端版本是 1.x。VNT 2.0 整体重构过，与 1.x 服务端不兼容，"
                        + "两者连握手报文格式都不同，无法靠修改填写解决，必须升级服务端。");
        faqItem(faq, "显示在线但访问不了对方",
                "常见原因是密码不一致，或对方开启了「允许 IKEv2 客户端」限制。"
                        + "可在「在线设备」的加密状态里查看是否出现密钥不一致。");
        faqItem(faq, "期间其他 VPN 无法使用",
                "Android 同一时间通常只能启用一个 VPN。启动 VNT 前请关闭其他 VPN 应用。");
        faqItem(faq, "如何访问对方的局域网",
                "让对方在「组网配置」→「子网路由」填写出栈网段，"
                        + "你在入栈网段里填写目标网段和对方的虚拟 IP。两端都要配置。");
        faqItem(faq, "想排查具体问题",
                "打开「运行日志」，里面记录了注册、建立 VPN 接口、IP 变更和错误信息，可一键复制。");
        pageHost.addView(faq, top(14));

        LinearLayout safety = card();
        safety.addView(text("安全建议", 15, true, textStrong()));
        safety.addView(text("· 组网密码使用高强度随机字符串，不要与他人共用同一密码。\n"
                + "· 服务端证书校验优先选择「系统证书验证」，生产环境不建议长期使用「跳过验证」。\n"
                + "· 「关闭内置 NAT」仅适用于已自行配置转发的设备，普通手机请保持开启。\n"
                + "· 二维码包含组网凭据，不要截图转发到公开场合。", 13, false, textMuted()), top(9));
        pageHost.addView(safety, top(14));

        Button openConfig = primary("去新建配置");
        openConfig.setOnClickListener(v -> {
            page = Page.CONFIG;
            render();
        });
        pageHost.addView(openConfig, top(18));
    }

    private void step(int number, String title, String body) {
        LinearLayout card = card();
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(String.valueOf(number), 13, true, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(INDIGO, 11, 0, 0));
        head.addView(badge, size(22, 22));
        TextView titleView = text(title, 15, true, textStrong());
        LinearLayout.LayoutParams titleParams = weighted();
        titleParams.leftMargin = dp(10);
        head.addView(titleView, titleParams);
        card.addView(head);
        TextView bodyView = text(body, 13, false, textBody());
        bodyView.setLineSpacing(dp(3), 1f);
        card.addView(bodyView, top(10));
        pageHost.addView(card, top(12));
    }

    private void faqItem(LinearLayout card, String question, String answer) {
        TextView questionView = text(question, 13, true, textBody());
        questionView.setPadding(0, dp(12), 0, 0);
        card.addView(questionView);
        TextView answerView = text(answer, 12, false, textMuted());
        answerView.setLineSpacing(dp(2), 1f);
        card.addView(answerView, top(4));
    }

    private void about() {
        String currentVersion = currentVersion();
        LinearLayout hero = card();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.vnt_icon);
        logo.setContentDescription("VNT");
        hero.addView(logo, size(64, 64));
        hero.addView(text("VNT", 20, true, textStrong()), top(12));
        hero.addView(text("简单、高效的异地组网与内网穿透工具", 13, false, textMuted()), top(5));
        hero.addView(text("Android 客户端 v" + currentVersion + " · Rust 核心 " + coreVersion(),
                11, false, textMuted()), top(10));
        pageHost.addView(hero);

        LinearLayout update = card();
        update.addView(text("版本更新", 15, true, textStrong()));
        update.addView(text("当前版本 v" + currentVersion, 13, false, textMuted()), top(9));
        Button checkUpdate = primary("检查更新");
        checkUpdate.setOnClickListener(v -> checkForUpdates(checkUpdate, currentVersion));
        update.addView(checkUpdate, top(12));
        pageHost.addView(update, top(14));

        LinearLayout source = card();
        source.addView(text("开源项目", 15, true, textStrong()));
        source.addView(text("项目代码、使用说明和问题反馈均托管在 GitHub", 13, false, textMuted()), top(9));
        TextView repositoryUrl = text("github.com/vnt-dev/vnt", 13, true, INDIGO);
        repositoryUrl.setTextIsSelectable(true);
        repositoryUrl.setOnClickListener(v -> copyToClipboard(
                "VNT GitHub 地址", "https://github.com/vnt-dev/vnt"));
        source.addView(repositoryUrl, top(12));
        pageHost.addView(source, top(14));
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(label + "已复制到剪贴板");
    }

    private void checkForUpdates(Button button, String currentVersion) {
        button.setEnabled(false);
        button.setText("检查中…");
        updateExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "VNT-Android");
                int statusCode = connection.getResponseCode();
                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("GitHub 返回 HTTP " + statusCode);
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) != -1) response.append(buffer, 0, count);
                }

                JSONObject release = new JSONObject(response.toString());
                String latestVersion = normalizeVersion(release.optString("tag_name"));
                String releaseUrl = release.optString("html_url");
                if (latestVersion.isEmpty() || releaseUrl.isEmpty()) {
                    throw new IllegalStateException("最新版本信息不完整");
                }
                boolean hasUpdate = compareVersions(latestVersion, currentVersion) > 0;
                runOnUiThread(() -> showUpdateResult(button, currentVersion, latestVersion,
                        releaseUrl, hasUpdate));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    resetUpdateButton(button);
                    toast("检查更新失败：" + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showUpdateResult(Button button, String currentVersion, String latestVersion,
                                  String releaseUrl, boolean hasUpdate) {
        if (isFinishing() || isDestroyed()) return;
        resetUpdateButton(button);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        if (hasUpdate) {
            dialog.setTitle("发现新版本 v" + latestVersion)
                    .setMessage("当前版本：v" + currentVersion + "\n最新版本：v" + latestVersion)
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("前往下载", (ignored, which) -> openUrl(releaseUrl));
        } else {
            dialog.setTitle("已是最新版本")
                    .setMessage("当前版本：v" + currentVersion)
                    .setPositiveButton("确定", null);
        }
        dialog.show();
    }

    private void resetUpdateButton(Button button) {
        button.setEnabled(true);
        button.setText("检查更新");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            toast("无法打开下载页面");
        }
    }

    private String currentVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null || version.trim().isEmpty() ? "未知" : normalizeVersion(version);
        } catch (PackageManager.NameNotFoundException ignored) {
            return "未知";
        }
    }

    private String coreVersion() {
        String tag = getString(R.string.vnt_core_tag).trim();
        if (tag.isEmpty() || tag.equalsIgnoreCase("unknown")) return "未知";
        return tag.startsWith("v") || tag.startsWith("V") ? tag : "v" + tag;
    }

    private static String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private static int compareVersions(String candidate, String current) {
        String[] candidateParts = normalizeVersion(candidate).split("[-+]", 2)[0].split("\\.");
        String[] currentParts = normalizeVersion(current).split("[-+]", 2)[0].split("\\.");
        int length = Math.max(candidateParts.length, currentParts.length);
        try {
            for (int i = 0; i < length; i++) {
                int candidatePart = i < candidateParts.length ? Integer.parseInt(candidateParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (candidatePart != currentPart) return Integer.compare(candidatePart, currentPart);
            }
            return 0;
        } catch (NumberFormatException ignored) {
            return normalizeVersion(candidate).equalsIgnoreCase(normalizeVersion(current)) ? 0 : 1;
        }
    }

    private void editProfile(VntConfigStore.Profile existing) {
        JSONObject config = existing == null ? new JSONObject() : existing.config();
        VntConfigStore.Draft draft = new VntConfigStore.Draft();
        LinearLayout form = column();
        form.setPadding(dp(14), dp(4), dp(14), dp(18));

        LinearLayout basic = section(form, "基础配置", true);
        EditText name = field(basic, "配置名称", existing == null ? "" : existing.name, false,
                "例如：我的组网", "只用于配置列表展示，不参与组网认证。可留空。");
        EditText token = field(basic, "Token *", config.optString("token"), false,
                "例如：xieyuhua",
                "服务端 --white-token 设置的白名单令牌。\n"
                        + "与 VNT 2.x 的网络编号不同：1.x 用 Token 标识一个组网。\n"
                        + "连接同一服务端且 Token 相同的节点会进入同一个虚拟网络。");
        EditText server = field(basic, "服务器地址 *", config.optString("server_address"), false,
                "例如：nat1.wherewego.top:29872",
                "服务端地址与端口，默认 29872。\n"
                        + "可加协议前缀指定传输方式：\n"
                        + "· 不写前缀 —— UDP（1.x 默认，绝大多数服务端用这个）\n"
                        + "· tcp:// —— TCP 之上做 TLS\n"
                        + "· ws:// —— 明文 WebSocket\n"
                        + "· wss:// —— WebSocket over TLS\n"
                        + "注意：1.x 与 2.x 服务端互不兼容，必须连接 vnts 1.x。");

        LinearLayout security = section(form, "安全配置", false);
        EditText password = field(security, "组网加密密码", config.optString("password"), true,
                "留空则不加密", "互通的 VNT 节点必须使用完全相同的密码。");
        Spinner cipher = new Spinner(this);
        cipher.setAdapter(spinnerAdapter(new String[]{
                "aes_gcm（默认）", "chacha20_poly1305", "aes_cbc", "aes_ecb", "xor（不加密）"}));
        String[] cipherValues = {"aes_gcm", "chacha20_poly1305", "aes_cbc", "aes_ecb", "xor"};
        String savedCipher = config.optString("cipher_model", "aes_gcm");
        for (int i = 0; i < cipherValues.length; i++) {
            if (cipherValues[i].equals(savedCipher)) { cipher.setSelection(i); break; }
        }
        fieldLabel(security, "加密模式", "节点之间数据包的加密算法，双方必须一致。\n"
                + "aes_gcm 兼容性与性能最好；xor 表示不加密，仅建议在完全可信的网络使用。");
        security.addView(spinnerBox(cipher), top(8));
        CheckBox serverEncrypt = toggle(security, "服务端加密", "与服务端之间的通道加密",
                "关闭后仅节点间加密。建议保持开启。", config.optBoolean("server_encrypt", true));
        CheckBox finger = toggle(security, "校验服务端指纹", "连接时校验服务端证书指纹",
                "开启后首次连接会记录服务端指纹，之后指纹变化会拒绝连接。"
                        + "可防止中间人攻击，但服务端更换证书后需要重新连接。",
                config.optBoolean("finger", false));

        LinearLayout networkSection = section(form, "网络设置", false);
        EditText virtualIp = field(networkSection, "自定义虚拟 IP（可选）", config.optString("ip"), false,
                "例如：10.26.0.2", "请求固定的虚拟 IPv4 地址。留空由服务端分配。");
        EditText mtu = field(networkSection, "MTU", String.valueOf(config.optInt("mtu", 1400)), false,
                "1400", "虚拟网卡单个 IP 包的最大长度，常用 1200-1500。");
        mtu.setInputType(InputType.TYPE_CLASS_NUMBER);
        ListInput stun = new ListInput(networkSection, "STUN 服务器",
                "例如：stun.l.google.com:19302", "＋ 添加 STUN",
                toList(config.optJSONArray("stun_server")),
                "用于 NAT 类型探测和打洞。留空使用内置列表。可填多个，用逗号或换行分隔。");

        LinearLayout transport = section(form, "传输优化", false);
        CheckBox compress = toggle(transport, "LZ4 压缩", "减少可压缩流量的数据量",
                "文本、日志收益较高；视频和压缩包收益较小。", config.optBoolean("compress"));

        LinearLayout subnet = section(form, "子网路由（点对网）", false);
        ListInput inIps = new ListInput(subnet, "入栈网段",
                "例如：192.168.2.0/24,10.26.0.2", "＋ 添加入栈网段",
                toList(config.optJSONArray("in_ips")),
                "配置在访问端。格式：目标网段/前缀,出口节点虚拟IP。\n"
                        + "例如 192.168.2.0/24,10.26.0.2 表示访问 10.26.0.2 所在设备的 192.168.2.0/24 网段。");
        ListInput outIps = new ListInput(subnet, "出栈允许网段",
                "例如：192.168.1.0/24", "＋ 添加出栈网段",
                toList(config.optJSONArray("out_ips")),
                "配置在出口端，是本机允许转发出去的网段。只开放确实需要访问的网段。");

        LinearLayout deviceSection = section(form, "设备配置", false);
        EditText device = field(deviceSection, "设备名称", config.optString("device_name", Build.MODEL), false,
                "默认为手机型号", "其他节点看到的名称，只用于识别设备。");
        EditText deviceId = field(deviceSection, "设备 ID", config.optString("device_id", store.deviceId()), false,
                "不同设备不能相同", "稳定的唯一标识。不同设备不能共用同一 ID。");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "新建组网配置" : "编辑组网配置")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                draft.name = name.getText().toString();
                draft.token = token.getText().toString();
                draft.server = server.getText().toString();
                draft.password = password.getText().toString();
                draft.deviceId = deviceId.getText().toString();
                draft.deviceName = device.getText().toString();
                draft.virtualIp = virtualIp.getText().toString();
                draft.mtu = Integer.parseInt(mtu.getText().toString().trim());
                draft.stun = stun.joined();
                draft.inIps = inIps.joined();
                draft.outIps = outIps.joined();
                draft.finger = finger.isChecked();
                draft.serverEncrypt = serverEncrypt.isChecked();
                draft.compress = compress.isChecked();
                draft.cipherModel = cipherValues[cipher.getSelectedItemPosition()];

                VntConfigStore.Profile profile = VntConfigStore.Profile.create(draft);
                if (existing != null) profile = profile.withId(existing.id);
                store.save(profile);
                dialog.dismiss();
                render();
            } catch (Exception error) {
                toast(error.getMessage() == null ? "配置内容无效" : error.getMessage());
            }
        }));
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }


    private void scanNetworkQr() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("扫描 VNT 加入网络二维码");
        options.setBeepEnabled(false);
        options.setCaptureActivity(PortraitCaptureActivity.class);
        options.setOrientationLocked(true);
        qrScanner.launch(options);
    }

    private void showProfileQr(VntConfigStore.Profile profile) {
        try {
            JSONObject config = profile.config();
            String token = config.getString("token");
            String server = config.optString("server_address", "");
            int mtu = config.optInt("mtu", 1400);
            String password = config.optString("password");
            JSONObject payload = new JSONObject()
                    .put("type", "vnt1-network")
                    .put("version", 1)
                    .put("token", token)
                    .put("server_address", server)
                    .put("mtu", mtu)
                    .put("password", password);
            Bitmap bitmap = new BarcodeEncoder().encodeBitmap(
                    payload.toString(), BarcodeFormat.QR_CODE, dp(320), dp(320));

            LinearLayout content = column();
            content.setPadding(dp(24), dp(8), dp(24), dp(16));
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setContentDescription("VNT 加入网络二维码");
            image.setAdjustViewBounds(true);
            content.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(text("Token：" + token, 13, false, textBody()), top(12));
            content.addView(text("服务器：" + server, 13, false, textBody()), top(6));
            content.addView(text("MTU：" + mtu, 13, false, textBody()), top(6));
            content.addView(text("加密密码：" + (password.isEmpty() ? "未设置" : "已包含"),
                    13, false, textBody()), top(6));
            content.addView(text("二维码包含组网凭据，请仅分享给可信设备。",
                    12, false, AMBER), top(12));

            new AlertDialog.Builder(this)
                    .setTitle("扫码加入网络")
                    .setView(content)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception error) {
            toast(error.getMessage() == null ? "二维码生成失败" : error.getMessage());
        }
    }

    private void handleNetworkQr(String raw) {
        try {
            JSONObject payload = new JSONObject(raw);
            if (!"vnt1-network".equals(payload.getString("type")) || payload.getInt("version") != 1) {
                throw new IllegalArgumentException("不是受支持的 VNT 加入网络二维码");
            }
            String token = payload.getString("token").trim();
            String server = payload.getString("server_address").trim();
            int mtu = payload.getInt("mtu");
            String password = payload.getString("password");
            if (token.isEmpty()) throw new IllegalArgumentException("二维码中的 Token 为空");
            if (server.isEmpty()) throw new IllegalArgumentException("二维码中的服务器地址为空");
            if (mtu < 576 || mtu > 9000) throw new IllegalArgumentException("二维码中的 MTU 超出 576-9000 范围");

            JoinNetwork network = new JoinNetwork(token, server, mtu, password);
            String message = "Token\uFF1A" + network.token() + "\n"
                    + "\u670d\u52a1\u5668\uFF1A" + network.server() + "\n"
                    + "MTU\uFF1A" + network.mtu() + "\n"
                    + "\u52a0\u5bc6\u5bc6\u7801\uFF1A" + (network.password().isEmpty() ? "\u672a\u8bbe\u7f6e" : "\u5df2\u5305\u542b");
            new AlertDialog.Builder(this)
                    .setTitle("加入网络")
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("添加配置", (dialog, which) -> addScannedProfile(network))
                    .show();
        } catch (Exception error) {
            toast(error.getMessage() == null ? "无法识别该二维码" : error.getMessage());
        }
    }

    private void addScannedProfile(JoinNetwork network) {
        try {
            VntConfigStore.Profile profile = VntConfigStore.Profile.createFromQr(
                    network.token(), network.server(), network.mtu(), network.password(),
                    store.deviceId());
            store.save(profile);
            page = Page.CONFIG;
            render();
            toast("已添加组网配置");
        } catch (Exception error) {
            toast(error.getMessage() == null ? "添加配置失败" : error.getMessage());
        }
    }


    private void requestVpn(VntConfigStore.Profile profile) {
        // 1.x 的 Android 构建总是需要虚拟网卡，没有"无网卡"模式。
        pendingProfile = profile;
        Intent prepare = VpnService.prepare(this);
        if (prepare == null) {
            VntVpnService.start(this, profile);
            pendingProfile = null;
            promptBatteryOptimization();
        } else vpnPermission.launch(prepare);
    }

    /**
     * 提示用户关闭电池优化。
     *
     * <p>P2P 依赖每 3 秒一次的 UDP 心跳。系统进入省电模式后会限制后台网络，
     * 心跳中断导致 P2P 路由过期并回落到中继。国产 ROM（小米、华为等）尤其激进。
     */
    private void promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
                .setTitle("保持后台连接稳定")
                .setMessage("为了让 P2P 直连在息屏后不中断，建议允许 VNT 在后台不受电量限制运行。\n\n"
                        + "系统设置 → 应用管理 → VNT → 省电策略 → 无限制\n"
                        + "同时在最近任务中锁定 VNT（下拉卡片加锁）。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(intent);
                    } catch (Exception error) {
                        toast("请手动在系统设置中允许后台运行");
                    }
                })
                .show();
    }

    private void confirmStop() {
        new AlertDialog.Builder(this).setTitle("停止组网").setMessage("确定要停止当前 VNT 虚拟网络吗？")
                .setNegativeButton("取消", null).setPositiveButton("停止", (d, w) -> VntVpnService.stop(this)).show();
    }

    /** 与输入框同风格的下拉框适配器：文字配色与内边距跟随当前主题。 */
    /** 圆角边框容器 + 右侧箭头，替代 Spinner 默认样式。 */
    private void sectionTitle(String value) { pageHost.addView(text(value, 16, true, textStrong()), top(20)); }

    private void empty(String value) {
        TextView empty = text(value, 13, false, textMuted());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(38), dp(16), dp(38));
        empty.setBackground(round(bgCard(), 12, border(), 1));
        pageHost.addView(empty, top(14));
    }

    private static String statusLabel(VntState.Status status) {
        return switch (status) {
            case RUNNING -> "运行中";
            case STARTING -> "启动中";
            case STOPPING -> "停止中";
            case ERROR -> "连接失败";
            default -> "已停止";
        };
    }

    private static boolean isSessionActive(VntState.Status status) {
        return status == VntState.Status.STARTING || status == VntState.Status.RUNNING
                || status == VntState.Status.STOPPING;
    }

    private static String encryptionLabel(int state) {
        return switch (state) {
            case 1 -> "双方加密";
            case 2 -> "双方未加密";
            case 3 -> "本机加密，对端未加密";
            case 4 -> "本机未加密，对端加密";
            case 5 -> "密钥不一致";
            default -> "-";
        };
    }

    /**
     * 连接方式文案。
     *
     * <p>VNT 1.x 用 `Route.metric` 区分路径：1 表示 P2P 打洞直连，
     * 其它值表示经服务器或其它节点中继。没有路由记录时说明还没产生流量。
     */
    private static String connectionMode(VntApi.ClientInfo client) {
        if (!client.online()) return "-";
        if (client.routeMetric() == null) return "尚未建立路径（无流量）";
        if (client.routeMetric() != 1) return "经节点中继";
        String protocol = client.routeProtocol();
        if (protocol == null) return "P2P 直连";
        return protocol.contains("Tcp") ? "P2P TCP 直连"
                : protocol.contains("Ws") ? "P2P WebSocket 直连"
                : "P2P UDP 直连";
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format(Locale.US, "%.1f KB", value / 1024d);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", value / (1024d * 1024));
        return String.format(Locale.US, "%.1f GB", value / (1024d * 1024 * 1024));
    }

    private record JoinNetwork(String token, String server, int mtu, String password) {}

    private enum Page {
        DASHBOARD("网络总览"), PEERS("在线设备"), ROUTES("路由表"),
        CONFIG("组网配置"), GUIDE("设置教程"), LOGS("运行日志"), ABOUT("关于");
        final String title;
        Page(String title) { this.title = title; }
    }
}
