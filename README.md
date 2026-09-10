# BroDue

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="96" alt="BroDue logo" />
</p>

**BroDue** is a simple, privacy-friendly money manager for tracking what you lend and what you borrow — from friends, family, or anyone. Open source and free forever.

<a href="https://github.com/Arun-Kaswan/BroDue/releases/latest"><img alt="Download" src="https://img.shields.io/github/v/release/Arun-Kaswan/BroDue?label=download&color=5B6BA3" /></a>
<a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/Arun-Kaswan/BroDue?color=2E9E6E" /></a>

## ✨ Features

- 👥 **People** — track everyone you transact with, sorted by name / value / recent activity
- 💸 **Money records** — "I received" / "I gave" entries with categories, notes and custom date-time
- 🗂️ **Custom categories** — create your own with 28 icons, reorder by drag & drop, hide defaults
- 📜 **Logs** — every transaction in one timeline with net balance trend graph and 5 display modes (Net / +ve / -ve / Send / Received)
- 📄 **Statements** — share as JPG/PDF text or image, per person
- 📴 **Offline mode** — keeps working without internet, syncs when back online
- 🔐 **Fingerprint Lock** — protect opening the app and/or individual actions (add entry, edit entry, archive, edit person) with biometrics
- ⚡ **Instant Lock** — optional: require fingerprint every time you minimize the app
- 🌙 Theme support *(coming soon)*
- 🔄 **System Update** — in-app updates via GitHub Releases

## 📥 Download

Grab the latest APK from the [**Releases page**](https://github.com/Arun-Kaswan/BroDue/releases/latest).

> Requires Android 7.0+ (API 24). Sign in with Google.

## 🛠️ Build from source

1. Clone:
   ```bash
   git clone https://github.com/Arun-Kaswan/BroDue.git
   ```
2. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com):
   - Add an **Android app** with package `com.abk.brodue`
   - Enable **Google Sign-In** under Authentication → Sign-in method, and add your debug SHA-1
   - Enable **Realtime Database**
   - Download `google-services.json` and place it at `app/google-services.json`
     *(copy `app/google-services.json.example` for the expected shape)*
3. Build & run in Android Studio, or:
   ```bash
   ./gradlew :app:assembleDebug
   ```

## 📁 Project layout

```
app/src/main/java/com/abk/brodue/    # App code (Kotlin)
app/src/main/res/                    # Layouts, drawables, values
data-send.html                       # Standalone data-import helper page
```

## 🤝 Contributing

PRs are welcome! For bigger changes, open an issue first to discuss what you'd like to change.

## 📄 License

Released under the [MIT License](LICENSE).
