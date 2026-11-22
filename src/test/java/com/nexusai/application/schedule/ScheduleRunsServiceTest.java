package com.nexusai.application.schedule;

import com.nexusai.repository.schedule.entity.ScheduleRunRecord;
import com.nexusai.repository.schedule.mapper.ScheduleRunMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ScheduleRunsService} 的读取聚合（V306）。
 *
 * <p><b>为什么「每任务只取最新一条」值得单测</b>：列表的绿点/红点是这个方法的输出。
 * 若取错（比如拿到更早的那条 error），用户会看到「上次失败」而实际最近一次成功 ——
 * 这正是本次改造要消灭的假信号（原实现读 {@code schedules.last_run_status}，
 * 那是<b>入队那一刻</b>写的）。
 *
 * <p><b>为什么还要测 null mapper 不抛</b>：本服务在 {@code AgentJob} / {@code ScheduleService}
 * 里都是 {@code @Autowired(required = false)}（非 Spring 夹具、旧测试装配都会是 null）。
 * 那时若抛异常，「读列表」会整页 500 —— 用户裁定过口径：查不到就回落旧列，不能把列表打挂。
 *
 * <p>⛔ 不启 Spring（会迁移用户真库）；不测真实 SQL（mapper 用 {@link Proxy} 假造）。
 */
class ScheduleRunsServiceTest {

    private static ScheduleRunRecord run(String scheduleId, String status) {
        ScheduleRunRecord r = new ScheduleRunRecord();
        r.setScheduleId(scheduleId);
        r.setStatus(status);
        return r;
    }

    /** 让 `selectListByQuery` 恒返回给定行的 mapper（模拟「已按 started_at 倒序」的查询结果）。 */
    private static ScheduleRunsService withRows(List<ScheduleRunRecord> rows) throws Exception {
        ScheduleRunMapper fake = (ScheduleRunMapper) Proxy.newProxyInstance(
            ScheduleRunsServiceTest.class.getClassLoader(),
            new Class<?>[]{ScheduleRunMapper.class},
            (proxy, method, args) -> {
                if ("selectListByQuery".equals(method.getName())) return rows;
                return null;
            });
        ScheduleRunsService svc = new ScheduleRunsService();
        var f = ScheduleRunsService.class.getDeclaredField("runMapper");
        f.setAccessible(true);
        f.set(svc, fake);
        return svc;
    }

    @Test
    @DisplayName("每任务取**第一条**（查询已按 started_at 倒序）—— 取错就会把「最近成功」显示成「上次失败」")
    void latestStatus_takesFirstRowPerSchedule() throws Exception {
        var svc = withRows(List.of(
            run("s1", "ok"),        // 最新
            run("s1", "error"),     // 更早
            run("s2", "running")
        ));
        Map<String, String> m = svc.latestStatusBySchedule();
        assertThat(m).containsEntry("s1", "ok");
        assertThat(m).containsEntry("s2", "running");
        assertThat(m.get("s1")).as("必须是最近一次，不是更早那次").isNotEqualTo("error");
    }

    @Test
    @DisplayName("mapper 未注入 ⇒ 返回空 Map，不抛（调用方据此回落 schedules.last_run_status）")
    void latestStatus_nullMapper_isEmpty() {
        var svc = new ScheduleRunsService();
        assertThatCode(svc::latestStatusBySchedule).doesNotThrowAnyException();
        assertThat(svc.latestStatusBySchedule()).isEmpty();
    }

    @Test
    @DisplayName("listBySchedule：scheduleId 为空 ⇒ 空列表（不查库）")
    void listBySchedule_nullId_isEmpty() {
        var svc = new ScheduleRunsService();
        assertThat(svc.listBySchedule(null, 50)).isEmpty();
    }

    @Test
    @DisplayName("listRecent：mapper 未注入 ⇒ 空列表，不抛（运行记录页显示空态而不是报错）")
    void listRecent_nullMapper_isEmpty() {
        var svc = new ScheduleRunsService();
        assertThatCode(() -> svc.listRecent(100)).doesNotThrowAnyException();
        assertThat(svc.listRecent(100)).isEmpty();
    }
}
