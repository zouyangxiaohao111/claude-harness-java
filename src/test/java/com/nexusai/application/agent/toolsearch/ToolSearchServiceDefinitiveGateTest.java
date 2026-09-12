package com.nexusai.application.agent.toolsearch;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.impl.BashTool;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.infra.llm.CountTokensClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H4] ToolSearch definitive 门控测试 · 对齐 CC toolSearch.ts:385-473 {@code isToolSearchEnabled}
 * （modelSupportsToolReference :239-252 + isToolSearchToolAvailable + mode + tst-auto 阈值）+
 * {@code filteredTools}（claude.ts:1154-1172）+ {@code isDeferredToolsDeltaEnabled}
 * （toolSearch.ts:629-633）。
 *
 * <p>WHY: H3 的 isToolSearchEnabledOptimistic 只是乐观门控（mode≠standard 恒 true），主循环
 * schema 必须走 definitive 门控（model 支持 tool_reference + ToolSearch 可用 + 阈值）——
 * 否则 haiku 模型会被塞入 tool_reference 产物（无法解析）导致 API 报错，definitive 门控
 * 是 CC claude.ts:1120 的真实入口行为。
 *
 * <p>变异点：删 modelSupportsToolReference 负向模式 → haiku 用例变红；删 filteredTools
 * discovered 过滤 → 未发现 deferred 工具错误入 schema 变红；短路/auto 边界缺一即红。
 */
class ToolSearchServiceDefinitiveGateTest {

    /** Bash(非 deferred) + ToolSearch + WebSearch(shouldDefer=true，H3 接线) · 均非 SPECIAL_TOOLS. */
    private static final List<Tool> TOOLS_WITH_SEARCH =
            List.of(new BashTool(), new ToolSearchTool(), new WebSearchTool());
    /** 无 ToolSearch 的列表（模拟 disallowedTools 剔除）. */
    private static final List<Tool> TOOLS_NO_SEARCH = List.of(new BashTool(), new WebSearchTool());

    @AfterEach
    void resetEnv() {
        ToolSearchService.envOverride = null;
        // IMP-C6 memoize：getDeferredToolTokenCount 按 deferred 工具名缓存（toolSearch.ts:124-152），
        // 测试间必须隔离（tokenPath_takesPrecedence 缓存高 token 值 → 污染后续 null-client 用例的
        // char fallback 断言）。生产 MCP connect/disconnect 显式失效（OPD-IMP-30）。
        ToolSearchService.invalidateDeferredToolTokenCountCache();
    }

    @Test
    @DisplayName("modelSupportsToolReference 负向模式：haiku 命中 → false；其余模型 → true")
    void modelSupportsToolReference_negativePattern() {
        // WHY: CC toolSearch.ts:241-244 负向匹配（新模型默认支持 tool_reference）。
        //   变异点：默认不支持列表改用正向白名单 → haiku 之外也会误杀 → 红。
        assertThat(ToolSearchService.modelSupportsToolReference("claude-haiku-4-5")).isFalse();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-3-5-haiku")).isFalse();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-sonnet-4-5")).isTrue();
        assertThat(ToolSearchService.modelSupportsToolReference("claude-opus-4-1")).isTrue();
        // 2026-09-11 期望变更（WHY）：DEFAULT_UNSUPPORTED_MODEL_PATTERNS 归位回 CC 原样只留 'haiku'
        //   （toolSearch.ts:200-204），deepseek 从负向名单移除 → modelSupportsToolReference 判 true。
        //   「openai 系不启用 tool search」不再由模型名单承担，改由主循环门控单点负责
        //   （isToolSearchEnabled && toolReferenceUsable(providerType, model)；非 anthropic → useToolSearch=false
        //   → 全部工具 schema 直发）。变异：若把 'deepseek' 加回名单 → 本行变红。
        assertThat(ToolSearchService.modelSupportsToolReference("deepseek-v4-flash"))
            .as("deepseek 不在模型不支持名单（非 anthropic 不启用 tool search 由门控 AND 负责）")
            .isTrue();
    }

    @Test
    @DisplayName("toolReferenceUsable 单点：provider 语义 取与 模型能力；判不出即不支持")
    void toolReferenceUsable_singlePoint() {
        // WHY: 2026-09-11 用户定义语义「ant 支持 tool_reference（除 haiku），其他都不支持」——
        //   tool_reference 是 anthropic wire 专属（openai 序列化整块丢弃 → 零载荷死锁），
        //   且 anthropic 下 haiku 不支持 → 两半 AND 缺一即 false。
        // 两半都成立 → true
        assertThat(ToolSearchService.toolReferenceUsable("anthropic", "claude-opus-4")).isTrue();
        assertThat(ToolSearchService.toolReferenceUsable("Anthropic", "claude-sonnet-4-5"))
            .as("provider 大小写不敏感（equalsIgnoreCase）").isTrue();
        // 模型那一半 load-bearing：anthropic + haiku → false
        //   变异 (i)：把单点改成只判 provider（去掉 && modelSupportsToolReference）→ 本行变红。
        assertThat(ToolSearchService.toolReferenceUsable("anthropic", "claude-haiku-4-5")).isFalse();
        // provider 那一半 load-bearing：openai_compatible + claude 名 → false（代理暴露 claude 名陷阱）
        //   变异 (ii)：把单点改成只判 modelSupportsToolReference（去掉 provider 取与）→ 本行变红。
        assertThat(ToolSearchService.toolReferenceUsable("openai_compatible", "claude-sonnet-4-5"))
            .as("openai 代理暴露 claude 名也必须判不支持（无 tool_reference wire 语义）").isFalse();
        assertThat(ToolSearchService.toolReferenceUsable("openai_compatible", "deepseek-v4-flash")).isFalse();
        // 判不出即不支持：任一 null / 未知 → false
        assertThat(ToolSearchService.toolReferenceUsable(null, "claude-opus-4")).isFalse();
        assertThat(ToolSearchService.toolReferenceUsable("anthropic", null)).isFalse();
        assertThat(ToolSearchService.toolReferenceUsable(null, null)).isFalse();
        assertThat(ToolSearchService.toolReferenceUsable("openai_sdk", "claude-opus-4")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：haiku 模型恒 false（不支持 tool_reference，即使 ToolSearch 可用）")
    void isToolSearchEnabled_haikuModel_alwaysFalse() {
        // WHY: CC toolSearch.ts:411-418 —— tool_reference 仅 Sonnet 4+ / Opus 4+ 支持，
        //   haiku 收到 tool_reference 会解析失败；门控必须在主循环 schema 构建前拦截。
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-haiku-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：ToolSearch 不在列表 → false（respects disallowedTools，CC :420-427）")
    void isToolSearchEnabled_noSearchTool_false() {
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_NO_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：model 支持 + ToolSearch 可用 + 默认 mode(tst) → true")
    void isToolSearchEnabled_defaultMode_true() {
        // WHY: ENABLE_TOOL_SEARCH 未设置 → mode='tst'（CC toolSearch.ts:197 默认启用）。
        ToolSearchService.envOverride = Map.of();
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：ENABLE_TOOL_SEARCH=0（standard）→ false")
    void isToolSearchEnabled_standardMode_false() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "0");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：auto:100（standard）→ false；auto:0（tst）→ true（CC :189-190）")
    void isToolSearchEnabled_autoEdges() {
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto:100");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();

        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto:0");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto 阈值 char fallback（默认 10%×200k×2.5=50k chars）→ 小工具集 false")
    void isToolSearchEnabled_tstAuto_belowThreshold_false() {
        // WHY: CC toolSearch.ts:742-754 char fallback（token 计数不可得时）。默认阈值 50,000 chars，
        //   Bash/ToolSearch/WebSearch 描述远低于 → deferred 工具量不足时自动模式保持关闭（防全量预声明）。
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5")).isFalse();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto token 优先——count_tokens 返回超阈值 → true（char fallback 本应 false）")
    void isToolSearchEnabled_tstAuto_tokenPath_takesPrecedence() {
        // WHY: CC checkAutoThreshold toolSearch.ts:712-738 先走精确 token 计数，非 null 即用 token 阈值
        //   （不回退 char）。char fallback（50k chars）下 WebSearch 描述远低于阈值 → false；
        //   但 token client 返回 100_000（raw）→ max(0,100000-500) ≥ 10%×200k=20_000 tokens → true。
        //   若实现退化回纯 char fallback（或 token 结果被忽略），本用例变红——锁定 token 优先契约。
        CountTokensClient highTokenClient = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 0; }
            @Override public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
                return 100_000;
            }
        };
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5", highTokenClient))
                .isTrue();
    }

    @Test
    @DisplayName("isToolSearchEnabled：tst-auto token client 返回 null → 回退 char fallback（小工具集 false）")
    void isToolSearchEnabled_tstAuto_tokenNull_fallsBackToChar() {
        // WHY: CC getDeferredToolTokenCount toolSearch.ts:133-136 API 不可得（null）→ char fallback。
        //   token client 返回 null 时不得改变 char fallback 结论（小工具集 → false）。
        CountTokensClient nullTokenClient = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return null; }
            @Override public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) { return null; }
        };
        ToolSearchService.envOverride = Map.of("ENABLE_TOOL_SEARCH", "auto");
        assertThat(ToolSearchService.isToolSearchEnabled(TOOLS_WITH_SEARCH, "claude-sonnet-4-5", nullTokenClient))
                .isFalse();
    }

    @Test
    @DisplayName("filterToolsForSchema：useToolSearch=true 三支（非 deferred 恒留 / ToolSearch 恒留 / deferred 仅 discovered 含才留）")
    void filterToolsForSchema_enabledThreeBranches() {
        // WHY: claude.ts:1163-1168 动态工具加载语义——未预声明的 deferred 工具绝不进 schema，
        //   只有消息历史 tool_reference 已发现的才发送（消除全量预声明 + 数量上限）。
        Set<String> deferred = Set.of("WebSearch");

        List<Tool> noDiscovery = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, true, deferred, Set.of());
        assertThat(names(noDiscovery))
                .as("deferred 未发现 → 剔除 WebSearch，Bash/ToolSearch 恒留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch");

        List<Tool> withDiscovery = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, true, deferred, Set.of("WebSearch"));
        assertThat(names(withDiscovery))
                .as("deferred 已发现 → 三工具全留")
                .containsExactlyInAnyOrder("Bash", "ToolSearch", "WebSearch");
    }

    @Test
    @DisplayName("filterToolsForSchema：useToolSearch=false 恒「排除 ToolSearch + 全量」（CC claude.ts:1170-1172，R8 回归）")
    void filterToolsForSchema_disabled_ccForm() {
        // WHY（R8 2026-09-11 回归 CC）：useToolSearch=false（非 anthropic / anthropic×haiku / 显式关闭）
        //   时模型无 tool_reference —— 被剔工具对模型不存在即死锁，故必须「排除 ToolSearch + 其余（含
        //   deferred/MCP）全部完整 schema 内联直发」。原 [openai-lazy]「ToolSearch 保留 + deferred 过滤」
        //   子路径已删（其唯一动因 = 主循环靠「清空 deferred」豁免兜底；R8 改由门控 AND 直接判 false）。
        //   变异 (ii)：把 ② 改回「保留 ToolSearch」→ 本用例变红。
        // ① 无 deferred：排除 ToolSearch + 全量（CC :1170-1172）
        List<Tool> noDeferred = ToolSearchService.filterToolsForSchema(TOOLS_WITH_SEARCH, false, null, null);
        assertThat(names(noDeferred))
                .as("useToolSearch=false → 排除 ToolSearch，其余全量（含 WebSearch）")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        // ② deferred 未发现 → 不参与过滤，仍全量（CC 恒等式）
        List<Tool> withDeferred = ToolSearchService.filterToolsForSchema(
                TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of());
        assertThat(names(withDeferred))
                .as("deferred 未发现也照发（false 分支 deferred 不参与过滤）")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        // ③ deferred 已 discovered → 结果与 ② 逐项相同（deferred/discovered 均为恒等式输入）
        List<Tool> discovered = ToolSearchService.filterToolsForSchema(
                TOOLS_WITH_SEARCH, false, Set.of("WebSearch"), Set.of("WebSearch"));
        assertThat(names(discovered))
                .as("discovered 不改变结果 —— false 分支对 deferred/discovered 恒等（CC 回归核心）")
                .containsExactlyInAnyOrder("Bash", "WebSearch");
        // ④ 中性证明：filterToolsForSchema(false, deferred=∅)（旧非 anthropic 生产形态：豁免清空 deferred）
        //    === filterToolsForSchema(false, deferred 非空)（R8 新形态：门控直接 false，deferred 保留）
        //    → 非 anthropic 最终工具集与改动前逐项一致。
        assertThat(names(ToolSearchService.filterToolsForSchema(
                TOOLS_WITH_SEARCH, false, Set.of(), Set.of())))
            .as("中性证明：deferred 空（改动前形态）与非空（R8 形态）结果逐项相同")
            .containsExactlyInAnyOrderElementsOf(names(withDeferred));
    }

    @Test
    @DisplayName("[mode=activate] activateTools 门：activate 生效写激活集并回传；search 门关 → 空返回不写")
    void activateTools_modeGate() {
        // WHY（R11 2026-09-12）：原 isActivateMode() accessor 是死码（仅测试引用）已删；mode=activate
        //   的唯一真实消费路径是 activateTools(...)（ToolSearchTool.java:262/283 调用）→ 门在
        //   doActivateTools（modeEnum() != ACTIVATE 即短路）。本用例经该真实路径接管原 accessor 的
        //   语义覆盖（activate 写激活集 / search 不写），不因删 accessor 丢掉这层。
        //   变异：把 doActivateTools 的 ACTIVATE 门去掉（改成恒写）→ 下方 search 分支变红。
        Object prevInstance = injectInstance(new ToolSearchService());
        try {
            ToolSearchService.modeOverride = "activate";
            assertThat(ToolSearchService.activateTools(List.of("WebSearch")))
                    .as("mode=activate → 命中 defer 名写入激活集并回传（ToolSearchTool 据此发激活提示）")
                    .containsExactly("WebSearch");
            assertThat(ToolSearchService.isActivated("WebSearch"))
                    .as("激活集已写入 → filterToolsForSchema 将其视为 discovered 保留")
                    .isTrue();

            ToolSearchService.modeOverride = "search";
            assertThat(ToolSearchService.activateTools(List.of("Bash")))
                    .as("mode=search → 门关，空返回、不写激活集")
                    .isEmpty();
            assertThat(ToolSearchService.isActivated("Bash")).isFalse();
        } finally {
            ToolSearchService.modeOverride = null;
            clearActivatedTools();
            restoreInstance(prevInstance);
        }
    }

    @Test
    @DisplayName("[mode] 两态互斥解析：search（默认）/ activate；非法值含已删 full → 回落 search")
    void parseMode_twoStates() {
        // WHY（R11 2026-09-12）：删除 full 死码后 mode 仅两态（search | activate）—— 一个键切换，
        //   非法值/未配（含已删除的 "full"）→ 回落默认 search（懒加载，最安全）。
        assertThat(ToolSearchService.parseMode("activate"))
                .isEqualTo(ToolSearchService.ToolSearchOpenAiMode.ACTIVATE);
        assertThat(ToolSearchService.parseMode("search"))
                .isEqualTo(ToolSearchService.ToolSearchOpenAiMode.SEARCH);
        assertThat(ToolSearchService.parseMode("bogus"))
                .isEqualTo(ToolSearchService.ToolSearchOpenAiMode.SEARCH);
        assertThat(ToolSearchService.parseMode(null))
                .isEqualTo(ToolSearchService.ToolSearchOpenAiMode.SEARCH);
        assertThat(ToolSearchService.parseMode("full"))
                .as("full 枚举值已删（R11）→ 不再识别，回落默认 search")
                .isEqualTo(ToolSearchService.ToolSearchOpenAiMode.SEARCH);
    }

    // ═══ 非 Spring 单测 helper：activateTools 经 doActivateTools 需 INSTANCE 非 null（无容器时为 null）
    //     → 注入一个裸实例并复位；激活集是全局静态，用后必清（否则污染 filterToolsForSchema 用例）═══

    /** 注入 ToolSearchService.INSTANCE · 返回旧值供 {@link #restoreInstance(Object)} 还原。 */
    private static Object injectInstance(ToolSearchService inst) {
        try {
            java.lang.reflect.Field f = ToolSearchService.class.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            Object prev = f.get(null);
            f.set(null, inst);
            return prev;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ToolSearchService.INSTANCE 注入失败", e);
        }
    }

    /** 还原 INSTANCE 为 {@link #injectInstance} 返回的旧值。 */
    private static void restoreInstance(Object prev) {
        try {
            java.lang.reflect.Field f = ToolSearchService.class.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            f.set(null, prev);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ToolSearchService.INSTANCE 还原失败", e);
        }
    }

    /** 清空全局激活集（ACTIVATED_TOOLS）· 测试间隔离。 */
    @SuppressWarnings("unchecked")
    private static void clearActivatedTools() {
        try {
            java.lang.reflect.Field f = ToolSearchService.class.getDeclaredField("ACTIVATED_TOOLS");
            f.setAccessible(true);
            ((java.util.Map<String, Boolean>) f.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("清空 ACTIVATED_TOOLS 失败", e);
        }
    }

    @Test
    @DisplayName("isDeferredToolsDeltaEnabled：默认 false（USER_TYPE≠ant + glacier flag Java N/A）")
    void isDeferredToolsDeltaEnabled_defaultFalse() {
        // WHY: toolSearch.ts:629-633 —— false → claude.ts:1330 prepend 路径（H4 实现）；
        //   true → 完整 deferred_tools_delta attachment（OPD-H-06 残留）。
        // [dtd-cfg] 该 env 层判据现经 currentEnv() seam（测试可注入）；未注入时读 System.getenv()
        //   → 默认非 'ant' 环境 → false。生产「前端可配」入口是 DB 覆盖（统一判定
        //   PromptAlignSettingsResolver.staticDeferredToolsDeltaEnabled，另测）。
        assertThat(ToolSearchService.isDeferredToolsDeltaEnabled()).isFalse();
    }

    @Test
    @DisplayName("isDeferredToolsDeltaEnabled：currentEnv seam 注入 USER_TYPE=ant → true（env 层可注入，dtd-cfg）")
    void isDeferredToolsDeltaEnabled_envSeam_antTrue() {
        // WHY（dtd-cfg）：把 System.getenv 直读改为 currentEnv() seam —— 内圈拷贝删除后 env 层
        //   可测、可注入。变异：改回 System.getenv("USER_TYPE") 直读 → 本用例注入失效 → false → 红。
        ToolSearchService.envOverride = Map.of("USER_TYPE", "ant");
        assertThat(ToolSearchService.isDeferredToolsDeltaEnabled()).isTrue();
    }

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }
}
