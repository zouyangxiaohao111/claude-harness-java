package com.nexusai.infra.util;

import java.util.Map;

/**
 * ControlMessageCompat · 对齐 CC utils/controlMessageCompat.ts.
 */
public final class ControlMessageCompat {

    private ControlMessageCompat() {}

    @SuppressWarnings("unchecked")
    public static Object normalizeControlMessageKeys(Object obj) {
        if (obj == null || !(obj instanceof Map)) return obj;
        Map<String, Object> record = (Map<String, Object>) obj;
        if (record.containsKey("requestId") && !record.containsKey("request_id")) {
            record.put("request_id", record.get("requestId"));
        }
        if (record.containsKey("requestId")) {
            record.remove("requestId");
        }
        Object response = record.get("response");
        if (response instanceof Map) {
            Map<String, Object> resp = (Map<String, Object>) response;
            if (resp.containsKey("requestId") && !resp.containsKey("request_id")) {
                resp.put("request_id", resp.get("requestId"));
            }
            if (resp.containsKey("requestId")) {
                resp.remove("requestId");
            }
        }
        return obj;
    }
}
