package com.nexusai.repository.subagent.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * {@code cli_runtime_config} 表行 · 外部 CLI 运行时的配置（每个 runtime 一行）。
 *
 * <p><b>为什么连接信息不进 md</b>（用户 2026-11-04 拍板）：
 * md 会进版本库，密钥写进去就是明文外泄；而且每加一个 CLI 都要用户自己去查该 CLI 认哪个
 * 环境变量名。⇒ 统一放这张表 + 页面配置，md 只留 {@code sub-runtime: claude}。
 *
 * <p><b>语义</b>：每列独立，空 = 该项用 CLI 自己的默认。整行都空 ⇒ 不注入任何环境变量。
 *
 * <p>⛔ {@code apiKeyEncrypted} / {@code secretsJson} 是 AES/GCM 密文；明文只在进程内解密后使用。
 */
@Table("cli_runtime_config")
public class CliRuntimeConfigRecord {
    @Id private String runtime;
    private String configDir;
    private String baseUrl;
    /** 旧单 key 列（V261）。保留读兼容；新写入走 {@link #secretsJson}。 */
    private String apiKeyEncrypted;
    private String apiKeyMasked;
    private String apiKeyHash;
    /** 多 key（JSON：{@code {"CODEX_API_KEY":"sk-..."}}），AES/GCM 密文。V262 新增。 */
    private String secretsJson;
    /** 自定义可执行文件绝对路径。V262 新增。 */
    private String bin;
    /** 选定模型 id。V262 新增。 */
    private String model;
    /** 推理强度 id。V262 新增。 */
    private String reasoning;
    /** 上次「测试连接」的结果（V262 新增）。 */
    private Boolean lastTestOk;
    private String lastTestedAt;
    private String lastTestMessage;
    private String updatedAt;

    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }

    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }

    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }

    public String getSecretsJson() { return secretsJson; }
    public void setSecretsJson(String secretsJson) { this.secretsJson = secretsJson; }

    public String getBin() { return bin; }
    public void setBin(String bin) { this.bin = bin; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public Boolean getLastTestOk() { return lastTestOk; }
    public void setLastTestOk(Boolean lastTestOk) { this.lastTestOk = lastTestOk; }

    public String getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(String lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
