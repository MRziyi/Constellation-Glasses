package com.constellation.glass.halo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.constellation.glass.ShortcutsLocalCache
import timber.log.Timber

/**
 * ContentProvider that Halo Ring queries to list bindable actions for the
 * Action Picker (see `halo-ring-plugin-protocol.md` §4).
 *
 * URI: `content://com.constellation.glass.halo_actions/list`
 *
 * Columns (per protocol §4.4):
 *   - `action_id`   String — stable id, used in subsequent Intent.TRIGGER
 *   - `label`       String — short user-visible label
 *   - `description` String — one-line longer description
 *   - `group`       String — sub-group ("CORE" / "SHORTCUTS")
 *
 * Two action sources:
 *   1. **Core actions** — always present, hand-defined (voice_invoke,
 *      kill_active). User-driven invocations that aren't shortcuts.
 *   2. **Shortcuts (dynamic)** — read from [ShortcutsLocalCache], which
 *      [com.constellation.glass.MainActivity] keeps in sync with Cortex
 *      via `/api/shortcuts`. Each shortcut becomes one cursor row with
 *      `action_id = shortcut_<id>` per protocol §4.6 (dynamic action lists).
 *
 * Network I/O is **forbidden** inside `query()` — Halo Ring blocks its picker
 * UI while this returns. So we read from the local cache file, never call
 * Cortex synchronously. If the cache is stale (Cortex was updated but the
 * app hasn't refreshed yet), Halo Ring sees the previous list — which is
 * fine for binding semantics.
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
        val cursor = MatrixCursor(arrayOf("action_id", "label", "description", "group"))

        // Core (always-available) actions
        cursor.addRow(arrayOf(
            "voice_invoke",
            "Voice — wake Constellation",
            "Open mic and send to Cortex",
            "CORE",
        ))
        cursor.addRow(arrayOf(
            "kill_active",
            "Kill — cancel current task",
            "Abort the current agent",
            "CORE",
        ))

        // User-defined shortcuts (cached from /api/shortcuts)
        val ctx = context
        if (ctx != null) {
            ShortcutsLocalCache.read(ctx).forEach { sc ->
                cursor.addRow(arrayOf(
                    "shortcut_${sc.id}",
                    sc.name,
                    if (sc.photo) "Sends preset prompt + a fresh camera frame" else "Sends preset prompt",
                    "SHORTCUTS",
                ))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/halo-action"

    // No write surface — read-only catalog (shortcut writes go through the
    // in-app Settings UI → Cortex /api/shortcuts → local cache refresh).
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
