package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.memory.AutoMemPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [memory-cc B1] resolveAll 会话上下文快照测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9)</b>：resolveAll 用 CompletableFuture.supplyAsync（ForkJoinPool）并行
 * compute；worker 线程无 AutoMemPaths ThreadLocal projectRoot → dynamic 'memory' section 恒判无项目
 * （用户日志 auto-only ERROR）。修复 = 请求线程快照 projectRoot → worker 内 set/restore。变异点：
 * <ul>
 *   <li>修复前 worker 读不到快照 projectRoot → 结果回落 env/config-home（≠ 会话项目）→ 红</li>
 *   <li>restore 漏 → worker 残留上一会话 projectRoot → 下一无会话 resolveAll 串台 → 红</li>
 * </ul>
 */
@DisplayName("[memory-cc B1] SystemPromptSectionRegistry.resolveAll 会话 projectRoot 快照进 ForkJoin worker")
class SystemPromptSectionRegistryThreadContextTest {

    /** 注册一个 compute 直接读 AutoMemPaths.currentSessionProjectRoot() 的 section（仿 dynamic memory section 依赖）。 */
    private static SystemPromptSectionRegistry ctxRegistry() {
        SystemPromptSectionRegistry reg = new SystemPromptSectionRegistry();
        reg.register(SystemPromptSections.systemPromptSection("ctx", () ->
            CompletableFuture.completedFuture(AutoMemPaths.currentSessionProjectRoot())));
        return reg;
    }

    @Test
    @DisplayName("请求线程 set 会话 projectRoot → resolveAll 并行 worker 内读到同一 projectRoot（修复前 ForkJoin 丢 ThreadLocal → 回落默认≠会话项目）")
    void worker_seesSnapshotProjectRoot() {
        AutoMemPaths.setCurrentProjectRoot("C:/proj-A");
        try {
            List<String> results = ctxRegistry().resolveAll(new SystemPromptSectionCache());
            assertThat(results).containsExactly("C:/proj-A");
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
        }
    }

    @Test
    @DisplayName("restore 不泄漏：快照会话 resolveAll 后清空再 resolveAll（无会话）→ 不带上一会话残留")
    void restore_doesNotLeakAcrossResolves() {
        SystemPromptSectionRegistry reg = ctxRegistry();
        AutoMemPaths.setCurrentProjectRoot("C:/proj-A");
        try {
            reg.resolveAll(new SystemPromptSectionCache());
        } finally {
            AutoMemPaths.resetCurrentProjectRoot(); // 模拟会话结束清 ThreadLocal
        }
        // 同一 registry（同一批 worker 池）再 resolveAll、无会话：若 worker 残留 proj-A → 红；restore 后回落默认
        List<String> results = reg.resolveAll(new SystemPromptSectionCache());
        assertThat(results).doesNotContain("C:/proj-A");
    }
}
