package com.omnidocs.app.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.AgentEventEntity
import com.omnidocs.app.data.local.entity.AgentJobEntity
import com.omnidocs.app.data.local.entity.AiArtifactEntity
import com.omnidocs.app.data.local.entity.AiRunEntity
import com.omnidocs.app.data.local.entity.AuditEventEntity
import com.omnidocs.app.data.local.entity.ChatMessageEntity
import com.omnidocs.app.data.local.entity.ChatSessionEntity
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import com.omnidocs.app.data.local.entity.EntityEntity
import com.omnidocs.app.data.local.entity.EntityMentionEntity
import com.omnidocs.app.data.local.entity.EvidenceLinkEntity
import com.omnidocs.app.data.local.entity.FlashcardEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.NoteFtsEntity
import com.omnidocs.app.data.local.entity.NoteLinkEntity
import com.omnidocs.app.data.local.entity.NoteVersionEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.SavedSearchEntity
import com.omnidocs.app.data.local.entity.SourceDocumentEntity
import com.omnidocs.app.data.local.entity.SpeakerEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File
import java.security.SecureRandom

@Database(
    entities = [
        NoteEntity::class, NoteFtsEntity::class,
        RecordingEntity::class, TranscriptSegmentEntity::class, SpeakerEntity::class,
        ClaimEntity::class, EvidenceLinkEntity::class, ActionItemEntity::class,
        EntityEntity::class, EntityMentionEntity::class, NoteLinkEntity::class,
        EmbeddingEntity::class, AiRunEntity::class, NoteVersionEntity::class,
        AuditEventEntity::class, SavedSearchEntity::class,
        AgentJobEntity::class, AgentEventEntity::class,
        SourceDocumentEntity::class, ContentBlockEntity::class,
        AiArtifactEntity::class, FlashcardEntity::class,
        ChatSessionEntity::class, ChatMessageEntity::class
    ],
    version = 18,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun claimDao(): ClaimDao
    abstract fun evidenceLinkDao(): EvidenceLinkDao
    abstract fun actionItemDao(): ActionItemDao
    abstract fun entityDao(): EntityDao
    abstract fun entityMentionDao(): EntityMentionDao
    abstract fun noteLinkDao(): NoteLinkDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun aiRunDao(): AiRunDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun agentJobDao(): AgentJobDao
    abstract fun agentEventDao(): AgentEventDao
    abstract fun sourceDocumentDao(): SourceDocumentDao
    abstract fun contentBlockDao(): ContentBlockDao
    abstract fun aiArtifactDao(): AiArtifactDao
    abstract fun flashcardDao(): FlashcardDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING fts4(
                        content=`notes`,
                        `title`,
                        `plainText`
                    )"""
                )
                // Triggers keep the FTS token index in sync with the notes table
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS `notes_fts_ai` AFTER INSERT ON `notes` BEGIN
                        INSERT INTO `notes_fts`(`docid`, `title`, `plainText`)
                        VALUES (new.`rowid`, new.`title`, new.`plainText`);
                    END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS `notes_fts_ad` AFTER DELETE ON `notes` BEGIN
                        DELETE FROM `notes_fts` WHERE `docid` = old.`rowid`;
                    END"""
                )
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS `notes_fts_au` AFTER UPDATE ON `notes` BEGIN
                        DELETE FROM `notes_fts` WHERE `docid` = old.`rowid`;
                        INSERT INTO `notes_fts`(`docid`, `title`, `plainText`)
                        VALUES (new.`rowid`, new.`title`, new.`plainText`);
                    END"""
                )
                // Populate FTS index with existing data
                db.execSQL(
                    "INSERT INTO `notes_fts`(`docid`, `title`, `plainText`) SELECT `rowid`, `title`, `plainText` FROM `notes` WHERE `isDeleted` = 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN relatedNotes TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Track when a note was soft-deleted so backup restore can distinguish
                // "deleted after this backup" (must stay deleted) from "deleted before".
                // NULL = never deleted / pre-migration rows.
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
            }
        }

        fun getDatabase(context: Context): NotesDatabase {
            return INSTANCE ?: synchronized(this) {
                // Initialize SQLCipher native libraries (only once)
                SQLiteDatabase.loadLibs(context)

                // Derive encryption passphrase
                val passwordResult = getEncryptionPassword(context)
                val password = passwordResult.passphrase
                val passphraseBytes = SQLiteDatabase.getBytes(password.toCharArray())

                // If the database file exists, verify that our current passphrase
                // can open it. This handles the case where the passphrase was rotated
                // or SharedPreferences was corrupted (e.g. during a prior failed
                // KeyStore migration attempt).
                val dbFile = context.getDatabasePath("notes_database")
                if (dbFile.exists()) {
                    try {
                        val testDb = SQLiteDatabase.openDatabase(
                            dbFile.absolutePath, password, null,
                            SQLiteDatabase.OPEN_READONLY
                        )
                        testDb.close()
                    } catch (e: Exception) {
                        // A restored device restores both the encrypted database and
                        // SharedPreferences, but the AndroidKeyStore key that guarded the
                        // passphrase is NOT restored. Decrypting then silently generates a
                        // fresh random passphrase, which would not match the existing DB.
                        //
                        // A valid, deliberate rotation (legacy -> KeyStore) always has the
                        // plaintext still readable from the legacy pref at rotation time, so
                        // we can prove we have the correct passphrase. When that proof is
                        // absent, deleting the workspace is permanent data loss. Fail loudly
                        // and recoverably instead of destroying user data.
                        if (!passwordResult.verified) {
                            throw IllegalStateException(
                                "Database is encrypted with a passphrase that cannot be recovered " +
                                    "after restore. KeyStore material is unavailable. Do NOT delete data. " +
                                    "Recover by restoring a matching backup, or clear app data manually."
                            )
                        }
                        // Proof of a deliberate passphrase rotation exists; the DB is stale and
                        // safe to recreate.
                        dbFile.delete()
                        File(dbFile.absolutePath + "-wal").delete()
                        File(dbFile.absolutePath + "-shm").delete()
                    }
                }

                // SupportFactory wraps SQLCipher's database to implement Room's
                // SupportSQLiteOpenHelper interface — this is the official integration path.
                val factory = SupportFactory(passphraseBytes)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "notes_database"
                )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }

        private const val KEY_LEGACY = "db_passphrase"
        private const val KEY_KEYSTORE_ENCRYPTED = "db_passphrase_keystore_enc"

        /**
         * Returns a stable encryption passphrase for SQLCipher.
         *
         * Derives or loads a 256-bit passphrase protected by AndroidKeyStore AES-256-GCM.
         * Automatically migrates legacy plaintext preferences to hardware-backed KeyStore encryption.
         */
        private fun getEncryptionPassword(context: Context): PasswordResult {
            val prefs = context.getSharedPreferences("crypto_prefs", Context.MODE_PRIVATE)
            val keyStoreManager = try {
                com.omnidocs.app.security.KeyStoreManager()
            } catch (e: Exception) {
                null
            }

            var passphrase: String? = null
            // Set when we can positively prove the passphrase matches the existing database.
            var verified = false

            // 1. Try reading KeyStore-encrypted passphrase
            val encryptedPass = prefs.getString(KEY_KEYSTORE_ENCRYPTED, null)
            if (encryptedPass != null && keyStoreManager != null) {
                try {
                    passphrase = keyStoreManager.decryptString(encryptedPass)
                } catch (e: Exception) {
                    // Fallback to legacy check if KeyStore decryption failed
                }
            }

            // 2. Try migrating legacy plaintext passphrase. A readable legacy pref proves we
            //    hold the real passphrase, so the on-disk DB can be trusted (deliberate rotation).
            if (passphrase == null) {
                val legacyPass = prefs.getString(KEY_LEGACY, null)
                if (legacyPass != null) {
                    passphrase = legacyPass
                    verified = true
                    if (keyStoreManager != null) {
                        try {
                            val enc = keyStoreManager.encryptString(legacyPass)
                            prefs.edit()
                                .putString(KEY_KEYSTORE_ENCRYPTED, enc)
                                .remove(KEY_LEGACY)
                                .apply()
                        } catch (e: Exception) {
                            // Non-fatal fallback
                        }
                    }
                }
            }

            // 3. First-run generation. Only "verified" when no database file exists yet,
            //    proving this passphrase is the original one for a fresh install. If a DB
            //    file IS present but we could not recover its passphrase (restored prefs +
            //    unavailable KeyStore), generating a fresh one must stay unverified so the
            //    open-check below refuses to delete the user's workspace. The generated
            //    passphrase is NOT persisted in that case either, so a later launch with a
            //    working KeyStore can still recover the original blob instead of clobbering it.
            if (passphrase == null) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                passphrase = Base64.encodeToString(bytes, Base64.NO_WRAP)
                verified = !context.getDatabasePath("notes_database").exists()
                if (verified) {
                    if (keyStoreManager != null) {
                        try {
                            val enc = keyStoreManager.encryptString(passphrase)
                            prefs.edit().putString(KEY_KEYSTORE_ENCRYPTED, enc).apply()
                        } catch (e: Exception) {
                            prefs.edit().putString(KEY_LEGACY, passphrase).apply()
                        }
                    } else {
                        prefs.edit().putString(KEY_LEGACY, passphrase).apply()
                    }
                }
            }

            return PasswordResult("omnidocs_db_${passphrase}", verified)
        }
    }

    data class PasswordResult(val passphrase: String, val verified: Boolean)
}
