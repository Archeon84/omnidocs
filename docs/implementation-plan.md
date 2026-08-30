# OmniDocs Master Implementation Plan & Execution Roadmap

## 1. Vision & Architectural Objective
To establish **OmniDocs** as the premier private, local-first, evidence-first knowledge workspace on Android, empowering users to turn notes, document scans, audio recordings, and BM-English conversations into searchable, interconnected, and source-backed knowledge.

---

## 2. Phase-by-Phase Roadmap & Milestones

```
┌─────────────────────────┐
│ Phase 0: Audit & Docs   │ ───► COMPLETE: Full state audit, architecture, risk register, gap matrix
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 1: Storage & Sec  │ ───► COMPLETE: SQLCipher KeyStore encryption, 11 Room migrations, Audit logging
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 2: Editor & OCR   │ ───► COMPLETE: Tri-mode editor (Rich/MD/Preview), OCR Bounding-Box Overlay
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 3: STT & Audio    │ ───► COMPLETE: Real-time Whisper streaming, live waveform, timestamped segments
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 4: Evidence AI    │ ───► COMPLETE: EvidenceExtractor, ClaimDao, EvidenceLinkDao, quote hashes, anti-repetition
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 5: Hybrid Search  │ ───► COMPLETE: BM25 FTS4 + E5-Small vector cosine + RRF fusion + provenance cards
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 6: Knowledge & Gr │ ───► COMPLETE: GraphEngine, contradictions, timeline evolution, GraphML/JSON-LD export
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 7: Study & Agent  │ ───► IN PROGRESS: Spaced Repetition (SM-2), Calendar .ics export, third-party adapters
└───────────┬─────────────┘
            │
┌───────────▼─────────────┐
│ Phase 8: Hardening & QA │ ───► IN PROGRESS: On-device testing (Xiaomi 13 Ultra), thermal budgeting, release builds
└─────────────────────────┘
```

---

## 3. Detailed Phase Breakdown & Deliverables

### Phase 0: Audit & Foundation (Delivered)
- [x] Complete codebase state inspection (`docs/current-state.md`).
- [x] Comprehensive architecture audit (`docs/architecture-audit.md`).
- [x] Requirement-to-capability gap matrix (`docs/gap-matrix.md`).
- [x] Security and privacy threat modeling (`docs/security-risk-register.md`).
- [x] Master implementation plan (`docs/implementation-plan.md`).

### Phase 1: Storage, Security & Audit Logging (Delivered)
- [x] SQLCipher 4.5.4 AES-256 database encryption with hardware `AndroidKeyStore` master key.
- [x] 11 sequential Room database migrations with automated schema verification.
- [x] Append-only tamper-evident `AuditEventEntity` logging all data mutations.
- [x] Encrypted local backup and restore service with SQL injection protections.

### Phase 2: Tri-Mode Editor & Document Ingestion (Delivered)
- [x] Rich-Text (Quill.js WebView), Raw Markdown (Syntax highlighted), and Markdown Preview modes.
- [x] Lossless format conversion facade (`MarkdownCodec.kt`).
- [x] Document Converter Factory supporting 8 formats (PDF, DOCX, XLSX, PPTX, HTML, Markdown, Plain Text, Scanned Images).
- [x] Interactive OCR Bounding-Box Overlay inspector (`OcrBoundingBoxInspectorSheet.kt`) with confidence badges.

### Phase 3: Speech-to-Text & Real-Time Audio (Delivered)
- [x] Multi-engine STT abstraction (`SherpaOnnxSttEngine`, Moonshine, whisper.cpp JNI, System Recognizer).
- [x] Real-time streaming partial callbacks with sub-second latency.
- [x] Live pulsing audio waveform level visualizer (`liveAudioLevel`).
- [x] Timestamped transcript segments with speaker profiles and retention controls.

### Phase 4: Evidence-First AI & Structured Extraction (Delivered)
- [x] `EvidenceExtractor` extracting facts, decisions, action items, questions, and entities.
- [x] Direct evidence linkage (`EvidenceLinkEntity`) with start/end character offsets, audio timestamps, and SHA-256 quote hashes.
- [x] Grounded output parser (`GroundedResponseParser.kt`).
- [x] Robust AI output post-processor (`AiOutputProcessor.kt`) stripping `<think>` tags, turn cutoffs, preambles, and self-repetition.

### Phase 5: Hybrid Retrieval & Search (Delivered)
- [x] SQLite FTS4 full-text search with custom BM25 ranking (`NoteFtsEntity`).
- [x] In-memory cosine similarity search over `multilingual-e5-small` 384-d vector embeddings.
- [x] Reciprocal Rank Fusion (RRF) combining lexical and semantic search results.
- [x] Search result provenance explanations ("Why this result?").

### Phase 6: Knowledge Graph & Interchange (Delivered)
- [x] Interactive visual knowledge graph canvas (`GraphScreen.kt`, `GraphEngine.kt`).
- [x] Contradiction detection agent (`ContradictionDetectionAgent.kt`) showing conflicting claims and resolution actions.
- [x] Temporal idea evolution tracking (`IdeaEvolutionAgent.kt`).
- [x] Standard interchange export to **GraphML (XML)** and **JSON-LD (W3C Linked Data)** with Android Share Sheet integration (`GraphExportService.kt`).

### Phase 7: Multi-Agent Workflows & Study Features (Next Focus)
- [ ] Interactive Spaced Repetition (SM-2 algorithm) flashcard and quiz review interface.
- [ ] Calendar `.ics` file generation and export for extracted action items and deadlines.
- [ ] Cross-note draft email generator for meeting summaries.
- [ ] Offline note conflict resolution dialog for multi-device sync.

### Phase 8: Production Hardening & Release QA (Ongoing)
- [x] Target device verification and continuous APK deployment on Xiaomi 13 Ultra (`29eb447c`).
- [x] Automated unit test suite execution (`./gradlew :app:testDebugUnitTest`).
- [x] Clean debug build compilation (`./gradlew :app:assembleDebug`).
- [ ] Dynamic token budgeting based on device temperature and thermal throttling.
- [ ] Release ProGuard/R8 shrinking and signed release bundle generation.

---

## 4. Verification & Quality Gates
Every subsequent feature must satisfy:
1. **Automated Testing**: Unit and integration test coverage for all domain logic.
2. **Offline Integrity**: 100% functionality without internet connection.
3. **Data Safety**: Zero silent overwrites or data loss.
4. **Performance Target**: UI frame rendering under 16ms (60-120 FPS), hybrid search retrieval under 100ms.
