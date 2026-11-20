package com.rustvnt.vntapp;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class VntConfigStoreTest {
    private static VntConfigStore.Draft minimalDraft() {
        VntConfigStore.Draft draft = new VntConfigStore.Draft();
        draft.token = "test-token";
        draft.server = "example.com:29872";
        draft.deviceId = "device-1";
        return draft;
    }

    @Test public void writesOneXFieldLayout() throws Exception {
        JSONObject config = new JSONObject(VntConfigStore.Profile.create(minimalDraft()).json);

        assertEquals("test-token", config.getString("token"));
        assertEquals("example.com:29872", config.getString("server_address"));
        assertEquals("device-1", config.getString("device_id"));
        assertTrue(config.getBoolean("server_encrypt"));
        assertEquals("aes_gcm", config.getString("cipher_model"));
        assertEquals("none", config.getString("compressor"));
        // 2.x 专属字段不应出现。
        assertFalse(config.has("network_code"));
        assertFalse(config.has("device_mode"));
        assertFalse(config.has("allow_ikev2"));
    }

    @Test public void routeRulesKeepCommasInsideEachLine() throws Exception {
        VntConfigStore.Draft draft = minimalDraft();
        draft.inIps = "192.168.2.0/24,10.26.0.2\n172.16.0.0/16,10.26.0.3";
        draft.outIps = "192.168.1.0/24\n192.168.3.0/24";

        JSONObject config = new JSONObject(VntConfigStore.Profile.create(draft).json);

        JSONArray inIps = config.getJSONArray("in_ips");
        assertEquals(2, inIps.length());
        assertEquals("192.168.2.0/24,10.26.0.2", inIps.getString(0));
        assertEquals(2, config.getJSONArray("out_ips").length());
    }

    @Test public void stunSplitsOnBothNewlinesAndCommas() throws Exception {
        VntConfigStore.Draft draft = minimalDraft();
        draft.stun = "stun1.l.google.com:19302,stun2.l.google.com:19302\nstun.qq.com:3478";

        JSONObject config = new JSONObject(VntConfigStore.Profile.create(draft).json);

        assertEquals(3, config.getJSONArray("stun_server").length());
    }

    @Test public void titleFallsBackToToken() throws Exception {
        VntConfigStore.Draft draft = minimalDraft();
        assertEquals("test-token", VntConfigStore.Profile.create(draft).name);

        draft.name = "  办公网  ";
        assertEquals("办公网", VntConfigStore.Profile.create(draft).name);
    }

    @Test public void optionalFieldsAreOmittedWhenBlank() throws Exception {
        JSONObject config = new JSONObject(VntConfigStore.Profile.create(minimalDraft()).json);

        assertFalse(config.has("password"));
        assertFalse(config.has("ip"));
        assertEquals(0, config.getJSONArray("in_ips").length());
        assertEquals(0, config.getJSONArray("out_ips").length());
    }

    @Test public void rejectsMissingRequiredFields() {
        VntConfigStore.Draft noToken = minimalDraft();
        noToken.token = "  ";
        assertThrows(IllegalArgumentException.class, () -> VntConfigStore.Profile.create(noToken));

        VntConfigStore.Draft noServer = minimalDraft();
        noServer.server = "";
        assertThrows(IllegalArgumentException.class, () -> VntConfigStore.Profile.create(noServer));

        VntConfigStore.Draft noDevice = minimalDraft();
        noDevice.deviceId = "";
        assertThrows(IllegalArgumentException.class, () -> VntConfigStore.Profile.create(noDevice));
    }

    @Test public void rejectsOverlongToken() {
        VntConfigStore.Draft draft = minimalDraft();
        draft.token = "x".repeat(129);
        assertThrows(IllegalArgumentException.class, () -> VntConfigStore.Profile.create(draft));
    }

    @Test public void compressionMapsToCompressorName() throws Exception {
        VntConfigStore.Draft draft = minimalDraft();
        draft.compress = true;
        JSONObject config = new JSONObject(VntConfigStore.Profile.create(draft).json);
        assertEquals("lz4", config.getString("compressor"));
    }
}
