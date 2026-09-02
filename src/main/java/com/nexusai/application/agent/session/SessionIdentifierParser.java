package com.nexusai.application.agent.session;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SessionIdentifierParser · 对齐 CC utils/sessionUrl.ts.
 *
 * <p>L1 语义: 解析 session resume 标识符 (可为 URL / UUID / .jsonl 路径)。
 * <ul>
 *   <li>'.jsonl' 路径 → {@link Parsed} with jsonlFile + random sessionId</li>
 *   <li>UUID → {@link Parsed} with sessionId;isUrl=false</li>
 *   <li>URL → {@link Parsed} with ingressUrl + random sessionId;isUrl=true</li>
 *   <li>其他 → null</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: parseSessionIdentifier(input)→Parsed|null;UUID generator + URL parser + UUID validator 注入式</li>
 *   <li><b>A2 Golden Trace</b>: endsWith .jsonl → Parsed(jsonlFile);valid UUID → Plain;valid URL → URL with random sessionId;其他 → null</li>
 *   <li><b>A3 副作用</b>: 调用 UUID generator + 解析 URL (mutable);randomID 注入式</li>
 *   <li><b>A4 边界</b>: null/empty input → null;invalid URL → null;Windows path 不被误判为 URL</li>
 *   <li><b>A5 业务场景</b>: /resume 接受 URL/UUID/JSONL 三种格式</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS crypto.randomUUID → Java UUID.randomUUID (injectable);
 * TS new URL() parse → Java URI.create + try/catch;
 * TS UUID validate regex → Java Pattern。
 */
public final class SessionIdentifierParser {

    public record Parsed(UUID sessionId, String ingressUrl, boolean isUrl,
                        String jsonlFile, boolean isJsonlFile) {}

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private SessionIdentifierParser() {}

    /**
     * Variant with default UUID generator.
     */
    public static Parsed parseSessionIdentifier(String input) {
        return parseSessionIdentifier(input, UUID::randomUUID);
    }

    /**
     * Test-friendly variant with injected UUID generator.
     */
    public static Parsed parseSessionIdentifier(String input,
                                                Supplier<UUID> uuidSupplier) {
        if (input == null || input.isEmpty()) return null;
        String lower = input.toLowerCase();
        if (lower.endsWith(".jsonl")) {
            return new Parsed(uuidSupplier.get(), null, false, input, true);
        }
        if (UUID_PATTERN.matcher(lower).matches()) {
            return new Parsed(UUID.fromString(input), null, false, null, false);
        }
        try {
            java.net.URI uri = java.net.URI.create(input);
            if (uri.getScheme() != null) {
                return new Parsed(uuidSupplier.get(), uri.toString(), true, null, false);
            }
        } catch (IllegalArgumentException ignored) {
            // Not a valid URI
        }
        return null;
    }
}
