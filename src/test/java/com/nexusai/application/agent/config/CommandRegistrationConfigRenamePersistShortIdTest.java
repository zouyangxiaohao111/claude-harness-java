package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[session-id-short]</b> {@code /rename} 生产链路在 <b>short 直键</b>会话下必须把
 * custom-title / agent-name <b>真落盘</b>到 {@code <shortSessionId>.jsonl}。
 *
 * <p><b>WHY（规则九 · 意图）</b>：{@code CommandRegistrationConfig.registerRenameHandler} 把
 * 会话标识槽接成 {@code () -> resolveSessionUuid(ctx.sessionId())}。生产会话 ID 是
 * {@code SessionService:189 setId(generateId("sess"))} 产出的 <b>short 直键</b>
 * （{@code sess-xxxxxxxx}），而 {@code UUID.fromString("sess-xxxxxxxx")} <b>必抛
 * IllegalArgumentException</b> ⇒ 恒 null ⇒ {@code persistSessionMetadata} 首行
 * {@code if (sessionId == null) return;} 早返 ⇒ {@code /rename <name>} 在真实 Web 会话里
 * <b>静默不写任何元数据</b>（无报错、无日志、transcript 无变化）。
 *
 * <p>本测试用<b>生产注册面</b>（真实 {@code UserInputDispatcher} + 真实
 * {@code SessionAgentStateRegistry} + 真实 handler）锁定该语义，⛔ 不手搓 {@code RenameCommand.Env}
 * （那等于自己伪造 sessionId，测不到「会话标识从哪来」）。
 *
 * <p><b>RED teeth（反向实验配方）</b>：把 {@code registerRenameHandler} 里
 * {@code ctx::sessionId} 改回任何「对 short 键返回 null / 变换后返回非原值」的赋值
 * （例如 {@code () -> null}、或再套一层 {@code UUID.fromString} 转换）⇒ 本测试两条断言
 * （文件不存在 / 无 custom-title entry）<b>必红</b>。恒绿即夹具失效。
 */
class CommandRegistrationConfigRenamePersistShortIdTest {

    /** 生产 short 直键形态（{@code SessionService:189 generateId("sess")}）。 */
    private static final String SESSION_SHORT = "sess-1a2b3c4d";

    // [S2 · F-09/F-20] 夹具 DB 姿态显式声明：本夹具不接 DB 回源 ⇒ 该 sessionId 属「确无会话」，
    //   CwdResolution 走 user.dir 兜底（同 AgentColorCommandTest 先例）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearState() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionProjectRoot.reset();
    }

    @Test
    @DisplayName("[session-id-short] /rename <name> 生产 handler：short 键会话 → custom-title/agent-name 真落盘到 <shortId>.jsonl")
    void rename_shortIdSession_persistsCustomTitleAndAgentName() throws Exception {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        // 普通（非 teammate）会话：/rename 守卫放行；同时证明会话键为 short 直键时 registry 命中
        registry.register(SESSION_SHORT, new AgentState("test-sys", SESSION_SHORT, null));

        new CommandRegistrationConfig()
            .commandLocalSlashRegistration(dispatcher, null, registry);

        Path transcript = SessionStorage.sessionProjectDir(SESSION_SHORT)
            .resolve(SESSION_SHORT + ".jsonl");
        try {
            UserInputDispatcher.RoutingResult r =
                dispatcher.dispatch("/rename 批P1-重命名验证", SESSION_SHORT, null);
            assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
            assertThat(r.routedTo()).isEqualTo("rename");

            // ⭐ 断言①：transcript 真落盘，且文件名 = <shortSessionId>.jsonl
            //   （⛔ 若会话标识被 UUID 转换吞成 null，此处文件根本不会创建）
            assertThat(Files.isRegularFile(transcript))
                .as("short 键会话 /rename 必须在 <shortId>.jsonl 落盘（UUID 转换 ⇒ 恒 null ⇒ 文件不创建）")
                .isTrue();

            String content = Files.readString(transcript);
            // ⭐ 断言②：custom-title（saveCustomTitle 分支）真写入，且 sessionId 字段为 short 原值
            assertThat(content)
                .as("transcript 含 custom-title entry")
                .contains("\"type\":\"custom-title\"");
            assertThat(content)
                .as("custom-title 值为 /rename 的入参")
                .contains("\"customTitle\":\"批P1-重命名验证\"");
            // ⭐ 断言③：agent-name（saveAgentName 分支）真写入
            assertThat(content)
                .as("transcript 含 agent-name entry")
                .contains("\"type\":\"agent-name\"");
            // ⭐ 断言④：落盘 entry 的 sessionId 字段是 short 直键原值（⛔ 非 UUID 形态）
            assertThat(content)
                .as("entry.sessionId 必须是 short 直键原值")
                .contains("\"sessionId\":\"" + SESSION_SHORT + "\"");
        } finally {
            try {
                Files.deleteIfExists(transcript);
            } catch (IOException ignored) {
                // 清理失败不掩盖测试结果
            }
        }
    }

    /**
     * 反向对照：即便会话键是 <b>UUID 形态</b>，同样要落盘（证明修复不是「只对 short 键特判」）。
     *
     * <p>UUID 形态曾让旧实现「碰巧能解析」——但旧实现随后走 {@code get(UUID)} 错路由（agents map），
     * 且 {@code .toString()} 归一化会改变非规范形态的键值。本用例锁定「键形态无关，一律直传」。
     */
    @Test
    @DisplayName("[session-id-short] /rename 生产 handler：UUID 形态会话键同样直传落盘（非 short 特判）")
    void rename_uuidShapedSession_persistsByDirectKey() throws Exception {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        String sessionUuid = UUID.randomUUID().toString();
        registry.register(sessionUuid, new AgentState("test-sys", sessionUuid, null));

        new CommandRegistrationConfig()
            .commandLocalSlashRegistration(dispatcher, null, registry);

        Path transcript = SessionStorage.sessionProjectDir(sessionUuid)
            .resolve(sessionUuid + ".jsonl");
        try {
            dispatcher.dispatch("/rename uuid-shaped-name", sessionUuid, null);

            assertThat(Files.isRegularFile(transcript))
                .as("UUID 形态会话键也必须落盘（直传，非 short 特判）")
                .isTrue();
            assertThat(Files.readString(transcript))
                .contains("\"type\":\"custom-title\"")
                .contains("\"customTitle\":\"uuid-shaped-name\"");
        } finally {
            try {
                Files.deleteIfExists(transcript);
            } catch (IOException ignored) {
                // 清理失败不掩盖测试结果
            }
        }
    }
}
