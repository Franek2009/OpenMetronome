package org.openmetronome.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openmetronome.R

@Composable
fun DiagnosticsDialog(report: String, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_title)) },
        text = {
            SelectionContainer {
                Text(report, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton({ onCopy(report) }) { Text(stringResource(R.string.copy_report)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close_dialog)) } })
}
