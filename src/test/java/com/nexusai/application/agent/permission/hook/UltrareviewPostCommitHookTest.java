package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.UltrareviewQuota;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ultrareview post-commit 提示 hook · 对齐 claude.exe v2.1.325
 * {@code registerUltrareviewPostCommitHook}（off≈366,376,5xx）的判别性与门控。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：这条 hook 是 CC 唯一与 code review 相关的内置
 * 钩子，但它<b>只提示、不审查</b>。测试要证明三件事，而不是仅证明"返回了字符串"：
 * <ol>
 *   <li>误报边界：非 git commit、无 commit 输出标记、非 Bash/PowerShell 都不提示
 *       （否则每条命令都会刷屏）；</li>
 *   <li>作用域：子代理不提示（CC {@code agent_id !== undefined} 直接返回 {}）；</li>
 *   <li>门控与配额：特性关 / 已跑过 / lifetime ≥5 / 本启动已显示 都不提示；
 *       quota 剩余 1 用单数 review、其余用复数 reviews。</li>
 * </ol>
 */
class UltrareviewPostCommitHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 真实 commit 输出片段（`[main abc1234] msg` + 统计行）。 */
    private static final String COMMIT_OUTPUT =
        "[main abc1234] feat: add thing\n 3 files changed, 10 insertions(+), 2 deletions(-)\n";

    private final Map<String, PostToolUseHook> registered = new LinkedHashMap<>();

    /** 记录注册结果 · 等价 HookRegistry.registerPostToolUse 的最小桩。 */
    private final SessionFileAccessHooks.PostToolUseRegistrar registrar =
        new SessionFileAccessHooks.PostToolUseRegistrar() {
            @Override
            public void registerPostToolUse(String name, PostToolUseHook hook) {
                registered.put(name, hook);
            }
        };

    private BooleanSupplier enabled;
    private BooleanSupplier hasRun;
    private Supplier<UltrareviewQuota.UltrareviewQuotaResponse> quota;

    @BeforeEach
    void setUp() {
        UltrareviewPostCommitHook.resetProcessStateForTest();
        registered.clear();
        enabled = () -> true;
        hasRun = () -> false;
        quota = () -> null;
    }

    private UltrareviewPostCommitHook newHook() {
        return new UltrareviewPostCommitHook(enabled, hasRun, quota, null);
    }

    private List<String> run(String toolName, String command, String output, ToolUseContext ctx) {
        newHook().register(registrar);
        PostToolUseHook hook = registered.get("ultrareviewPostCommit:" + toolName);
        assertThat(hook).as("hook 应已注册: %s", toolName).isNotNull();
        ObjectNode input = MAPPER.createObjectNode();
        if (command != null) {
            input.put("command", command);
        }
        GenericHook.HookResult r = hook.onPostToolUse(toolName, input,
            ToolResult.success("tu-1", output), ctx, false);
        return r.systemMessages() == null ? List.of() : r.systemMessages();
    }

    private static ToolUseContext mainThreadCtx() {
        return ToolUseContext.of(null, "sess-review-hook", PermissionMode.DEFAULT, List.of());
    }

    private static ToolUseContext subagentCtx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-review-hook", PermissionMode.DEFAULT, List.of());
    }

    @Test
    @DisplayName("注册 Bash 与 PowerShell 两个 matcher")
    void registersBashAndPowerShell() {
        newHook().register(registrar);
        assertThat(registered).containsOnlyKeys(
            "ultrareviewPostCommit:" + ToolNameConstants.BASH_TOOL_NAME,
            "ultrareviewPostCommit:" + ToolNameConstants.POWER_SHELL_TOOL_NAME);
    }

    @Test
    @DisplayName("git commit + commit 输出标记 → 返回 CC 原文提示")
    void commitTip() {
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m \"feat: add thing\"", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(msgs).containsExactly(UltrareviewPostCommitHook.TIP);
    }

    @Test
    @DisplayName("git commit 的各种前置参数写法（-C / -c / --flag）都命中")
    void commitCommandVariants() {
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git -C /repo commit -m x")).isTrue();
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git -c user.name=a commit -m x")).isTrue();
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git --no-pager commit -m x")).isTrue();
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git commit --amend --no-edit")).isTrue();
    }

    @Test
    @DisplayName("非 commit 命令不提示（含 git status / git commit 子串陷阱）")
    void nonCommitCommands() {
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git status")).isFalse();
        assertThat(UltrareviewPostCommitHook.isCommitCommand("echo git committed")).isFalse();
        assertThat(UltrareviewPostCommitHook.isCommitCommand("git log --grep=commit")).isFalse();
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git status", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("command 缺失（PowerShell 非法入参）不提示")
    void missingCommand() {
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME, null, COMMIT_OUTPUT, mainThreadCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("commit 输出无标记（如 git commit -m 但输出被截断）不提示")
    void noCommitOutputMarker() {
        assertThat(UltrareviewPostCommitHook.hasCommitOutput("fatal: nothing to commit")).isFalse();
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", "fatal: nothing to commit, working tree clean", mainThreadCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("输出标记各分支（create mode / files changed / insertions / deletions）")
    void outputMarkerVariants() {
        assertThat(UltrareviewPostCommitHook.hasCommitOutput(" create mode 100644 a.txt")).isTrue();
        assertThat(UltrareviewPostCommitHook.hasCommitOutput(" 1 file changed, 1 insertion(+)")).isTrue();
        assertThat(UltrareviewPostCommitHook.hasCommitOutput(" 3 deletions(-)")).isTrue();
        assertThat(UltrareviewPostCommitHook.hasCommitOutput("[main abc] msg")).isTrue();
    }

    @Test
    @DisplayName("子代理不提示（CC agent_id 已定义直接跳过）")
    void subagentSkips() {
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, subagentCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("特性门控关闭 → 不提示")
    void disabledFeature() {
        enabled = () -> false;
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("已跑过 ultrareview → 不提示")
    void alreadyRanUltrareview() {
        hasRun = () -> true;
        List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(msgs).isEmpty();
    }

    @Test
    @DisplayName("quota=1 → 单数 review；quota=3 → 复数 reviews")
    void quotaSuffix() {
        quota = () -> new UltrareviewQuota.UltrareviewQuotaResponse(0, 1, 1, false);
        List<String> one = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(one).containsExactly(UltrareviewPostCommitHook.TIP + " — 1 free review left");

        UltrareviewPostCommitHook.resetProcessStateForTest();
        quota = () -> new UltrareviewQuota.UltrareviewQuotaResponse(0, 3, 3, false);
        List<String> three = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(three).containsExactly(UltrareviewPostCommitHook.TIP + " — 3 free reviews left");
    }

    @Test
    @DisplayName("同一次启动只显示一次（CC gW(tipId)===0 语义）")
    void oncePerStartup() {
        List<String> first = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m x", COMMIT_OUTPUT, mainThreadCtx());
        List<String> second = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m y", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("lifetime 上限 5 次（CC b=5）")
    void lifetimeCap() {
        for (int i = 0; i < UltrareviewPostCommitHook.LIFETIME_SHOWS; i++) {
            // 每次模拟一个新的 startup：清空"本次启动已显示"，保留 lifetime 计数
            UltrareviewPostCommitHook.clearStartupHistoryForTest();
            List<String> msgs = run(ToolNameConstants.BASH_TOOL_NAME,
                "git commit -m " + i, COMMIT_OUTPUT, mainThreadCtx());
            assertThat(msgs).as("第 %s 次应提示", i + 1).hasSize(1);
        }
        UltrareviewPostCommitHook.clearStartupHistoryForTest();
        List<String> sixth = run(ToolNameConstants.BASH_TOOL_NAME,
            "git commit -m 6", COMMIT_OUTPUT, mainThreadCtx());
        assertThat(sixth).isEmpty();
        assertThat(UltrareviewPostCommitHook.lifetimeShown())
            .isEqualTo(UltrareviewPostCommitHook.LIFETIME_SHOWS);
    }
}
