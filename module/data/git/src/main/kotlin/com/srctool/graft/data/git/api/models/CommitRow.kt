// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Graft contributors

package com.srctool.graft.data.git.api.models

/**
 * One commit as the graph needs it (Commit Graph note): the hash, parents, who and when, and the subject. Names and
 * emails are interned in a [People] table and referenced by index, since the same few people author most of a history;
 * full messages load on demand.
 */
public data class CommitRow(
    val id: ObjectId,
    /** First parent first; empty for a root commit. */
    val parents: List<ObjectId>,
    val authorIndex: Int,
    /** Seconds since the epoch. */
    val authorTime: Long,
    val committerIndex: Int,
    /** Seconds since the epoch. */
    val committerTime: Long,
    /** The first line of the message; may be empty. */
    val subject: String,
) {
    val isMerge: Boolean get() = parents.size > 1
    val isRoot: Boolean get() = parents.isEmpty()
}

/** A name and email as git recorded them. */
public data class Person(val name: String, val email: String)

/** Interns [Person]s so each distinct name and email is stored once. Not thread-safe: one per stream. */
public class People {
    private val byPerson = HashMap<Person, Int>()
    private val list = ArrayList<Person>()

    /** The index of [person], adding it the first time. */
    public fun intern(person: Person): Int = byPerson.getOrPut(person) { list.add(person).let { list.size - 1 } }

    public operator fun get(index: Int): Person = list[index]

    public val size: Int get() = list.size
}
