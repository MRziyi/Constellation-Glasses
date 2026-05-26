package com.constellation.glass.halo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import timber.log.Timber

/**
 * ContentProvider that Halo Ring queries to list bindable actions for the
 * Action Picker (see halo-ring-plugin-protocol.md §4).
 *
 * URI: content://com.constellation.glass.halo_actions/list
 * Columns: action_id, label, group
 *
 * Phase 3b.1: returns the static action list. Phase 3b.3 makes the action
 * set state-aware via profile push.
 */
class HaloActionsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        Timber.i("HaloActionsProvider · query $uri")
        val cursor = MatrixCursor(arrayOf("action_id", "label", "group"))
        // Static action set; ring users bind these in the Halo Ring Profile editor.
        cursor.addRow(arrayOf("voice_invoke", "Voice — wake Constellation", "CONSTELLATION"))
        cursor.addRow(arrayOf("kill_active",  "Kill — cancel current task", "CONSTELLATION"))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/halo-action"

    // No write surface — read-only catalog.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
