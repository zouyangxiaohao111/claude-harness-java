package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.compact.CompactionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OD-01 S4（B9）· SM 压缩双 flag 门控：tengu_session_memory && tengu_sm_compact AND。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC {@code shouldUseSessionMemoryCompaction()}
 * （sessionMemoryCompact.ts:403-432）双 flag AND —— 单 flag 置 true 不应启用 SM 压缩，
 * 双 true 才启用；env ENABLE/DISABLE_CLAUDE_CODE_SM_COMPACT 覆盖优先（:404-409）。
 * OD-10 遗留单字段 smCompactFeatureEnabled 会绕过 tengu_session_memory 半边（S4 B9 修正）。
 */
@DisplayName("[OD-01 S4] shouldUseSessionMemoryCompaction 双 flag AND + env override（B9）")
class SessionMemoryCompactGateTest {

    @TempDir
    Path baseDir;

    @Test
    @DisplayName("双 false → false（默认全关）")
    void bothFalse_isFalse() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        assertThat(svc.shouldUseSessionMemoryCompaction()).isFalse();
    }

    @Test
    @DisplayName("仅 smSessionMemory=true → false（tengu_sm_compact 半边缺失）")
    void onlySmSessionMemory_isFalse() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSmSessionMemoryEnabled(true);
        assertThat(svc.shouldUseSessionMemoryCompaction())
            .as("tengu_session_memory=true 但 tengu_sm_compact=false → AND=false")
            .isFalse();
    }

    @Test
    @DisplayName("仅 smCompact=true → false（tengu_session_memory 半边缺失）")
    void onlySmCompact_isFalse() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSmCompactEnabled(true);
        assertThat(svc.shouldUseSessionMemoryCompaction())
            .as("tengu_sm_compact=true 但 tengu_session_memory=false → AND=false")
            .isFalse();
    }

    @Test
    @DisplayName("双 true → true（tengu_session_memory && tengu_sm_compact AND · sessionMemoryCompact.ts:412-420）")
    void bothTrue_isTrue() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        assertThat(svc.shouldUseSessionMemoryCompaction()).isTrue();
    }

    @Test
    @DisplayName("env ENABLE_CLAUDE_CODE_SM_COMPACT truthy → true（覆盖优先 · sessionMemoryCompact.ts:404-406）")
    void enableEnvOverride_true() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setEnvProvider(k -> "ENABLE_CLAUDE_CODE_SM_COMPACT".equals(k) ? "1" : null);
        assertThat(svc.shouldUseSessionMemoryCompaction())
            .as("ENABLE 覆盖优先，即便双 flag 均 false")
            .isTrue();
    }

    @Test
    @DisplayName("env DISABLE_CLAUDE_CODE_SM_COMPACT truthy → false（覆盖优先 · sessionMemoryCompact.ts:407-409）")
    void disableEnvOverride_false() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        svc.setEnvProvider(k -> "DISABLE_CLAUDE_CODE_SM_COMPACT".equals(k) ? "true" : null);
        assertThat(svc.shouldUseSessionMemoryCompaction())
            .as("DISABLE 覆盖优先，即便双 flag 均 true")
            .isFalse();
    }

    @Test
    @DisplayName("空串 memory 文件 → 回落 legacy（!sessionMemory falsy · sessionMemoryCompact.ts:533）")
    void emptyMemoryFile_fallsBackToLegacy() throws Exception {
        Path dir = baseDir.resolve("s1").resolve("session-memory");
        Files.createDirectories(dir);
        // 文件被手工清空：读成功返回 ""（CC getSessionMemoryContent 读成功返回原样）
        Files.writeString(dir.resolve("summary.md"), "");

        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        CompactionResult r = svc.trySessionMemoryCompaction(
            List.of(), "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r)
            .as("空串是 falsy（!sessionMemory）→ 与 null 同分支回落 legacy，不产空摘要 SM 压缩")
            .isNull();
    }
}
