// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.implementation.source.local

import com.srctool.graft.data.git.api.models.ObjectId
import com.srctool.graft.data.git.api.models.OperationEntry
import com.srctool.graft.data.git.api.models.OperationKind
import com.srctool.graft.data.git.api.models.OperationOutcome
import com.srctool.graft.data.git.api.models.RefState
import com.srctool.graft.data.git.api.models.UndoState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The op log's lines (Storage Formats): a header `{"version":1}`, then one JSON object per entry. Pure; a line it can't
 * read is null, never an exception, so one bad line never costs the rest of the log.
 */
internal object OpLogCodec {
    const val VERSION = 1

    val header: String = """{"version":$VERSION}"""

    /** The version a header line declares, or null when the line isn't a header. */
    fun version(line: String): Int? = parseObject(line)?.get("version")?.let { (it as? JsonPrimitive)?.intOrNull }

    fun encode(entry: OperationEntry): String = buildJsonObject {
        put("time", entry.time)
        put("worktree", entry.worktree)
        put("kind", entry.kind.name.lowercase())
        putJsonArray("argv") { entry.argv.forEach { add(JsonPrimitive(it)) } }
        put("exit", entry.exitCode)
        put("outcome", entry.outcome.name.lowercase())
        put("stderr", entry.stderrTail)
        put("before", refs(entry.before))
        put("after", refs(entry.after))
    }.toString()

    fun decode(line: String): OperationEntry? = try {
        val o = parseObject(line)
        if (o == null || "time" !in o) {
            null
        } else {
            OperationEntry(
                time = o.getValue("time").jsonPrimitive.longOrNull ?: return null,
                worktree = o.getValue("worktree").jsonPrimitive.content,
                kind = OperationKind.valueOf(o.getValue("kind").jsonPrimitive.content.uppercase()),
                argv = o.getValue("argv").jsonArray.map { it.jsonPrimitive.content },
                exitCode = o["exit"]?.jsonPrimitive?.intOrNull,
                outcome = OperationOutcome.valueOf(o.getValue("outcome").jsonPrimitive.content.uppercase()),
                stderrTail = o["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                before = refs(o.getValue("before").jsonObject),
                after = refs(o.getValue("after").jsonObject),
            )
        }
    } catch (_: Exception) {
        // Missing field, wrong type, unknown kind or a hash that isn't one: this line is lost, the rest are not.
        null
    }

    private fun refs(state: RefState): JsonObject = buildJsonObject {
        put("head", state.head?.hex)
        put("branch", state.branch)
        put("stash", state.stash?.hex)
    }

    private fun refs(o: JsonObject): RefState = RefState(
        head = o["head"]?.jsonPrimitive?.contentOrNull?.let(::ObjectId),
        branch = o["branch"]?.jsonPrimitive?.contentOrNull,
        stash = o["stash"]?.jsonPrimitive?.contentOrNull?.let(::ObjectId),
    )

    private fun parseObject(line: String): JsonObject? = try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

/** What Undo and Redo may act on, from the log's entries oldest first (Working Copy, "Undo"). */
internal object UndoReplay {
    /** A commit or amend that moved a branch (or a detached HEAD) forward and succeeded. */
    fun undoable(e: OperationEntry): Boolean = e.outcome == OperationOutcome.SUCCEEDED &&
        (e.kind == OperationKind.COMMIT || e.kind == OperationKind.AMEND) &&
        e.before.head != e.after.head && e.before.branch == e.after.branch

    /**
     * Two stacks, as in any editor: a new commit or amend goes on `done` and empties `undone`; Undo moves the top of
     * `done` to `undone`; Redo moves it back. Anything else in the log leaves them alone.
     */
    fun replay(oldestFirst: List<OperationEntry>): UndoState {
        val done = ArrayDeque<OperationEntry>()
        val undone = ArrayDeque<OperationEntry>()
        for (e in oldestFirst) {
            if (undoable(e)) {
                done.addLast(e)
                undone.clear()
            } else if (e.outcome == OperationOutcome.SUCCEEDED && e.kind == OperationKind.UNDO) {
                done.removeLastOrNull()?.let(undone::addLast)
            } else if (e.outcome == OperationOutcome.SUCCEEDED && e.kind == OperationKind.REDO) {
                undone.removeLastOrNull()?.let(done::addLast)
            }
        }
        return UndoState(done.lastOrNull(), undone.lastOrNull())
    }
}
