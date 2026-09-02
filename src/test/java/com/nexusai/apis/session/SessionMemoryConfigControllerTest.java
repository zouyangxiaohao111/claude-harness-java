package com.nexusai.apis.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.SessionMemoryConfigChannel;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.memory.SessionMemoryUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SessionMemoryConfigController} 测试 · IMP-CM-35 Web 调参通道（REST 配置端点）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC GrowthBook 远端配置通道
 * （tengu_sm_config / tengu_sm_compact_config）在 Java 无远端，OPD-CM3-14 拍板以 Web REST
 * 端点建模。本测试钉死端点契约：
 * <ol>
 *   <li><b>GET /api/v1/session-memory/config</b> —— 返回当前生效 SM 提取 + 压缩阈值
 *       （通道未配置 → DEFAULT：提取 10000/5000/3，压缩 10000/5/40000）。</li>
 *   <li><b>PUT /sm</b> —— 更新 SM 提取阈值（tengu_sm_config 等价），返回更新后完整配置；
 *       运行期 SessionMemoryUtils 立即读新值（不重启）。</li>
 *   <li><b>PUT /sm-compact</b> —— 更新 SM 压缩阈值（tengu_sm_compact_config 等价）；
 *       运行期 SessionMemoryService.getSmCompactConfig() 立即读新值（不重启，
 *       calculateMessagesToKeepIndex :1625 读同一字段，grep 自验 2026-08-15）。</li>
 *   <li><b>null/0 不覆盖</b> —— CC「仅正值覆盖」语义（sessionMemory.ts:246-262 /
 *       sessionMemoryCompact.ts:113-128）：partial 中 null/≤0 字段保留当前值。</li>
 * </ol>
 *
 * <p>测试用真实 {@link SessionMemoryService}（真实运行时状态持有者）+ 真实
 * {@link SessionMemoryConfigChannel}，经 ReflectionTestUtils 注入 controller；
 * 不依赖 Spring 全量上下文。
 */
class SessionMemoryConfigControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path baseDir;

    private SessionMemoryService service;
    private SessionMemoryConfigChannel channel;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SessionMemoryUtils.resetSessionMemoryState();
        service = new SessionMemoryService(baseDir);
        channel = new SessionMemoryConfigChannel();
        channel.setSessionMemoryService(service);
        SessionMemoryConfigController controller = new SessionMemoryConfigController();
        ReflectionTestUtils.setField(controller, "channel", channel);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        // [sm-cursor-sessionize] 清 "unknown" 键游标（本测试不指定会话）
        SessionMemoryService.setLastSummarizedMessageId(null, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    @Test
    @DisplayName("GET → 200 + 通道未配置时返回 DEFAULT（提取 10000/5000/3 + 压缩 10000/5/40000）")
    void get_returnsDefaultsWhenUnconfigured() throws Exception {
        mockMvc.perform(get("/api/v1/session-memory/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minimumMessageTokensToInit").value(10000))
            .andExpect(jsonPath("$.minimumTokensBetweenUpdate").value(5000))
            .andExpect(jsonPath("$.toolCallsBetweenUpdates").value(3))
            .andExpect(jsonPath("$.minTokens").value(10000))
            .andExpect(jsonPath("$.minTextBlockMessages").value(5))
            .andExpect(jsonPath("$.maxTokens").value(40000));
    }

    @Test
    @DisplayName("PUT /sm → 更新提取阈值 + 运行期 SessionMemoryUtils 立即读新值（不重启）")
    void putSm_updatesExtractionConfigAndRuntimeRead() throws Exception {
        mockMvc.perform(put("/api/v1/session-memory/config/sm")
                .contentType(APPLICATION_JSON)
                .content("{\"minimumMessageTokensToInit\": 12000, \"minimumTokensBetweenUpdate\": 6000, \"toolCallsBetweenUpdates\": 5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minimumMessageTokensToInit").value(12000))
            .andExpect(jsonPath("$.minimumTokensBetweenUpdate").value(6000))
            .andExpect(jsonPath("$.toolCallsBetweenUpdates").value(5));

        // 运行期读新值：hasMetInitializationThreshold 用新 init 阈值（12000）判定
        // [sm-cursor-sessionize] recordExtractionTokenCount 会话化（null → "unknown" 键，纯阈值判定用）
        SessionMemoryUtils.recordExtractionTokenCount(null, 0);
        org.assertj.core.api.Assertions.assertThat(SessionMemoryUtils.hasMetInitializationThreshold(11000))
            .as("11000 < 12000（新 init 阈值）→ 未达初始化")
            .isFalse();
        org.assertj.core.api.Assertions.assertThat(SessionMemoryUtils.hasMetInitializationThreshold(13000))
            .as("13000 ≥ 12000（新 init 阈值）→ 已达初始化")
            .isTrue();
        org.assertj.core.api.Assertions.assertThat(SessionMemoryUtils.getToolCallsBetweenUpdates())
            .as("工具调用阈值同步新值")
            .isEqualTo(5);

        // GET 反映新值
        mockMvc.perform(get("/api/v1/session-memory/config"))
            .andExpect(jsonPath("$.minimumMessageTokensToInit").value(12000));
    }

    @Test
    @DisplayName("PUT /sm-compact → 更新压缩阈值 + 运行期 SessionMemoryService 立即读新值（不重启）")
    void putSmCompact_updatesCompactConfigAndRuntimeRead() throws Exception {
        mockMvc.perform(put("/api/v1/session-memory/config/sm-compact")
                .contentType(APPLICATION_JSON)
                .content("{\"minTokens\": 8000, \"minTextBlockMessages\": 3, \"maxTokens\": 30000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minTokens").value(8000))
            .andExpect(jsonPath("$.minTextBlockMessages").value(3))
            .andExpect(jsonPath("$.maxTokens").value(30000));

        // 运行期读新值：SessionMemoryService.smCompactConfig 字段（calculateMessagesToKeepIndex :1485 读同一字段，grep 自验）
        org.assertj.core.api.Assertions.assertThat(service.getSmCompactConfig())
            .as("运行期持有者立即读到 PUT 的新压缩配置（不重启）")
            .isEqualTo(new SessionMemoryService.SmCompactConfig(8000, 3, 30000));

        // GET 反映新值
        mockMvc.perform(get("/api/v1/session-memory/config"))
            .andExpect(jsonPath("$.maxTokens").value(30000));
    }

    @Test
    @DisplayName("PUT /sm 仅传部分字段 → null/0 字段保留当前值（CC 仅正值覆盖）")
    void putSm_partial_preservesUnsetFields() throws Exception {
        // 先设完整值
        mockMvc.perform(put("/api/v1/session-memory/config/sm")
                .contentType(APPLICATION_JSON)
                .content("{\"minimumMessageTokensToInit\": 12000, \"minimumTokensBetweenUpdate\": 6000, \"toolCallsBetweenUpdates\": 5}"))
            .andExpect(status().isOk());

        // 仅覆盖 minimumMessageTokensToInit → 其余保留
        mockMvc.perform(put("/api/v1/session-memory/config/sm")
                .contentType(APPLICATION_JSON)
                .content("{\"minimumMessageTokensToInit\": 9000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minimumMessageTokensToInit").value(9000))
            .andExpect(jsonPath("$.minimumTokensBetweenUpdate").value(6000))
            .andExpect(jsonPath("$.toolCallsBetweenUpdates").value(5));
    }

    @Test
    @DisplayName("PUT /sm-compact 传 0 → 0 字段不覆盖（CC 仅正值覆盖，0/缺省用当前值）")
    void putSmCompact_zeroDoesNotOverride() throws Exception {
        // 先设完整值
        mockMvc.perform(put("/api/v1/session-memory/config/sm-compact")
                .contentType(APPLICATION_JSON)
                .content("{\"minTokens\": 8000, \"minTextBlockMessages\": 3, \"maxTokens\": 30000}"))
            .andExpect(status().isOk());

        // 传 0 → 保留当前（minTokens 不变）
        mockMvc.perform(put("/api/v1/session-memory/config/sm-compact")
                .contentType(APPLICATION_JSON)
                .content("{\"minTokens\": 0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.minTokens").value(8000))
            .andExpect(jsonPath("$.minTextBlockMessages").value(3))
            .andExpect(jsonPath("$.maxTokens").value(30000));
    }
}
