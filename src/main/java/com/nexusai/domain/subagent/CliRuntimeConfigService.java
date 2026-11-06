package com.nexusai.domain.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeCatalog;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeEnvStore;
import com.nexusai.infra.util.ApiKeyHasher;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.repository.subagent.entity.CliRuntimeConfigRecord;
import com.nexusai.repository.subagent.mapper.CliRuntimeConfigMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 CLI 运行时配置 · 读写 + 内存快照刷新。
 *
 * <p><b>语义（用户 2026-11-04 拍板）</b>：
 * <ol>
 *   <li><b>未配置</b>：各项全空 ⇒ 不注入任何环境变量，CLI 用自己的默认配置/登录态；</li>
 *   <li><b>配置了就替换指定</b>：填了哪项就只覆盖哪项，其余仍走默认；</li>
 *   <li>各项互相独立 —— 只填 baseUrl 不会把 key 也一起改掉。</li>
 * </ol>
 *
 * <p><b>为什么写库后要 reload</b>：执行链（{@code ExternalAgentRunner}）是静态的，
 * 不注入 Spring。保存后必须把新快照推给 {@link ExternalRuntimeEnvStore}，
 * 否则会出现「页面显示已保存，实际派发还用旧配置」的假成功。
 */
@Service
public class CliRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(CliRuntimeConfigService.class);

    /** secrets 的 JSON 序列化（变量名 → 明文）。字段极少，用默认配置即可。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 旧单 key 列在 secrets 里的占位名（见 {@link ExternalRuntimeEnvStore.Overlay}）。 */
    private static final String LEGACY_SECRET = "__default__";

    private final CliRuntimeConfigMapper mapper;
    private final CryptoUtil cryptoUtil;

    public CliRuntimeConfigService(CliRuntimeConfigMapper mapper, CryptoUtil cryptoUtil) {
        this.mapper = mapper;
        this.cryptoUtil = cryptoUtil;
    }

    /** 启动期装载：把库里已配置的项解密后推入静态快照。 */
    @PostConstruct
    public void reload() {
        Map<String, ExternalRuntimeEnvStore.Overlay> byRuntime = new LinkedHashMap<>();
        try {
            List<CliRuntimeConfigRecord> rows = mapper.selectAll();
            if (rows != null) {
                for (CliRuntimeConfigRecord row : rows) {
                    if (row == null || row.getRuntime() == null) continue;
                    Map<String, String> secrets = decryptSecrets(row);
                    ExternalRuntimeEnvStore.Overlay overlay = new ExternalRuntimeEnvStore.Overlay(
                            row.getConfigDir(), row.getBaseUrl(), secrets,
                            row.getBin(), row.getModel(), row.getReasoning());
                    if (!overlay.isEmpty()) {
                        byRuntime.put(row.getRuntime().trim(), overlay);
                    }
                }
            }
        } catch (Exception e) {
            // 表还没建（迁移未跑）/ 读库失败：保持「全部未配置」的默认行为，不阻断启动
            log.warn("[CliRuntimeConfig] 读取 cli_runtime_config 失败，按全部未配置处理: {}", e.toString());
        }
        ExternalRuntimeEnvStore.replace(byRuntime);
    }

    /**
     * 解出该行的 key 映射（变量名 → 明文）。
     *
     * <p>两代格式并存：V262 的 {@code secrets_json}（多变量）与 V261 的
     * {@code api_key_encrypted}（单 key）。后者用 {@code __default__} 作为变量名，
     * 由 {@link ExternalRuntimeEnvStore#envFor} 映射到该 CLI 的第一个 key 变量。
     */
    private Map<String, String> decryptSecrets(CliRuntimeConfigRecord row) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = row.getSecretsJson();
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, String> parsed = JSON.readValue(
                        cryptoUtil.decrypt(raw), new TypeReference<Map<String, String>>() {});
                if (parsed != null) {
                    for (Map.Entry<String, String> e : parsed.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        out.put(e.getKey(), e.getValue());
                    }
                }
            } catch (Exception e) {
                // key 轮换 / 密文损坏：跳过而不是让整个应用起不来。
                // 用户会看到「未配置」并按需重填，比启动失败可恢复。
                log.warn("[CliRuntimeConfig] runtime={} 的 secrets 解密失败，按未配置处理: {}",
                        row.getRuntime(), e.toString());
            }
        }
        if (out.isEmpty() && row.getApiKeyEncrypted() != null && !row.getApiKeyEncrypted().isBlank()) {
            try {
                out.put(LEGACY_SECRET, cryptoUtil.decrypt(row.getApiKeyEncrypted()));
            } catch (Exception e) {
                log.warn("[CliRuntimeConfig] runtime={} 的旧 API key 解密失败，按未配置处理: {}",
                        row.getRuntime(), e.toString());
            }
        }
        return out;
    }

    /** 全部已配置项（掩码视图，⛔ 不含明文 key）。 */
    public List<View> list() {
        List<View> out = new ArrayList<>();
        try {
            List<CliRuntimeConfigRecord> rows = mapper.selectAll();
            if (rows != null) {
                for (CliRuntimeConfigRecord row : rows) {
                    if (row == null || row.getRuntime() == null) continue;
                    out.add(toView(row));
                }
            }
        } catch (Exception e) {
            log.warn("[CliRuntimeConfig] 列表读取失败: {}", e.toString());
        }
        return out;
    }

    /** 单个 runtime 的配置（未配置时返回各项为空的 View）。 */
    public View get(String runtime) {
        String rt = normalize(runtime);
        for (View v : list()) {
            if (v.runtime().equals(rt)) return v;
        }
        return new View(rt, null, null, Map.of(), false, null, null, null, null);
    }

    private View toView(CliRuntimeConfigRecord row) {
        Map<String, String> masked = new LinkedHashMap<>();
        String raw = row.getSecretsJson();
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, String> parsed = JSON.readValue(
                        cryptoUtil.decrypt(raw), new TypeReference<Map<String, String>>() {});
                if (parsed != null) {
                    for (Map.Entry<String, String> e : parsed.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        masked.put(e.getKey(), ApiKeyHasher.mask(e.getValue()));
                    }
                }
            } catch (Exception e) {
                log.warn("[CliRuntimeConfig] runtime={} 的 secrets 无法解出掩码: {}",
                        row.getRuntime(), e.toString());
            }
        }
        if (masked.isEmpty() && row.getApiKeyEncrypted() != null && !row.getApiKeyEncrypted().isBlank()) {
            masked.put(LEGACY_SECRET, row.getApiKeyMasked() == null ? "****" : row.getApiKeyMasked());
        }
        return new View(row.getRuntime(), row.getConfigDir(), row.getBaseUrl(), masked,
                !masked.isEmpty(), row.getBin(), row.getModel(), row.getReasoning(),
                row.getUpdatedAt());
    }

    /**
     * 保存一个 runtime 的配置。
     *
     * <p>请求里各项 {@code null} = 不改该项，空串 = 清空该项。
     *
     * @param runtime 运行时 id（必须在 {@link ExternalRuntimeCatalog} 里）
     * @param req     配置项
     * @return 保存后的掩码视图
     */
    public View save(String runtime, SaveRequest req) {
        String rt = normalize(runtime);
        ExternalRuntimeCatalog.RuntimePreset preset = ExternalRuntimeCatalog.find(rt);
        if (preset == null) {
            throw new IllegalArgumentException(
                    "未知的外部运行时 '" + runtime + "'，可选：" + ExternalRuntimeCatalog.knownRuntimes());
        }
        if (req == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        // 推理强度的合法性：只做「该 CLI 支不支持推理强度」这一层校验。
        // ⚠️ 具体档位**不在这里判**：codex 的档位是**按模型**给的（gpt-6-luna 到 max、
        //    gpt-6.1-sol 到 ultra），而这里的预设表只是全局兜底。硬判会拒掉合法值。
        if (req.reasoning() != null) {
            String r = req.reasoning().trim();
            if (!r.isEmpty() && !"default".equalsIgnoreCase(r)) {
                if (!preset.supportsReasoning()) {
                    throw new IllegalArgumentException("运行时 '" + rt + "' 不支持推理强度");
                }
                // 全局表里有的档位直接放行；不在表里但形如合法标识符也放行（模型级档位）
                if (!r.matches("^[A-Za-z][A-Za-z0-9_-]{0,31}$")) {
                    throw new IllegalArgumentException("推理强度 '" + r + "' 格式非法");
                }
            }
        }

        CliRuntimeConfigRecord row = mapper.selectOneByQuery(
                QueryWrapper.create().eq("runtime", rt));
        boolean insert = row == null;
        if (insert) {
            row = new CliRuntimeConfigRecord();
            row.setRuntime(rt);
        }

        // 各项：null = 不改，空串 = 清空
        if (req.configDir() != null) row.setConfigDir(emptyToNull(req.configDir()));
        if (req.baseUrl() != null) row.setBaseUrl(emptyToNull(req.baseUrl()));
        if (req.bin() != null) row.setBin(emptyToNull(req.bin()));
        if (req.model() != null) row.setModel(emptyToNull(req.model()));
        if (req.reasoning() != null) row.setReasoning(emptyToNull(req.reasoning()));

        // secrets：null = 不改；空 map = 清空；非空 = 替换（**值为掩码的项保留原值**）
        if (req.secrets() != null) {
            // 已保存的明文，用于「提交的是掩码 ⇒ 保持原值」的判定
            Map<String, String> existing = insert ? Map.of() : decryptSecrets(row);
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : req.secrets().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String name = e.getKey().trim();
                String value = e.getValue().trim();
                if (name.isEmpty() || value.isEmpty()) continue;
                // 前端回传掩码（含 ****）= 用户没改这个 key ⇒ 沿用已保存的明文。
                // ⚠️ 没有这条，用户「只新填了第二个 key」会把已保存的第一个 key 冲掉（整体替换语义）。
                if (value.contains("****") && existing.containsKey(name)) {
                    cleaned.put(name, existing.get(name));
                    continue;
                }
                // ⛔ 只接受该 CLI 认得的变量名：写错名字 = 静默不生效
                if (!preset.apiKeyEnvs().contains(name)) {
                    throw new IllegalArgumentException("运行时 '" + rt + "' 不认环境变量 '" + name
                            + "'，可选：" + String.join(", ", preset.apiKeyEnvs()));
                }
                cleaned.put(name, value);
            }
            if (cleaned.isEmpty()) {
                row.setSecretsJson(null);
                row.setApiKeyEncrypted(null);
                row.setApiKeyMasked(null);
                row.setApiKeyHash(null);
            } else {
                String plain;
                try {
                    plain = JSON.writeValueAsString(cleaned);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // 只有 String→String map，序列化失败说明是环境级问题，fail-loud
                    throw new IllegalStateException("序列化 secrets 失败: " + e.getMessage(), e);
                }
                row.setSecretsJson(cryptoUtil.encrypt(plain));
                // 旧列同步清掉，避免两代格式并存时歧义
                row.setApiKeyEncrypted(null);
                row.setApiKeyMasked(null);
                row.setApiKeyHash(null);
            }
        }
        row.setUpdatedAt(OffsetDateTime.now().toString());

        if (insert) {
            mapper.insert(row);
        } else {
            // ⛔ 必须用 ignoreNulls=false 显式写 NULL（对齐 AccountOAuthTokenService:142）。
            //   默认 update(entity) 会**跳过 null 字段** ⇒ 用户点「清空某项」后库里仍是旧值，
            //   界面下次加载又显示回来，看起来像「清空按钮没用」。2026-11-04 真机踩到。
            mapper.updateByQuery(row, false,
                    QueryWrapper.create().eq("runtime", rt));
        }
        // 写库后立刻刷新静态快照：保证「页面已保存」= 「下一次派发就用新值」
        reload();
        return get(rt);
    }

    /**
     * 记录一次「测试连接」的结果（供页面显示上次测过的时间/成败）。
     *
     * <p>⛔ 不改变任何配置项 —— 测试只是观测，不该顺手动别人的设置。
     */
    public void recordTestResult(String runtime, boolean ok, String message) {
        String rt = normalize(runtime);
        try {
            CliRuntimeConfigRecord row = mapper.selectOneByQuery(
                    QueryWrapper.create().eq("runtime", rt));
            if (row == null) {
                // 从没配过该运行时：不为了记一条测试结果而凭空建行（会让「未配置」变成「已配置」）
                return;
            }
            row.setLastTestOk(ok);
            row.setLastTestMessage(message == null ? null
                    : (message.length() > 500 ? message.substring(0, 500) : message));
            row.setLastTestedAt(OffsetDateTime.now().toString());
            row.setUpdatedAt(row.getUpdatedAt());
            // 同样要 ignoreNulls=false：测试通过时 last_test_message 应被清掉，
            // 否则旧的失败原因会一直挂着
            mapper.updateByQuery(row, false,
                    QueryWrapper.create().eq("runtime", rt));
        } catch (Exception e) {
            log.warn("[CliRuntimeConfig] 记录 runtime={} 的测试结果失败: {}", rt, e.toString());
        }
    }

    private static String normalize(String runtime) {
        if (runtime == null || runtime.isBlank()) {
            throw new IllegalArgumentException("runtime 不能为空");
        }
        return runtime.trim();
    }

    private static String emptyToNull(String v) {
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 对外视图 · ⛔ 只有掩码，没有明文 key。
     *
     * @param configDir  配置目录（可回显，非敏感）
     * @param baseUrl    API base URL（可回显，非敏感）
     * @param secrets    变量名 → 掩码（如 {@code ANTHROPIC_AUTH_TOKEN: "sk-****d23d"}）
     * @param apiKeySet  是否已存有任一 key
     * @param bin        自定义可执行路径
     * @param model      选定模型
     * @param reasoning  推理强度
     */
    public record View(String runtime, String configDir, String baseUrl,
                       Map<String, String> secrets, boolean apiKeySet,
                       String bin, String model, String reasoning, String updatedAt) {
    }

    /** 保存请求 · 各项 null = 不改，空串 = 清空；{@code secrets} null = 不改。 */
    public record SaveRequest(String configDir, String baseUrl, String bin, String model,
                              String reasoning, Map<String, String> secrets) {
    }
}
