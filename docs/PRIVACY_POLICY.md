# Privacy Policy for OmniDocs

**Effective Date:** October 8, 2026  
**Last Updated:** October 8, 2026

OmniDocs ("we", "our", or "the App") is developed as a privacy-first, offline-centric personal document and knowledge management application for Android. We believe that your personal thoughts, notes, documents, and research should remain strictly confidential and under your exclusive control.

This Privacy Policy explains how OmniDocs handles your data in compliance with Google Play Developer Policies and global privacy regulations (including GDPR and CCPA).

---

## 1. Core Principle: Zero Telemetry & Local-First Processing

* **No Remote Analytics:** OmniDocs contains **no third-party tracking SDKs**, analytics trackers (e.g., Google Analytics, Firebase Analytics), or advertising libraries.
* **On-Device Artificial Intelligence:** All generative AI text processing, summarization, vector embeddings, and semantic searches are executed **entirely on your physical Android device** via Google LiteRT-LM, USearch, and SQLite FTS4. Your notes and prompts are never sent to external AI servers or cloud APIs.
* **Offline Speech-to-Text & OCR:** Voice transcription (Sherpa-ONNX) and camera document scanning (Google ML Kit) run locally on device hardware without transmitting audio or video streams over the internet.

---

## 2. Information Storage & Cryptographic Protection

1. **Encrypted Local Database:**
   * All user notes, tags, revisions, chat histories, action items, and flashcards are persisted in a local SQLite database encrypted with **SQLCipher AES-256**.
   * Encryption keys are managed securely by the **Android KeyStore** system, backed by hardware security modules (TEE / StrongBox) where available on the device.
2. **Local Encrypted Backups:**
   * When you export a backup, OmniDocs packages your encrypted database and assets into an AES-256-GCM ZIP archive protected by a passphrase of your choosing. We do not store or transmit this passphrase.

---

## 3. Device Permissions & Why They Are Requested

OmniDocs requests only the minimum runtime permissions necessary to deliver user-initiated features:

| Permission | Purpose | Data Handling |
| :--- | :--- | :--- |
| **`CAMERA`** | Required solely when using the live Document OCR scanner. | Frames are processed in real-time in device memory by Google ML Kit. No photos are uploaded or shared. |
| **`RECORD_AUDIO`** | Required solely when using the Voice Note transcription tool. | Audio is sampled in real-time by the local Sherpa-ONNX neural engine. Raw audio is saved only to your private app storage and never transmitted. |
| **`INTERNET`** | Required for: (1) downloading optional on-device AI model weights chosen by the user; (2) optional Google Drive cloud sync. | No background telemetry or silent outbound traffic occurs. |
| **`POST_NOTIFICATIONS`** | Required to display download progress for large on-device AI model files. | No marketing or remote push notifications are sent. |
| **`FOREGROUND_SERVICE_DATA_SYNC`** | Required to maintain uninterrupted downloads of large model files (e.g., 350MB–1GB) when the app is minimized. | Only active during an explicit user-initiated model download. |

---

## 4. Optional Cloud Synchronization (Google Drive)

* Cloud synchronization is **completely optional and disabled by default**.
* If you choose to enable Google Drive sync, the app interacts directly with Google's Drive REST API using the restricted `drive.file` and `drive.appdata` OAuth scopes.
* Sync payloads are placed strictly within your private Google Drive account (or the hidden app-specific data folder). We have no access to your Google credentials, Drive files, or account tokens.

---

## 5. Biometric Authentication

* If enabled in App Settings, OmniDocs uses Android's standard `BiometricPrompt` API to lock the application on launch or resume.
* Biometric verification (fingerprint or face recognition) is handled entirely by the Android operating system. OmniDocs never accesses, stores, or transmits raw biometric data.

---

## 6. Data Retention & Deletion

* **Full User Ownership:** Because all data is stored locally on your device, you retain full ownership and control over data deletion.
* **Instant Purge:** Deleting notes, chats, or attachments permanently removes them from the encrypted SQLite database.
* **App Uninstallation:** Uninstalling OmniDocs immediately and irreversibly deletes the application sandbox, including the encrypted database, local models, and settings.

---

## 7. Children's Privacy

OmniDocs does not knowingly collect or solicit personal information from children under the age of 13. The application operates entirely offline and does not harvest personal user information.

---

## 8. Changes to This Privacy Policy

We may update this Privacy Policy from time to time to reflect product enhancements or policy updates. Any updates will be published within the application and in the official repository.

---

## 9. Contact Us

If you have any questions or feedback regarding this Privacy Policy, please contact the developer via:
* **GitHub Repository:** [https://github.com/Archeon84/omnidocs](https://github.com/Archeon84/omnidocs)
* **Issue Tracker:** [https://github.com/Archeon84/omnidocs/issues](https://github.com/Archeon84/omnidocs/issues)
