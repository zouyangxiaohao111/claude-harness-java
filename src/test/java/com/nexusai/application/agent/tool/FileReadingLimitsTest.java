package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [OPD-D1-01] FileReadingLimits 默认值解析 · 对齐 CC limits.ts:53-92
 * {@code getDefaultFileReadingLimits()} 优先级与防御校验。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * CC 的默认上限优先级是 {@code env(CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS) > GB(tengu_amber_wren) >
 * DEFAULT}，且 maxSizeBytes 无 env 层（仅 GB > DEFAULT）。本测试验证 Java
 * {@link FileReadingLimits#resolve(java.util.function.Supplier, java.util.function.Supplier, java.util.function.Supplier)}
 * 严格复刻该优先级——若优先级被写反（GB 压过 env）或非法值被透传成 cap=0，用户设的 env override 会被
 * 实验基础设施覆盖、或上限被击穿成无限，都是 CC 对齐契约事故（limits.ts:47-51 "No route to cap=0"）。
 */
@DisplayName("OPD-D1-01 · FileReadingLimits 默认值解析对齐 CC limits.ts:53-92")
class FileReadingLimitsTest {

    // ── resolveEnvMaxTokens：防御校验（CC limits.ts:24-33 getEnvMaxTokens）──

    @Test
    @DisplayName("env 解析：null/空/非数字/0/负 → null；正整数 → 值")
    void resolveEnvMaxTokensDefensive() {
        assertThat(FileReadingLimits.resolveEnvMaxTokens(null)).as("null → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("")).as("空串 → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("  ")).as("空白 → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("abc")).as("非数字 → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("0")).as("0 → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("-5")).as("负数 → undefined").isNull();
        assertThat(FileReadingLimits.resolveEnvMaxTokens("50000")).as("正整数 → 50000").isEqualTo(50000);
        assertThat(FileReadingLimits.resolveEnvMaxTokens(" 30000 ")).as("trim 后解析").isEqualTo(30000);
    }

    // ── resolveMaxTokens：env > GB > DEFAULT（CC limits.ts:67-74）──

    @Test
    @DisplayName("maxTokens 优先级：env override 压过 GB；无 env 时 GB；都缺省回 DEFAULT")
    void resolveMaxTokensPrecedence() {
        assertThat(FileReadingLimits.resolveMaxTokens(50000, 30000))
            .as("env=50000 必须压过 GB=30000").isEqualTo(50000);
        assertThat(FileReadingLimits.resolveMaxTokens(null, 30000))
            .as("无 env → GB=30000").isEqualTo(30000);
        assertThat(FileReadingLimits.resolveMaxTokens(null, null))
            .as("无 env/GB → DEFAULT 25000").isEqualTo(FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS);
        assertThat(FileReadingLimits.resolveMaxTokens(null, 0))
            .as("GB 非法(0) → DEFAULT（CC 无 cap=0 路径）").isEqualTo(FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS);
        assertThat(FileReadingLimits.resolveMaxTokens(0, null))
            .as("env 非法(0) → DEFAULT").isEqualTo(FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS);
    }

    // ── resolve(3 suppliers)：getDefaultFileReadingLimits 全量（env + GB maxTokens + GB maxSizeBytes）──

    @Test
    @DisplayName("默认解析：env maxTokens 生效 + GB maxSizeBytes 生效（CC maxSizeBytes 无 env 层）")
    void resolveAppliesEnvTokensAndGbBytes() {
        FileReadingLimits.Limits limits = FileReadingLimits.resolve(
            () -> "50000",   // env CLAUDE_CODE_FILE_READ_MAX_OUTPUT_TOKENS
            () -> 30000,     // GB tengu_amber_wren.maxTokens
            () -> 512 * 1024);  // GB tengu_amber_wren.maxSizeBytes

        assertThat(limits.maxTokens()).as("env=50000 压过 GB=30000").isEqualTo(50000);
        assertThat(limits.maxSizeBytes()).as("GB maxSizeBytes=512KB 生效（CC :60-65）").isEqualTo(512 * 1024);
    }

    @Test
    @DisplayName("默认解析：无 env 时 GB maxTokens 生效；GB 非法回落 DEFAULT")
    void resolveFallsBackToGbAndDefaults() {
        FileReadingLimits.Limits withGbTokens = FileReadingLimits.resolve(
            () -> null, () -> 30000, () -> null);
        assertThat(withGbTokens.maxTokens()).as("无 env → GB=30000").isEqualTo(30000);
        assertThat(withGbTokens.maxSizeBytes()).as("无 GB maxSizeBytes → DEFAULT 256KB").isEqualTo(256 * 1024);

        FileReadingLimits.Limits allDefault = FileReadingLimits.resolve(() -> null, () -> null, () -> null);
        assertThat(allDefault.maxTokens()).isEqualTo(FileReadingLimits.DEFAULT_MAX_OUTPUT_TOKENS);
        assertThat(allDefault.maxSizeBytes()).isEqualTo(FileReadingLimits.DEFAULT_MAX_SIZE_BYTES);

        FileReadingLimits.Limits gbInvalidBytes = FileReadingLimits.resolve(
            () -> null, () -> null, () -> 0);
        assertThat(gbInvalidBytes.maxSizeBytes()).as("GB maxSizeBytes=0 非法 → DEFAULT（CC :62-64 Number.isFinite && >0）")
            .isEqualTo(FileReadingLimits.DEFAULT_MAX_SIZE_BYTES);
    }

    @Test
    @DisplayName("Override record：null = 未覆写；值原样透传（CC Tool.ts:251 fileReadingLimits 无校验）")
    void overrideRecordPassThrough() {
        assertThat(new FileReadingLimits.Override(null, null).maxTokens()).isNull();
        assertThat(new FileReadingLimits.Override(50000, null).maxTokens()).isEqualTo(50000);
        assertThat(new FileReadingLimits.Override(null, 1024).maxSizeBytes()).isEqualTo(1024);
    }
}
