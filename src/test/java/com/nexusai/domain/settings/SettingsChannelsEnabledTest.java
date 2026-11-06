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
import static org.mockito.Mockito.when;

/**
 * [V254 外部渠道] {@code settings.channels_enabled} 实时读源 + PUT 契约。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：这个开关决定**外部 MCP server 能不能往会话里注入消息**
 * —— 它是外部入口的总闸。把语义写反的后果是：
 * <ul>
 *   <li>把「未配置」当成「开」⇒ 用户没打开通道，外部消息就进来了（安全边界失守，且无任何报错）；</li>
 *   <li>把「用户显式关闭」当成「未配置」⇒ 用户在 UI 关掉后，重启后端通道又被 yml 顶回来
 *       （症状是「我明明关了，怎么又生效了」——最难排查的那类问题）。</li>
 * </ul>
 * 故本类把三条语义钉死：
 * <ol>
 *   <li><b>三态</b>：读源返回 {@code null} = 未配置（≠ false），调用方据此回落 yml 兜底；</li>
 *   <li><b>显式 false 必须被尊重</b>：DB 列 = 0 → 返回 false（不被 yml 覆盖）；</li>
 *   <li><b>三处同步</b>：DTO 字段 / toDto 透出 / update merge 分支，缺一处 = 前端开关「点了等于没点」。</li>
 * </ol>
 */
@DisplayName("[V254] settings.channels_enabled 实时读源与开关契约")
class SettingsChannelsEnabledTest {

    private static final int SINGLETON_ID = 1;

    private SettingsService newService(SettingsMapper mapper) {
        SettingsService service = new SettingsService();
        ReflectionTestUtils.setField(service, "settingsMapper", mapper);
        return service;
    }

    private SettingsRecord rowWith(Boolean channelsEnabled) {
        SettingsRecord row = new SettingsRecord();
        row.setChannelsEnabled(channelsEnabled);
        return row;
    }

    private SettingsDto reqFromJson(String json) throws Exception {
        return new ObjectMapper().readValue(json, SettingsDto.class);
    }

    // ────────────────── 读源三态 ──────────────────

    @Test
    @DisplayName("三态：列 NULL 与整行缺失都返回 null（=未配置，不等于 false）")
    void nullColumnAndMissingRowMeanUnconfigured() {
        // WHY：null 与 false 必须可区分。null = 「没配过」→ 回落 yml 兜底；
        //   false = 「用户明确关了」→ 必须压住 yml。若这里返回 false，
        //   yml 开着的通道在用户 UI 关掉后会被顶回来（重启即复现）。
        SettingsMapper nullColumnMapper = mock(SettingsMapper.class);
        when(nullColumnMapper.selectOneById(SINGLETON_ID)).thenReturn(new SettingsRecord());
        assertThat(newService(nullColumnMapper).readDbChannelsEnabled())
            .as("列 NULL = 未配置 → 必须返回 null（调用方据此回落 yml），不能是 false")
            .isNull();

        SettingsMapper missingRowMapper = mock(SettingsMapper.class);
        when(missingRowMapper.selectOneById(SINGLETON_ID)).thenReturn(null);
        assertThat(newService(missingRowMapper).readDbChannelsEnabled())
            .as("行缺失 = 未配置 → 必须返回 null")
            .isNull();
    }

    @Test
    @DisplayName("用户显式关闭：列 = 0 → false（不得被 yml 兜底顶回）")
    void explicitFalseMeansDisabled() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(rowWith(false));

        assertThat(newService(mapper).readDbChannelsEnabled())
            .as("用户显式关闭必须原样返回 false —— 这是「UI 关掉后重启仍生效」的基础")
            .isFalse();
    }

    @Test
    @DisplayName("用户显式开启：列 = 1 → true")
    void explicitTrueMeansEnabled() {
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(rowWith(true));

        assertThat(newService(mapper).readDbChannelsEnabled())
            .as("用户在 UI 打开通道 → 读源必须返回 true")
            .isTrue();
    }

    // ────────────────── 三处同步（前端开关「点了等于没点」的判据）──────────────────

    @Test
    @DisplayName("端到端契约：PUT channelsEnabled=true → 落库 + update 返回 + GET 读回 true")
    void updateThenGetRoundTripsChannelsEnabled() throws Exception {
        // WHY：settings 的 PUT 是 PATCH 语义，三处任缺一处，用户看到的都是「点了等于没点」：
        //   · 缺 DTO 字段   → Jackson 直接抛（该字段不在线上契约里）
        //   · 缺 toDto 透出 → 读回 null（前端开关恒显默认「关」，用户以为没保存）
        //   · 缺 update 分支 → 落库仍是旧值（全程零报错，最难察觉）
        SettingsRecord row = rowWith(null);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto updated = service.update(reqFromJson("{\"channelsEnabled\":true}"));

        assertThat(updated.channelsEnabled())
            .as("update 的返回值必须透出新值（前端 PUT 的响应体取的就是它）").isTrue();
        assertThat(row.getChannelsEnabled())
            .as("必须真的写进 DB 列 —— 否则门序下一轮读到的还是旧值").isTrue();
        assertThat(service.get().channelsEnabled())
            .as("GET 必须读回 true；读不回 = 前端开关恒显示「已关闭」").isTrue();
    }

    @Test
    @DisplayName("null-skip：PUT 未带 channelsEnabled（= null）→ 不得改动既有值（PATCH 语义）")
    void nullDoesNotOverwriteExistingValue() throws Exception {
        // WHY：任何一次「只改别的设置」的 PUT 都不该把这个开关静默重置。
        //   若写成无条件写，DB 值被清成 null → 回落 yml → 用户显式关掉的通道又被顶回来。
        SettingsRecord row = rowWith(false);   // 既有 DB 值 = 用户已关闭
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(SINGLETON_ID)).thenReturn(row);
        SettingsService service = newService(mapper);

        SettingsDto req = reqFromJson("{\"language\":\"zh-CN\"}");   // 未带该字段 → null
        assertThat(req.channelsEnabled()).as("前置条件：字段缺失必须解析成 null").isNull();

        service.update(req);

        assertThat(row.getChannelsEnabled())
            .as("null = 不覆盖：既有 false 必须原样保留").isFalse();
        assertThat(service.get().channelsEnabled())
            .as("读回同样必须是 false").isFalse();
    }
}
