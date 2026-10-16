package com.adamchesters.dazedev.mobile.ui

import android.content.SharedPreferences

/** Removes terminal drafts, originals and frozen targets not saved for seven days, except [draftKey]'s. */
internal fun pruneDrafts(
    drafts: SharedPreferences,
    draftKey: String,
) {
    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val edit = drafts.edit()
    drafts.all.keys.filter { it.startsWith("original:") }.forEach { key ->
        val old = key.removePrefix("original:")
        if (!drafts.contains("saved:$old")) edit.putLong("saved:$old", System.currentTimeMillis())
    }
    drafts.all.filter { it.key.startsWith("saved:") && it.key != "saved:$draftKey" && (it.value as? Long ?: Long.MAX_VALUE) < cutoff }.forEach { (key, _) ->
        val old = key.removePrefix("saved:")
        edit
            .remove(old)
            .remove("original:$old")
            .remove("target:$old")
            .remove(key)
    }
    edit.apply()
}
