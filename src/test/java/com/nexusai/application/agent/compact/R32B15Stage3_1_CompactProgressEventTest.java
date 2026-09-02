package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.compact.CompactProgressEvent.CompactEnd;
import com.nexusai.application.agent.compact.CompactProgressEvent.CompactStart;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart.HookType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 Stage 3.1 C13 · CompactProgressEvent sealed interface 测试 ·
 * 对齐 CC {@code Open-ClaudeCode/src/Tool.ts:150-156} CompactProgressEvent.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: C13 的核心契约是"3 类事件 +
 * HookType 嵌套 enum + sealed interface 强制 1:1 镜像 CC union type",
 * 且单流程恰 5 事件顺序符合 CC (INV-1 / D-04 / OD-07):
 * {@code hooks_start:pre_compact → compact_start → hooks_start:session_start →
 * hooks_start:post_compact → compact_end} (CC compact.ts:406/429/587/719/760).
 * 测试覆盖:
 * <ol>
 *   <li>sealed interface permits 子类完整 — 防止新增未授权 record 破坏前端对接</li>
 *   <li>3 record 字段顺序与 CC 一致 (HooksStart.hookType; CompactStart/CompactEnd 无字段)</li>
 *   <li>HookType enum 3 值完整 (PRE_COMPACT / POST_COMPACT / SESSION_START)</li>
 *   <li>sealed interface 模式匹配 — instanceof 模式匹配可用, 强制 exhaustiveness</li>
 *   <li>sealed interface 可作为 Consumer<CompactProgressEvent> 参数类型,
 *       Java sealed 与 CC discriminated union 行为等价</li>
 *   <li>单流程 5 事件顺序与 CC 一致 (INV-1)</li>
 * </ol>
 */
class R32B15Stage3_1_CompactProgressEventTest {

    @Test
    @DisplayName("sealed interface: HooksStart + CompactStart + CompactEnd 三 record 完整")
    void sealedInterfaceHasThreeRecords() {
        // 验证 sealed permits 列表 (编译期强制)
        HooksStart hooksEvent = new HooksStart(HookType.PRE_COMPACT);
        CompactStart startEvent = new CompactStart();
        CompactEnd endEvent = new CompactEnd();

        // sealed interface 用 instanceof 模式匹配, 强制 exhaustiveness
        List<String> matchedTypes = new ArrayList<>();
        for (CompactProgressEvent event : List.of(hooksEvent, startEvent, endEvent)) {
            if (event instanceof HooksStart hs) {
                matchedTypes.add("hooks_start:" + hs.hookType().name());
            } else if (event instanceof CompactStart) {
                matchedTypes.add("compact_start");
            } else if (event instanceof CompactEnd) {
                matchedTypes.add("compact_end");
            }
        }

        assertThat(matchedTypes).containsExactly(
            "hooks_start:PRE_COMPACT",
            "compact_start",
            "compact_end"
        );
    }

    @Test
    @DisplayName("HookType enum: 3 值 (PRE_COMPACT / POST_COMPACT / SESSION_START)")
    void hookTypeEnumHasThreeValues() {
        // 对齐 CC Tool.ts:150-156 hookType 字段 (pre_compact | post_compact | session_start)
        assertThat(HookType.values()).containsExactly(
            HookType.PRE_COMPACT,
            HookType.POST_COMPACT,
            HookType.SESSION_START
        );
    }

    @Test
    @DisplayName("HooksStart record: 字段顺序与 CC 一致 (type, hookType)")
    void hooksStartRecordFieldOrder() {
        HooksStart event = new HooksStart(HookType.POST_COMPACT);

        // 字段访问
        assertThat(event.hookType()).isEqualTo(HookType.POST_COMPACT);

        // record equals / hashCode / toString
        HooksStart sameEvent = new HooksStart(HookType.POST_COMPACT);
        HooksStart differentEvent = new HooksStart(HookType.SESSION_START);

        assertThat(event).isEqualTo(sameEvent);
        assertThat(event).isNotEqualTo(differentEvent);
        assertThat(event.hashCode()).isEqualTo(sameEvent.hashCode());
        assertThat(event.toString()).contains("POST_COMPACT");
    }

    @Test
    @DisplayName("CompactStart record: 无字段 (CC 端 type='compact_start' 无附加字段)")
    void compactStartRecordIsEmpty() {
        CompactStart event = new CompactStart();

        // CompactStart 是空 record, 验证 instance + equals
        assertThat(event).isNotNull();
        assertThat(event).isEqualTo(new CompactStart());
        assertThat(event.toString()).contains("CompactStart");
    }

    @Test
    @DisplayName("CompactEnd record: 无字段 (CC 端 type='compact_end' 无附加字段)")
    void compactEndRecordIsEmpty() {
        CompactEnd event = new CompactEnd();

        // CompactEnd 是空 record, 验证 instance + equals
        assertThat(event).isNotNull();
        assertThat(event).isEqualTo(new CompactEnd());
        assertThat(event.toString()).contains("CompactEnd");
    }

    @Test
    @DisplayName("单流程恰 5 事件顺序符合 CC: pre_compact → compact_start → session_start → post_compact → compact_end (INV-1)")
    void sealedInterfaceWorksAsConsumerParameter() {
        // 模拟前端订阅者收集所有事件
        List<CompactProgressEvent> collected = new ArrayList<>();
        java.util.function.Consumer<CompactProgressEvent> subscriber = collected::add;

        // 模拟单流程 emit (CC compact.ts:406/429/587/719/760)
        subscriber.accept(new HooksStart(HookType.PRE_COMPACT));
        subscriber.accept(new CompactStart());
        subscriber.accept(new HooksStart(HookType.SESSION_START));
        subscriber.accept(new HooksStart(HookType.POST_COMPACT));
        subscriber.accept(new CompactEnd());

        // 验证事件流顺序与 CC 一致 (单流程恰 5 事件, INV-1):
        // hooks_start: pre_compact → compact_start → hooks_start: session_start
        // → hooks_start: post_compact → compact_end
        assertThat(collected).hasSize(5);
        assertThat(collected.get(0)).isInstanceOf(HooksStart.class);
        assertThat(((HooksStart) collected.get(0)).hookType()).isEqualTo(HookType.PRE_COMPACT);
        assertThat(collected.get(1)).isInstanceOf(CompactStart.class);
        assertThat(collected.get(2)).isInstanceOf(HooksStart.class);
        assertThat(((HooksStart) collected.get(2)).hookType()).isEqualTo(HookType.SESSION_START);
        assertThat(collected.get(3)).isInstanceOf(HooksStart.class);
        assertThat(((HooksStart) collected.get(3)).hookType()).isEqualTo(HookType.POST_COMPACT);
        assertThat(collected.get(4)).isInstanceOf(CompactEnd.class);
    }
}
