<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# create markdown file with your recommendation for my agentic ai to build and implement.

I’m unable to generate the downloadable Markdown file in this environment because file-generation execution is unavailable. However, I prepared the complete content below; save it as:

`OmniDocs-Agentic-AI-Implementation-Blueprint.md`

# OmniDocs Agentic AI Build and Implementation Plan

**Prepared:** 28 August 2026\
**Application:** OmniDocs (`com.omnidocs.app`)\
**Platform:** Android, Kotlin, Jetpack Compose\
**Plan status:** Implementation blueprint

## 1. Executive direction

OmniDocs should be built as a **private, offline-first knowledge workspace** that turns documents, scans, recordings, and notes into searchable knowledge with answers linked to their original sources.

The primary product loop is:

1. Capture or import information.
2. Extract and normalize content.
3. Index it for exact and semantic search.
4. Ask questions against the workspace.
5. Show evidence and let the user verify results.
6. Protect, export, and restore the data.

Do not make the knowledge graph, themes, or number of supported models the main product story. They should remain supporting capabilities.

## 2. Current profile assessment

### Strengths

- Local-first AI, encrypted Room storage, optional cloud services, model integrity validation, and audit logging create a strong privacy foundation.
- OCR, document import, voice transcription, hybrid retrieval, source-backed Q\&A, version history, and encrypted backup provide meaningful differentiation.
- The existing separation into AI, OCR, STT, document import, persistence, search, and UI packages is a practical base for continued development. [^1]


### Main risks

- The product currently exposes too many concepts: notes, OCR, voice, recordings, Ask, graph, feed, tasks, intelligence, and multiple themes.
- The fixed BM25/vector formula may not behave consistently across exact, semantic, multilingual, OCR, and task queries.
- The profile does not prove data-integrity behavior, citation correctness, performance on ordinary devices, or recovery from interrupted operations.
- Bidirectional Rich Text/Markdown conversion may silently lose formatting.
- In-memory vector search may become slow or memory-heavy as the workspace grows.
- Privacy claims require protection for recordings, imported files, thumbnails, temporary files, logs, WebViews, and backups—not only the database.


## 3. Product principles

1. **Evidence before fluency.** A useful answer must show where it came from.
2. **Local by default.** Cloud processing must be explicit, visible, and user-controlled.
3. **Never lose user data.** Every import, edit, AI operation, and restore must be recoverable.
4. **Progressive disclosure.** Hide advanced AI controls until they are needed.
5. **Graceful degradation.** The app remains useful without downloaded AI models or network access.
6. **Human confirmation.** AI-generated tasks, claims, entities, tags, and contradictions are suggestions until accepted.
7. **Portable data.** Users can export their notes and leave without losing access to their content.

## 4. Target user experience

Reduce primary navigation to:

- Home
- Capture
- Search
- Tasks
- Settings

Keep Ask, evidence, intelligence, versions, graph, and recordings contextual to a note, search result, or capture result.

The first screen should present three obvious actions:

- New note
- Scan/import
- Record

After processing, display a result such as:

> Imported 7 pages, found 3 possible action items, and indexed the document.

Every long-running operation must expose status, progress, cancel, retry, and failure details.

## 5. Agentic AI architecture

### 5.1 Agent roles

Use a coordinator with narrowly scoped agents. Agents must not directly mutate arbitrary database tables.


| Agent | Responsibility | Output |
| :-- | :-- | :-- |
| Capture Agent | Accept text, files, camera images, and recordings | `CaptureJob` |
| Extraction Agent | Convert source material into text and structured blocks | `ExtractionResult` |
| Normalization Agent | Clean text, detect language, preserve provenance | `NormalizedDocument` |
| Indexing Agent | Chunk, embed, and update exact/semantic indexes | `IndexingResult` |
| Organization Agent | Suggest title, tags, notebook, entities, and links | `OrganizationProposal` |
| Task Agent | Detect action items and dates | `TaskProposal` |
| Research Agent | Retrieve evidence and construct grounded answers | `ResearchAnswer` |
| Verification Agent | Check citation coverage, schema validity, and unsupported claims | `VerificationResult` |
| Backup Agent | Export, encrypt, verify, and restore workspace data | `BackupResult` |
| Policy Guard | Enforce privacy, permissions, model, and network rules | `PolicyDecision` |

### 5.2 Coordinator rules

The coordinator should:

- Create a traceable job with a unique ID.
- Select the minimum agents needed for the request.
- Check model availability and privacy policy before processing.
- Persist each state transition.
- Enforce timeouts, cancellation, retries, and idempotency.
- Pass structured objects rather than unbounded prompt text where possible.
- Require verification before presenting AI results as trustworthy.
- Never silently switch from local to cloud processing.


### 5.3 Suggested job states

`QUEUED -> RUNNING -> WAITING_FOR_MODEL -> WAITING_FOR_USER -> SUCCEEDED`

Failure states:

`CANCELLED`, `FAILED_RETRYABLE`, `FAILED_PERMANENT`, `PARTIAL_SUCCESS`.

Persist the state in a new `AgentJobEntity` and retain an append-only `AgentEventEntity` for debugging and audit purposes.

## 6. Recommended domain contracts

Create domain-level contracts similar to the following:

```kotlin
interface Agent {
    val id: String
    suspend fun execute(input: AgentInput, context: AgentContext): AgentResult
}

data class AgentContext(
    val jobId: String,
    val workspaceId: String,
    val privacyMode: PrivacyMode,
    val modelPolicy: ModelPolicy,
    val cancellation: CancellationSignal
)

sealed interface AgentResult {
    data class Success(val payload: JsonObject) : AgentResult
    data class NeedsUserInput(val question: String) : AgentResult
    data class RetryableFailure(val reason: String) : AgentResult
    data class PermanentFailure(val reason: String) : AgentResult
}
```

Use typed Kotlin data classes at application boundaries. JSON should be used only for model output or durable interchange, followed by strict validation.

## 7. Data-model improvements

Add or review these entities.

### `SourceDocumentEntity`

Stores original URI, MIME type, filename, checksum, import date, page count, language, and encryption status.

### `ContentBlockEntity`

Stores normalized text blocks with source document ID, note ID, page number, timestamp range, block type, character offsets, and OCR bounding box when available.

### `IndexChunkEntity`

Stores chunk text hash, content block IDs, token estimate, embedding model/version, indexing state, and checksum.

### `AgentJobEntity`

Stores job type, input references, status, progress, local/cloud execution, model ID, timestamps, retry count, and error code.

### `AgentEventEntity`

Stores job state transitions, agent ID, event type, safe metadata, and timing information. Never store raw sensitive content in event logs.

### `AiArtifactEntity`

Stores generated summaries, tags, claims, tasks, and answers with source revision, prompt version, model ID, verification status, and user approval state.

### Required provenance fields

Every extracted or generated item should retain:

- Source document or note ID.
- Page, paragraph, or recording timestamp.
- Source revision ID.
- Extraction method.
- Model and model version.
- Confidence or verification status.
- Creation and update timestamps.


## 8. Capture and ingestion pipeline

### Phase A: Capture

Support note creation, file import, camera scan, and audio recording. Copy imported content into app-controlled storage and calculate a checksum immediately.

### Phase B: Extraction

Route by MIME type through the existing converter factory. Add page-aware PDF extraction, slide/sheet references, OCR confidence, and transcript timestamps.

### Phase C: Normalization

Normalize whitespace and headings without destroying layout information. Detect Malay, English, Chinese, Japanese, Korean, and mixed-language content. Preserve the original extraction output.

### Phase D: Human review

For low-confidence OCR, ambiguous dates, speaker labels, and important extracted tasks, show a review step before indexing or creating actions.

### Phase E: Indexing

Chunk by document structure, generate embeddings, update FTS, and expose progress. Failed chunks must be retryable without reprocessing successful chunks.

## 9. RAG implementation plan

Replace the single fixed score formula with a retrieval pipeline:

1. Classify the query as exact, semantic, task, entity, date, or mixed.
2. Run FTS/BM25 and vector retrieval in parallel.
3. Normalize scores or use Reciprocal Rank Fusion.
4. Apply metadata filters.
5. Deduplicate overlapping chunks.
6. Rerank the best candidates.
7. Build a context window with source labels.
8. Generate an answer constrained to the supplied evidence.
9. Verify every factual statement against retrieved evidence.
10. Display citations that open the exact source location.

The answer contract should include:

```json
{
  "answer": "...",
  "citations": [
    {
      "sourceId": "...",
      "noteId": "...",
      "page": 2,
      "startOffset": 140,
      "endOffset": 280,
      "quoteHash": "..."
    }
  ],
  "confidence": "high|medium|low",
  "insufficientEvidence": false
}
```

If there is insufficient evidence, return that explicitly. Do not fill gaps with general model knowledge when evidence mode is enabled.

## 10. Agentic workflows to implement first

### Workflow 1: Import to knowledge

`Capture Agent -> Extraction Agent -> Normalization Agent -> Organization Agent -> Indexing Agent -> Verification Agent`

The user receives a review card containing title, tags, notebook, extracted tasks, and source statistics.

### Workflow 2: Meeting recording

`Capture Agent -> STT Agent -> Transcript Normalizer -> Task Agent -> Summary Agent -> Indexing Agent`

The user can correct transcript text, speaker names, tasks, and due dates before saving the final artifact.

### Workflow 3: Evidence-backed Ask

`Query Classifier -> Retrieval Agent -> Reranker -> Answer Agent -> Verification Agent`

The UI must show answer confidence, citations, and an “evidence insufficient” state.

### Workflow 4: Workspace health scan

`Health Coordinator -> Orphan Detector -> Failed Index Detector -> Broken Citation Detector -> Backup Validator`

Show repair actions rather than only reporting problems.

### Workflow 5: Backup and restore

`Backup Agent -> Snapshot -> Encrypt -> Write Archive -> Checksum -> Verify -> User Confirmation`

Restore into a temporary workspace first, validate it, then replace or merge the active workspace.

## 11. Privacy and security requirements

- Store encryption keys using Android Keystore-backed protection.
- Encrypt recordings, original imports, thumbnails, temporary files, and exported archives where applicable.
- Add app lock and optional biometric unlock.
- Disable sensitive WebView debugging in release builds.
- Restrict WebView navigation and JavaScript bridges.
- Prevent sensitive text from entering logs, crash reports, notifications, and clipboard previews.
- Make local/cloud execution visible before every cloud fallback.
- Add a privacy dashboard showing processed data, model used, network usage, and retention.
- Provide secure deletion for notes, derived chunks, embeddings, recordings, and cached files.
- Give users export and recovery instructions before enabling encryption.


## 12. Performance requirements

Create a benchmark harness for:

- Cold start.
- Import of a 100-page PDF.
- OCR of 20 camera pages.
- Transcription of 60 minutes of audio.
- Indexing 1,000 notes.
- Search over 10,000 chunks.
- Local Q\&A with small and large models.
- Backup and restore of a large workspace.

Track latency, peak RAM, storage growth, CPU temperature, battery use, crash rate, and ANR rate across flagship, mid-range, low-memory, and older Android devices.

Move indexing and model operations to resilient background work with WorkManager or an equivalent durable job system. The UI must survive process death and resume jobs safely.

## 13. Testing strategy

### Unit tests

- Markdown and HTML round-trip behavior.
- Query classification and score fusion.
- Chunking and provenance mapping.
- JSON schema validation.
- Retry and idempotency behavior.
- Encryption key and checksum handling.


### Integration tests

- Every Room migration from the earliest supported schema.
- FTS trigger consistency.
- Embedding model replacement and reindexing.
- Backup corruption and interrupted restore.
- JNI cancellation and concurrent access.
- OCR and transcript source references.


### UI tests

- New note to autosave.
- Import to review to indexed note.
- Recording to editable transcript.
- Ask to citation source.
- Permission denial and retry.
- Model missing, model downloading, and model failure states.


### AI evaluation set

Create a local test corpus containing English, Malay, Malay-English mixed text, Chinese names, receipts, meeting transcripts, PDFs, tables, and deliberately conflicting notes.

Measure:

- Retrieval recall@k.
- Citation precision.
- Unsupported-claim rate.
- OCR character error rate.
- Transcription word error rate.
- Task extraction precision and recall.
- Median and p95 latency.


## 14. Implementation phases

### Phase 0: Baseline and safety

- Freeze a reproducible build toolchain.
- Inventory current code against the profile.
- Add crash-safe logging without sensitive content.
- Add migration, backup, and restore tests.
- Define performance and AI quality baselines.


### Phase 1: Reliable core loop

- Simplify navigation.
- Implement capture/import status UI.
- Add source documents and content blocks.
- Add provenance throughout extraction and indexing.
- Make exact search, semantic search, export, and restore dependable.


### Phase 2: Agent runtime

- Add `Agent`, `AgentContext`, `AgentResult`, job persistence, event tracing, cancellation, retry, and policy guard.
- Implement Import, Indexing, Task, and Verification agents.
- Add human approval for generated artifacts.


### Phase 3: Grounded intelligence

- Implement query classification, fused retrieval, reranking, evidence-constrained generation, and citation navigation.
- Add meeting workflow and workspace health scan.


### Phase 4: Advanced features

- Reintroduce knowledge graph, contradiction detection, idea evolution, and advanced entity browsing after the core loop passes quality gates.


### Phase 5: Optional cloud ecosystem

- Add cloud backup and cloud AI as explicit opt-in features.
- Add sync conflict resolution only after local data integrity is proven.


## 15. Definition of done

A feature is complete only when it has:

- A clear user outcome.
- A typed domain contract.
- Persistent job state where work is asynchronous.
- Cancellation, retry, and failure handling.
- Provenance and audit behavior.
- Unit, integration, and UI tests.
- Offline behavior defined.
- Privacy behavior defined.
- Performance measurements.
- Export/restore compatibility considered.
- User-facing explanation of what the AI did.


## 16. Agent instruction for coding sessions

Use the following instruction at the beginning of every implementation task:

> You are the OmniDocs implementation agent. Work only from the current repository and this blueprint. First inspect the existing implementation and identify the smallest safe change. Do not invent APIs, entities, routes, or dependency versions without checking the project. Preserve backward compatibility and user data. For every change, define the data flow, failure states, privacy implications, cancellation behavior, and tests. Never silently send content to the cloud. Never present AI output as verified unless citations and validation pass. Implement one coherent vertical slice at a time, run relevant tests, and report changed files, commands, risks, and remaining work.

## 17. Final priority order

1. Prevent data loss and make backup/restore trustworthy.
2. Make capture-to-search-to-evidence fast and understandable.
3. Add durable agent orchestration with cancellation and retries.
4. Improve RAG grounding, provenance, and citation navigation.
5. Validate Malay-English and ordinary-device performance.
6. Add advanced knowledge features only after the above are stable.

OmniDocs does not need more features immediately. It needs a dependable, explainable, privacy-preserving workflow that users can trust with their real documents and recordings.

<div align="center">⁂</div>

[^1]: OmniDoc-Profile.md

