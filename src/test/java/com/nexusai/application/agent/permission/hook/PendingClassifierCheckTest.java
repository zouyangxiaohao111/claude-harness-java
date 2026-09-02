package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionResult.PendingClassifierCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H14] Ask.pendingClassifierCheck boolean → 结构体 升级测试.
 *
 * <p>WHY (规则九 · 测试验证意图): CC {@code types/permissions.ts:190-194} 把
 * PendingClassifierCheck 定义为 <b>结构体</b> {@code {command, cwd, descriptions}} —
 * 分类器需要 command/cwd/descriptions 才能判断 bash 命令是否安全 (async classifier
 * auto-approval). boolean 语义只能表达 "有/无", 无法携带分类器真正需要的上下文 →
 * 分类器永远无法对齐. 本测试断言 Ask 主字段是结构体 (含 command/cwd), 而非 boolean.
 */
@DisplayName("[H14] Ask.pendingClassifierCheck 结构体升级")
class PendingClassifierCheckTest {

    private static final String COMMAND = "git status";
    private static final String CWD = "/Users/tester/project";
    private static final List<String> DESCRIPTIONS = List.of("Read-only git command");

    /** 构造携带 pendingClassifierCheck 结构体的 Ask. */
    private static PermissionResult.Ask askWithCheck() {
        return new PermissionResult.Ask(
            "stub ask",
            new PermissionDecisionReason.Other("test"),
            List.of(),
            null,
            null,
            null,
            false,
            new PendingClassifierCheck(COMMAND, CWD, DESCRIPTIONS),
            List.of());
    }

    @Test
    @DisplayName("Ask.pendingClassifierCheck 是结构体 (非 boolean), 携带 command/cwd/descriptions")
    void pendingClassifierCheck_upgradedToStruct_carriesCommandCwd() {
        // WHY: 分类器需要 command/cwd/descriptions 才能判断 bash 命令，
        //       boolean 语义无法承载 → 分类器永远无法对齐.
        // 断言: Ask.pendingClassifierCheck 是结构体（含 command/cwd）而非 boolean.
        PermissionResult.Ask ask = askWithCheck();

        assertThat(ask.pendingClassifierCheck())
            .as("pendingClassifierCheck 必须是非空结构体（CC types/permissions.ts:190-194）")
            .isInstanceOf(PendingClassifierCheck.class);
        assertThat(ask.pendingClassifierCheck().command()).isEqualTo(COMMAND);
        assertThat(ask.pendingClassifierCheck().cwd()).isEqualTo(CWD);
        assertThat(ask.pendingClassifierCheck().descriptions()).isEqualTo(DESCRIPTIONS);
    }

    @Test
    @DisplayName("未设置分类器检查时 Ask.pendingClassifierCheck 为 null（CC 可选字段语义）")
    void pendingClassifierCheck_absent_isNull() {
        // WHY: CC {@code pendingClassifierCheck?: PendingClassifierCheck} 是可选字段 —
        //       非 bash 工具 / 无分类器场景应缺省为 undefined (Java null), 而非 boolean false.
        PermissionResult.Ask ask = new PermissionResult.Ask(
            "stub ask", new PermissionDecisionReason.Other("test"), List.of(),
            null, null, null, false, null, List.of());

        assertThat(ask.pendingClassifierCheck()).isNull();
    }

    @Test
    @DisplayName("Passthrough 同样升级为结构体（CC :265）")
    void passthrough_pendingClassifierCheck_isStruct() {
        // WHY: CC PermissionResult passthrough 变体同样声明
        //       {@code pendingClassifierCheck?: PendingClassifierCheck} (types/permissions.ts:265).
        //       工具 "中立票" 也可携带分类器上下文供上层竞速.
        PermissionResult.Passthrough passthrough = new PermissionResult.Passthrough(
            "stub passthrough", null, List.of(), null,
            new PendingClassifierCheck(COMMAND, CWD, DESCRIPTIONS));

        assertThat(passthrough.pendingClassifierCheck())
            .isInstanceOf(PendingClassifierCheck.class);
        assertThat(passthrough.pendingClassifierCheck().command()).isEqualTo(COMMAND);
    }

    @Test
    @DisplayName("结构体可构造为 JsonNode 兼容字段 (command/cwd/descriptions)")
    void struct_fields_roundTrip() {
        // WHY: 结构体经 metadata 塞入 / 序列化时, 字段名必须稳定映射
        //       (command/cwd/descriptions), 供分类器消费.
        PendingClassifierCheck check = new PendingClassifierCheck("ls -la", CWD, List.of("a", "b"));
        JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(check);

        assertThat(node.has("command")).isTrue();
        assertThat(node.has("cwd")).isTrue();
        assertThat(node.has("descriptions")).isTrue();
        assertThat(node.get("command").asText()).isEqualTo("ls -la");
    }
}
