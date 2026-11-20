package com.vnt;

import org.json.JSONObject;

public final class RegisterResult {
    private final String ip;
    private final int prefixLen;
    private final String gateway;
    private final String broadcast;

    private RegisterResult(String ip, int prefixLen, String gateway, String broadcast) {
        this.ip = ip;
        this.prefixLen = prefixLen;
        this.gateway = gateway;
        this.broadcast = broadcast;
    }

    static RegisterResult fromJson(String json) throws VntException {
        // 原生层返回 null 表示 panic 或内部错误，给出明确提示而不是 NPE。
        if (json == null) throw new VntException("原生核心未返回注册结果（可能已崩溃，请查看运行日志）");
        try {
            JSONObject object = new JSONObject(json);
            if (!object.getBoolean("success")) {
                throw new VntException(object.optString("error", "注册被服务器拒绝"));
            }
            return new RegisterResult(object.getString("ip"), object.getInt("prefix_len"),
                    object.getString("gateway"), object.getString("broadcast"));
        } catch (VntException error) {
            throw error;
        } catch (Exception error) {
            throw new VntException("无法解析注册结果", error);
        }
    }

    public String getIp() { return ip; }
    public int getPrefixLen() { return prefixLen; }
    public String getGateway() { return gateway; }
    public String getBroadcast() { return broadcast; }
    public String toCidr() { return ip + "/" + prefixLen; }
}
