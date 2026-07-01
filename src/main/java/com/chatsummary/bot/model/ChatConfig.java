package com.chatsummary.bot.model;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "chat_configs")
public final class ChatConfig {

    @Id
    private String id;

    @Indexed(unique = true)
    private long chatId;

    private String cron;
    private Instant lastProcessedAt;
    private int summaryCredits = 30;
    private String language = "English";
    private boolean enabled = true;
    private String customPrompt;
    private boolean monthlySummaryEnabled;
    private Instant lastMonthlyProcessedAt;
    private Integer lastSummaryMessageId;
    private String lastSummaryText;
    private Integer lastSummaryTailMessageId;
    private String lastSummaryTailText;

    public ChatConfig(long chatId, String cron) {
        this.chatId = chatId;
        this.cron = cron;
    }
}
