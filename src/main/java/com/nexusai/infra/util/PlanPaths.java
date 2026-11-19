package com.nexusai.infra.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 计划文件路径与词对 slug · 对齐 CC {@code utils/plans.ts}。
 *
 * <p><b>CC 契约</b>：
 * <ul>
 *   <li>目录默认 {@code join(getClaudeConfigHomeDir(), 'plans')}（plans.ts:100）——
 *       本仓同形，只是配置根换成本应用自有根；</li>
 *   <li>文件名 {@code {planSlug}.md}，slug 为随机词对、按会话缓存、冲突重试 10 次
 *       （plans.ts:24-49）；子代理为 {@code {planSlug}-agent-{agentId}.md}。</li>
 * </ul>
 *
 * <p>旧实现用 sessionId 当 slug（决策 D3）。用户 2026-11-19 裁定「对齐 CC」⇒ 改回词对 slug。
 * 缓存按「目录 + 会话」隔离，避免测试临时目录与真实目录互相串 slug。
 */
public final class PlanPaths {

    private static final Map<String, String> SLUG_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_SLUG_RETRIES = 10;

    private static final String[] ADJECTIVES = {
        "brave", "calm", "clever", "eager", "fancy", "gentle", "happy", "jolly",
        "keen", "lively", "mellow", "noble", "polite", "quiet", "rapid", "sunny",
        "tidy", "vivid", "warm", "witty", "young", "zesty", "bright", "cosmic",
    };
    private static final String[] NOUNS = {
        "otter", "panda", "falcon", "tiger", "maple", "river", "cloud", "comet",
        "ember", "forest", "harbor", "island", "jungle", "kettle", "lantern", "meadow",
        "nebula", "ocean", "pepper", "quartz", "rocket", "shadow", "thunder", "willow",
    };

    private PlanPaths() {
        throw new AssertionError("utility class - do not instantiate");
    }

    /** 会话 plan slug（词对）· 同目录+会话稳定；文件已存在则重试，最多 10 次。 */
    public static String slugFor(String plansDirectory, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for plan slug");
        }
        String key = plansDirectory + "|" + sessionId;
        return SLUG_CACHE.computeIfAbsent(key, k -> generateUniqueSlug(plansDirectory));
    }

    /** 主会话 → {slug}.md；子代理 → {slug}-agent-{agentId}.md。 */
    public static String planFilePath(String plansDirectory, String sessionId, UUID agentId) {
        String slug = slugFor(plansDirectory, sessionId);
        return agentId == null
            ? Path.of(plansDirectory, slug + ".md").toString()
            : Path.of(plansDirectory, slug + "-agent-" + agentId + ".md").toString();
    }

    /** /clear 等场景清掉会话 slug（下次重新生成）· 对齐 CC clearPlanSlug。 */
    public static void clearSlug(String plansDirectory, String sessionId) {
        if (sessionId != null) {
            SLUG_CACHE.remove(plansDirectory + "|" + sessionId);
        }
    }

    private static String generateUniqueSlug(String plansDirectory) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < MAX_SLUG_RETRIES; i++) {
            String slug = ADJECTIVES[random.nextInt(ADJECTIVES.length)]
                + "-" + NOUNS[random.nextInt(NOUNS.length)];
            try {
                if (!Files.exists(Path.of(plansDirectory, slug + ".md"))) {
                    return slug;
                }
            } catch (RuntimeException e) {
                return slug; // 路径异常不阻塞：交给后续读写降级
            }
        }
        // 十次都撞名：加随机后缀兜底（仍保证同会话缓存稳定）
        return ADJECTIVES[random.nextInt(ADJECTIVES.length)]
            + "-" + NOUNS[random.nextInt(NOUNS.length)]
            + "-" + Integer.toHexString(random.nextInt(0x1000));
    }
}
