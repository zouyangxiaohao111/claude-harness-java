package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [r10b · D11] {@code UserContextProvider} 的 projectRoot 改在<b>调用点</b>解析后形参下传。
 *
 * <p><b>WHY（守护什么）</b>：{@code PartialCompactService:851} 与 {@code ToolRegistrationConfig:3020}
 * 两处相邻的 provider 构造（UserContextProvider + GitStatusProvider）本该同形 —— D1/D2 已把
 * git 侧的 cwd 提到调用点解析，本项把 user 侧对齐。真正被钉住的不变量是
 * <b>「扫描根 = getOriginalCwdLayer 语义（会话存档锚），不是进程 user.dir」</b>：
 * 用 {@code user.dir} 顶替会让会话绑定项目的 CLAUDE.md 进不了 system prompt（用户可见回归）。
 *
 * <p>⚠️ <b>照实声明</b>：本项是<b>形态统一</b>，不是缺陷修复 —— 4 参构造的 projectRoot 与
 * 被替换的 2 参构造内部解析<b>同值同时机</b>（都在构造期），故不存在「改造前后行为变化」的
 * 反向实验；本类守住的是「值必须是 getOriginalCwdLayer 的那一个」。
 *
 * <p><b>反向实验配方</b>：把下面 {@code Path.of(CwdResolution.getOriginalCwdLayer(sid))}
 * 换成 {@code Path.of(System.getProperty("user.dir"))} ⇒ claudeMd 变成进程目录的内容 ⇒ 红。
 */
@DisplayName("[r10b-D11] UserContextProvider projectRoot：调用点解析 = getOriginalCwdLayer 语义")
class UserContextProviderProjectRootTest {

    private static final String SESSION = "sess-r10b-d11";

    private String savedUserDir;

    @BeforeEach
    void setup() {
        savedUserDir = System.getProperty("user.dir");
        // 显式覆盖全局默认：答「DB 明确答无此会话」⇒ 会话层全 MISS 的读取必抛（可证伪）
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

    @Test
    @DisplayName("D11 调用点解析的 projectRoot 必须取会话 originalCwd 层（非进程 user.dir）")
    void callSiteResolvedProjectRoot_usesOriginalCwdLayer(@TempDir Path tmp) throws Exception {
        Path sessionAnchor = Files.createDirectories(tmp.resolve("session-anchor"));
        Files.writeString(sessionAnchor.resolve("CLAUDE.md"), "SESSION-ANCHOR-CLAUDE-MD");
        Path processDir = Files.createDirectories(tmp.resolve("process-launch-dir"));
        Files.writeString(processDir.resolve("CLAUDE.md"), "PROCESS-DIR-CLAUDE-MD");

        System.setProperty("user.dir", processDir.toString());
        // 会话 originalCwd 重锚层（worktree 入口 / 启动锚，对齐 CC STATE.originalCwd）
        SessionCwdHolder.setOriginalCwd(SESSION, sessionAnchor.toString());

        // ── D11 的目标形态：projectRoot 在调用点解析 ⇒ 4 参构造形参下传 ──
        Path projectRoot = Path.of(CwdResolution.getOriginalCwdLayer(SESSION));
        UserContextProvider d11 = new UserContextProvider(
            projectRoot, System::getenv, null, SESSION);

        assertThat(d11.claudeMd())
            .as("⭐ 扫描根 = 会话 originalCwd 层（不是进程 user.dir）")
            .isEqualTo("SESSION-ANCHOR-CLAUDE-MD");

        // ── 等价性证据：与「被替换的 2 参形态」逐字段同值（本项属形态统一，非行为变更）──
        UserContextProvider legacyForm = new UserContextProvider(null, SESSION);
        assertThat(d11.claudeMd())
            .as("D11 形态与被替换的 2 参构造同值（形参下传不改值）")
            .isEqualTo(legacyForm.claudeMd());
    }
}
