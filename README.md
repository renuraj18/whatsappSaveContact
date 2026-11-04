# WA Contact Exporter

Android app that collects WhatsApp / WhatsApp Business chats from people who are **not saved in your contacts**, gets their WhatsApp profile name, and exports them as a CSV you can import into Google Contacts.

## How it works
- An Accessibility Service puts a small floating panel on top of WhatsApp Business.
- **Scan chats** scrolls the Chats list and records every chat whose title is a phone number (that's how WhatsApp shows unsaved contacts). It only reads the screen; nothing is opened.
- **Get names** opens each collected chat one at a time (via an `api.whatsapp.com/send?phone=` link into WhatsApp Business), reads the `~ Name` WhatsApp shows for non-contacts (on the chat screen, else on the contact-info screen), and goes back. ~5 seconds per contact.
  - ⚠️ Opening a chat marks its unread messages as **read** (blue ticks). Reply to anything urgent first. The panel asks you to tap twice before it starts.
  - People with no WhatsApp profile name get the fallback name, e.g. `WA +919876543210`.
- Everything stays on the phone until you export.

## Build the APK
**Option A – GitHub (no Android Studio needed):** push this folder to a new GitHub repo. The *Build APK* workflow runs automatically; open the run under the **Actions** tab and download `WaContactExporter-apk`.

**Option B – Android Studio:** File → Open → this folder → Run (or Build → Build APK).

## Use it
1. Install the APK. Open the app → **Turn on the scanner** → find *WA Contact Exporter* in Accessibility and enable it.
   - Android 13+: if the toggle is greyed out ("restricted setting"), go to Settings → Apps → WA Contact Exporter → ⋮ menu → **Allow restricted settings**, then try again.
2. Tap **Open WhatsApp Business**, stay on the **Chats** tab, tap **Scan chats** on the floating panel. It stops by itself at the bottom of the list. (For archived chats, open *Archived* and scan again.)
3. Tap **Get names** (twice). Leave the phone alone until it says *Names done*. Tap **Stop** any time; run it again later to continue with the remaining numbers.
4. Back in the app, set the fallback name and the Google Contacts label, then **Export CSV** and save it (e.g. Downloads).
5. On a computer, go to contacts.google.com → **Import** → choose the CSV. All imported contacts get the label you chose, so you can review or undo them easily.

## If something isn't detected
WhatsApp changes its screen IDs now and then. The scanner looks for `conversations_row_contact_name` (chat list) and `conversation_contact` / `conversation_contact_name` (chat header), with fallbacks. If a WhatsApp update breaks it, these IDs in `ExporterService.kt` are the place to adjust.
