package com.nexusai.domain.settings;

import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [T5 前置实测探针] {@code SettingsService} 读路径有没有缓存？
 *
 * <p><b>WHY（规则九·验证意图）</b>：设计
 * {@code docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.6} 计划让
 * {@code DynamicHeaderExpander} 的全局开关（{@code allow_dynamic_header_values}）由**每次
 * LLM 请求**读取。若 {@code SettingsService} 无缓存，则该开关会把「每次 LLM 请求」变成
 * 「每次 LLM 请求打一次 DB」——这是设计 §11 R5 登记的待决策项，也是本任务要求「若无缓存
 * 停下报告」的那个判据。
 *
 * <p><b>取证方式</b>：mock {@link SettingsMapper}，连续调 3 次
 * {@link SettingsService#get()}，用 Mockito {@code verify(times(3))} 断言 mapper 被调了 3 次
 * ——**服务层若有任何 memoize/字段缓存/请求级缓存，第 2、3 次就不会到达 mapper**。
 *
 * <p><b>未覆盖</b>：本用例只证明「服务层不缓存」，不证明 mapper 之下是否有 MyBatis 层缓存。
 * 后者由静态事实回答：{@code SettingsMapper} 是空接口
 * （{@code interface SettingsMapper extends BaseMapper<SettingsRecord> {}），
 * {@code selectOneById} 是 MyBatis-Flex 的 BaseMapper 默认方法 → 每次生成并执行 SQL；
 * {@code application.yml} 的 {@code mybatis-flex.configuration} 未设任何 cache 开关。
 * 且本仓两个既有「实时读源」（{@code CompactSettingsResolver} / {@code PromptAlignSettingsResolver}）
 * 的类级 JavaDoc **明文写「不缓存」**：
 * 「每次 {@code SettingsMapper#selectOneById(int)} 单行（id=1，settings 单行多列），不缓存」。
 *
 * @since 2026-09-12 provider-custom-headers 前置实测
 */
@DisplayName("[T5 探针] SettingsService 读路径无缓存")
class SettingsServiceCacheProbeTest {

    private static final Logger log = LoggerFactory.getLogger(SettingsServiceCacheProbeTest.class);

    @Test
    @DisplayName("T5 · get() 连续 3 次 → settingsMapper.selectOneById(1) 被调 3 次（服务层零缓存）")
    void t5_getHitsMapperEveryCall() {
        SettingsRecord row = new SettingsRecord();
        row.setId(1);
        row.setAgentSwarmsEnabled(true);

        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        Mockito.when(mapper.selectOneById(1)).thenReturn(row);

        SettingsService svc = new SettingsService();
        ReflectionTestUtils.setField(svc, "settingsMapper", mapper);

        int calls = 3;
        for (int i = 0; i < calls; i++) {
            svc.get();
        }

        verify(mapper, times(calls)).selectOneById(1);

        System.out.println("[T5 探针] get() 调用 " + calls + " 次 → mapper.selectOneById(1) 命中 "
            + calls + " 次 ⇒ 服务层无缓存，每次 get() 都到 mapper");
        log.info("[T5 探针] get() 调用 {} 次 → mapper.selectOneById(1) 命中 {} 次 ⇒ 服务层无缓存",
            calls, calls);
    }
}
