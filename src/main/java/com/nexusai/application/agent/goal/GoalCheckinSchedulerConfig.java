package com.nexusai.application.agent.goal;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.domain.session.SessionService;
import com.nexusai.domain.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link GoalCheckinScheduler} 的装配 · 显式把「设置 → 高级」的 check-in 分钟数
 * 接到调度器上。
 *
 * <p><b>WHY 需要独立的 @Bean 而不是直接在调度器里 {@code @Value}</b>：
 * 调度器的 {@code checkinMinutesSource} 是 {@code IntSupplier}（读 DB 列的实时函数），
 * Spring 无法按类型自动装配这种 lambda ⇒ 必须显式构造。
 *
 * <p><b>⛔ 不能在 {@code @Bean} 方法里手动 {@code new} 一个带 {@code @Value} 的对象</b>：
 * 手动 new 出来的实例<b>不会经过 Spring 的属性注入</b> ⇒ {@code @Value} 永不执行、
 * 拿到 null。本类因此走「构造器参数注入 + 显式透传」：{@code SettingsService} 是
 * 真 bean（构造函数参数由 Spring 注入），lambda 里调它的方法读 DB。
 *
 * <p>读源用 {@code ObjectProvider} 懒解析：{@code SettingsService → … } 的依赖链很深，
 * 直接注入可能在启动期形成环（同 {@code GoalService} 的宿主机理由）。
 */
@Configuration
public class GoalCheckinSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(GoalCheckinSchedulerConfig.class);

    /**
     * 注册调度器单例。
     *
     * @param sessionService     会话/目标真源
     * @param settingsProvider   设置服务（懒解析 · 可空：非 Spring 场景/读不到 → 默认 30 分钟）
     * @param notificationQueue  让会话再跑一轮的队列（可空 → check-in 只记日志不投递）
     * @return 常驻调度器（{@code @PostConstruct} 里启动定时线程）
     */
    @Bean
    public GoalCheckinScheduler goalCheckinScheduler(
            SessionService sessionService,
            ObjectProvider<SettingsService> settingsProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            NotificationQueue notificationQueue) {
        // 读源：每次 tick 现读 DB（用户改完「设置→高级」下一拍即生效，不缓存）。
        // SettingsService 不可用 / 读失败 → 返回 0 ⇒ 调度器回落 GoalCheckinConfig 默认 30。
        java.util.function.IntSupplier minutesSource = () -> {
            SettingsService svc = settingsProvider == null ? null : settingsProvider.getIfAvailable();
            if (svc == null) {
                return 0;
            }
            Integer minutes = svc.readDbGoalCheckinMinutes();
            return minutes == null ? 0 : minutes;
        };
        log.info("[GoalCheckinSchedulerConfig] 目标 check-in 调度器已装配"
            + "（间隔读 settings.goal_checkin_minutes，未配置时默认 {} 分钟）",
            GoalCheckinConfig.DEFAULT_CHECKIN_MINUTES);
        return new GoalCheckinScheduler(sessionService, notificationQueue, minutesSource);
    }
}
