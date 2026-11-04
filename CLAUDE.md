# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app (Kotlin, no third-party libraries, minSdk 24 / targetSdk 34, JVM 17) that finds WhatsApp / WhatsApp Business chats from numbers not saved in contacts, reads their `~ Name` profile name via an Accessibility Service, and exports a Google Contacts-compatible CSV. See `README.md` for the end-user flow.

## Commands

- Build debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- CI (`.github/workflows/build-apk.yml`) runs the same command on every push and uploads the APK artifact.
- There are no tests and no lint configuration. Behavior can only be verified on a device/emulator with WhatsApp Business installed and the accessibility service enabled.

## Architecture

All code is in `app/src/main/java/com/renu/waexporter/`:

- `ExporterService.kt` — the core. An `AccessibilityService` that draws a floating overlay (`TYPE_ACCESSIBILITY_OVERLAY`) whenever a package in `WA_PACKAGES` (`com.whatsapp.w4b`, `com.whatsapp`) is foregrounded. It is a handler-driven state machine (`Mode.IDLE / SCANNING / NAMING`) using chained `postDelayed` steps rather than coroutines:
  - **Scan**: `scanStep` reads chat-list rows, saves rows whose title parses as a phone number, scrolls forward, and stops after 3 consecutive identical snapshots (`staleRounds`).
  - **Naming**: for each pending number, `openNextChat` fires an `api.whatsapp.com/send?phone=` intent → `readChatScreen` looks for a `~ Name` text → otherwise taps the header to open contact info (`readInfoScreen`) → `finishOne(name, backs)` presses Back the right number of times (0/1/2, depending on how deep it navigated) before the next number. The back count must match the screens actually opened, or navigation drifts.
  - Opening chats marks messages as read, hence the two-tap `namesArmed` confirmation; preserve it.
- `ContactStore.kt` — persists `number -> name` (blank = name still pending) in SharedPreferences as tab/newline-delimited text, insertion-ordered. "Pending" means blank name.
- `PhoneUtil.kt` — `normalize` (is this title a phone number?), `pushName` (is this text a `~ Name`?), `clean` (strips WhatsApp's invisible bidi/NBSP characters). Both scan and naming depend on these.
- `CsvExport.kt` — builds the Google Contacts import CSV; blank names get `"<fallback prefix> <number>"`, all rows get the user's label plus `* myContacts`.
- `MainActivity.kt` — plain `Activity` (no layouts framework/Compose): enables the service, shows counts, sets fallback name/label, exports CSV.

## Fragile points

- The scraper depends on WhatsApp's internal view IDs, resolved as `"$waPkg:id/<id>"` so it works for both packages: `conversations_row_contact_name` (chat list), `conversation_contact` / `conversation_contact_name` (chat header), plus `BUSINESS_NAME_IDS` for the info screen. WhatsApp updates may break these; fix them in `ExporterService.kt`. Fallbacks exist (largest scrollable RecyclerView/ListView for the chat list; text scan for `~`-prefixed names).
- Timing constants (`SCROLL_DELAY`, `CHAT_OPEN_DELAY`, `INFO_OPEN_DELAY`, `BACK_DELAY`) are tuned for device speed; slow devices may need larger values.
- Accessibility behavior is configured in `app/src/main/res/xml/accessibility_config.xml` and the manifest; changes to the service's event handling often need matching changes there.
