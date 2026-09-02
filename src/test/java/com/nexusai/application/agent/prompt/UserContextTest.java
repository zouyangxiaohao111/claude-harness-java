package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserContextProvider 意图测试 · 对齐 CC {@code getUserContext} 的 claudeMd 侧
 * （claudemd.ts:1153-1195）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：currentDate 会话冻结（I-10，跨午夜不陈旧）是 prompt
 * cache-key 稳定性的根基；claudeMd 门控决定 user 通道注入。测试钉死这些契约，防止回归到
 * "实时取日期"或"context 拼进 system 通道"。
 *
 * <p>FIX-CL 已删本类 prependUserContext（第三套并行实现，仅测试调用）—— 前置渲染生产唯一
 * 实现为 {@code AgentLoopContext.prependUserContext}（LlmAgentLoopChainTest 覆盖）。
 */
class UserContextProviderTest {

    @TempDir
    Path tmp;

    // ── currentDate 会话冻结（I-10）──

    @Test
    @DisplayName("currentDate 格式：Today's date is <会话冻结 ISO>.（context.ts:186）")
    void currentDate_formatsFrozenDate() {
        UserContextProvider p = new UserContextProvider(tmp);

        assertThat(p.currentDate("2026-08-05")).isEqualTo("Today's date is 2026-08-05.");
    }

    @Test
    @DisplayName("currentDate 使用会话冻结值，不读实时时钟 —— 冻结日期跨午夜后仍不变（I-10，common.ts:17-24 注释语义）")
    void currentDate_frozenAcrossMidnight() {
        UserContextProvider p = new UserContextProvider(tmp);
        String frozen = "2026-08-04"; // 冻结于 8-04 凌晨，当前真实日期已是 8-05

        assertThat(p.currentDate(frozen))
            .as("I-10：currentDate 只依赖会话冻结日期，不受实时时钟推进影响")
            .isEqualTo("Today's date is 2026-08-04.");
    }

    // ── claudeMd：读取 + 门控 ──

    @Test
    @DisplayName("claudeMd：项目根 CLAUDE.md → trim 后内容（等价 CC getClaudeMds 单主文件子集，concern #4）")
    void claudeMd_readsRootTrimmed() throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), "  第一行指令\n\n第二行指令\n  ");
        UserContextProvider p = new UserContextProvider(tmp);

        assertThat(p.claudeMd()).as("trim 后完整内容").isEqualTo("第一行指令\n\n第二行指令");
    }

    @Test
    @DisplayName("claudeMd 缺失 → null（不注入 user 通道）")
    void claudeMd_missing_returnsNull() {
        UserContextProvider p = new UserContextProvider(tmp);

        assertThat(p.claudeMd()).isNull();
    }

    @Test
    @DisplayName("claudeMd 禁用门控：CLAUDE_CODE_DISABLE_CLAUDE_MDS truthy → null（context.ts:165-166 硬关）")
    void claudeMd_disabledByEnv_returnsNull() throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), "有内容");
        UserContextProvider p = new UserContextProvider(tmp, key ->
            key.equals("CLAUDE_CODE_DISABLE_CLAUDE_MDS") ? "1" : null);

        assertThat(p.claudeMd()).as("禁用 env → 即便文件存在也 null").isNull();
    }


    @Test
    @DisplayName("bare 模式 → claudeMd 恒 null（SP-07 △-2：CC context.ts:165-167 isBareMode && 无 add-dir → null；Java 无 --add-dir 通道 → isBareMode 即抑制）")
    void claudeMd_bareMode_returnsNull() throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), "有内容");
        UserContextProvider p = new UserContextProvider(tmp);
        p.setBareModeSupplier(() -> true);

        assertThat(p.claudeMd()).as("bare=true → 即便文件存在也 null").isNull();
    }

    @Test
    @DisplayName("守卫：非 bare 模式 → claudeMd 正常返回内容（bare 门控不误伤默认路径）")
    void claudeMd_nonBare_returnsContent() throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), "有内容");
        UserContextProvider p = new UserContextProvider(tmp);
        p.setBareModeSupplier(() -> false);

        assertThat(p.claudeMd()).as("bare=false → 正常返回").isEqualTo("有内容");
    }

    // ── cwd-align-extended 方案1：默认 projectRoot 接线 CwdResolution.getOriginalCwdLayer ──

    @Test
    @DisplayName("默认 projectRoot = 会话 originalCwd 层（对齐 CC getOriginalCwd claudemd.ts:851）：会话绑 P → claudeMd 从 P 读，非恒 user.dir")
    void defaultProjectRoot_usesSessionOriginalCwdLayer() throws Exception {
        // WHY (cwd-align-extended prompt A1/A3 · 对齐 CC getMemoryFiles 用 getOriginalCwd() 作
        // CLAUDE.md 向上 walk 起点 claudemd.ts:851): 旧实现 Paths.get("") 恒进程 user.dir，未捕获会话
        // worktree 重锚。接线 CwdResolution.getOriginalCwdLayer(sessionId) 后，会话绑 P 时 fallback
        // 单文件 CLAUDE.md 应从会话 originalCwd 层 P 读（worktree 重锚跟随），非恒 user.dir。
        Files.writeString(tmp.resolve("CLAUDE.md"), "会话根指令");
        String sid = "sess-ucp-wt1";
        try {
            RequestContext.setSession(sid);
            SessionProjectRoot.setForSession(sid, tmp.toString());
            UserContextProvider p = new UserContextProvider(); // 无参 → getOriginalCwdLayer(sessionId)

            assertThat(p.claudeMd()).as("会话绑 P → fallback CLAUDE.md 从会话 originalCwd 层 P 读取")
                .isEqualTo("会话根指令");
        } finally {
            RequestContext.clear();
            SessionProjectRoot.reset();
            SessionCwdHolder.reset();
        }
    }

    @Test
    @DisplayName("默认 projectRoot 无会话回落 user.dir（零行为变化）：projectRoot 字段 = CwdResolution.getOriginalCwdLayer(null)")
    void defaultProjectRoot_fallsBackUserDir_whenNoSession() throws Exception {
        // WHY (cwd-align-extended 零行为变化红线 · 对齐 CC getOriginalCwd 末端 user.dir):
        // 无会话上下文（startup / 无 sessionId 通道 / 测试）构造时 RequestContext.sessionId()=null →
        // getOriginalCwdLayer(null) 回落 user.dir，与旧实现 Paths.get("").toAbsolutePath()（=JVM user.dir）
        // 等价 → 接线零回归。测试锁定 projectRoot 字段值 = CwdResolution.getOriginalCwdLayer(null)。
        UserContextProvider p = new UserContextProvider(); // 无会话 → 回落 user.dir

        Path projectRoot = readProjectRootField(p);
        assertThat(projectRoot.toString()).as("无会话 → projectRoot = user.dir（realpath 归一，零行为变化）")
            .isEqualTo(CwdResolution.getOriginalCwdLayer(null));
    }

    /** 反射读取私有 {@code projectRoot} 字段（结构守卫，模式同 SubagentEnvInfoTest 反射读取）。 */
    private static Path readProjectRootField(UserContextProvider p) throws Exception {
        java.lang.reflect.Field f = UserContextProvider.class.getDeclaredField("projectRoot");
        f.setAccessible(true);
        return (Path) f.get(p);
    }
}
