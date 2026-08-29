package com.omnidocs.app.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-chosen SAF backup folder URI so the user does not have to
 * re-pick a folder on every backup. The URI grant itself is persisted by the
 * caller via [android.content.ContentResolver.takePersistableUriPermission].
 */
@Singleton
class LocalBackupPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("local_backup_prefs", Context.MODE_PRIVATE)

    var backupFolderUri: String?
        get() = prefs.getString(KEY_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_URI, value).apply()

    private companion object {
        const val KEY_FOLDER_URI = "backup_folder_uri"
    }
}
