package com.nexusai.model.settings.dto;

/**
 * 模型档位角色（settings 里的 11 个「档位模型」字段）。
 *
 * <p><b>为什么需要这个枚举</b>：settings 的档位绑定字段（{@code main_model_name} … {@code classifier_model}）
 * 只能<b>写入非 null 值</b>（{@link com.nexusai.domain.settings.SettingsService#update} 的 merge 契约是
 * 「{@code null} = 不覆盖」）⇒ <b>无法通过 PUT 清除任何一个档位</b>。清除需要一条<b>语义相反</b>的通道
 * （「置 null」），若混进 PUT 会造成契约分叉。故独立成 DELETE 端点 + 本枚举作为档位标识。
 *
 * <p><b>wire 名 = 小写下划线</b>（{@code main} / {@code fast} / …），前端 <code>ModelTierRole</code>
 * 与之一一对应（前端不得自行另起一套 key）。
 *
 * <p><b>消费链路现状</b>（供 UI 如实标注用，勿假设「配了就生效」）：
 * <ul>
 *   <li><b>真实生效</b>：{@code main} / {@code fast} / {@code weak} / {@code medium} /
 *       {@code strong} / {@code subagent} / {@code classifier} / {@code fallback}</li>
 *   <li><b>只写不读</b>（有 resolver 但无调用方，配了当前版本不上发 LLM）：
 *       {@code multimodal} / {@code tts} / {@code asr}</li>
 * </ul>
 */
public enum ModelTierRole {

    /** 主模型 · 对话主力，新建会话默认（会话级可临时覆盖）。 */
    MAIN("main"),
    /** 快速模型 · 轻量任务（命名/摘要/away/hook）。 */
    FAST("fast"),
    /** 最强模型 · 最强复杂任务（计划）。 */
    STRONG("strong"),
    /** 中等模型 · 中等任务（记忆筛选）。 */
    MEDIUM("medium"),
    /** 弱模型 · 轻量查询/快速回答。 */
    WEAK("weak"),
    /** 子代理 · 子代理运行默认模型。 */
    SUBAGENT("subagent"),
    /** 降级模型 · 主模型失败时自动降级。 */
    FALLBACK("fallback"),
    /** 多模态模型 · 文本+图像理解（当前版本只写不读）。 */
    MULTIMODAL("multimodal"),
    /** TTS · 语音合成（当前版本只写不读）。 */
    TTS("tts"),
    /** ASR · 语音转文字（当前版本只写不读）。 */
    ASR("asr"),
    /** 权限分类器 · auto 权限模式分类器用模型；留空用主循环模型。 */
    CLASSIFIER("classifier");

    private final String wire;

    ModelTierRole(String wire) {
        this.wire = wire;
    }

    /** wire 名（小写下划线）· 前端传参用。 */
    public String wire() {
        return wire;
    }

    /**
     * 按 wire 名解析档位 · 未知值返回 null（**不抛异常**）。
     *
     * <p>刻意不抛：档位 key 是前端传的路径参数，未知 key 属于「客户端传错」，
     * 由 Controller 层决定 404 还是静默忽略；Service 层保持「null = 无此档位」的单一语义。
     */
    public static ModelTierRole fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        String w = wire.trim().toLowerCase();
        for (ModelTierRole r : values()) {
            if (r.wire.equals(w)) {
                return r;
            }
        }
        return null;
    }

    /** 全部 wire 名（校验失败时给用户看允许值列表用）。 */
    public static java.util.List<String> wireNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ModelTierRole r : values()) {
            names.add(r.wire);
        }
        return names;
    }
}
