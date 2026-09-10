package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.CountdownDiagnosticEvent
import com.example.ui.theme.CyberNavySurfaceVariant
import java.text.DateFormat
import java.util.Date

@Composable
fun CountdownDiagnosticsCard(
    events: List<CountdownDiagnosticEvent>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CyberNavySurfaceVariant.copy(alpha = 0.9f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFFFBBF24))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ui_countdown_diagnostics_title),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ui_countdown_diagnostics_description),
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val report = events.asReversed().joinToString("\n") { event ->
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(Date(event.timestamp))
                        "$time | ${event.stage}/${event.status} | ${event.detail.ifBlank { "—" }}"
                    }
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            context.getString(R.string.ui_countdown_diagnostics_title),
                            report.ifBlank { context.getString(R.string.ui_countdown_diagnostics_empty) }
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_copy_diagnostics))
            }
            Spacer(Modifier.height(10.dp))
            if (events.isEmpty()) {
                Text(
                    stringResource(R.string.ui_countdown_diagnostics_empty),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            } else {
                events.take(8).forEach { event ->
                    DiagnosticRow(event)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(event: CountdownDiagnosticEvent) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(event.timestamp))
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "$time  ${event.stage}/${event.status}",
            color = if (event.status == "failed") Color(0xFFFCA5A5) else Color(0xFF93C5FD),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(event.detail.ifBlank { "—" }, color = Color(0xFFCBD5E1), fontSize = 11.sp)
    }
}
