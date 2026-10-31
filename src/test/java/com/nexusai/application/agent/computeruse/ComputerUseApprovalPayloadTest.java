package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase 1b · D] computer-use <b>审批面板载荷</b>的单测（入参 → 固定形状）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：前端要显示「哪些应用、每档授权到什么程度、
 * 还请求了哪些额外权限、为什么」⇒ 后端必须把这份数据<b>成形状</b>算好。
 * 数据源 = Phase 1a 在权限检查时就地注入的 {@code resolvedApps}（同一份入参实例）+
 * {@code reason} + 三个授权位。锁：
 * <ol>
 *   <li>档位机器值（read/click/full）与<b>大白话标签</b>（tierLabel）都由后端给出
 *       ⇒ 前端不翻译业务语义；</li>
 *   <li>{@code requestedFlags} <b>只含被请求的位</b>，{@code flagLabels} <b>只含同名键</b>；</li>
 *   <li>非 CU 工具 ⇒ 返回 null（前端走普通分支）。</li>
 * </ol>
 */
class ComputerUseApprovalPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-panel";

    @TempDir
    Path tempDir;

    private SessionMapper sessionMapper;
    private SessionRecord sessionRecord;
    private ComputerUseGrantStore grantStore;
    private ComputerUseLockService lockService;

    @BeforeEach
    void setUp() throws Exception {
        sessionMapper = mock(SessionMapper.class);
        sessionRecord = new SessionRecord();
        sessionRecord.setId(SESSION);
        sessionRecord.setComputerUseGrants(null);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(sessionRecord);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
        grantStore = new ComputerUseGrantStore(sessionMapper);

        SQLiteDataSource ds = ComputerUseTestSqlite.create(tempDir, "cu-panel.db");
        ComputerUseTestSqlite.createLockTable(ds);
        lockService = new ComputerUseLockService(ds, true);
    }

    private ComputerUseRequestAccessTool newTool() {
        return new ComputerUseRequestAccessTool(grantStore, lockService);
    }

    private static ObjectNode input(String reason, boolean clipboardRead, boolean clipboardWrite,
                                    boolean systemKeyCombos, String... apps) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode arr = node.putArray("apps");
        for (String a : apps) {
            arr.add(a);
        }
        node.put("reason", reason);
        node.put("clipboardRead", clipboardRead);
        node.put("clipboardWrite", clipboardWrite);
        node.put("systemKeyCombos", systemKeyCombos);
        return node;
    }

    @Test
    @DisplayName("从真实工具入参提取：应用列表含档位机器值 + 大白话标签")
    void payloadCarriesAppsWithTierAndLabel() {
        ObjectNode toolInput = input("我要在记事本里写点东西", false, false, false, "Notepad");
        ComputerUseRequestAccessTool tool = newTool();

        // 走真实的权限检查（Phase 1a 在此就地注入 resolvedApps）
        PermissionResult result = tool.checkPermissions(toolInput, ToolUseContext.of((UUID) null, SESSION));
        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);

        ComputerUseApprovalPayload payload =
                ComputerUseApprovalPayload.fromToolInput(tool.name(), toolInput);
        assertThat(payload).isNotNull();
        assertThat(payload.apps()).hasSize(1);

        ComputerUseApprovalPayload.App app = payload.apps().get(0);
        assertThat(app.appId()).isEqualTo("notepad");
        assertThat(app.displayName()).isEqualTo("Notepad");
        assertThat(app.tier())
                .as("记事本不是浏览器 / 终端 ⇒ 默认 full 档（CCB categoryToTier 兜底）")
                .isEqualTo("full");
        assertThat(app.tierLabel()).isEqualTo("可以完全操作");
        assertThat(app.alreadyGranted()).isFalse();
        assertThat(app.notInstalled())
                .as("本仓无应用枚举能力 ⇒ 恒 false（不编造「未安装」结论）")
                .isFalse();
        assertThat(payload.reason()).isEqualTo("我要在记事本里写点东西");
    }

    @Test
    @DisplayName("浏览器的 read 档 ⇒ tierLabel=只能查看（档位分类由后端算，前端不翻译）")
    void browserTierLabelIsPlainChinese() {
        ObjectNode toolInput = input("看看网页", false, false, false, "Chrome");
        ComputerUseRequestAccessTool tool = newTool();
        tool.checkPermissions(toolInput, ToolUseContext.of((UUID) null, SESSION));

        ComputerUseApprovalPayload payload =
                ComputerUseApprovalPayload.fromToolInput(tool.name(), toolInput);
        assertThat(payload).isNotNull();
        assertThat(payload.apps().get(0).tier()).isEqualTo("read");
        assertThat(payload.apps().get(0).tierLabel()).isEqualTo("只能查看");
    }

    @Test
    @DisplayName("requestedFlags 只含被请求的位，flagLabels 只含同名键")
    void requestedFlagsContainOnlyRequestedBits() {
        ObjectNode toolInput = input("要读剪贴板", true, false, false, "Notepad");
        ComputerUseRequestAccessTool tool = newTool();
        tool.checkPermissions(toolInput, ToolUseContext.of((UUID) null, SESSION));

        ComputerUseApprovalPayload payload =
                ComputerUseApprovalPayload.fromToolInput(tool.name(), toolInput);
        assertThat(payload).isNotNull();
        assertThat(payload.requestedFlags()).containsExactly("clipboardRead");
        assertThat(payload.flagLabels()).containsOnlyKeys("clipboardRead");
        assertThat(payload.flagLabels().get("clipboardRead")).isEqualTo("允许读取剪贴板");
    }

    @Test
    @DisplayName("未请求任何授权位 ⇒ requestedFlags 为空、flagLabels 为空")
    void noFlagsRequestedMeansEmptyLists() {
        ObjectNode toolInput = input("只是看看", false, false, false, "Notepad");
        ComputerUseRequestAccessTool tool = newTool();
        tool.checkPermissions(toolInput, ToolUseContext.of((UUID) null, SESSION));

        ComputerUseApprovalPayload payload =
                ComputerUseApprovalPayload.fromToolInput(tool.name(), toolInput);
        assertThat(payload).isNotNull();
        assertThat(payload.requestedFlags()).isEmpty();
        assertThat(payload.flagLabels()).isEmpty();
    }

    @Test
    @DisplayName("非 CU 工具 ⇒ 返回 null（前端走普通权限弹窗分支）")
    void nonCuToolYieldsNullPayload() {
        JsonNode bashInput = JSON.createObjectNode().put("command", "ls");
        assertThat(ComputerUseApprovalPayload.fromToolInput("Bash", bashInput)).isNull();
    }
}
