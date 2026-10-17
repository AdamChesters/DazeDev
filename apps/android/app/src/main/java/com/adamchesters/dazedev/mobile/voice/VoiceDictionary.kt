package com.adamchesters.dazedev.mobile.voice

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/** One revision of a dictionary entry. [disabled] is null when the stored revision has no such field. */
data class DictionaryVersion(
    val clock: Map<String, Long>,
    val preferred: String,
    val aliases: List<String>,
    val deleted: Boolean,
    val disabled: Boolean? = null,
)

/** Schema 1 dictionary: concurrent revisions of an entry stay side by side until the user resolves them. */
data class Dictionary(
    val entries: Map<String, List<DictionaryVersion>> = emptyMap(),
)

/**
 * The desktop's dictation dictionary (lib/dictionary.ts) for Android voice drafts: the same file format,
 * multi-value merge, conflict and whole-phrase replacement rules, so phone and desktop share one host file.
 */
object VoiceDictionary {
    private val id = Regex("^[\\w-]{1,80}$")
    private val reserved = setOf("__proto__", "constructor", "prototype")
    private const val MAX_SAFE = 9_007_199_254_740_991L

    fun validatePhrase(value: Any?): String {
        if (value !is String || value.isBlank() || value.length > 160 || value.any { it.code < 32 || it.code == 127 }) {
            throw IllegalArgumentException("Use a phrase of 1–160 characters without line breaks or control characters.")
        }
        return value
    }

    fun parse(value: Any?): Dictionary {
        if (value !is JSONObject || value.opt("schema") != 1 || value.optJSONObject("entries") == null || value.getJSONObject("entries").length() > 2000) {
            throw IllegalArgumentException("Invalid dictionary: expected version 1 with up to 2,000 entries.")
        }
        val entries = value.getJSONObject("entries")
        val result = LinkedHashMap<String, List<DictionaryVersion>>()
        for (key in entries.keys()) {
            val versions = entries.opt(key)
            if (!id.matches(key) || key in reserved || versions !is JSONArray || versions.length() == 0 || versions.length() > 30) throw IllegalArgumentException("Invalid dictionary entry.")
            result[key] = (0 until versions.length()).map { version(versions.opt(it)) }
        }
        return Dictionary(result)
    }

    private fun version(v: Any?): DictionaryVersion {
        val clock = (v as? JSONObject)?.optJSONObject("clock")
        if (v !is JSONObject || clock == null || clock.length() == 0 || clock.length() > 100 || v.opt("deleted") !is Boolean || (v.has("disabled") && v.opt("disabled") !is Boolean)) {
            throw IllegalArgumentException("Invalid dictionary revision.")
        }
        val counts = LinkedHashMap<String, Long>()
        for (k in clock.keys()) {
            val n = clock.opt(k)
            val whole = (n is Int || n is Long) || (n is Double && n == Math.floor(n) && !n.isInfinite())
            if (!id.matches(k) || k in reserved || n !is Number || !whole || n.toLong() < 1 || n.toLong() > MAX_SAFE) throw IllegalArgumentException("Invalid dictionary revision.")
            counts[k] = n.toLong()
        }
        val preferred = validatePhrase(v.opt("preferred"))
        val aliases = v.optJSONArray("aliases")
        if (aliases == null || aliases.length() == 0 || aliases.length() > 30) throw IllegalArgumentException("Add between 1 and 30 heard phrases.")
        return DictionaryVersion(counts, preferred, (0 until aliases.length()).map { validatePhrase(aliases.opt(it)) }, v.getBoolean("deleted"), if (v.has("disabled")) v.getBoolean("disabled") else null)
    }

    fun toJson(d: Dictionary): JSONObject {
        val entries = JSONObject()
        for ((key, versions) in d.entries) {
            entries.put(
                key,
                JSONArray(
                    versions.map { v ->
                        JSONObject()
                            .put("clock", JSONObject(v.clock as Map<*, *>))
                            .put("preferred", v.preferred)
                            .put("aliases", JSONArray(v.aliases))
                            .put("deleted", v.deleted)
                            .also { if (v.disabled != null) it.put("disabled", v.disabled) }
                    },
                ),
            )
        }
        return JSONObject().put("schema", 1).put("entries", entries)
    }

    private fun dominates(
        a: DictionaryVersion,
        b: DictionaryVersion,
    ) = b.clock.all { (k, n) -> (a.clock[k] ?: 0) >= n } && a.clock.any { (k, n) -> n > (b.clock[k] ?: 0) }

    private fun key(v: DictionaryVersion) =
        JSONArray()
            .put(
                JSONArray(
                    v.clock.entries
                        .sortedBy { it.key }
                        .map { JSONArray().put(it.key).put(it.value) },
                ),
            ).put(v.preferred)
            .put(JSONArray(v.aliases.sorted()))
            .put(v.deleted)
            .put(v.disabled == true)
            .toString()

    /** Keeps every revision no other revision has seen; commutative and idempotent. */
    fun merge(
        a: Dictionary,
        b: Dictionary,
    ): Dictionary {
        val merged = LinkedHashMap<String, List<DictionaryVersion>>()
        for (entry in (a.entries.keys + b.entries.keys)) {
            val versions = (a.entries[entry].orEmpty() + b.entries[entry].orEmpty()).associateBy(::key).values.toList()
            merged[entry] = versions.filter { v -> versions.none { other -> dominates(other, v) } }.sortedBy(::key)
        }
        return Dictionary(merged).also { parse(toJson(it)) }
    }

    /** A new revision of [entry] that supersedes every revision this device has seen. */
    fun edit(
        d: Dictionary,
        deviceId: String,
        entry: String,
        preferred: String,
        aliases: List<String>,
        deleted: Boolean = false,
        disabled: Boolean = false,
    ): Dictionary {
        val clock = LinkedHashMap<String, Long>()
        for (v in d.entries[entry].orEmpty()) for ((k, n) in v.clock) clock[k] = maxOf(clock[k] ?: 0, n)
        clock[deviceId] = (clock[deviceId] ?: 0) + 1
        val next = LinkedHashMap(d.entries)
        next[entry] = listOf(DictionaryVersion(clock, preferred.trim(), aliases.map(String::trim).filter(String::isNotEmpty).distinct(), deleted, disabled))
        return parse(toJson(Dictionary(next)))
    }

    fun conflicts(d: Dictionary) = d.entries.values.count { it.size > 1 }

    private fun normalized(phrase: String) = phrase.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Heard phrases with more than one preferred spelling; they are left alone. */
    fun ambiguous(d: Dictionary): List<String> = targets(d).filter { it.value.size > 1 }.keys.sorted()

    private fun targets(d: Dictionary): Map<String, Set<String>> {
        val targets = LinkedHashMap<String, MutableSet<String>>()
        for (versions in d.entries.values) {
            val v = versions.singleOrNull() ?: continue
            if (v.deleted || v.disabled == true) continue
            for (phrase in v.aliases + v.preferred) targets.getOrPut(normalized(phrase)) { LinkedHashSet() }.add(v.preferred)
        }
        return targets
    }

    /**
     * Replaces whole heard phrases (any case and spacing) with their preferred spelling in one pass: no
     * cascades, and URLs, paths and code tokens stay as they are. Conflicting or ambiguous entries are skipped.
     */
    fun apply(
        text: String,
        d: Dictionary,
        matched: MutableList<String>? = null,
    ): String {
        val choices = targets(d)
        val aliases = choices.filter { it.value.size == 1 }.keys.sortedByDescending { it.length }
        if (aliases.isEmpty()) return text
        val alternatives = aliases.joinToString("|") { alias -> alias.split(' ').joinToString("\\s+") { Regex.escape(it) } }
        // Case-insensitive across scripts, as the desktop's "giu" flags.
        val pattern = Pattern.compile("(?<![\\p{L}\\p{N}_/\\\\@.-])(?:$alternatives)(?![\\p{L}\\p{N}_/\\\\@-]|\\.[\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE).toRegex()
        return pattern.replace(text) { m ->
            val preferred = choices[normalized(m.value)]?.singleOrNull() ?: return@replace m.value
            matched?.add(preferred)
            preferred
        }
    }

    /** Changes when another device changed this entry; an edit based on an older revision is refused. */
    fun entryRevision(
        d: Dictionary,
        entry: String,
    ) = d.entries[entry]
        .orEmpty()
        .map(::key)
        .sorted()
        .joinToString("\n")

    /** Compares content, not JSON text: key order may differ between devices. */
    fun same(
        a: Dictionary,
        b: Dictionary,
    ) = a.entries.keys == b.entries.keys &&
        a.entries.all { (k, v) ->
            v.map(::key).sorted() ==
                b.entries[k]
                    .orEmpty()
                    .map(::key)
                    .sorted()
        }
}
