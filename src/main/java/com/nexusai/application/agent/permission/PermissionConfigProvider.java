package com.nexusai.application.agent.permission;

import com.nexusai.repository.permissions_config.entity.PermissionsConfigRecord;
import com.nexusai.repository.permissions_config.mapper.PermissionsConfigMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * bypassPermissions 数据库开关提供者 · 对齐 CC Statsig org 门
 * {@code 'tengu_disable_bypass_permissions_mode'}（permissionSetup.ts:701 / :934 / :1374）。
 *
 * <h2>为什么存在（方案 A，用户拍板）</h2>
 * <p>本项目无 Statsig infra，CC 生产 {@code Config.defaults()} 恒 {@code ()->false}（org 门永关）。
 * 用户拍板以数据库存开关替代：{@code permissions_config} 单行 id=1 表，启动读一次 + 登录重读
 * （{@link #refresh()}）+ REST 管理端点。本类提供
 * {@link #isBypassPermissionsDisabled()} 作为
 * {@link InitialPermissionModeResolver.Config#statsigDisableBypassPermissionsMode()} 的
 * {@code BooleanSupplier}（等价 CC {@code checkStatsigFeatureGate_CACHED_MAY_BE_STALE(...)}）。
 *
 * <h2>读策略</h2>
 * <ul>
 *   <li><b>启动读一次</b>：{@code @PostConstruct} 在 Spring 实例化后立即读 DB 入缓存。</li>
 *   <li><b>登录重读</b>：{@link #refresh()} 重读 DB 刷新缓存（登录 / 会话初始化端点调用，
 *       对齐 CC {@code /login} 后 {@code resetBypassPermissionsCheck()} 语义——org 门随新账号生效）。</li>
 *   <li><b>读写开关</b>：{@link #setDisableBypassPermissions(boolean)} 写库 + 刷新缓存（REST 管理端点调用）。</li>
 * </ul>
 *
 * <h2>注入约定</h2>
 * <p>{@code @Autowired(required=false)} mapper（对齐 {@code SettingsService} 字段注入惯例）——
 * 非 Spring 单测 / 无 DB 场景 mapper 为 null，读 DB 回退 {@code false}（不禁用），
 * 与 CC 生产恒 {@code false} 一致。
 */
@Component
public class PermissionConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigProvider.class);

    /** 单行配置 id（同 V16 DDL {@code CHECK (id=1)}）。 */
    private static final int SINGLETON_ID = 1;

    /** 缓存开关值：启动读一次 + refresh() 重读。默认 false = 不禁用（对齐 CC 生产恒 false）。 */
    private final AtomicBoolean disableBypassPermissions = new AtomicBoolean(false);

    /** 生产由 {@code @Autowired(required=false)} 注入；测试可经 {@link #PermissionConfigProvider(PermissionsConfigMapper)} 手动注入。 */
    @Autowired(required = false)
    private PermissionsConfigMapper mapper;

    public PermissionConfigProvider() {
    }

    /** 测试 / 手动注入构造器（Mockito 单测显式传假 mapper）。 */
    public PermissionConfigProvider(PermissionsConfigMapper mapper) {
        this.mapper = mapper;
    }

    /** 启动读一次：Spring 容器实例化后立即从 DB 读入缓存。 */
    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 当前是否禁用 bypassPermissions（读缓存，不做 DB 查询）。
     *
     * @return true = 禁用门关闭（对齐 CC gate true）；false = 不禁用（bypass 可用）
     */
    public boolean isBypassPermissionsDisabled() {
        return disableBypassPermissions.get();
    }

    /**
     * 重读 DB 并刷新缓存（登录 / 会话初始化后调用）。
     *
     * <p>对齐 CC {@code /login} 后 {@code resetBypassPermissionsCheck()}（bypassPermissionsKillswitch.ts:53）：
     * org 门随新账号重新生效。DB 读失败（表缺失 / 连接异常）→ 回退 {@code false}（不禁用），
     * 与 CC 生产恒 {@code false} 一致，避免 killswitch 配置异常拖垮会话创建。
     */
    public void refresh() {
        boolean v;
        try {
            v = readFromDb();
        } catch (Exception e) {
            log.error("PermissionConfigProvider: 读取 bypassPermissions 开关失败，回退 false（不禁用）", e);
            v = false;
        }
        disableBypassPermissions.set(v);
        log.info("PermissionConfigProvider: bypassPermissions 禁用开关已从 DB 读取，disableBypassPermissions={}（对齐 CC tengu_disable_bypass_permissions_mode）", v);
    }

    /**
     * 写库 + 刷新缓存（REST 管理端点 PUT /disable、PUT /enable 调用）。
     *
     * @param disabled true = 禁用 bypassPermissions；false = 启用
     */
    public void setDisableBypassPermissions(boolean disabled) {
        if (mapper == null) {
            log.warn("PermissionConfigProvider: mapper 未注入（非 Spring/无 DB），无法写库，仅更新内存缓存 disableBypassPermissions={}", disabled);
            disableBypassPermissions.set(disabled);
            return;
        }
        PermissionsConfigRecord rec = mapper.selectOneById(SINGLETON_ID);
        boolean insert = rec == null;
        if (insert) {
            rec = new PermissionsConfigRecord();
            rec.setId(SINGLETON_ID);
        }
        rec.setDisableBypassPermissions(disabled ? 1 : 0);
        if (insert) {
            mapper.insert(rec);
        } else {
            mapper.update(rec);
        }
        disableBypassPermissions.set(disabled);
        log.info("PermissionConfigProvider: bypassPermissions 禁用开关已写入 DB 并刷新缓存，disableBypassPermissions={}（insert={}）", disabled, insert);
    }

    private boolean readFromDb() {
        if (mapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("PermissionConfigProvider: mapper 未注入（非 Spring/无 DB），回退 false（不禁用）");
            }
            return false;
        }
        PermissionsConfigRecord rec = mapper.selectOneById(SINGLETON_ID);
        return rec != null
                && rec.getDisableBypassPermissions() != null
                && rec.getDisableBypassPermissions() == 1;
    }
}
