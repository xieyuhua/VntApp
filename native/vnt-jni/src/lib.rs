//! VNT 1.x 的 Android JNI 桥。
//!
//! 1.x 核心本身没有 JNI 层，但它通过 `VntCallback::generate_tun` 预留了 Android
//! 接入点：核心注册成功后把服务端分配的网段交给 Java，由 Java 建立 Android VPN
//! 并把 TUN fd 回传给 Rust。这里只负责把这一协议翻译成 JNI 方法。
//!
//! 与 2.x 的差异：
//!   - 1.x 是同步回调模型，没有 `nextEvent` 事件流；
//!   - 状态通过 `device_list()` / `nat_info()` / `route()` 逐项查询。

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use std::net::Ipv4Addr;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use vnt::channel::punch::PunchModel;
use vnt::channel::{ConnectProtocol, UseChannelType};
use vnt::cipher::CipherModel;
use vnt::compression::Compressor;
use vnt::core::{Config, Vnt};
use vnt::handle::callback::{
    ConnectInfo, DeviceConfig, ErrorInfo, HandshakeInfo, PeerClientInfo, RegisterInfo, VntCallback,
};

/// Java 侧回调方法：由 Java 建立 VPN 并返回 TUN fd。
const GENERATE_TUN_METHOD: &str = "generateTunFd";
const GENERATE_TUN_SIG: &str = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I";

static CREATED: AtomicBool = AtomicBool::new(false);
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static NETWORKS: OnceLock<Mutex<Vec<(u64, Arc<NetworkHandle>)>>> = OnceLock::new();
static JVM: OnceLock<JavaVM> = OnceLock::new();

fn networks() -> &'static Mutex<Vec<(u64, Arc<NetworkHandle>)>> {
    NETWORKS.get_or_init(|| Mutex::new(Vec::new()))
}

/// 一次组网会话。
struct NetworkHandle {
    vnt: Vnt,
    netmask: Arc<Mutex<Option<(Ipv4Addr, Ipv4Addr, Ipv4Addr)>>>,
    stopped: Arc<AtomicBool>,
}

impl NetworkHandle {
    fn ip(&self) -> Option<Ipv4Addr> {
        self.netmask.lock().ok().and_then(|v| v.map(|x| x.0))
    }
}

/// 核心运行在独立线程，回调时通过这里回调 Java。
#[derive(Clone)]
struct JavaCallback {
    tun_fd: Arc<AtomicI32>,
    netmask: Arc<Mutex<Option<(Ipv4Addr, Ipv4Addr, Ipv4Addr)>>>,
    listener: Arc<Mutex<Option<GlobalRef>>>,
    stopped: Arc<AtomicBool>,
}

impl VntCallback for JavaCallback {
    fn success(&self) {
        log::info!("vnt success");
    }

    fn connect(&self, info: ConnectInfo) {
        log::info!("vnt connect {info}");
    }

    fn handshake(&self, _info: HandshakeInfo) -> bool {
        true
    }

    fn register(&self, info: RegisterInfo) -> bool {
        if let Ok(mut guard) = self.netmask.lock() {
            *guard = Some((info.virtual_ip, info.virtual_netmask, info.virtual_gateway));
        }
        log::info!(
            "vnt register ip={} netmask={} gateway={}",
            info.virtual_ip,
            info.virtual_netmask,
            info.virtual_gateway
        );
        true
    }

    /// Android 分支：核心把网段交给我们，我们回调 Java 建立 VPN 并拿回 fd。
    fn generate_tun(&self, info: DeviceConfig) -> usize {
        let fd = self.call_java(&info);
        if fd > 0 {
            self.tun_fd.store(fd, Ordering::SeqCst);
            fd as usize
        } else {
            log::error!("generateTunFd 返回无效 fd: {fd}");
            0
        }
    }

    fn peer_client_list(&self, info: Vec<PeerClientInfo>) {
        log::info!("vnt peers {}", info.len());
    }

    fn error(&self, info: ErrorInfo) {
        log::error!("vnt error {info}");
    }

    fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        log::info!("vnt stop");
    }
}

impl JavaCallback {
    fn call_java(&self, info: &DeviceConfig) -> i32 {
        let Some(vm) = JVM.get() else {
            log::error!("JVM 尚未保存，无法回调 Java");
            return -1;
        };
        let guard = match self.listener.lock() {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let Some(listener) = guard.as_ref() else {
            log::error!("listener 为空，无法建立 VPN");
            return -1;
        };
        let mut env = match vm.attach_current_thread_permanently() {
            Ok(v) => v,
            Err(e) => {
                log::error!("attach JVM 失败: {e}");
                return -1;
            }
        };
        let ip = match env.new_string(info.virtual_ip.to_string()) {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let mask = match env.new_string(info.virtual_netmask.to_string()) {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let gateway = match env.new_string(info.virtual_gateway.to_string()) {
            Ok(v) => v,
            Err(_) => return -1,
        };
        let result = env.call_method(
            listener.as_obj(),
            GENERATE_TUN_METHOD,
            GENERATE_TUN_SIG,
            &[
                JValue::Object(&JObject::from(ip)),
                JValue::Object(&JObject::from(mask)),
                JValue::Object(&JObject::from(gateway)),
            ],
        );
        match result {
            Ok(v) => v.i().unwrap_or(-1),
            Err(e) => {
                log::error!("调用 generateTunFd 失败: {e}");
                -1
            }
        }
    }
}

fn find_network(id: u64) -> Option<Arc<NetworkHandle>> {
    networks()
        .lock()
        .ok()?
        .iter()
        .find(|(k, _)| *k == id)
        .map(|(_, v)| v.clone())
}

fn err(env: &mut JNIEnv, message: String) -> jstring {
    log::error!("{message}");
    let _ = env.throw_new("java/lang/IllegalStateException", message);
    std::ptr::null_mut()
}

fn to_jstring(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}


/// 从 JSON 配置构造 1.x 的 `Config`。
///
/// Android 构建下 `Config::new` 排除 `tap`、`device_name`，但保留 `no_proxy`。
fn build_config(json: &str) -> Result<Config, String> {
    let value: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("配置 JSON 解析失败: {e}"))?;
    let text = |key: &str| -> String {
        value
            .get(key)
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .trim()
            .to_string()
    };
    let flag = |key: &str, default: bool| -> bool {
        value.get(key).and_then(|v| v.as_bool()).unwrap_or(default)
    };

    let token = text("token");
    if token.is_empty() {
        return Err("Token 不能为空".into());
    }
    let device_id = text("device_id");
    if device_id.is_empty() {
        return Err("设备 ID 不能为空".into());
    }
    let server_address = text("server_address");
    if server_address.is_empty() {
        return Err("服务器地址不能为空".into());
    }
    let name = {
        let raw = text("device_name");
        if raw.is_empty() {
            "Android".to_string()
        } else {
            raw
        }
    };

    let cipher_model = {
        let raw = text("cipher_model");
        if raw.is_empty() {
            CipherModel::AesGcm
        } else {
            CipherModel::from_str(&raw).map_err(|e| format!("加密模式无效: {e}"))?
        }
    };
    let punch_model = {
        let raw = text("punch_model");
        if raw.is_empty() {
            PunchModel::default()
        } else {
            PunchModel::from_str(&raw).map_err(|e| format!("打洞模式无效: {e}"))?
        }
    };
    // 直接按枚举取值，不走 Compressor::from_str：那个实现依赖编译期 feature，
    // 未启用任何压缩算法时会连 "none" 一起拒绝。
    let compressor = match text("compressor").to_lowercase().as_str() {
        "lz4" => Compressor::Lz4,
        _ => Compressor::None,
    };

    let ip = {
        let raw = text("ip");
        if raw.is_empty() {
            None
        } else {
            Some(Ipv4Addr::from_str(&raw).map_err(|e| format!("虚拟 IP 无效: {e}"))?)
        }
    };
    let password = {
        let raw = text("password");
        if raw.is_empty() {
            None
        } else {
            Some(raw)
        }
    };
    let mtu = value.get("mtu").and_then(|v| v.as_u64()).map(|v| v as u32);
    let stun_server = string_list(&value, "stun_server");
    let in_ips = parse_in_ips(&value)?;
    let out_ips = parse_out_ips(&value)?;

    Config::new(
        token,
        device_id,
        name,
        server_address,
        vec![],
        stun_server,
        in_ips,
        out_ips,
        password,
        mtu,
        ip,
        // 未启用 ip_proxy feature，Config::new 在此构建下没有 no_proxy 参数。
        flag("server_encrypt", true),
        cipher_model,
        flag("finger", false),
        punch_model,
        None,
        false,
        UseChannelType::All,
        None,
        0,
        compressor,
        // 必须开启流量统计，否则 up_stream_all/down_stream_all 返回 None，
        // 界面上的上传/下载会一直是空的。
        true,
        flag("allow_wire_guard", false),
        None,
    )
    .map_err(|e| format!("{e:?}"))
}

fn string_list(value: &serde_json::Value, key: &str) -> Vec<String> {
    value
        .get(key)
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str())
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

/// `in_ips`：每行 `网段/前缀,目标虚拟IP`。
fn parse_in_ips(value: &serde_json::Value) -> Result<Vec<(u32, u32, Ipv4Addr)>, String> {
    let mut result = Vec::new();
    for raw in string_list(value, "in_ips") {
        let parts: Vec<&str> = raw.split(',').map(str::trim).collect();
        if parts.len() != 2 {
            return Err(format!("入栈网段格式应为 网段/前缀,目标IP：{raw}"));
        }
        let (net, prefix) = parse_cidr(parts[0])?;
        let target = Ipv4Addr::from_str(parts[1]).map_err(|e| format!("目标 IP 无效：{e}"))?;
        result.push((net, prefix, target));
    }
    Ok(result)
}

/// `out_ips`：每行 CIDR。
fn parse_out_ips(value: &serde_json::Value) -> Result<Vec<(u32, u32)>, String> {
    string_list(value, "out_ips")
        .iter()
        .map(|raw| parse_cidr(raw))
        .collect()
}

fn parse_cidr(raw: &str) -> Result<(u32, u32), String> {
    let (addr, prefix) = raw
        .split_once('/')
        .ok_or_else(|| format!("缺少前缀长度：{raw}"))?;
    let ip = Ipv4Addr::from_str(addr.trim()).map_err(|e| format!("网段地址无效：{e}"))?;
    let len: u32 = prefix
        .trim()
        .parse()
        .map_err(|_| format!("前缀长度无效：{raw}"))?;
    if len > 32 {
        return Err(format!("前缀长度超出 0-32：{raw}"));
    }
    Ok((u32::from(ip), len))
}

fn netmask_to_prefix(mask: Ipv4Addr) -> u32 {
    u32::from(mask).count_ones()
}

fn broadcast_of(ip: Ipv4Addr, prefix: u32) -> Ipv4Addr {
    if prefix >= 32 {
        return ip;
    }
    let mask = !((1u32 << (32 - prefix)) - 1);
    Ipv4Addr::from(u32::from(ip) | !mask)
}

// ---------------------------------------------------------------------------
// JNI 入口
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntManager_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    init_logging();
    CREATED.store(true, Ordering::SeqCst);
    1
}

/// 把 Rust 日志导入 logcat，并记录 panic。
///
/// 1.x 核心没有任何日志后端，若不在 JNI 层初始化，核心的诊断信息
/// （连接、注册、打洞失败原因）会全部丢弃，出问题时无从排查。
fn init_logging() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("VNT-Rust"),
        );
        std::panic::set_hook(Box::new(|info| {
            log::error!("Rust panic: {info}");
        }));
        log::info!("Rust 核心日志已接入 logcat");
    });
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntManager_nativeDestroy(_env: JNIEnv, _class: JClass) {
    if let Ok(mut list) = networks().lock() {
        for (_, handle) in list.drain(..) {
            handle.vnt.stop();
        }
    }
    CREATED.store(false, Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntManager_nativeCreateNetwork<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
    listener: JObject<'local>,
) -> jlong {
    create_network(&mut env, &config_json, &listener)
}

fn create_network<'local>(
    env: &mut JNIEnv<'local>,
    config_json: &JString<'local>,
    listener: &JObject<'local>,
) -> jlong {
    if !CREATED.load(Ordering::SeqCst) {
        err(env, "VNT 尚未初始化".into());
        return -1;
    }
    let json: String = match env.get_string(config_json) {
        Ok(v) => v.into(),
        Err(e) => {
            err(env, format!("读取配置失败: {e}"));
            return -1;
        }
    };
    let config = match build_config(&json) {
        Ok(v) => v,
        Err(message) => {
            err(env, message);
            return -1;
        }
    };

    if JVM.get().is_none() {
        match env.get_java_vm() {
            Ok(vm) => {
                let _ = JVM.set(vm);
            }
            Err(e) => {
                err(env, format!("获取 JavaVM 失败: {e}"));
                return -1;
            }
        }
    }

    let listener_ref = if listener.is_null() {
        None
    } else {
        match env.new_global_ref(listener) {
            Ok(v) => Some(v),
            Err(e) => {
                err(env, format!("保存回调失败: {e}"));
                return -1;
            }
        }
    };

    let netmask = Arc::new(Mutex::new(None));
    let stopped = Arc::new(AtomicBool::new(false));
    let callback = JavaCallback {
        tun_fd: Arc::new(AtomicI32::new(-1)),
        netmask: netmask.clone(),
        listener: Arc::new(Mutex::new(listener_ref)),
        stopped: stopped.clone(),
    };

    let vnt = match Vnt::new(config, callback) {
        Ok(v) => v,
        Err(e) => {
            err(env, format!("创建网络实例失败: {e:?}"));
            return -1;
        }
    };

    let handle_id = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
    let handle = Arc::new(NetworkHandle {
        vnt,
        netmask,
        stopped,
    });
    match networks().lock() {
        Ok(mut list) => list.push((handle_id, handle)),
        Err(_) => {
            err(env, "内部状态不可用".into());
            return -1;
        }
    }
    handle_id as jlong
}

/// 1.x 在创建网络后异步注册，这里等待注册结果。
#[no_mangle]
pub extern "system" fn Java_com_vnt_VntNetwork_nativeRegister<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return err(&mut env, "无效的网络句柄".into());
    };
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    loop {
        if let Ok(guard) = network.netmask.lock() {
            if let Some((ip, mask, gateway)) = *guard {
                let prefix = netmask_to_prefix(mask);
                let json = serde_json::json!({
                    "success": true,
                    "ip": ip.to_string(),
                    "prefix_len": prefix,
                    "gateway": gateway.to_string(),
                    "broadcast": broadcast_of(ip, prefix).to_string(),
                });
                return to_jstring(&mut env, json.to_string());
            }
        }
        if network.stopped.load(Ordering::SeqCst) {
            return to_jstring(
                &mut env,
                serde_json::json!({"success": false, "error": "组网已停止"}).to_string(),
            );
        }
        if std::time::Instant::now() > deadline {
            return to_jstring(
                &mut env,
                serde_json::json!({"success": false, "error": "注册超时（30 秒）"}).to_string(),
            );
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntNetwork_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    let Some(network) = find_network(handle as u64) else {
        return 0;
    };
    network.vnt.stop();
    if let Ok(mut list) = networks().lock() {
        list.retain(|(k, _)| *k != handle as u64);
    }
    1
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntNetwork_nativeGetApi(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    if find_network(handle as u64).is_some() {
        handle
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntApi_nativeGetClientList<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return err(&mut env, "无效的网络句柄".into());
    };
    // 逐节点累计流量。1.x 在 enable_traffic 打开后按 IP 分别统计，
    // 这里一次性取出，避免每个节点重复加锁。
    let up = network.vnt.up_stream_all().map(|(_, map)| map).unwrap_or_default();
    let down = network.vnt.down_stream_all().map(|(_, map)| map).unwrap_or_default();

    let list: Vec<serde_json::Value> = network
        .vnt
        .device_list()
        .iter()
        .map(|info| {
            let route = network.vnt.route(&info.virtual_ip);
            // metric == 1 表示 P2P 直连（Route::is_p2p 的定义），
            // 其它值表示经服务器或其它节点中继。
            let is_p2p = route.map(|r| r.metric == 1).unwrap_or(false);
            let tx = up.get(&info.virtual_ip).copied();
            let rx = down.get(&info.virtual_ip).copied();
            serde_json::json!({
                "ip": info.virtual_ip.to_string(),
                "name": info.name,
                // 1.x 的 DeviceInfo 协议里没有版本字段，无法获取。
                "version": "",
                "client_type": if info.wireguard { "WIREGUARD" } else { "VNT" },
                "online": info.status.is_online(),
                "direct": is_p2p,
                // 这两个字段界面用来区分"直连/服务器中继/客户端中继"，
                // 缺失时设备页会一律显示成"服务器中继"。
                "route_metric": route.map(|r| r.metric as i64),
                "route_protocol": route.map(|r| protocol_name(r.protocol)),
                "rtt": route.map(|r| r.rt).filter(|rt| *rt < 9999),
                "key_equal": if info.client_secret { 1 } else { 2 },
                // 1.x 不统计丢包率，保持 null 让界面隐藏该行。
                "packet_loss": serde_json::Value::Null,
                "traffic": match (tx, rx) {
                    (None, None) => serde_json::Value::Null,
                    _ => serde_json::json!({
                        "tx_bytes": tx.unwrap_or(0),
                        "rx_bytes": rx.unwrap_or(0),
                    }),
                },
            })
        })
        .collect();
    to_jstring(
        &mut env,
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntApi_nativeGetNetwork<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return to_jstring(&mut env, "null".into());
    };
    let Some(ip) = network.ip() else {
        return to_jstring(&mut env, "null".into());
    };
    let (mask, gateway) = network
        .netmask
        .lock()
        .ok()
        .and_then(|v| v.map(|x| (x.1, x.2)))
        .unwrap_or((Ipv4Addr::new(255, 255, 255, 0), Ipv4Addr::new(0, 0, 0, 0)));
    let prefix = netmask_to_prefix(mask);
    let json = serde_json::json!({
        "ip": ip.to_string(),
        "prefix_len": prefix,
        "gateway": gateway.to_string(),
        "broadcast": broadcast_of(ip, prefix).to_string(),
    });
    to_jstring(&mut env, json.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntApi_nativeGetNatInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return to_jstring(&mut env, "null".into());
    };
    let info = network.vnt.nat_info();
    let ips: Vec<String> = info.public_ips.iter().map(|v| v.to_string()).collect();
    let json = serde_json::json!({
        "nat_type": format!("{:?}", info.nat_type),
        "public_ips": ips,
        // NatInfo.ipv6 是私有字段，1.x 不暴露；显式给 null 以保持 Java 侧解析一致。
        "ipv6": serde_json::Value::Null,
    });
    to_jstring(&mut env, json.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntApi_nativeGetServerList<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return to_jstring(&mut env, "[]".into());
    };
    let connected = network.ip().is_some();
    let json = serde_json::json!([{
        "server_addr": network.vnt.config().server_address_str,
        "connected": connected,
        "rtt": serde_json::Value::Null,
        "server_version": serde_json::Value::Null,
    }]);
    to_jstring(&mut env, json.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_vnt_VntApi_nativeGetRouteTable<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let Some(network) = find_network(handle as u64) else {
        return to_jstring(&mut env, "[]".into());
    };
    // 1.x 没有整表接口，只能对已知节点逐个查询。
    let list: Vec<serde_json::Value> = network
        .vnt
        .device_list()
        .iter()
        .filter_map(|info| {
            let route = network.vnt.route(&info.virtual_ip)?;
            Some(serde_json::json!({
                "ip": info.virtual_ip.to_string(),
                "routes": [{
                    "route_key": route.addr.to_string(),
                    "protocol": protocol_name(route.protocol),
                    "metric": route.metric,
                    "rtt": route.rt,
                }],
            }))
        })
        .collect();
    to_jstring(
        &mut env,
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()),
    )
}


fn protocol_name(protocol: ConnectProtocol) -> &'static str {
    match protocol {
        ConnectProtocol::UDP => "Udp",
        ConnectProtocol::TCP => "Tcp",
        ConnectProtocol::WS => "Ws",
        ConnectProtocol::WSS => "Wss",
    }
}
