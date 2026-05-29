package com.constellation.glass.halo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.constellation.glass.ShortcutSlots
import timber.log.Timber

/**
 * ContentProvider that Halo Ring queries to list bindable actions for the
 * Action Picker (see `halo-ring-plugin-protocol.md` §4).
 *
 * URI: `content://com.constellation.glass.halo_actions/list`
 *
 * Columns (per protocol §4.4): `action_id`, `label`, `description`, `group`.
 *
 * We expose exactly the two WAKE surfaces (per Zack's model — the ring's only
 * "拉起" ports; killing / approving / scrolling are in-HUD ring gestures, not
 * wake actions):
 *   1. `voice_invoke` — start / continue a voice agent session (converse,
 *      photo, etc.).
 *   2. `shortcut1` / `shortcut2` / `shortcut3` — three fixed slots that fire a
 *      preset prompt without speaking. Slot content is app-local (see
 *      [ShortcutSlots]) and edited by voice; the action ids are stable so a
 *      ring binding survives a slot's content changing.
 *
 * Network I/O is forbidden here (Halo Ring blocks its picker render on this
 * call) — [ShortcutSlots] reads a local file only.
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

        // Wake surface 1: voice agent.
        cursor.addRow(arrayOf(
            "voice_invoke",
            "Voice — wake Constellation",
            "Open mic; start or continue a session",
            "VOICE",
        ))

        // Wake surface 2: the three fixed shortcut slots.
        val ctx = context
        if (ctx != null) {
            ShortcutSlots.read(ctx).forEach { slot ->
                cursor.addRow(arrayOf(
                    slot.actionId,
                    "Shortcut ${slot.index}: ${slot.label}",
                    when {
                        !slot.configured -> "Empty — set by voice"
                        slot.sendPhoto -> "Sends preset prompt + a photo"
                        else -> "Sends preset prompt"
                    },
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
