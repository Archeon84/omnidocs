# OmniDocs Security & Privacy Risk Register

## 1. Executive Summary & Security Model
OmniDocs is designed under a **Zero-Trust, Local-First, Evidence-First** security architecture. All personal data, notes, audio recordings, OCR scans, and AI inferences remain strictly on the user's physical device. 

This register tracks identified threat vectors, mitigation controls, verification status, and ongoing compliance with privacy standards (including Malaysia's Personal Data Protection Act / PDPA principles).

---

## 2. Risk Matrix & Threat Register

| ID | Threat / Vulnerability Vector | Severity | Likelihood | Impact | Mitigation Strategy & Implemented Controls | Verification Mechanism | Status |
|---|---|---|---|---|---|---|---|
| **SEC-01** | **Indirect Prompt Injection in Notes/Transcripts** | High | High | Medium | 1. User/untrusted text is strictly quarantined into `userPrompt` / `Note content` sections.<br>2. System prompts enforce strict anti-override rules ("Base your answer ONLY on the provided text").<br>3. `AiOutputProcessor` cuts off simulated turn markers (`User:`, `<|im_start|>`, `<|start_header_id|>`) preventing role hijacking. | `AiPromptAndOutputTest.kt` unit test suite verifying turn cutoff and prompt containment. | **Mitigated** |
| **SEC-02** | **XSS & Code Execution via Rich-Text WebView** | High | Low | High | 1. `HtmlSanitizer` cleans all imported and rendered HTML, stripping script tags, malicious event handlers (`onload`, `onerror`), and `javascript:` URIs.<br>2. JavaScript interface in WebView editor exposes only narrow, typed content update callbacks with origin checks. | `HtmlSanitizerTest.kt` + WebView security audits. | **Mitigated** |
| **SEC-03** | **Unauthorized Database Access & Data Theft at Rest** | Critical | Low | Critical | 1. SQLite database encrypted via SQLCipher 4.5.4 with AES-256 in CBC mode.<br>2. Database passphrase derived and stored in hardware-backed `AndroidKeyStore` with `PURPOSE_ENCRYPT \| PURPOSE_DECRYPT`.<br>3. No plaintext fallback allowed. | Room SQLCipher initialization tests + KeyStore verification. | **Mitigated** |
| **SEC-04** | **Native JNI Memory Corruption / Buffer Overflows** | Critical | Low | Critical | 1. All native buffers passed to `llama.cpp` and `sherpa-onnx` utilize direct `ByteBuffer` allocation with explicit capacity validation.<br>2. Lifetime management ensures C++ model contexts are safely released in `close()` methods.<br>3. Stale .so libraries and conflicting libc++ symbols removed. | Continuous memory stress tests + AddressSanitizer (ASan) build targets. | **Mitigated** |
| **SEC-05** | **Path Traversal & Insecure File Sharing via FileProvider** | Medium | Low | Medium | 1. Export files (`.graphml`, `.jsonld`, `.md`, `.zip`) are written strictly to app-isolated `cacheDir/exports/`.<br>2. `FileProvider` specifies `<cache-path name="exports" path="exports/"/>` with temporary `FLAG_GRANT_READ_URI_PERMISSION`. | Path normalization checks and unit tests. | **Mitigated** |
| **SEC-06** | **Eavesdropping / Unintended Microphone Recording** | High | Low | High | 1. Microphone access is strictly bound to user-initiated capture in `VoiceCaptureManager`.<br>2. Foreground audio status is prominently indicated via Compose pulse visualizer.<br>3. Zero background audio recording when the app is closed. | Manual and automated permission checks. | **Mitigated** |
| **SEC-07** | **SQL Injection in Local Backup / Dynamic Queries** | Medium | Low | High | 1. All database operations use Room typed parameterized DAO methods with SQLite parameter binding (`?`).<br>2. Dynamic queries in backup services validate column names against table metadata via `PRAGMA table_info` before query composition. | Backup/Restore integration tests. | **Mitigated** |
| **SEC-08** | **AI Hallucination of Unsubstantiated Decisions/Tasks** | Medium | Medium | Medium | 1. `EvidenceExtractor` and `AnswerAgent` enforce strict evidence linkage (`EvidenceLinkEntity`) containing SHA-256 quote hashes and character/timestamp offsets.<br>2. Claims without source evidence are marked `uncertain` or flagged for user review. | `GroundedResponseParserTest.kt` and multi-agent QA tests. | **Mitigated** |
| **SEC-09** | **Tampering with Audit Logs / History Manipulation** | Medium | Low | Medium | 1. `AuditEventEntity` records all note creations, updates, deletions, and security operations with monotonic timestamps.<br>2. Audit events table is append-only with no update DAO methods exposed. | Audit event logging unit tests. | **Mitigated** |

---

## 3. Privacy Compliance & PDPA Framework Alignment
OmniDocs adheres to the core data protection principles:
1. **Notice & Choice**: The user is fully informed that all AI processing runs locally on-device. No data leaves the device without an explicit user share action.
2. **Security Principle**: Database encryption at rest with hardware KeyStore and in-memory sanitization.
3. **Retention Principle**: Audio recordings can be deleted independently while retaining the verified text transcript, with configurable retention limits.
4. **Data Integrity & Access**: Users have full export portability (Markdown, JSON-LD, GraphML, raw Audio) and full cascading deletion capabilities.
