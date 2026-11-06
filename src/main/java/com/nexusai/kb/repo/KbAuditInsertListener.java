package com.nexusai.kb.repo;

import com.mybatisflex.annotation.InsertListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

import java.time.LocalDateTime;

/**
 * 插入时自动填充审计字段 · 对齐 zjky {@code DefaultDBFieldHandler.insertFill}。
 *
 * <p><b>源侧真源</b>（反编译 {@code zjky-spring-boot-starter-mybatis-2026.2.10.jar}，
 * {@code com.zjky.pro.framework.mybatis.core.handler.DefaultDBFieldHandler}）：
 * <pre>
 *   insertFill(MetaObject):
 *     if (originalObject instanceof BaseDO) {
 *       LocalDateTime now = LocalDateTime.now();
 *       if (getCreateTime() == null) setCreateTime(now);
 *       if (getUpdateTime() == null) setUpdateTime(now);
 *       ... creator/updater 取自 SecurityFrameworkUtils.getLoginUserId() ...
 *     }
 * </pre>
 * 即：<b>只在字段为空时填充</b>（不覆盖调用方显式设置的值）。
 *
 * <p><b>与源侧的差异（必须明说）</b>：
 * <ul>
 *   <li>源侧 creator/updater 取登录用户 ID；NexusAI 单机无登录 ⇒ 保持调用方给的值，
 *       未给则留空串（⛔ 不伪造用户 ID）。</li>
 *   <li>源侧用 MyBatis-Plus 的 {@code MetaObjectHandler}；NexusAI 用 MyBatis-Flex 的
 *       {@code InsertListener}（挂在 {@code @Table(onInsert = ...)} 上）。</li>
 * </ul>
 *
 * <p><b>时间格式化</b>：写入前不走 {@link LocalDateTime#toString()}，
 * 而是由 {@link KbSqliteLocalDateTimeTypeHandler} 在 JDBC 层统一格式化
 * —— 两条路径都必须保证落库形态是 {@code yyyy-MM-dd HH:mm:ss}。
 */
public class KbAuditInsertListener implements InsertListener {

    @Override
    public void onInsert(Object entity) {
        if (!(entity instanceof KbBaseRecord record)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 只在为空时填充（对齐源侧 Objects.isNull 判据，⛔ 不覆盖调用方显式值）
        if (record.getCreateTime() == null) {
            record.setCreateTime(now);
        }
        if (record.getUpdateTime() == null) {
            record.setUpdateTime(now);
        }
        if (record.getDeleted() == null) {
            record.setDeleted(0);
        }
        if (record.getTenantId() == null) {
            record.setTenantId(0L);
        }
        // ⚠️ MyBatis-Flex 的 insert 会把 null 字段也列进 INSERT 列清单 ⇒ DB 的
        //    DEFAULT '' 不生效（实测 creator/updater 落库为 null）。zjky 的建表 DDL
        //    是 `creator varchar(64) NULL DEFAULT ''` ⇒ 空串是它的默认值，在此补上。
        if (record.getCreator() == null) {
            record.setCreator("");
        }
        if (record.getUpdater() == null) {
            record.setUpdater("");
        }
    }
}
