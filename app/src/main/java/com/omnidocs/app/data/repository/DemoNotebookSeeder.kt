package com.omnidocs.app.data.repository

import android.content.Context
import android.util.Log
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.FlashcardDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteVersionDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.FlashcardEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.NoteVersionEntity
import com.omnidocs.app.search.EmbeddingService
import com.omnidocs.app.util.HtmlSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds a pre-indexed interactive Demo Notebook on first install so new users can
 * immediately experience Hybrid Search, Knowledge Graph (`[[Wiki-Links]]`),
 * SM-2 Spaced Repetition Flashcards, Evidence Claims, and Action Items with zero wait.
 */
@Singleton
class DemoNotebookSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val flashcardDao: FlashcardDao,
    private val claimDao: ClaimDao,
    private val actionItemDao: ActionItemDao,
    private val noteVersionDao: NoteVersionDao,
    private val embeddingService: EmbeddingService
) {
    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
    private val seedMutex = Mutex()

    suspend fun seedIfEmptyWorkspace() = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            if (prefs.getBoolean(KEY_DEMO_SEEDED, false)) return@withLock
            try {
                val existing = noteDao.getAllNotesSync()
                if (existing.isNotEmpty()) {
                    prefs.edit().putBoolean(KEY_DEMO_SEEDED, true).apply()
                    return@withLock
                }

                val now = System.currentTimeMillis()
                val welcomeId = "demo-note-welcome"
                val archId = "demo-note-architecture"
                val meetingId = "demo-note-meeting"

                val welcomeHtml = """
                    <h2>Welcome to Your 100% Offline AI Second Brain</h2>
                    <p><strong>OmniDocs</strong> combines private note-taking, on-device AI synthesis, voice transcription, and spaced-repetition learning — all encrypted locally on your phone with zero cloud subscriptions.</p>
                    <h3>Explore Interactive Features Right Now</h3>
                    <ul>
                        <li><strong>Knowledge Graph:</strong> Tap the <em>Graph</em> tab to see how this note connects to [[On-Device AI &amp; Privacy Architecture]] and [[Product Strategy Meeting — Q4 Launch]].</li>
                        <li><strong>Ask Your Notes (Graph RAG):</strong> Tap <em>Talk with your Notes</em> on the Home screen to ask questions across your workspace with verified source citations.</li>
                        <li><strong>Spaced Repetition (SM-2):</strong> Tap <em>Practice</em> on the Home screen to review pre-built flashcards generated from these starter notes.</li>
                        <li><strong>Evidence &amp; Version History:</strong> Check the top bar of this editor to inspect extracted claims and version snapshots.</li>
                    </ul>
                """.trimIndent()

                val archHtml = """
                    <h2>On-Device AI &amp; Privacy Architecture</h2>
                    <p>Every note in OmniDocs is stored inside an <strong>AES-256 SQLCipher</strong> database whose master key is protected by the Android Hardware KeyStore. See [[Welcome to OmniDocs — Your Offline AI Second Brain]] for a quick tour.</p>
                    <h3>Hybrid Retrieval Engine</h3>
                    <p>Search combines <strong>SQLite FTS4</strong> lexical matching with <strong>USearch HNSW</strong> dense vector similarity and <strong>2-hop Graph RAG</strong> expansion via Reciprocal Rank Fusion (RRF).</p>
                    <h3>Local LLM &amp; Speech Pipeline</h3>
                    <p>Generative tasks run on-device via <strong>LiteRT-LM</strong>, while streaming multilingual voice notes use <strong>Sherpa-ONNX</strong> offline speech recognition.</p>
                """.trimIndent()

                val meetingHtml = """
                    <h2>Product Strategy Meeting — Q4 Launch</h2>
                    <p><strong>Context:</strong> Reviewing readiness for [[On-Device AI &amp; Privacy Architecture]] and mobile performance targets.</p>
                    <h3>Key Decisions</h3>
                    <ul>
                        <li>All user notes, voice recordings, and embeddings remain 100% local by default unless the user explicitly enables Google Drive sync.</li>
                        <li>Support 16 KB page alignment and hardware-accelerated GPU/CPU fallback for mobile inference.</li>
                    </ul>
                    <h3>Action Items</h3>
                    <ul>
                        <li>Test the 1-tap Voice Note capture and timestamped transcript player.</li>
                        <li>Complete the first 4 SM-2 flashcard reviews in the Study tab.</li>
                    </ul>
                """.trimIndent()

                val notes = listOf(
                    NoteEntity(
                        id = welcomeId,
                        title = "Welcome to OmniDocs — Your Offline AI Second Brain",
                        content = welcomeHtml,
                        plainText = HtmlSanitizer.toPlainText(welcomeHtml),
                        isPinned = true,
                        language = "en",
                        tags = "[\"welcome\",\"guide\",\"pkms\"]",
                        relatedNotes = "[\"$archId\",\"$meetingId\"]",
                        createdAt = now - 120_000L,
                        updatedAt = now,
                        imageUrl = null
                    ),
                    NoteEntity(
                        id = archId,
                        title = "On-Device AI & Privacy Architecture",
                        content = archHtml,
                        plainText = HtmlSanitizer.toPlainText(archHtml),
                        isPinned = false,
                        language = "en",
                        tags = "[\"architecture\",\"privacy\",\"ai\"]",
                        relatedNotes = "[\"$welcomeId\",\"$meetingId\"]",
                        createdAt = now - 240_000L,
                        updatedAt = now - 60_000L,
                        imageUrl = null
                    ),
                    NoteEntity(
                        id = meetingId,
                        title = "Product Strategy Meeting — Q4 Launch",
                        content = meetingHtml,
                        plainText = HtmlSanitizer.toPlainText(meetingHtml),
                        isPinned = false,
                        language = "en",
                        tags = "[\"meeting\",\"strategy\",\"launch\"]",
                        relatedNotes = "[\"$welcomeId\",\"$archId\"]",
                        createdAt = now - 360_000L,
                        updatedAt = now - 120_000L,
                        imageUrl = null
                    )
                )

                for (note in notes) {
                    noteDao.insertNote(note)
                    noteVersionDao.insertVersion(
                        NoteVersionEntity(
                            id = UUID.randomUUID().toString(),
                            noteId = note.id,
                            version = 1,
                            content = note.content,
                            plainText = note.plainText,
                            createdAt = note.createdAt
                        )
                    )
                }

                // Seed 4 SM-2 flashcards due immediately
                val flashcards = listOf(
                    FlashcardEntity(
                        id = "demo-card-1",
                        noteId = archId,
                        type = "QA",
                        prompt = "How does OmniDocs encrypt notes at rest on Android?",
                        answer = "Using an AES-256 SQLCipher database with its master key protected by the Android Hardware KeyStore.",
                        optionsJson = "[]",
                        sourceSnippet = "Every note in OmniDocs is stored inside an AES-256 SQLCipher database whose master key is protected by the Android Hardware KeyStore.",
                        sourceTitle = "On-Device AI & Privacy Architecture",
                        tagsJson = "[\"privacy\",\"security\"]",
                        nextReviewDateMs = now - 1000L,
                        createdAt = now,
                        updatedAt = now
                    ),
                    FlashcardEntity(
                        id = "demo-card-2",
                        noteId = archId,
                        type = "QA",
                        prompt = "Which three retrieval signals are fused by OmniDocs' Hybrid Search?",
                        answer = "SQLite FTS4 lexical search, USearch HNSW dense vector similarity, and 2-hop Knowledge Graph expansion via Reciprocal Rank Fusion (RRF).",
                        optionsJson = "[]",
                        sourceSnippet = "Search combines SQLite FTS4 lexical matching with USearch HNSW dense vector similarity and 2-hop Graph RAG expansion via Reciprocal Rank Fusion (RRF).",
                        sourceTitle = "On-Device AI & Privacy Architecture",
                        tagsJson = "[\"search\",\"rag\"]",
                        nextReviewDateMs = now - 1000L,
                        createdAt = now,
                        updatedAt = now
                    ),
                    FlashcardEntity(
                        id = "demo-card-3",
                        noteId = welcomeId,
                        type = "QA",
                        prompt = "How do you link two notes together in the OmniDocs Knowledge Graph?",
                        answer = "Wrap the target note's title in double brackets, such as [[Note Title]].",
                        optionsJson = "[]",
                        sourceSnippet = "Tap the Graph tab to see how this note connects to [[On-Device AI & Privacy Architecture]].",
                        sourceTitle = "Welcome to OmniDocs — Your Offline AI Second Brain",
                        tagsJson = "[\"graph\",\"guide\"]",
                        nextReviewDateMs = now - 1000L,
                        createdAt = now,
                        updatedAt = now
                    ),
                    FlashcardEntity(
                        id = "demo-card-4",
                        noteId = meetingId,
                        type = "QA",
                        prompt = "What is the default data residency policy for notes, recordings, and embeddings in OmniDocs?",
                        answer = "100% local on-device by default unless the user explicitly enables Google Drive sync.",
                        optionsJson = "[]",
                        sourceSnippet = "All user notes, voice recordings, and embeddings remain 100% local by default unless the user explicitly enables Google Drive sync.",
                        sourceTitle = "Product Strategy Meeting — Q4 Launch",
                        tagsJson = "[\"privacy\",\"policy\"]",
                        nextReviewDateMs = now - 1000L,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                flashcardDao.insertFlashcards(flashcards)

                // Seed verified claims
                val claims = listOf(
                    ClaimEntity(
                        id = "demo-claim-1",
                        noteId = archId,
                        text = "Notes are encrypted at rest with AES-256 SQLCipher and Android Hardware KeyStore.",
                        claimType = "fact",
                        confidence = 0.98f,
                        status = "approved",
                        createdAt = now,
                        updatedAt = now
                    ),
                    ClaimEntity(
                        id = "demo-claim-2",
                        noteId = archId,
                        text = "Hybrid retrieval fuses FTS4 lexical matching, USearch HNSW vectors, and 2-hop Graph RAG via RRF.",
                        claimType = "fact",
                        confidence = 0.96f,
                        status = "approved",
                        createdAt = now,
                        updatedAt = now
                    ),
                    ClaimEntity(
                        id = "demo-claim-3",
                        noteId = meetingId,
                        text = "All user notes, voice recordings, and embeddings remain 100% local by default.",
                        claimType = "decision",
                        confidence = 0.95f,
                        status = "ai_suggested",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                claimDao.insertClaims(claims)

                // Seed action items
                val actions = listOf(
                    ActionItemEntity(
                        id = "demo-action-1",
                        noteId = meetingId,
                        claimId = "demo-claim-3",
                        title = "Complete your first 4 SM-2 flashcard reviews in Practice mode",
                        description = "Open Practice from the Home screen Quick Actions bar.",
                        owner = "You",
                        dueAt = now + 86_400_000L,
                        status = "pending",
                        priority = "high",
                        createdAt = now,
                        updatedAt = now
                    ),
                    ActionItemEntity(
                        id = "demo-action-2",
                        noteId = welcomeId,
                        claimId = null,
                        title = "Explore the interactive Knowledge Graph",
                        description = "Tap the Graph tab in the bottom navigation bar to inspect linked notes.",
                        owner = "You",
                        dueAt = now + 172_800_000L,
                        status = "pending",
                        priority = "medium",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                actionItemDao.insertActionItems(actions)

                // Index embeddings so hybrid search & Ask Your Notes work immediately
                for (note in notes) {
                    try {
                        embeddingService.embedAndStoreNotePassages(note.id, note.title, note.plainText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Demo note embedding fallback for ${note.id}: ${e.message}")
                    }
                }

                prefs.edit().putBoolean(KEY_DEMO_SEEDED, true).apply()
                Log.i(TAG, "Seeded interactive Demo Notebook (3 notes, 4 flashcards, 3 claims, 2 tasks)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed demo notebook", e)
            }
        }
    }

    companion object {
        private const val TAG = "DemoNotebookSeeder"
        const val KEY_DEMO_SEEDED = "demo_notebook_seeded"
    }
}
