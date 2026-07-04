# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Telegram bot (Java 25, Spring Boot 4.0.3) that records group-chat messages into MongoDB and produces AI summaries via the Google Gemini API. Summaries can be triggered on demand (`/summary`) or on a per-chat cron schedule, plus an optional monthly digest. Donations via Telegram Stars grant ad-free "summary credits".

## Commands

```bash
./gradlew build              # compile + package + run tests
./gradlew bootRun            # run locally (needs MongoDB + env vars set)
./gradlew bootJar            # build runnable jar -> build/libs/ (no tests)
./gradlew test               # JUnit 5 (unit + integration)
./gradlew test -PexcludeIntegration              # unit tests only (no Docker needed)
./gradlew test --tests "com.chatsummary.bot.service.AdServiceTest"   # single test class/method

docker-compose up -d         # run app + MongoDB together (reads .env)
```

There is no separate lint step; the build is the gate. Lombok is an annotation processor — code won't compile without it on the processor path (already configured in `build.gradle.kts`).

## Required configuration

The app will not start without these env vars (see `.env.example`): `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `GEMINI_API_KEY`, `ADMIN_CHAT_ID`. `SPRING_MONGODB_URI` defaults to `mongodb://localhost:27017/chatsummarybot`. Note `application.yml` defaults `GEMINI_MODEL` to `gemini-3-flash-preview` (the README's `gemini-2.5-flash` is stale). Gemini retry tuning: `GEMINI_RETRY_MAX_ATTEMPTS`, `GEMINI_RETRY_DELAY`, `GEMINI_RETRY_MULTIPLIER`, `GEMINI_RETRY_MAX_DELAY` (exponential backoff; defaults 5 attempts, 10s initial, x2, 60s cap). Retries fire only on Gemini 5xx (`ServerException`) and empty responses (`GeminiEmptyResponseException`) — 4xx (`ClientException`) is not retried; a 429 on the primary model falls back to `GEMINI_BACKUP_MODEL` once per attempt instead.

## Architecture

Single Spring Boot process; long-polling Telegram bot, MongoDB persistence, two scheduled jobs. Flow:

1. **`telegram/ChatSummaryBot`** — the single update entry point (`consume(Update)`). It is the *only* place Telegram is read/written. It routes membership changes, payments/pre-checkout, photos, plain text (saved as messages), and slash commands. All commands are admin-gated: a command runs only if it is in the `ADMIN_COMMANDS` set **and** `isUserAdmin` confirms the sender is a group admin/creator (live `GetChatMember` call).
2. **`service/MessageService`** — persists messages/photos and daily summaries; queries by chat + timestamp window; lists active chat IDs.
3. **`service/ChatConfigService`** — per-chat settings (cron, language, custom prompt, enabled flag, monthly flag, summary credits, `lastProcessedAt`/`lastMonthlyProcessedAt`).
4. **`service/GeminiSummaryService`** — builds the prompt and calls Gemini. `summarize` interleaves message text with image bytes as multimodal `Part`s; `summarizeMonthly` condenses stored daily summaries.
5. **`scheduler/DailySummaryScheduler`** — `@Scheduled(fixedRate=60s)`; for each active chat, evaluates that chat's own cron against its `lastProcessedAt` and fires if due. **`scheduler/MonthlySummaryScheduler`** — last-day-of-month digest for chats with monthly enabled.
6. **`service/AdminNotificationService`** — pushes new-chat / payment / failure alerts to `ADMIN_CHAT_ID`.
7. **`service/AdService`** — owns the "remove ads" upsell: answers Stars pre-checkout, credits successful payments (localized thank-you), and the credit-consumption + invoice logic (`consumeCreditAndMaybeShowAd`, `sendAdWithRemoveOption`). Self-contained with its own `OkHttpTelegramClient` to avoid a circular dep on the bot. `ChatSummaryBot` and `DailySummaryScheduler` both delegate ad/payment handling here.

`model/` holds MongoDB documents (`ChatMessage`, `DailySummary`, `ChatAttachment`, `ChatConfig`); `repository/` holds Spring Data Mongo repositories. `@EnableScheduling` and `@EnableRetry` are on the main application class.

## Testing

JUnit 5 + Mockito + AssertJ (via `spring-boot-starter-test`), Testcontainers for Mongo. Two flavors:

- **Unit tests** (`*Test`) — pure Mockito / `@TempDir`, no Docker or network. Cover `AdService` (incl. RU/UK/EN ad-copy localization, non-supported → English), `ChatConfigService`, `MessageService`, `VoiceStorageService`, `GeminiSummaryService` empty-input early returns, `TelegramLinks`.
- **Integration tests** (`*IT`, `@Tag("integration")`) — extend `integration/MongoIntegrationTest`, a `@DataMongoTest` slice against a Testcontainers `MongoDBContainer` (singleton pattern: started once, Ryuk reaps it). Exercise the repository derived/`@Query` methods. **Require a Docker daemon.**
- `*IT` are **skipped automatically in CI** (`build.gradle.kts` excludes tag `integration` when env `CI` is set) or with `-PexcludeIntegration`.

## Project-specific gotchas

- **`ChatConfigService.getChatConfig` returns an *unsaved* default** when no config row exists. The config is only persisted by the explicit `set*`/`update*`/`save*` methods. Don't assume reading a config created a DB row.
- **Circular dependency** between `AdminNotificationService` and `ChatSummaryBot` is broken with `@Lazy` on the bot injection — preserve it.
- **`lastProcessedAt` is the watermark for both `/summary` and the scheduler** (not calendar day). Updating it wrong will double-send or skip summaries. Scheduled runs also `clearOldMessages` after sending. The watermark advances as soon as the summary is *delivered* — post-send failures (credit consumption, nav-link edit, cleanup) are notified but do not trigger a re-send. Monthly is the mirror image: `lastMonthlyProcessedAt` advances only on success, and daily summaries are cleared only after confirmed delivery.
- **Schedulers sleep `2000*60` ms between chats** (`GEMINI_THROTTLE_MILLIS`) to throttle Gemini — long runs are expected, not hangs.
- **Cron is Spring 6-field** (`sec min hour day month dow`); validated with `CronExpression` before saving.
- **Outgoing messages use `parseMode("HTML")`** and the Gemini prompt is instructed to emit only Telegram-safe HTML (`<b><i><u><s><a>`), no markdown. Keep new bot text HTML-safe.
- **Ad/payment copy is localized by chat language** in `AdService.resolvePayLang`: Russian → RU, Ukrainian → UK, anything else → English (private `PayLang` switch). Keep the three branches in sync when editing invoice/thank-you text.
- **`AdService` has two constructors** — the package-private one (injects an `OkHttpTelegramClient`) exists only for tests; the public `@Value` one is marked `@Autowired` so Spring knows which to use. Don't drop the `@Autowired` or wiring breaks with an ambiguity error.
- **Supergroup source links** are built only for chat IDs starting with `-100` → `https://t.me/c/<id-without-100>/<messageId>`.
- `/testlink` appears in the command `switch` but is **not** in `ADMIN_COMMANDS`, so it is unreachable dead code.

## Deployment

Push to `master` triggers `.github/workflows/deploy.yml`: SCP the repo to a VPS, write `.env` from GitHub secrets, then `docker compose build --no-cache && up -d`. The Docker build runs `gradle bootJar` (no tests). Optional Grafana Loki log shipping via `LOKI_URL`/`LOKI_USERNAME`/`LOKI_PASSWORD`.
