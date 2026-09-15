package com.nexusai.application.agent.permission.classifier;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [r10b · 裁定 ② · 裁定 #10 第二域] 分类器 CLAUDE.md 读源必须吃<b>会话</b>（而非进程 user.dir）。
 *
 * <p><b>WHY（守护什么）</b>：读源 bean 原为无参 {@code Supplier<String>}，其内部
 * {@code new UserContextProvider(engine)} 走「无会话」⇒ 扫描根 = 进程 {@code user.dir}
 * （后端启动目录）。多会话下分类器会把<b>后端启动目录的 CLAUDE.md</b> 当作本会话项目规则注入
 * 分类提示词（错的）。裁定 ② 的判据「分类器有没有 sessionId」实测为<b>有</b>
 * （{@code YoloClassifierImpl.classify/classifyTextAction} 都收 {@code ToolUseContext ctx}，
 * 且 {@code ctx.sessionId()} 在同类 :780 已被真实消费）⇒ 必须改成
 * {@code Function<String,String>} 并显式传参。
 *
 * <p><b>装置（P0-2 锚点夹具同款）</b>：会话 originalCwd = 临时目录 A（含 CLAUDE.md 内容 "A"）；
 * 进程 {@code user.dir} = 临时目录 B（含 CLAUDE.md 内容 "B"）。
 *
 * <p><b>反向实验配方</b>：把 {@code ToolRegistrationConfig.claudeMdContentSupplier} 的 lambda
 * 改回 {@code sessionId -> new UserContextProvider(claudemdEngine).claudeMd()}（无会话形态）⇒
 * 读到 "B" ⇒ 红。
 */
@DisplayName("[r10b-裁定②] 分类器 CLAUDE.md 读源 = 会话扫描根（非进程 user.dir）")
class ClassifierClaudeMdSessionAnchorTest {

    private String savedUserDir;

    @BeforeEach
    void setup() {
        savedUserDir = System.getProperty("user.dir");
        // 显式覆盖全局默认：会话层未命中即抛 ⇒ 「值来自会话层」可证伪
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
    @DisplayName("裁定② 分类器读源锚会话项目根（非进程 user.dir）的 CLAUDE.md")
    void classifierClaudeMdSource_anchorsSessionProjectRoot(@TempDir Path tmp) throws Exception {
        Path sessionAnchor = Files.createDirectories(tmp.resolve("session-project"));
        Files.writeString(sessionAnchor.resolve("CLAUDE.md"), "SESSION-CLAUDE-MD");
        Path processDir = Files.createDirectories(tmp.resolve("process-launch-dir"));
        Files.writeString(processDir.resolve("CLAUDE.md"), "PROCESS-CLAUDE-MD");

        System.setProperty("user.dir", processDir.toString());
        String sid = "sess-r10b-classifier";
        SessionCwdHolder.setOriginalCwd(sid, sessionAnchor.toString());
        SessionCwdHolder.set(sid, sessionAnchor.toString());

        // 生产 bean 方法直调（engine=null ⇒ UserContextProvider 回退单文件子集，扫描 projectRoot）
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        Function<String, String> readSource = config.claudeMdContentSupplier(null);

        assertThat(readSource.apply(sid))
            .as("⭐ 读源按传入 sessionId 解析扫描根（会话锚），不是进程 user.dir")
            .isEqualTo("SESSION-CLAUDE-MD");

        // 交叉验证：同一装置下显式走 originalCwd 解析确实落在会话锚（证装置非空）
        assertThat(CwdResolution.getOriginalCwdLayer(sid))
            .isEqualTo(sessionAnchor.toRealPath().toString());
    }
}
