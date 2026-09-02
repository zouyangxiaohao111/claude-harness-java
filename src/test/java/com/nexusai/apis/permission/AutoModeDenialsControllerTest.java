package com.nexusai.apis.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.AutoModeDenials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [T4-OPD75] {@link AutoModeDenialsController} 只读端点测试。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: CC RecentDenialsTab 挂载时快照读
 * {@code getAutoModeDenials()}（RecentDenialsTab.tsx:204-206）→ Java 端 nexusai-ui 消费
 * 载体为 REST GET。本测试锁定端点契约：
 * <ol>
 *   <li><b>GET → 200 + JSON 数组</b>——最近拒绝在前（CC store 语义），最多 20 条
 *       （MAX_DENIALS，CC autoModeDenials.ts:17）。</li>
 *   <li><b>字段 = CC AutoModeDenial 类型 + SDK 补全字段</b>——camelCase
 *       {@code {toolName, display, reason, timestamp, toolUseId, toolInput}}
 *       （autoModeDenials.ts:8-14 + SDKPermissionDenialSchema coreSchemas.ts:1399-1404 的
 *       tool_use_id/tool_input，GC-04 补全）：Java store 已采集 tool_use_id/tool_input，
 *       前端可展示"哪个工具调用被拒、输入是什么"。</li>
 *   <li><b>只读无副作用</b>——GET 不改变 store 内容。</li>
 * </ol>
 *
 * <p>测试隔离：store 为进程级静态（对齐 CC 模块级数组），用反射清空（仅测试手段，
 * 不新增生产清空面）。
 */
@DisplayName("[T4-OPD75] AutoModeDenialsController GET /api/v1/permissions/auto-mode-denials")
class AutoModeDenialsControllerTest {

    /** JSON 构造器（测试用，等价工具类 ObjectMapper）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(new AutoModeDenialsController()).build();
        clearDenials();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearDenials();
    }

    /** 反射清空静态 store · 测试隔离用（不修改生产代码）。 */
    private static void clearDenials() throws Exception {
        Field field = AutoModeDenials.class.getDeclaredField("DENIALS");
        field.setAccessible(true);
        ((List<?>) field.get(null)).clear();
    }

    @Test
    @DisplayName("空 store → 200 + 空数组")
    void get_emptyStore_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/auto-mode-denials"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("多条记录 → 200 + 最近在前 + CC AutoModeDenial + SDK 补全字段 camelCase")
    void get_records_mostRecentFirstWithCcFields() throws Exception {
        AutoModeDenials.recordAutoModeDenial("Bash", "echo old", "低置信度", 1000L, "call_1",
            JSON.createObjectNode().put("cmd", "echo old"));
        AutoModeDenials.recordAutoModeDenial("Read", "read secret.txt", "危险文件", 2000L, "call_2",
            JSON.createObjectNode().put("path", "secret.txt"));
        AutoModeDenials.recordAutoModeDenial("Bash", "rm -rf /tmp/x", "破坏性命令", 3000L, "call_3",
            JSON.createObjectNode().put("cmd", "rm -rf /tmp/x"));

        mockMvc.perform(get("/api/v1/permissions/auto-mode-denials"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            // 最近拒绝在前（CC DENIALS = [denial, ...] 插队首语义）
            .andExpect(jsonPath("$[0].toolName").value("Bash"))
            .andExpect(jsonPath("$[0].display").value("rm -rf /tmp/x"))
            .andExpect(jsonPath("$[0].reason").value("破坏性命令"))
            .andExpect(jsonPath("$[0].timestamp").value(3000))
            // GC-04: SDK 面补全字段 tool_use_id/tool_input（coreSchemas.ts:1401-1402）
            .andExpect(jsonPath("$[0].toolUseId").value("call_3"))
            .andExpect(jsonPath("$[0].toolInput.cmd").value("rm -rf /tmp/x"))
            .andExpect(jsonPath("$[1].toolName").value("Read"))
            .andExpect(jsonPath("$[1].display").value("read secret.txt"))
            .andExpect(jsonPath("$[1].toolUseId").value("call_2"))
            .andExpect(jsonPath("$[1].toolInput.path").value("secret.txt"))
            .andExpect(jsonPath("$[2].toolName").value("Bash"))
            .andExpect(jsonPath("$[2].display").value("echo old"))
            .andExpect(jsonPath("$[2].toolUseId").value("call_1"))
            .andExpect(jsonPath("$[2].toolInput.cmd").value("echo old"));
    }

    @Test
    @DisplayName("超过 20 条 → 只返回 20 条（MAX_DENIALS 上限，CC 语义）")
    void get_overMax_returnsTwenty() throws Exception {
        for (int i = 1; i <= 25; i++) {
            AutoModeDenials.recordAutoModeDenial("Bash", "cmd-" + i, "理由" + i, i * 1000L,
                "call-" + i, JSON.createObjectNode().put("cmd", "cmd-" + i));
        }

        mockMvc.perform(get("/api/v1/permissions/auto-mode-denials"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(20))
            // 最新 25 条中的第 1..20（cmd-25 在前，cmd-6 为最旧保留）
            .andExpect(jsonPath("$[0].display").value("cmd-25"))
            .andExpect(jsonPath("$[19].display").value("cmd-6"));
    }

    @Test
    @DisplayName("GET 只读无副作用 · 两次读取内容一致且无新增")
    void get_readOnly_noSideEffect() throws Exception {
        AutoModeDenials.recordAutoModeDenial("Bash", "echo hi", "理由", 42L,
            "call-42", JSON.createObjectNode().put("cmd", "echo hi"));

        mockMvc.perform(get("/api/v1/permissions/auto-mode-denials"))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/permissions/auto-mode-denials"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].display").value("echo hi"));
    }
}
