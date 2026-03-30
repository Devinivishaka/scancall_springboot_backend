package protonest.co.scancallnewbackend.util;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MessageFactory {

    private MessageFactory() {
    }

    public static Map<String, Object> build(String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("timestamp", Instant.now().toString());
        return payload;
    }

    public static Map<String, Object> build(String type, Map<String, Object> data) {
        Map<String, Object> payload = build(type);
        if (data != null) {
            payload.putAll(data);
        }
        return payload;
    }

    public static String normalizeMode(String mode) {
        return "video".equalsIgnoreCase(mode) ? "video" : "audio";
    }

    public static String normalizePlatform(String platform) {
        String value = platform == null ? "" : platform.toLowerCase();
        if (value.contains("ios")) {
            return "ios";
        }
        if (value.contains("android")) {
            return "android";
        }
        return "unknown";
    }

    public static String normalizeAppState(String appState) {
        return "foreground".equalsIgnoreCase(appState) ? "foreground" : "background";
    }
}
