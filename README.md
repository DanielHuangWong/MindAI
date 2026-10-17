# MindAI

**Android notification intelligence — turn fleeting notifications into lasting memory.**

MindAI captures notifications you explicitly authorize, filters out ads and promotional noise, sends only selected content to AI for summarization, and extracts genuinely important items for your review. All raw data stays encrypted on-device.

## Features

- **Notification Capture** — Reads notifications after you grant Notification Listener access; nothing leaves the device unless you trigger a summary
- **Ad & Promo Filtering** — Local value gate removes ads, marketing, and low-signal notifications before any processing
- **AI Summarization** — Sends selected notifications to [OpenRouter](https://openrouter.ai) for summarization and key-point extraction; you pick the model
- **Important Records** — Save traceable, editable, deletable records extracted from notifications
- **Daily Brief** — Morning digest widget and notification summarizing yesterday's key items
- **Calendar Integration** — Auto-create calendar events from notification content
- **Home-screen Widgets** — 2×2 and 4×4 widgets showing today's count, pending summaries, and recent records
- **Auto-processing** — Optional JobService for scheduled background summary runs
- **On-device Encryption** — Keys and in-flight sessions encrypted via Android Keystore

## Requirements

- Android 8.0+ (API 26+)
- OpenRouter API key (for AI features)

## Build

```bash
git clone https://github.com/DanielHuangWong/MindAI.git
cd MindAI
./gradlew assembleDebug
```

For release builds, set the following in `local.properties` or as env vars:

```
MINDAI_AI_REPORT_ENDPOINT=<your-endpoint>
MINDAI_PRIVACY_POLICY_URL=<your-privacy-policy-url>
```

## Configuration

1. Install the APK
2. Open MindAI → grant Notification Listener access
3. Add your OpenRouter API key in Settings
4. Start capturing

## License

[MIT](LICENSE)
