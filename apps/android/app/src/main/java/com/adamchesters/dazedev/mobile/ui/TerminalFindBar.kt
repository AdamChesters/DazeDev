package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Finds text in what this phone's terminal holds (with tmux, the visible screen) without typing into the
 * process. Matches are selected in the terminal; a match whose line changed is searched again.
 */
@Composable
fun TerminalFindBar(
    page: TerminalPageController,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf<TerminalFind?>(null) }
    var shown by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun search(): TerminalFind? {
        if (query.isEmpty()) {
            find = null
            status = ""
            page.call("clearSelection")
            return null
        }
        return try {
            val lines =
                page.lines() ?: run {
                    status = "The terminal did not answer. Try again."
                    return null
                }
            withContext(Dispatchers.Default) { TerminalFind.search(lines, SearchOptions(query, matchCase, regex)) }.also {
                find = it
                status = if (it.size == 0) "No matches in this terminal." else ""
            }
        } catch (e: Exception) {
            find = null
            status = e.message ?: "Search failed."
            null
        }
    }

    suspend fun reveal(
        result: TerminalFind,
        match: LineMatch?,
    ) {
        if (match == null) return
        val (first, text) = result.place(match)
        if (!page.reveal(first, text, match.start, match.end)) {
            // Output changed since the search: search again and show the latest match.
            val fresh = search() ?: return
            fresh.selected()?.let { m -> fresh.place(m).let { (f, t) -> page.reveal(f, t, m.start, m.end) } }
        }
        shown++
    }

    LaunchedEffect(query, matchCase, regex) {
        delay(250)
        search()?.let { reveal(it, it.selected()) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("Find in terminal") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find?.let { f -> scope.launch { reveal(f, f.previous()) } } }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { find?.let { f -> scope.launch { reveal(f, f.previous()) } } }, enabled = (find?.size ?: 0) > 0) { Text("Prev") }
            TextButton(onClick = { find?.let { f -> scope.launch { reveal(f, f.next()) } } }, enabled = (find?.size ?: 0) > 0) { Text("Next") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Match case") })
            FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("Regular expression") })
            val f = find
            // [shown] changes after each reveal, so the position text follows Next and Prev.
            Text(
                status.ifEmpty { if (f == null || f.size == 0 || shown < 0) "" else "${f.current + 1} of ${f.size}${if (f.size >= 1000) "+" else ""}" },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                page.call("clearSelection")
                onClose()
            }) { Text("Close") }
        }
    }
}
