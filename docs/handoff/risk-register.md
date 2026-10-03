# OmniDocs — Risk Register

> **Document Role**: Formal tracking of technical risks, architectural debt, potential failure modes, and mitigations.  
> **Last Updated**: October 3, 2026

---

## 1. Risk Matrix

| Risk ID | Title | Severity | Affected Component | Evidence | Impact | Mitigation Plan | Owner / Next Action |
| :---: | :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **RSK-01** | Device RAM & Thermal Throttling on Lower-Tier Hardware | **HIGH** | `LiteRtLmService`, `ThermalBudgetManager` | Gemma 4 model requires ~2.5 GB RAM; continuous generation raises device temperature | Out-Of-Memory (OOM) killer terminating app; severe token generation slowdown on sustained chats | `ThermalBudgetManager` dynamically reduces context window ($8k \rightarrow 4k$) and lowers thread count when thermal status exceeds `THERMAL_STATUS_MODERATE`. | Mobile AI Lead / Run battery & thermal soak tests on 6GB RAM devices |
| **RSK-02** | Google Drive OAuth Client Provisioning | **MEDIUM** | `DriveService`, `SyncQueueManager` | `GoogleSignInClient` requires matching SHA-1 fingerprint and package name in Google Cloud Console | Users cannot sync notes if custom builds use a non-whitelisted debug keystore SHA-1 | Document exact Google Cloud Console setup in `onboarding.md`; display user-friendly prompt if sign-in fails due to OAuth configuration. | Integration Eng / Configure test GCP Project OAuth Client ID |
| **RSK-03** | Room Database Encryption KeyStore Recovery | **MEDIUM** | `NotesDatabase`, `KeyStoreManager` | KeyStore passphrase is generated per-device and unrecoverable if app data is restored to a different device | If user restores raw SQLite files to a new device without KeyStore material, database cannot be unlocked | `LocalBackupService` provides export to password-protected AES-256-GCM ZIP that survives device transfers. | Security Eng / Maintain user warnings on backup export screen |
| **RSK-04** | Large Model Asset Download Interruption | **LOW** | `ModelDownloadManager`, `WelcomeViewModel` | On-device models range from 120 MB to 1.1 GB; mobile network drops can corrupt incomplete files | User experiences failed model initialization or corrupted file read | `ModelDownloadManager` downloads to temporary `.tmp` files and executes SHA-256 integrity verification before finalizing file renaming. | Core Dev / Verify resumable HTTP range downloads |
| **RSK-05** | Native USearch ABI Compatibility | **LOW** | `USearchNative`, `CMakeLists.txt` | C++ HNSW native library compiled with NDK r27 across `arm64-v8a`, `armeabi-v7a`, `x86_64` | Incompatible custom Android ROMs could fail `System.loadLibrary("usearch-android")` | `VectorSearch.kt` implements automatic graceful degradation to SQLite FTS4 BM25 search if USearch native library fails to load. | Native Dev / Verify 32-bit `armeabi-v7a` performance |

---

## 2. Resolved Audit Gaps

1. **Storage Segregation [RESOLVED]**: Local Chat sessions previously stored in raw JSON files were migrated into SQLCipher Room database v18 (`chat_sessions`, `chat_messages`) with automatic startup migration.
2. **Missing `androidTest` Directory [RESOLVED]**: Established `app/src/androidTest/` containing `NotesDatabaseInstrumentationTest.kt` and `AppSmokeInstrumentationTest.kt`.
3. **Submodule & Native Hygiene [RESOLVED]**: Submodule `llama.cpp` was cleaned and pinned; `app/src/main/cpp/README.md` was authored; root clutter was archived or deleted.
