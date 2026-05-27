package com.constellation.glass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * On-disk cache of the shortcuts list, kept in sync with Cortex by the
 * in-app UI (MainActivity refreshes after every list/create/update/delete).
 *
 * **Why a file cache rather than calling Cortex from each consumer**:
 *   - [com.constellation.glass.halo.HaloActionsProvider] is a
 *     `ContentProvider` queried in-process by Halo Ring. Provider methods
 *     run on the binder thread and **must not do network I/O** (would
 *     stall the Halo Ring picker render).
 *   - [com.constellation.glass.halo.HaloTriggerReceiver] is a broadcast
 *     receiver with a tight 10s onReceive budget — also should not block
 *     on remote HTTP if the cache will do.
 *
 * The cache file is `<app filesDir>/shortcuts.json` — small JSON array of
 * Shortcut records. Reads/writes are synchronous (cache is < 5KB typical).
 *
 * Staleness model: cache reflects last successful list/CRUD. If Cortex is
 * unreachable when [HaloActionsProvider] is queried, we still return the
 * last-known list. The HaloTriggerReceiver will then fail at fire time
 * (HTTP unreachable) and log accordingly.
 */
object ShortcutsLocalCache {

    private const val FILE = "shortcuts.json"

    fun read(ctx: Context): List<ShortcutsClient.Shortcut> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ShortcutsClient.Shortcut(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    prompt = o.optString("prompt"),
                    photo = o.optBoolean("photo", false),
                    created = o.optString("created"),
                    updated = o.optString("updated"),
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "ShortcutsLocalCache · read failed")
            emptyList()
        }
    }

    fun write(ctx: Context, shortcuts: List<ShortcutsClient.Shortcut>) {
        val arr = JSONArray()
        shortcuts.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("prompt", s.prompt)
                put("photo", s.photo)
                put("created", s.created)
                put("updated", s.updated)
            })
        }
        try {
            File(ctx.filesDir, FILE).writeText(arr.toString())
        } catch (t: Throwable) {
            Timber.w(t, "ShortcutsLocalCache · write failed")
        }
    }
}
