package com.nexusai.domain.command;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.dto.CommandDto;
import com.nexusai.model.command.dto.UpdateCommandRequest;
import com.nexusai.repository.command.entity.CommandRecord;
import com.nexusai.repository.command.mapper.CommandMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方案2（用户拍板）· CommandService toggle/update enabled 后清 SkillRegistry 命令缓存测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：前端 PATCH toggle / update 只写 DB enabled
 * （{@code commandMapper.update}），磁盘 SKILL.md 无 enabled frontmatter。若 DB 变更后不清
 * {@link SkillRegistry#refreshCommandsOnly()} 命令缓存，则 {@link SkillRegistry#getAllCommands()}
 * 的 memoize（raw loadAllCommands 缓存在 allCommandsCache 内）不重载 → DB enabled 覆盖不生效
 * （前端禁用/启用仍不真实生效）。方案2 = DB 变更后清缓存 → 下次 getAllCommands 重载读 DB enabled
 * （方案1 覆盖）。本测试验证：
 * <ol>
 *   <li>{@code toggleEnabled} 改 DB enabled 后 → {@code skillRegistry.refreshCommandsOnly()} 被调用</li>
 *   <li>{@code update} 显式改 enabled 后 → refreshCommandsOnly() 被调用</li>
 *   <li>{@code update} 未改 enabled（enabled=null）→ 不触发刷新</li>
 *   <li>未注入 SkillRegistry（{@code @Autowired(required=false)} 容错 POJO 直构）→ toggle 仍工作不抛，
 *       跳过刷新（行为不变）</li>
 * </ol>
 *
 * <p>注入方式：字段反射注入（CommandService 无构造器注入，与 SkillRegistryTest 注入 ToolRegistrationConfig
 * 同款）。CommandMapper/SkillRegistry 均为 Mockito mock（CommandMapper 是 MyBatis-Flex BaseMapper
 * 接口，Mockito 可 mock；skillRegistry.refreshCommandsOnly() 用 verify 断言触发）。
 */
@DisplayName("[方案2] CommandService toggle/update enabled 后清 SkillRegistry 命令缓存")
class CommandServiceToggleRefreshTest {

    /** 反射注入私有字段（CommandService 字段为 @Autowired 私有，无 setter · POJO 测试同款） */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 最小 DB command 行（source='user'，可删/可 toggle） */
    private static CommandRecord userRecord(String id, String name, int enabled) {
        CommandRecord r = new CommandRecord();
        r.setId(id);
        r.setName(name);
        r.setSource("user");
        r.setEnabled(enabled);
        return r;
    }

    @Test
    @DisplayName("toggleEnabled → DB update 后 verify skillRegistry.refreshCommandsOnly()")
    void toggleEnabled_triggersRefresh() throws Exception {
        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(mapper.selectOneById("cmd-1")).thenReturn(userRecord("cmd-1", "my-skill", 1));

        CommandService service = new CommandService();
        setField(service, "commandMapper", mapper);
        setField(service, "skillRegistry", registry);

        CommandDto dto = service.toggleEnabled("cmd-1");

        // enabled 翻转 + DB 写
        assertThat(dto.enabled()).isFalse();
        Mockito.verify(mapper).update(Mockito.any(CommandRecord.class));
        // 方案2: DB enabled 变更 → 清 SkillRegistry 命令缓存（下次 getAllCommands 重载读 DB enabled 生效）
        Mockito.verify(registry).refreshCommandsOnly();
    }

    @Test
    @DisplayName("update 显式改 enabled → verify skillRegistry.refreshCommandsOnly()")
    void update_enabledChanged_triggersRefresh() throws Exception {
        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(mapper.selectOneById("cmd-1")).thenReturn(userRecord("cmd-1", "my-skill", 1));

        CommandService service = new CommandService();
        setField(service, "commandMapper", mapper);
        setField(service, "skillRegistry", registry);

        // 仅改 enabled（其余字段 null）→ baseDir null → 无文件写入，走纯 DB 更新路径
        UpdateCommandRequest req = new UpdateCommandRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, false);
        CommandDto dto = service.update("cmd-1", req);

        assertThat(dto.enabled()).isFalse();
        Mockito.verify(mapper).update(Mockito.any(CommandRecord.class));
        Mockito.verify(registry).refreshCommandsOnly();
    }

    @Test
    @DisplayName("update 未提供 enabled → 不触发刷新（enabledChanged=false 短路）")
    void update_enabledNotProvided_noRefresh() throws Exception {
        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        Mockito.when(mapper.selectOneById("cmd-1")).thenReturn(userRecord("cmd-1", "my-skill", 1));

        CommandService service = new CommandService();
        setField(service, "commandMapper", mapper);
        setField(service, "skillRegistry", registry);

        // 只改描述，enabled=null → enabledChanged=false → 不触发刷新（缓存保留，避免无谓重载）
        UpdateCommandRequest req = new UpdateCommandRequest(
            null, "new desc", null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null);
        service.update("cmd-1", req);

        Mockito.verify(registry, Mockito.never()).refreshCommandsOnly();
    }

    @Test
    @DisplayName("未注入 SkillRegistry（required=false 容错）→ toggle 仍工作不抛，跳过刷新")
    void toggleEnabled_withoutRegistry_noNpe() throws Exception {
        CommandMapper mapper = Mockito.mock(CommandMapper.class);
        Mockito.when(mapper.selectOneById("cmd-1")).thenReturn(userRecord("cmd-1", "my-skill", 1));

        CommandService service = new CommandService();
        setField(service, "commandMapper", mapper);
        // skillRegistry 不注入 → 默认 null（@Autowired(required=false) 容错 POJO 直构）

        CommandDto dto = service.toggleEnabled("cmd-1");

        // 走到这里即证明 null 分支不 NPE，且 enabled 翻转语义不变
        assertThat(dto.enabled()).isFalse();
        Mockito.verify(mapper).update(Mockito.any(CommandRecord.class));
    }
}
