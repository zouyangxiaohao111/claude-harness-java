package com.nexusai.apis.settings;

import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.domain.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** Settings REST 端点（singleton） */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    @Autowired private SettingsService settingsService;

    @GetMapping
    public SettingsDto get() {
        return settingsService.get();
    }

    @PutMapping
    public SettingsDto update(@RequestBody SettingsDto req) {
        return settingsService.update(req);
    }
}
