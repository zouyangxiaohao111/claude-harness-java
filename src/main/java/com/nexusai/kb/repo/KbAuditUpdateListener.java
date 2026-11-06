package com.nexusai.kb.repo;

import com.mybatisflex.annotation.UpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

import java.time.LocalDateTime;

/**
 * 更新时自动填充 {@code updateTime} · 对齐 zjky {@code DefaultDBFieldHandler.updateFill}。
 *
 * <p><b>源侧真源</b>：{@code DefaultDBFieldHandler} 的 updateFill 分支把
 * {@code updateTime} 置为当前时间（并取登录用户填 {@code updater}）。
 *
 * <p><b>与源侧的差异</b>：源侧 {@code updateTime} 上的注解是
 * {@code @TableField(fill = INSERT_UPDATE)} ⇒ <b>每次更新都覆盖</b>。
 * 本类同样在每次更新时无条件覆盖（⛔ 不允许调用方手工改 {@code updateTime}）。
 *
 * <p><b>为什么不覆盖 {@code updater}</b>：NexusAI 单机无登录用户，
 * 伪造用户 ID 会让审计失真 ⇒ 保持调用方给的值或原值不动。
 */
public class KbAuditUpdateListener implements UpdateListener {

    @Override
    public void onUpdate(Object entity) {
        if (!(entity instanceof KbBaseRecord record)) {
            return;
        }
        // 对齐源侧 INSERT_UPDATE 语义：每次更新无条件刷新
        record.setUpdateTime(LocalDateTime.now());
    }
}
