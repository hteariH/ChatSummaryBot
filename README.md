# ChatSummaryBot

A Telegram bot built with Kotlin and Quarkus that automatically summarizes daily chat messages using the Google Gemini API.

## Features

- **Automatic Message Tracking**: Saves text messages from chats where the bot is a member.
- **On-Demand Summary**: Generate a summary of today's messages using the `/summary` command.
- **Scheduled Summaries**: Automatically sends a summary at a configured time (default: 21:00) and clears the day's history. Each chat can configure its own schedule.
- **AI-Powered**: Uses Google Gemini (e.g., `gemini-2.5-flash`) for high-quality, concise summaries.
- **Admin Notifications**: Alerts an administrator in case of failures.

## Prerequisites

- Java 17+
- MongoDB 7
- Telegram Bot Token (from [@BotFather](https://t.me/botfather))
- Google Gemini API Key (from [Google AI Studio](https://aistudio.google.com/))

## Getting Started

### Using Docker (Recommended)

1. Clone the repository.
2. Create a `.env` file with the required variables (see [Configuration](#configuration) below).
3. Run with Docker Compose:

```bash
docker-compose up -d
```

### Manual Build

1. Build the project using Gradle:

```bash
./gradlew build
```

2. Run the application:

```bash
java -jar build/libs/ChatSummaryBot-1.0.0.jar
```

Run `./gradlew test` to execute all unit tests.

## Configuration

All settings are read from environment variables or `application.yml`.

| Variable | Description | Default |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | Telegram Bot API token | **required** |
| `GEMINI_API_KEY` | Google Gemini API key | **required** |
| `ADMIN_CHAT_ID` | Telegram chat ID to receive error notifications | **required** |
| `GEMINI_MODEL` | Gemini model to use for summarisation | `gemini-2.5-flash` |
| `GEMINI_RETRY_MAX_ATTEMPTS` | Max retry attempts on Gemini failure | `3` |
| `GEMINI_RETRY_DELAY` | Milliseconds between retries | `1000` |
| `SUMMARY_CRON` | 6-field cron expression for scheduled summaries | `0 0 21 * * *` |
| `MONGODB_URI` | MongoDB connection URI | `mongodb://localhost:27017/chatsummarybot` |
| `LOKI_URL` | Grafana Loki push endpoint URL | *(optional)* |
| `LOKI_USERNAME` | Loki username | *(optional)* |
| `LOKI_PASSWORD` | Loki password | *(optional)* |

## Bot Commands

| Command | Description |
|---|---|
| `/summary` | Generates and sends a summary of all messages recorded today in the current chat. |
| `/setcron <expr>` | Sets a custom summary schedule for the current chat (e.g., `/setcron 0 0 21 * * *`). Uses a 6-field cron expression (seconds minutes hours day month weekday). |

## Project Structure

```
src/main/kotlin/com/chatsummary/bot/
├── ChatSummaryBotApplication.kt          # Quarkus entry point
├── model/
│   ├── ChatConfig.kt                     # Per-chat configuration model
│   └── ChatMessage.kt                    # Message model
├── telegram/
│   └── ChatSummaryBot.kt                 # Telegram bot logic & command handling
├── scheduler/
│   └── DailySummaryScheduler.kt          # Cron-based scheduled summaries
└── service/
    ├── GeminiSummaryService.kt           # Calls Gemini to generate summaries
    ├── BotMessageSender.kt               # Sends messages via Telegram API
    ├── ChatConfigService.kt              # Manages per-chat configuration
    ├── MessageService.kt                 # Persists messages to MongoDB
    └── AdminNotificationService.kt       # Sends admin alerts on errors
```

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
