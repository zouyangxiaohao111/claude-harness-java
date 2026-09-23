package com.nexusai.model.settings.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.apis.settings.SettingsController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SettingsResponse} 纯单测（⛔ 不起 Spring 上下文）：只钉三件事 —— 出站 JSON 仍是<b>扁平</b>、
 * appName/configHome <b>结构上只读</b>、appName=nexusai 时与旧响应一致。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：这两个字段是给前端「跟着 appName 走」用的
 * （场景中台线把 appName 改成 nexusai-scene ⇒ 自有根变 ~/.nexusai-scene），前端拿它拼配置目录
 * 标记与启动页日志路径。<b>若包装方式退化</b>，症状是静默的：
 * <ol>
 *   <li><b>不是扁平</b>（{@code @JsonUnwrapped} 失效 ⇒ 出站变成 {@code {"settings":{...},"appName":…}}）：
 *       前端 AppSettings 的既有字段（theme/mainModelName…）会<b>集体读不到</b>，表现为所有设置项
 *       显示「未配置」——而 JSON 里其实有值。这类「结构变了但请求 200」的退步没有任何报错线索，
 *       只能靠断言钉住顶层同时有内层字段。</li>
 *   <li><b>可回写</b>：一旦有人把这两个字段塞进 {@link SettingsDto}（PUT 的入参类型），
 *       前端回传就会被当配置写库 —— 那是「只读标识」变成「可写配置」的语义漂移。故本类把
 *       SettingsDto 的 record 组件集合与 SettingsController.update 的签名都钉住。</li>
 * </ol>
 *
 * <p><b>ObjectMapper 纪律</b>：反序列化侧显式关闭
 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} —— 这不是放宽断言，而是<b>镜像
 * 生产配置</b>：Spring Boot 的 JacksonAutoConfiguration（Jackson2ObjectMapperBuilder）默认就关掉它，
 * Spring MVC 的 PUT 侧正是按这个配置吃请求体。裸 {@code new ObjectMapper()} 默认是开的，
 * 不关就测不出「PUT 侧真实行为」，会得到一个与线上相反的结论。
 */
class SettingsResponseTest {

    /** 反序列化侧镜像 Spring MVC（FAIL_ON_UNKNOWN_PROPERTIES=false）；序列化侧与默认一致。 */
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String prevAppName;

    @BeforeEach
    void setUp() {
        prevAppName = NexusaiPaths.getAppName();
        // appName 全局静态（volatile），测试共享 JVM ⇒ 期望值只在本类内自设自复原。
        // configHome 同时依赖可能被其它测试类覆写的 override：本类只验「默认派生」，
        // 故先清掉可能泄漏的覆写（clear = 回到生产默认，非引入新状态）。
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setAppNameOverride(prevAppName);
    }

    /** 内层 DTO：只有 theme 有值、其余全 null（避免手写 66 参）。 */
    private static SettingsDto dtoWithTheme(Theme theme) {
        return new SettingsDto(
            theme, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private JsonNode serialize(Theme theme) throws Exception {
        SettingsResponse res = new SettingsResponse(
            dtoWithTheme(theme), NexusaiPaths.getAppName(), NexusaiPaths.getAppConfigHomeDir());
        return mapper.readTree(mapper.writeValueAsString(res));
    }

    @Test
    @DisplayName("(a) 出站 JSON 扁平：顶层同时有 appName/configHome 与内层字段 theme，且【没有】settings 这个嵌套键")
    void flatJson_hasInnerFieldAndReadOnlyKeysAtTopLevel_noNestedSettingsKey() throws Exception {
        JsonNode json = serialize(Theme.light);

        // 内层字段被摊平到顶层（@JsonUnwrapped 生效）
        assertThat(json.has("theme")).as("内层 SettingsDto 字段 theme 必须被摊平到顶层").isTrue();
        assertThat(json.get("theme").asText()).isEqualTo("light");
        // 两个只读键与内层字段同级
        assertThat(json.has("appName")).isTrue();
        assertThat(json.has("configHome")).isTrue();
        assertThat(json.get("appName").asText()).isEqualTo(NexusaiPaths.getAppName());
        assertThat(json.get("configHome").asText()).isEqualTo(NexusaiPaths.getAppConfigHomeDir());
        // ⛔ 不得出现嵌套壳：出现即 AppSettings 既有字段集体读不到
        assertThat(json.has("settings")).as("出站不得有 settings 嵌套键（否则前端既有字段全读不到）").isFalse();
    }

    @Test
    @DisplayName("(b) 只读性：同一份 JSON 反序列化回 PUT 的入参类型 SettingsDto 不报错，且回写丢字段")
    void readOnly_appNameAndConfigHomeAreIgnoredByPutInputType() throws Exception {
        JsonNode out = serialize(Theme.dark);
        // 前提：这两个键在出站 JSON 里确实有值（否则「被忽略」是空断言）
        assertThat(out.get("appName").asText()).isNotBlank();
        assertThat(out.get("configHome").asText()).isNotBlank();

        // 拿同一份 JSON 当 PUT 请求体反序列化（unknown 字段按 Spring MVC 生产配置忽略）
        SettingsDto putInput = mapper.readValue(mapper.writeValueAsString(out), SettingsDto.class);
        assertThat(putInput).as("未知字段不得让 PUT 侧爆炸").isNotNull();
        assertThat(putInput.theme()).isEqualTo(Theme.dark);

        // 回写（PUT 出参 = SettingsDto）⇒ 两个只读键必须消失（结构上没有字段承接）
        JsonNode back = mapper.readTree(mapper.writeValueAsString(putInput));
        assertThat(back.has("appName")).as("SettingsDto 结构上不得承接 appName").isFalse();
        assertThat(back.has("configHome")).as("SettingsDto 结构上不得承接 configHome").isFalse();

        // 结构级正证：SettingsDto 的 record 组件里根本没有这两个名字（不依赖 Jackson 配置）
        assertThat(Arrays.stream(SettingsDto.class.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet()))
            .as("SettingsDto 不得新增 appName / configHome 组件（会连锁改 12 处调用点并放开 PUT 回写）")
            .doesNotContain("appName", "configHome");

        // 结构级正证：PUT 的入参/出参仍是 SettingsDto（本批「PUT 一行都不改」）
        Method update = Arrays.stream(SettingsController.class.getMethods())
            .filter(m -> m.getName().equals("update")).findFirst().orElseThrow();
        assertThat(update.getParameterTypes()).containsExactly(SettingsDto.class);
        assertThat(update.getReturnType()).isEqualTo(SettingsDto.class);
    }

    @Test
    @DisplayName("(c) appName=nexusai ⇒ 出站 appName==\"nexusai\"、configHome 尾段==\".nexusai\"（与旧行为一致）")
    void appNameNexusai_configHomeTailIsDotNexusai() throws Exception {
        NexusaiPaths.setAppNameOverride("nexusai");

        JsonNode json = serialize(Theme.auto);

        assertThat(json.get("appName").asText()).isEqualTo("nexusai");
        assertThat(Path.of(json.get("configHome").asText()).getFileName().toString()).isEqualTo(".nexusai");
    }
}
