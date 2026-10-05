# Third-party notices

SessionSense's own code is MIT-licensed (see [LICENSE](LICENSE)). The items below are included under their own terms.

## Fonts (SIL Open Font License 1.1)

| File | Font | License |
|---|---|---|
| `app/src/main/res/font/outfit.ttf` | Outfit, © 2021 The Outfit Project Authors | [licenses/OFL-Outfit.txt](licenses/OFL-Outfit.txt) |
| `app/src/main/res/font/ibm_plex_mono.ttf` | IBM Plex Mono, © 2017 IBM Corp., Reserved Font Name "Plex" | [licenses/OFL-IBMPlexMono.txt](licenses/OFL-IBMPlexMono.txt) |

## Provider logos and names

`app/src/main/res/drawable/ic_provider_claude.xml` and `ic_provider_codex.xml` depict trademarks of Anthropic and
OpenAI. They're used only to show which service an account belongs to, and they are **not** covered by the MIT license.
Claude is a trademark of Anthropic; ChatGPT and Codex are trademarks of OpenAI. SessionSense isn't affiliated with or
endorsed by either.

## Libraries

Dependencies are fetched by Gradle at build time and aren't vendored in this repository. They're all under the
Apache License 2.0: AndroidX (Core, Activity, Lifecycle, Navigation, Compose, Material 3, DataStore, Room, Security,
WorkManager, Glance, WebKit), OkHttp, MaterialKolor and Haze. Tests also use JUnit 4 (EPL 1.0) and org.json.

## Inspiration

The approach of reading usage from claude.ai with the user's own session was inspired by
[Claude Usage Tracker](https://github.com/hamed-elfayome/Claude-Usage-Tracker) (MIT), a macOS menu-bar app. No code
from it is included.
