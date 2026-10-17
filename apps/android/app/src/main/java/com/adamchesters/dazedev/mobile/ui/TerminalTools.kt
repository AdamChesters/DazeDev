package com.adamchesters.dazedev.mobile.ui

import android.net.Uri

/** Terminal screen actions that the view model carries out. Created once, so the screen does not recompose for it. */
class TerminalTools(
    /** Files picked with the document picker, to upload after confirmation. */
    val uploadFiles: (List<Uri>) -> Unit = {},
    /** A file found on the clipboard, for a quick upload. */
    val clipboardFile: (Uri) -> Unit = {},
    /** The terminal page now shown, or null when it goes away. */
    val page: (TerminalPageController?) -> Unit = {},
    /** A tapped web address or file path; shows the link sheet. */
    val link: (TerminalTarget) -> Unit = {},
    /** Opens Broadcast with this text (empty, or the voice draft). */
    val broadcast: (String) -> Unit = {},
    /** Applies the dictation dictionary to a voice transcript. */
    val applyDictionary: (String) -> String = { it },
    /** Opens the dictionary. */
    val openDictionary: () -> Unit = {},
)
