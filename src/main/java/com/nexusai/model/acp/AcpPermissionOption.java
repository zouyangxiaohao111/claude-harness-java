package com.nexusai.model.acp;

/**
 * ACP {@code session/request_permission} 里的一个选项 · 对齐 ACP schema
 * {@code PermissionOption{optionId, name, kind}}。
 *
 * <p>{@code kind} 取值域（协议闭集）：
 * {@code allow_once | allow_always | reject_once | reject_always}。
 *
 * <p>MVP 只做「允许 / 拒绝」二值语义：允许时取第一个 {@code allow_once|allow_always}
 * 的 optionId，拒绝时回 {@code cancelled}。精确 optionId 往返列为后续项（需要一个
 * 能承载 optionId 的响应字段，见影响面 §6 Q5）。
 *
 * @param optionId 选项唯一 id（回传给外部 agent 的原值）
 * @param name     人类可读文案
 * @param kind     选项性质（可空 · 外部 agent 可能省略）
 */
public record AcpPermissionOption(String optionId, String name, String kind) {

    /** 是否属于「允许」类选项。 */
    public boolean isAllow() {
        return "allow_once".equals(kind) || "allow_always".equals(kind);
    }
}
