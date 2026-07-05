# ChatSummaryBot

A Telegram bot built with Java 25 and Spring Boot that automatically summarizes daily chat messages using the Google Gemini API.

## Features

- **Automatic Message Tracking**: Saves text messages from chats where the bot is a member.
- **On-Demand Summary**: Generate a summary of today's messages using the `/summary` command.
- **Scheduled Summaries**: Automatically sends a summary at a configured time (default: 21:00) and clears the day's history. Each chat can configure its own schedule.
- **AI-Powered**: Uses Google Gemini (e.g., `gemini-2.5-flash`) for high-quality, concise summaries.
- **Admin Notifications**: Alerts an administrator in case of failures.

## Prerequisites

- Java 25
- MongoDB 7
- Telegram Bot Token (from [@BotFather](https://t.me/botfather))
- Google Gemini API Key (from [Google AI Studio](https://aistudio.google.com/))

## Configuration

The bot can be configured via environment variables or a `.env` file:

| Variable | Description | Default |
|----------|-------------|---------|
| `TELEGRAM_BOT_TOKEN` | Your Telegram Bot Token | (Required) |
| `TELEGRAM_BOT_USERNAME` | Your Telegram Bot Username | (Required) |
| `GEMINI_API_KEY` | Your Google Gemini API Key | (Required) |
| `GEMINI_MODELS` | Comma-separated models tried in order; falls through to the next on HTTP 429 | `gemini-3.5-flash,gemini-3-flash,gemini-2.5-flash` |
| `SUMMARY_CRON` | Cron expression for daily summary | `0 0 21 * * *` |
| `ADMIN_CHAT_ID` | Telegram Chat ID for error alerts | (Required) |
| `LOKI_URL` | URL of the Grafana Loki push endpoint | (Optional) |
| `SPRING_MONGODB_URI` | MongoDB Connection URI | `mongodb://localhost:27017/chatsummarybot` |

## Getting Started

### Using Docker (Recommended)

1. Clone the repository.
2. Create a `.env` file based on `.env.example`.
3. Run with Docker Compose:

```bash
docker-compose up -d
```

### Manual Build

1. Ensure you have Java 25 installed.
2. Build the project using Gradle:

```bash
./gradlew build
```

3. Run the application:

```bash
java -jar build/libs/ChatSummaryBot-1.0.0.jar
```

## Commands

- `/summary`: Generates and sends a summary of all messages recorded today in the current chat.
- `/setcron <cron>`: Sets a custom summary schedule for the current chat (e.g., `/setcron 0 0 21 * * *`). Uses Spring's 6-field cron expression (seconds minutes hours day month day-of-week).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
