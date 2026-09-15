package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.compact.PartialCompactService;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [r10b · D1/D2 · 裁定 #10 第二域] /compact 两条路径的 git 上下文 provider 必须锚<b>会话 cwd</b>。
 *
 * <p><b>WHY（守护什么）</b>：D1（{@code PartialCompactService.buildPartialSystemPromptCtxProvider}）
 * 与 D2（{@code ToolRegistrationConfig.buildManualSystemPromptCtxProvider}）原都写
 * {@code new GitStatusProvider()}（无参 ⇒ 显式「无会话」命名出口 = 进程 {@code user.dir}），
 * 而两处调用方**手里都有会话**（{@code state.sessionId()}）⇒ 按裁定 #10 口径属「有会话却走
 * 无会话出口」的真缺陷：静默把 A 会话的 git 快照（乃至后端启动目录的 git 快照）注入 B 会话。
 *
 * <p><b>装置（P0-2 锚点夹具同款）</b>：会话 cwd = 临时目录 A（<b>非</b> git 仓库）；
 * 进程 {@code user.dir} = 临时目录 B（<b>是</b> git 仓库）。断言这两条路径产出的
 * provider <b>不含</b> {@code gitStatus}（= 锚 A）。
 *
 * <p><b>反向实验配方</b>：把对应行改回 {@code new GitStatusProvider()} ⇒ 锚 B（git）⇒
 * {@code getSystemContext()} 含 {@code gitStatus} ⇒ 本类对应用例红。
 *
 * <p>⚠️ 用反射直调私有方法：两条路径的真实入口需要 Spring 上下文（REST / compact 编排），
 * 本类只钉住「provider 构造点的 cwd 来源」这一处，不覆盖整条编排链。
 */
@DisplayName("[r10b-D1/D2] /compact 上下文 provider 的 git 锚点 = 会话 cwd")
class CompactCtxProviderGitAnchorTest {

    private String savedUserDir;

    @BeforeEach
    void setup() {
        savedUserDir = System.getProperty("user.dir");
        // 显式覆盖全局默认（sessionless 不抛）⇒ 会话层未命中即抛，使「值来自会话」可证伪
        SessionProjectRoot.setDbResolver(s -> SessionProjectRoot.Lookup.unknown());
    }

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        } else {
            System.clearProperty("user.dir");
        }
    }

    /** 造「会话 cwd 非 git / 进程 user.dir 是 git」的对立装置，返回会话目录。 */
    private Path armAnchorFixture(Path tmp, String sessionId) throws Exception {
        Path sessionDir = Files.createDirectories(tmp.resolve("session-project"));
        Path processDir = Files.createDirectories(tmp.resolve("process-launch-dir"));
        Files.createDirectory(processDir.resolve(".git"));
        assertThat(new GitStatusProvider(sessionDir).findGitRoot())
            .as("夹具前置失败：会话临时目录落在 git 仓库内 ⇒ 锚点不可分辨，须停下报告")
            .isNull();
        System.setProperty("user.dir", processDir.toString());
        SessionCwdHolder.set(sessionId, sessionDir.toString());
        // AgentState 构造期还会解析 originalCwd（否则本类装的 unknown() 解析器会 fail-loud 抛），
        // 本用例不区分两槽 ⇒ 同值填上，保持夹具最小。
        SessionCwdHolder.setOriginalCwd(sessionId, sessionDir.toString());
        return sessionDir;
    }

    @Test
    @DisplayName("D1 PartialCompactService：会话非 git ⇒ 无 gitStatus（原无参构造会锚 user.dir=git）")
    void d1_partialCompactCtxProvider_anchorsSessionCwd(@TempDir Path tmp) throws Exception {
        String sid = "sess-r10b-d1";
        armAnchorFixture(tmp, sid);
        AgentState state = new AgentState("sys", sid, null);

        PartialCompactService service = new PartialCompactService(null, null, null);
        Method m = PartialCompactService.class.getDeclaredMethod(
            "buildPartialSystemPromptCtxProvider", AgentState.class);
        m.setAccessible(true);
        SystemPromptContextProvider provider = (SystemPromptContextProvider) m.invoke(service, state);
        try {
            assertThat(provider.getSystemContext())
                .as("⭐ D1：会话 cwd 非 git ⇒ 无 gitStatus 行（无参构造会锚进程 user.dir=B 而含 gitStatus）")
                .doesNotContainKey("gitStatus");
        } finally {
            provider.close();
        }
    }

    @Test
    @DisplayName("D2 ToolRegistrationConfig：会话非 git ⇒ 无 gitStatus（manual /compact 路径）")
    void d2_manualCompactCtxProvider_anchorsSessionCwd(@TempDir Path tmp) throws Exception {
        String sid = "sess-r10b-d2";
        armAnchorFixture(tmp, sid);
        AgentState state = new AgentState("sys", sid, null);

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        Method m = ToolRegistrationConfig.class.getDeclaredMethod(
            "buildManualSystemPromptCtxProvider", AgentState.class,
            com.nexusai.application.agent.context.ClaudemdEngine.class);
        m.setAccessible(true);
        SystemPromptContextProvider provider =
            (SystemPromptContextProvider) m.invoke(config, state, null);
        try {
            assertThat(provider.getSystemContext())
                .as("⭐ D2：会话 cwd 非 git ⇒ 无 gitStatus 行（无参构造会锚进程 user.dir=B 而含 gitStatus）")
                .doesNotContainKey("gitStatus");
        } finally {
            provider.close();
        }
    }

    /**
     * 正实验（对照）：同一装置下会话 cwd <b>是</b> git 仓库 ⇒ 含 {@code gitStatus}。
     *
     * <p>WHY：没有它，「恒不产出 gitStatus」这种坏实现也会让 D1/D2 两条用例绿
     * （本仓已登记「声称守护 X、实际守不住」的失效模式）。
     */
    @Test
    @DisplayName("D1/D2 对照：会话 cwd 是 git 仓库 ⇒ 含 gitStatus（证上面两条不是「恒无 gitStatus」）")
    void control_sessionCwdIsGitRepo_producesGitStatus(@TempDir Path tmp) throws Exception {
        Path sessionDir = Files.createDirectories(tmp.resolve("session-git-project"));
        Files.createDirectory(sessionDir.resolve(".git"));
        Path processDir = Files.createDirectories(tmp.resolve("process-nongit"));
        System.setProperty("user.dir", processDir.toString());
        String sid = "sess-r10b-d1-ctrl";
        SessionCwdHolder.set(sid, sessionDir.toString());
        SessionCwdHolder.setOriginalCwd(sid, sessionDir.toString());
        AgentState state = new AgentState("sys", sid, null);

        PartialCompactService service = new PartialCompactService(null, null, null);
        Method m = PartialCompactService.class.getDeclaredMethod(
            "buildPartialSystemPromptCtxProvider", AgentState.class);
        m.setAccessible(true);
        SystemPromptContextProvider provider = (SystemPromptContextProvider) m.invoke(service, state);
        try {
            assertThat(provider.getSystemContext())
                .as("会话 cwd 是 git 仓库 ⇒ 产出 gitStatus（装置可产出，前两条断言才有意义）")
                .containsKey("gitStatus");
        } finally {
            provider.close();
        }
    }

    /** 顺带钉住 D1/D2 用的就是「getCwd」而不是「getOriginalCwdLayer」（DIVERGED 会话下可分辨）。 */
    @Test
    @DisplayName("D1/D2 槽语义：锚点取 getCwd（cd 后目录），不是 getOriginalCwdLayer")
    void d1d2_useGetCwdSemantics_notOriginalCwdLayer(@TempDir Path tmp) throws Exception {
        Path sessionCwd = Files.createDirectories(tmp.resolve("cd-target-git"));
        Files.createDirectory(sessionCwd.resolve(".git"));
        Path originalAnchor = Files.createDirectories(tmp.resolve("orig-anchor-nongit"));
        assertThat(new GitStatusProvider(originalAnchor).findGitRoot())
            .as("夹具前置：originalCwd 侧必须不在任何 git 仓库内")
            .isNull();
        String sid = "sess-r10b-d1-sem";
        SessionCwdHolder.set(sid, sessionCwd.toString());
        SessionCwdHolder.setOriginalCwd(sid, originalAnchor.toString());
        assertThat(CwdResolution.getCwd(sid))
            .as("DIVERGED 夹具活性：两槽不同值")
            .isNotEqualTo(CwdResolution.getOriginalCwdLayer(sid));

        AgentState state = new AgentState("sys", sid, null);
        PartialCompactService service = new PartialCompactService(null, null, null);
        Method m = PartialCompactService.class.getDeclaredMethod(
            "buildPartialSystemPromptCtxProvider", AgentState.class);
        m.setAccessible(true);
        SystemPromptContextProvider provider = (SystemPromptContextProvider) m.invoke(service, state);
        try {
            assertThat(provider.getSystemContext())
                .as("⭐ 锚点 = getCwd（cd 后的 git 目录）⇒ 有 gitStatus；若误用 originalCwd 则无")
                .containsKey("gitStatus");
        } finally {
            provider.close();
        }
    }
}
