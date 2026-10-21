package org.com.sharekhan.telegram;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.CurrentUserService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/telegram")
@RequiredArgsConstructor
public class TelegramSettingsController {
    private final CurrentUserService current;
    private final TelegramSettingsService settings;

    @GetMapping public TelegramSettingsService.Status get() {
        return settings.get(current.requireAppUserId());
    }
    @PutMapping public TelegramSettingsService.Status save(@RequestBody TelegramSettingsService.Request request) {
        return settings.save(current.requireAppUserId(), request);
    }
    @PostMapping("/link") public TelegramSettingsService.Link link() { return settings.link(current.requireAppUserId()); }
    @DeleteMapping public void remove() { settings.remove(current.requireAppUserId()); }
}
