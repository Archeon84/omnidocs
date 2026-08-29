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
import com.omnidocs.app.data.local.entity.ClaimEntity
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import com.omnidocs.app.data.local.entity.EntityEntity
import com.omnidocs.app.data.local.entity.EntityMentionEntity
import com.omnidocs.app.data.local.entity.EvidenceLinkEntity
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
        AiArtifactEntity::class
    ],
    version = 12,
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
                val password = getEncryptionPassword(context)
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
                        // Passphrase doesn't match — delete the stale database
                        // so Room/SQLCipher can create a fresh one below
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
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
        private fun getEncryptionPassword(context: Context): String {
            val prefs = context.getSharedPreferences("crypto_prefs", Context.MODE_PRIVATE)
            val keyStoreManager = try {
                com.omnidocs.app.security.KeyStoreManager()
            } catch (e: Exception) {
                null
            }

            var passphrase: String? = null

            // 1. Try reading KeyStore-encrypted passphrase
            val encryptedPass = prefs.getString(KEY_KEYSTORE_ENCRYPTED, null)
            if (encryptedPass != null && keyStoreManager != null) {
                try {
                    passphrase = keyStoreManager.decryptString(encryptedPass)
                } catch (e: Exception) {
                    // Fallback to legacy check if KeyStore decryption failed
                }
            }

            // 2. Try migrating legacy plaintext passphrase
            if (passphrase == null) {
                val legacyPass = prefs.getString(KEY_LEGACY, null)
                if (legacyPass != null) {
                    passphrase = legacyPass
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

            // 3. First-run generation
            if (passphrase == null) {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                passphrase = Base64.encodeToString(bytes, Base64.NO_WRAP)
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

            return "omnidocs_db_${passphrase}"
        }
    }
}
