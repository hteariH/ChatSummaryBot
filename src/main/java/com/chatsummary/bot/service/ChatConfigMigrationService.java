package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatConfig;
import com.chatsummary.bot.repository.ChatConfigRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatConfigMigrationService {

    private final ChatConfigRepository chatConfigRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        log.info("Starting ChatConfig migration from cron to summaryHour...");
        List<ChatConfig> configs = chatConfigRepository.findAll();
        int migratedCount = 0;

        for (ChatConfig config : configs) {
            if (config.getCron() != null && !config.getCron().isBlank()) {
                int extractedHour = extractHourFromCron(config.getCron(), config.getSummaryHour());
                config.setSummaryHour(extractedHour);
                config.setCron(null);
                chatConfigRepository.save(config);
                migratedCount++;
                log.info("Migrated chat {} from cron '{}' to summaryHour {}",
                        config.getChatId(), config.getCron(), extractedHour);
            }
        }

        log.info("ChatConfig migration completed. Migrated {} configs.", migratedCount);
    }

    public static int extractHourFromCron(String cron, int defaultHour) {
        if (cron == null || cron.isBlank()) {
            return defaultHour;
        }
        String[] parts = cron.trim().split("\\s+");
        // Spring cron format has 6 parts: sec min hour day month dow
        // Standard unix cron has 5 parts: min hour day month dow
        try {
            if (parts.length == 6) {
                return parseHourPart(parts[2], defaultHour);
            } else if (parts.length == 5) {
                return parseHourPart(parts[1], defaultHour);
            }
        } catch (Exception e) {
            log.warn("Could not extract hour from cron '{}', fallback to {}: {}", cron, defaultHour, e.getMessage());
        }
        return defaultHour;
    }

    private static int parseHourPart(String hourPart, int defaultHour) {
        if (hourPart != null && hourPart.matches("^\\d+$")) {
            int h = Integer.parseInt(hourPart);
            if (h >= 0 && h <= 23) {
                return h;
            }
        }
        return defaultHour;
    }
}
