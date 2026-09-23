package com.nexusai.apis.settings;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.SettingsResponse;
import com.nexusai.domain.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Settings REST 端点（singleton） */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

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
}
