package com.we.meet.feature.assistant.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.we.meet.feature.assistant.R
import com.we.meet.ui.theme.Dimens

@Composable
fun AssistantSummaryPanel(entry: AssistantHistoryEntry, request: SummaryRequestState?, generate: (() -> Unit)?,
    setTodo: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    val summary = entry.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.SpaceL), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
            Text(stringResource(R.string.assistant_summary_title), style = MaterialTheme.typography.titleMedium)
            when {
                summary != null -> {
                    Text(summary.summary)
                    if (summary.decisions.isNotEmpty()) Text(stringResource(R.string.assistant_summary_decisions), style = MaterialTheme.typography.labelLarge)
                    summary.decisions.forEach { Text("• $it") }
                    Text(stringResource(R.string.assistant_summary_todos), style = MaterialTheme.typography.labelLarge)
                    if (summary.tasks.isEmpty()) Text(stringResource(R.string.assistant_summary_no_tasks))
                    summary.tasks.forEachIndexed { index, task ->
                        var sources by remember(entry.id, index) { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.done, onCheckedChange = { setTodo(index, it) })
                            Column(Modifier.weight(1f)) {
                                Text(task.text)
                                if (task.owner.isNotBlank() || task.due.isNotBlank()) Text(listOf(task.owner, task.due).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { sources = !sources }) { Text(stringResource(R.string.assistant_summary_sources)) }
                        if (sources) entry.rows.filter { it.id in task.sourceIds }.forEach { Text(it.displayText(context), style = MaterialTheme.typography.bodySmall) }
                    }
                    HistoryTextActions(summary.export())
                }
                entry.endedAt == null -> Text(stringResource(R.string.assistant_summary_end_first))
                generate != null -> {
                    Text(stringResource(R.string.assistant_summary_hint), style = MaterialTheme.typography.bodySmall)
                    if (request == SummaryRequestState.FAILED || request == SummaryRequestState.TOO_LONG) Text(
                        stringResource(if (request == SummaryRequestState.TOO_LONG) R.string.assistant_summary_too_long else R.string.assistant_summary_error),
                        color = MaterialTheme.colorScheme.error)
                    Button(onClick = generate, enabled = request != SummaryRequestState.WORKING) {
                        Text(stringResource(if (request == SummaryRequestState.WORKING) R.string.assistant_summary_working else R.string.assistant_summary_generate))
                    }
                }
            }
        }
    }
}
