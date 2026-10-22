package com.nexusai.application.agent.tasks;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * [2026-10-22 修复] 后台唤醒主会话必须用**会话里选的模型**，而不是 settings 默认。
 *
 * <p>WHY（规则九 · 测试验证意图）：用户在会话里切换了模型（sessions.model_name，存全名），
 * 但子代理完成通知触发的 CronIdleExecutor.runAgentLoop 只读 settings.main_model_name
 * （实测：对话选了别的模型，后台通知轮次仍走默认配置）。语义应对齐
 * {@code ChatService.resolveModelNameForSession} 的四层链：会话 override 优先于 settings。
 *
 * <p>验收条件：会话有 model_name ⇒ 用它解析出的模型（及其 provider 绑定）；
 * 会话无 override ⇒ 回落 settings；两者都以**全名**返回（避免 provider 绑定漂移）。
 */
@ExtendWith(MockitoExtension.class)
class CronIdleExecutorSessionModelTest {

    @Mock private SettingsMapper settingsMapper;
    @Mock private ModelMapper modelMapper;
    @Mock private ProviderMapper providerMapper;
    @Mock private SessionMapper sessionMapper;

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String invokeResolveMainModelName(CronIdleExecutor ex, String sessionId) throws Exception {
        Method m = CronIdleExecutor.class.getDeclaredMethod("resolveMainModelName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(ex, sessionId);
    }

    private CronIdleExecutor executorWithMappers() throws Exception {
        CronIdleExecutor ex = new CronIdleExecutor();
        set(ex, "settingsMapper", settingsMapper);
        set(ex, "modelMapper", modelMapper);
        set(ex, "providerMapper", providerMapper);
        set(ex, "sessionMapper", sessionMapper);
        return ex;
    }

    private static ModelRecord model(String id, String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName(name);
        m.setProviderId(providerId);
        m.setEnabled(true);   // resolve 后按 enabled 过滤：null 会被当成「未启用」→ 返回 null
        return m;
    }

    private static ProviderRecord provider(String id, String name) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(name);
        return p;
    }

    @Test
    @DisplayName("会话 override 优先于 settings（后台通知不得再用默认模型）")
    void sessionOverrideWins() throws Exception {
        SessionRecord sess = new SessionRecord();
        sess.setModelName("ds-zcw/deepseek-flash");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(sess);

        ProviderRecord p = provider("prov-e90af148", "ds-zcw");
        when(providerMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(p);
        // toFullName 用 selectOneById 还原 provider 名（与 resolve 的 selectOneByQuery 不是同一入口）
        lenient().when(providerMapper.selectOneById("prov-e90af148")).thenReturn(p);
        when(modelMapper.selectOneByQuery(any(QueryWrapper.class)))
            .thenReturn(model("m-dszcw", "deepseek-flash", "prov-e90af148"));

        // settings 默认是另一个 provider 的模型 —— 若实现退回 settings，断言会失败
        SettingsRecord s = new SettingsRecord();
        s.setMainModelName("deepseek/deepseek-flash");
        lenient().when(settingsMapper.selectOneById(eq(1))).thenReturn(s);

        String resolved = invokeResolveMainModelName(executorWithMappers(), "sess-1");

        assertThat(resolved).isEqualTo("ds-zcw/deepseek-flash");
    }

    @Test
    @DisplayName("cmd.sessionId() 为空（无会话锚）→ 安全回落 settings，不抛异常")
    void nullSessionId_fallsBackSafely() throws Exception {
        SettingsRecord s = new SettingsRecord();
        s.setMainModelName("deepseek/deepseek-flash");
        when(settingsMapper.selectOneById(eq(1))).thenReturn(s);

        ProviderRecord pd = provider("prov-b053b03d", "deepseek");
        when(providerMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(pd);
        lenient().when(providerMapper.selectOneById("prov-b053b03d")).thenReturn(pd);
        when(modelMapper.selectOneByQuery(any(QueryWrapper.class)))
            .thenReturn(model("m-deepseek", "deepseek-flash", "prov-b053b03d"));

        String resolved = invokeResolveMainModelName(executorWithMappers(), null);

        assertThat(resolved).isEqualTo("deepseek/deepseek-flash");
    }

    @Test
    @DisplayName("会话无 override → 回落 settings，且返回全名")
    void fallsBackToSettings() throws Exception {
        SessionRecord sess = new SessionRecord(); // modelName = null
        when(sessionMapper.selectOneById("sess-2")).thenReturn(sess);

        SettingsRecord s = new SettingsRecord();
        s.setMainModelName("deepseek/deepseek-flash");
        when(settingsMapper.selectOneById(eq(1))).thenReturn(s);

        ProviderRecord pd = provider("prov-b053b03d", "deepseek");
        when(providerMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(pd);
        lenient().when(providerMapper.selectOneById("prov-b053b03d")).thenReturn(pd);
        when(modelMapper.selectOneByQuery(any(QueryWrapper.class)))
            .thenReturn(model("m-deepseek", "deepseek-flash", "prov-b053b03d"));

        String resolved = invokeResolveMainModelName(executorWithMappers(), "sess-2");

        assertThat(resolved).isEqualTo("deepseek/deepseek-flash");
    }
}
