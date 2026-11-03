package com.nexusai.apis.settings;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.settings.dto.ModelTierRole;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Settings REST 端点（singleton） */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    @Autowired private SettingsService settingsService;

    /** GET：响应 = {@link SettingsResponse}（内层 SettingsDto 摊平 + appName/configHome 两个只读键）。
     *  ⛔ 这两个键只在这里出站：PUT 的入参/出参仍是 {@link SettingsDto} ⇒ 结构上写不动（见 SettingsResponse javadoc）。 */
    @GetMapping
    public SettingsResponse get() {
        return new SettingsResponse(
            settingsService.get(),
            NexusaiPaths.getAppName(),
            NexusaiPaths.getAppConfigHomeDir());
    }

    @PutMapping
    public SettingsDto update(@RequestBody SettingsDto req) {
        return settingsService.update(req);
    }

    /**
     * DELETE 模型档位（全部）—— 「一键清除所有已配置的模型」。
     *
     * <p><b>为什么需要独立的 DELETE</b>：{@link #update} 的 merge 契约是「{@code null} = 不覆盖」
     * ⇒ 用 PUT <b>无法</b>表达「把档位清空」（清空要传 {@code null}，而 {@code null} 恰被当成「别动它」）。
     * 清空与覆盖语义相反，故走独立通道。
     *
     * <p>返回清空后的 {@link SettingsDto}（前端经 mergeSettings 合并回 state）。
     */
    @DeleteMapping("/model-tiers")
    public SettingsDto clearAllModelTiers() {
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除全部模型档位绑定");
        }
        return settingsService.clearAllModelTiers();
    }

    /**
     * DELETE 模型档位（单个）—— 解除某一个档位与模型的绑定。
     *
     * @param role 档位 wire 名（main / fast / strong / medium / weak / subagent /
     *             fallback / multimodal / tts / asr / classifier）
     * @return 清空后的 {@link SettingsDto}
     * @throws ValidationException 档位名非法（400）
     * @throws NotFoundException   settings 单例行缺失（404）
     */
    @DeleteMapping("/model-tiers/{role}")
    public SettingsDto clearModelTier(@PathVariable String role) {
        ModelTierRole r = ModelTierRole.fromWire(role);
        if (r == null) {
            // fail loud：未知档位名 → 400，不静默忽略（静默会让前端以为清掉了，实际没动）
            throw new ValidationException(
                "档位非法（允许 " + String.join("/", ModelTierRole.wireNames()) + "）：" + role);
        }
        if (log.isInfoEnabled()) {
            log.info("[SettingsController] 收到请求：清除模型档位 {}", r.wire());
        }
        return settingsService.clearModelTier(r);
    }
}
