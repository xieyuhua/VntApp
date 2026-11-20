package com.rustvnt.vntapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 组网配置的本地存储。
 *
 * <p>字段布局对应 VNT 1.x 的配置项：token / server_address / in_ips / out_ips /
 * cipher_model / finger 等。与 2.x 的主要差异：
 * <ul>
 *   <li>用 <b>Token</b> 而不是网络编号；</li>
 *   <li>服务器是单个地址（带 tcp:// ws:// wss:// 前缀，默认 UDP）；</li>
 *   <li>证书校验只有开关，没有指纹和标准证书两种模式。</li>
 * </ul>
 */
final class VntConfigStore {
    private static final String PREFS = "vnt_profiles";
    private static final String KEY = "profiles";
    private final SharedPreferences preferences;

    VntConfigStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Profile> all() {
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                result.add(Profile.fromStorage(array.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return result;
    }

    synchronized Profile find(String id) {
        for (Profile profile : all()) if (profile.id.equals(id)) return profile;
        return null;
    }

    synchronized String deviceId() {
        String id = preferences.getString("device_id", null);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString("device_id", id).apply();
        }
        return id;
    }

    synchronized void save(Profile profile) {
        List<Profile> profiles = all();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(profile);
        write(profiles);
    }

    synchronized void delete(String id) {
        List<Profile> profiles = all();
        profiles.removeIf(profile -> profile.id.equals(id));
        write(profiles);
    }

    private void write(List<Profile> profiles) {
        JSONArray array = new JSONArray();
        for (Profile profile : profiles) array.put(profile.toStorage());
        preferences.edit().putString(KEY, array.toString()).apply();
    }

    /**
     * 内置 STUN 服务器。
     *
     * <p>1.x 核心没有默认 STUN 列表：列表为空时 NAT 探测会失败，
     * 随后 P2P 打洞无法建立，只能走服务器中继。这里提供一组公共 STUN，
     * 覆盖国内与海外网络；用户可以在配置里覆盖。
     */
    static final String DEFAULT_STUN =
            "stun.l.google.com:19302\n"
                    + "stun1.l.google.com:19302\n"
                    + "stun.qq.com:3478\n"
                    + "stun.miwifi.com:3478";

    /** 配置编辑器提交的原始输入，字段与表单一一对应。 */
    static final class Draft {
        String name = "";
        String token = "";
        String server = "";
        String password = "";
        String deviceId = "";
        String deviceName = "";
        String virtualIp = "";
        String stun = "";
        String inIps = "";
        String outIps = "";
        int mtu = 1400;
        boolean finger;
        boolean serverEncrypt = true;
        boolean compress;
        /** aes_gcm / chacha20_poly1305 / aes_cbc / aes_ecb / xor */
        String cipherModel = "aes_gcm";
    }

    static final class Profile {
        final String id;
        final String name;
        final String json;

        Profile(String id, String name, String json) {
            this.id = id;
            this.name = name;
            this.json = json;
        }

        static Profile create(Draft draft) throws Exception {
            if (draft.token.trim().isEmpty()) {
                throw new IllegalArgumentException("Token 不能为空");
            }
            if (draft.token.trim().length() > 128) {
                throw new IllegalArgumentException("Token 不能超过 128 个字符");
            }
            if (draft.server.trim().isEmpty()) {
                throw new IllegalArgumentException("服务器地址不能为空");
            }
            if (draft.deviceId.trim().isEmpty()) {
                throw new IllegalArgumentException("设备 ID 不能为空");
            }

            JSONObject config = new JSONObject();
            config.put("token", draft.token.trim());
            config.put("server_address", draft.server.trim());
            config.put("device_id", draft.deviceId.trim());
            config.put("device_name",
                    draft.deviceName.trim().isEmpty() ? Build.MODEL : draft.deviceName.trim());
            if (!draft.password.isEmpty()) config.put("password", draft.password);
            if (!draft.virtualIp.trim().isEmpty()) config.put("ip", draft.virtualIp.trim());
            config.put("mtu", draft.mtu);
            config.put("finger", draft.finger);
            config.put("server_encrypt", draft.serverEncrypt);
            config.put("cipher_model", draft.cipherModel);
            config.put("compressor", draft.compress ? "lz4" : "none");
            // 留空时使用内置列表：没有 STUN 就无法探测 NAT，P2P 会退化为中继。
            config.put("stun_server",
                    splitAny(draft.stun.trim().isEmpty() ? DEFAULT_STUN : draft.stun));
            // 1.x 的规则格式：入栈 "网段/前缀,目标虚拟IP"，出栈 "网段/前缀"。
            config.put("in_ips", splitLines(draft.inIps));
            config.put("out_ips", splitLines(draft.outIps));

            String title = draft.name.trim().isEmpty() ? draft.token.trim() : draft.name.trim();
            return new Profile(UUID.randomUUID().toString(), title, config.toString());
        }

        static Profile createFromQr(String token, String server, int mtu, String password,
                                    String deviceId) throws Exception {
            Draft draft = new Draft();
            draft.name = token;
            draft.token = token;
            draft.server = server;
            draft.password = password;
            draft.deviceId = deviceId;
            draft.mtu = mtu;
            return create(draft);
        }

        Profile withId(String existingId) { return new Profile(existingId, name, json); }

        JSONObject config() {
            try { return new JSONObject(json); } catch (Exception error) { return new JSONObject(); }
        }

        JSONObject toStorage() {
            try { return new JSONObject().put("id", id).put("name", name).put("json", json); }
            catch (Exception impossible) { return new JSONObject(); }
        }

        static Profile fromStorage(JSONObject value) {
            return new Profile(value.optString("id"), value.optString("name"),
                    value.optString("json", "{}"));
        }

        /** 服务器地址、STUN 允许逗号和换行分隔。 */
        private static JSONArray splitAny(String raw) {
            return split(raw, "[\\n,]");
        }

        /** 路由规则每行自带逗号，只能按行拆分。 */
        private static JSONArray splitLines(String raw) {
            return split(raw, "\\n");
        }

        private static JSONArray split(String raw, String separator) {
            JSONArray values = new JSONArray();
            for (String value : raw.split(separator)) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) values.put(trimmed);
            }
            return values;
        }
    }
}
