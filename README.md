# Lyrics Translator

An Android app that shows synced lyrics for the song you're playing in Spotify and translates them line by line into your language.

## Features

### Follows what you're playing in Spotify
- Reads the current track from Spotify's media session, so there's nothing to link up or sign in to. Other players (YouTube, browsers, etc.) are ignored.
- Pause, seek and track changes come in as session events, so nothing is polled.
- Built-in mini player with previous / play-pause / next and a seek bar. Tap any synced line to jump to it.

### Lyrics from six sources
| Source | Notes |
|---|---|
| NetEase Cloud Music | Synced, often includes a Chinese translation |
| QQ Music | Synced, often includes a Chinese translation |
| Kugou | Synced, often includes a Chinese translation |
| Spotify | Spotify's own lyrics; needs your `sp_dc` cookie (paste it, or log in from Settings) |
| LRCLib | Synced, open lyrics database |
| Uta-Net | Unsynced Japanese lyrics; can use an LLM to match romanized artist names to Japanese ones |

- Sources are tried in order and the first one with lyrics wins. Each source gets 15 seconds before the app moves on to the next.
- You can reorder the sources in Settings, or tap the source tag on the lyrics screen to always try that one first.
- On the Chinese services, a search result has to match both the title and the duration (within 3 seconds), which filters out live versions, remixes and covers. A result whose artist also matches is preferred, and artists listed under their native name (米津玄師 for "Kenshi Yonezu") are still accepted.
- Traditional Chinese titles are converted to Simplified so they match the Chinese services' listings.
- Spotify lists many Japanese songs under English or romanized names ("Usseewa" by Ado), while the other services know them only as うっせぇわ. When a source misses, the app looks up the song's native names in Apple's iTunes catalog, and that source searches again with them. Every source after it tries the native names first, then Spotify's. Each track is looked up once, and only when a source misses.
- Western artists, whom the Japan store spells in katakana (テイラー・スウィフト), keep their usual names. An artist with an all-katakana Japanese name (ヨルシカ) is searched that way once the app has found Japanese lyrics by them.
- On LRCLib, a search that misses with Spotify's album is retried without it, since songs there are often filed under a different album.

### Translation
- **Google Translate** is the default, free option with no setup.
- **OpenRouter** lets you use any LLM on OpenRouter with your own API key. You pick the model from a searchable catalog with prices, edit the prompt template, and adjust each model's request JSON (temperature, max_tokens, reasoning, …).
- If the source already ships a Chinese translation (NetEase, QQ Music and Kugou often do) and your target is Chinese, that translation is shown without making a request.
- Songs already in your target language aren't translated. The language is detected from the script, with ML Kit as a fallback for Latin-script lyrics.
- To save OpenRouter costs, you can turn off auto-translation entirely or only for certain languages. Hitting refresh on a song still translates it on demand.
- 20 target languages.

### Floating lyrics window
- Shows the current line over other apps. Drag it to move it, or drag its corner to resize it.
- Can show 1, 3 or 5 lines, either the original with its translation underneath or the translation alone.
- Long lines can shrink to fit or wrap.
- Four styles: Dark, Light, Album (uses the cover's color) and Clear (no background). Text size and opacity are adjustable.
- **Lock** makes touches pass through to the app underneath. You can toggle it from the window's notification.
- Toggle it from a **Quick Settings tile**. The window hides when the screen is off, when nothing is playing, or when the app itself is open.

### Polished, efficient UI
- Material 3 with Jetpack Compose. The lyrics screen takes its colors from the album cover.
- The lyrics auto-scroll to keep the current line centered, and resume following the song a few seconds after you scroll by hand.
- Lyrics and translations are cached on the device per track, source, translator and language, so replays show up instantly and offline. Errors aren't cached.
- Nothing is fetched unless the lyrics screen or the floating window is showing.

### Privacy
- The OpenRouter API key and Spotify cookie are stored encrypted on the device.
- When you log in to Spotify, only the `sp_dc` cookie is kept. The WebView's cookies are cleared right after.
- Settings has a **Copy debug log** button for bug reports.

## Requirements

- Android 8.0 (API 26) or newer
- **Notification access**, to read the playing track. The app asks for it on first launch.
- **Display over other apps**, only if you use the floating window
- Optional: an [OpenRouter](https://openrouter.ai) API key, and a Spotify `sp_dc` cookie for Spotify's own lyrics

## Building

```sh
./gradlew assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/`, or run it from Android Studio.

## Disclaimer

Several lyrics sources and the Google translator use unofficial endpoints, which can break without notice. When one fails, the app falls through to the next source.
