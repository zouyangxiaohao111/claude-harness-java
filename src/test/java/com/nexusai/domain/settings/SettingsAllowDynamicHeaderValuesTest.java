package com.nexusai.domain.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SettingsService#readDbAllowDynamicHeaderValues()} 单测（V72 列 + 规范 §6.6 D6）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：这个 gate 决定 provider 自定义 header 里的
 * {@code ${session_id}} 到底「按会话展开」还是「静默落常量 nexusai-static」——
 * 一旦默认值写反（或把列 NULL 当成 false），用户配好的会话亲和 header 会<b>无声退化成常量</b>，
 * 症状只是「网关偶发 400」，没有任何报错线索。故本类把三条语义钉死：
 * <ol>
 *   <li><b>默认开</b>：行缺失、列 NULL → true（D6：配了就生效，gate-off 只可能是用户主动关）；</li>
 *   <li><b>显式关闭才关</b>：列 = 0 → false；列 = 1 → true；</li>
 *   <li><b>不缓存</b>：每次调用都落 DB，前端 PUT 后下一轮即生效（对齐
 *       {@code readDbAgentSwarmsEnabled} 的同款实时读源；见 {@code SettingsServiceCacheProbeTest}）。</li>
 * </ol>
 *
 * <p><b>变异自证（反向实验实跑）</b>：把默认值改成 {@code false}（或把列的 NULL 当 false）⇒
 * {@link #nullColumnAndMissingRowMeanEnabled} 变红；还原后全绿。
 */
@DisplayName("[V72] settings.allow_dynamic_header_values 实时读源")
class SettingsAllowDynamicHeaderValuesTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        return service;
    }

    @Test
    @DisplayName("默认开：列 NULL 与整行缺失都视为 true（D6）")
    void nullColumnAndMissingRowMeanEnabled() {
        SettingsMapper nullColumnMapper = mock(SettingsMapper.class);
        when(nullColumnMapper.selectOneById(SINGLETON_ID)).thenReturn(new SettingsRecord());
        assertThat(newService(nullColumnMapper).readDbAllowDynamicHeaderValues())
            .as("列 NULL = 未配置 → 默认开")
            .isTrue();

        SettingsMapper missingRowMapper = mock(SettingsMapper.class);
        when(missingRowMapper.selectOneById(SINGLETON_ID)).thenReturn(null);
        assertThat(newService(missingRowMapper).readDbAllowDynamicHeaderValues())
            .as("行缺失 → 默认开")
            .isTrue();
    }

    @Test
    @DisplayName("用户显式关闭：列 = 0 → false")
    void explicitFalseMeansDisabled() {
        SettingsRecord row = new SettingsRecord();
        row.setAllowDynamicHeaderValues(false);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        assertThat(newService(mapper).readDbAllowDynamicHeaderValues()).isFalse();
    }

    @Test
    @DisplayName("用户显式开启：列 = 1 → true")
    void explicitTrueMeansEnabled() {
        SettingsRecord row = new SettingsRecord();
        row.setAllowDynamicHeaderValues(true);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);

        assertThat(newService(mapper).readDbAllowDynamicHeaderValues()).isTrue();
    }

    @Test
    @DisplayName("每次调用都读 DB（不缓存）—— 前端 PUT 后下一轮即生效")
    void readsDbEveryCallWithoutCache() {
        SettingsRecord on = new SettingsRecord();
        on.setAllowDynamicHeaderValues(true);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(on);
        SettingsService service = newService(mapper);

        assertThat(service.readDbAllowDynamicHeaderValues()).isTrue();

        // 模拟前端 PUT 关闭开关：换 mapper 返回行即可，不需要任何刷新/失效调用
        SettingsRecord off = new SettingsRecord();
        off.setAllowDynamicHeaderValues(false);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(off);

        assertThat(service.readDbAllowDynamicHeaderValues())
            .as("服务层若有任何缓存，这里会读到旧值 true")
            .isFalse();
        verify(mapper, times(2)).selectOneById(SINGLETON_ID);
    }

    @Test
    @DisplayName("读 DB 异常 → 回落默认 true（fail-open，不因读失败而关闭展开）")
    void dbFailureFallsBackToEnabled() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(newService(mapper).readDbAllowDynamicHeaderValues())
            .as("异常不得反转默认值（否则一次 DB 抖动就把会话亲和 header 静默降级成常量）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // [2026-09-13 补] SettingsDto 往返契约 —— 让前端的开关真的「点了就生效」
    // ════════════════════════════════════════════════════════════════════

    /**
     * 用 Jackson 反序列化 JSON 造请求体，而<b>不是</b>手写 65 个位置参数
     * （{@code SettingsDto} 是位置 record，手写极易错位，见 {@code SettingsServiceTest} 里
     * 那一串 {@code null} 与其「避免手写 66 参」的注释）。
     *
     * <p><b>为什么必须走 JSON（规则九 · 验证意图而非行为）</b>：这条 bug 的<b>真实形状</b>就是
     * 「{@code SettingsController.update} 收 {@code @RequestBody SettingsDto} → Jackson 反序列化」。
     * 直接 new 一个 Java DTO 会把这条链绕过去，从而<b>测不到</b>「线上契约里根本没有这个字段，
     * 前端传了也白传」这一类故障。顺带钉住「字段确实在 DTO 上」：本测试用的是<b>裸</b>
     * {@code ObjectMapper}（{@code FAIL_ON_UNKNOWN_PROPERTIES} 默认 true），
     * 字段一旦被删就<b>抛</b>——比 Spring Boot 的静默丢弃响亮（有意更严）。
     */
    private static SettingsDto reqFromJson(String json) throws Exception {
        return new ObjectMapper().readValue(json, SettingsDto.class);
    }

    /** 造 DB 行。autoMemoryEnabled 设值：避免 {@code toDto} 回落 settings.json 文件读（沿用
     *  {@code SettingsServiceTest.webSearchRow()} 的既有做法，保持 POJO 单测不碰文件系统）。 */
    private static SettingsRecord rowWith(Boolean allowDynamicHeaderValues) {
        SettingsRecord row = new SettingsRecord();
        row.setAutoMemoryEnabled(true);
        row.setAllowDynamicHeaderValues(allowDynamicHeaderValues);
        return row;
    }

    @Test
    @DisplayName("端到端契约：PUT allowDynamicHeaderValues=false → 落库 + update 返回 + GET 读回 false")
    void updateThenGetRoundTripsAllowDynamicHeaderValues() throws Exception {
        // WHY：「点了等于没点」的直接判据。三处（DTO 字段 / toDto 透出 / update merge 分支）
        //   任缺一处，下面的断言就会从**用户视角**失败：
        //     · 缺 DTO 字段   → Jackson 直接抛（该字段根本不在线上契约里）
        //     · 缺 toDto 透出 → 读回 null（前端开关恒显示默认「开」，用户以为没保存）
        //     · 缺 update 分支 → 落库仍是旧值（用户点了没反应，且全程零报错）
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto updated = service.update(reqFromJson("{\"allowDynamicHeaderValues\":false}"));

        assertThat(updated.allowDynamicHeaderValues())
            .as("update 的返回值必须透出新值（前端 PUT 的响应体取的就是它）").isFalse();
        assertThat(row.getAllowDynamicHeaderValues())
            .as("必须真的写进 DB 列 —— 否则 gate 下一轮读到的还是旧值").isFalse();
        assertThat(service.get().allowDynamicHeaderValues())
            .as("GET 必须读回 false；读不回 = 前端开关恒显示「已开启」").isFalse();
    }

    @Test
    @DisplayName("null-skip：PUT 未带 allowDynamicHeaderValues（= null）→ 不得改动既有值（PATCH 语义）")
    void nullDoesNotOverwriteExistingValue() throws Exception {
        // WHY：settings 的 PUT 是「仅覆盖非空字段」的 PATCH 语义。漏了 null 判断而写成无条件写，
        //   任何一次「只改别的设置」的 PUT 都会把这个开关**静默重置**（清成 null → 回落默认开）。
        SettingsRecord row = rowWith(false);   // 既有 DB 值 = 关
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto req = reqFromJson("{\"language\":\"zh-CN\"}");   // 未带该字段 → null
        assertThat(req.allowDynamicHeaderValues()).as("前置条件：字段缺失必须解析成 null").isNull();

        service.update(req);

        assertThat(row.getAllowDynamicHeaderValues())
            .as("null = 不覆盖：既有 false 必须原样保留").isFalse();
        assertThat(service.get().allowDynamicHeaderValues())
            .as("读回同样必须是 false").isFalse();
    }
}
