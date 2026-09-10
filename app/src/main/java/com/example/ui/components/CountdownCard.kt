package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyberNavySurfaceVariant
import com.example.ui.theme.EmeraldActive

private data class CountdownOption(@androidx.annotation.StringRes val labelRes: Int, val durationMillis: Long)

private val countdownOptions = listOf(
    CountdownOption(com.example.R.string.ui_countdown_one_minute, 1 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_40add5d3e4a1, 60 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_2dbaa8dc4ba5, 2 * 60 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_3be76f716469, 6 * 60 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_efc446efeb10, 24 * 60 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_dd843ab7c6f4, 2 * 24 * 60 * 60 * 1000L),
    CountdownOption(com.example.R.string.ui_b5b126bd23e6, 0L)
)

@Composable
fun CountdownCard(
    isTrackingEnabled: Boolean,
    countdownEnabled: Boolean,
    remainingMillis: Long,
    selectedDurationMillis: Long,
    autoRestart: Boolean,
    onStart: (Long) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStopConfirmation by remember { mutableStateOf(false) }
    var showCustomDuration by remember { mutableStateOf(false) }
    var customHours by remember { mutableStateOf("24") }
    var customError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var selectedDuration by remember(selectedDurationMillis) {
        mutableStateOf(
            countdownOptions.firstOrNull { it.durationMillis == selectedDurationMillis }
                // FIX: custom durations (e.g. 5h) previously snapped back to
                // 1-min preset. Keep a synthetic custom option instead.
                ?: if (selectedDurationMillis > 0L) CountdownOption(
                    com.example.R.string.ui_b5b126bd23e6,
                    selectedDurationMillis
                ) else countdownOptions.first()
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CyberNavySurfaceVariant.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, if (countdownEnabled) EmeraldActive.copy(alpha = 0.65f) else CyanAccent.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, contentDescription = null, tint = if (countdownEnabled) EmeraldActive else CyanAccent)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(context.getString(com.example.R.string.ui_5a089be6b9c0), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (countdownEnabled) context.getString(com.example.R.string.ui_37132290bfa1)
                        else context.getString(com.example.R.string.ui_0f01b098d5a1),
                        color = Color(0xFFCBD5E1), fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (countdownEnabled) {
                Text(context.getString(com.example.R.string.ui_60086bba9b5d), color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text(
                    formatRemaining(remainingMillis),
                    color = EmeraldActive,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(com.example.R.string.ui_8a18596c3a0d))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showStopConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5))
                ) {
                    Icon(Icons.Filled.StopCircle, contentDescription = context.getString(com.example.R.string.ui_9e6d341a7ea9))
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(com.example.R.string.ui_9e6d341a7ea9))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(context.getString(com.example.R.string.ui_89f8b035060e), color = Color.White, fontSize = 14.sp)
                        Text(context.getString(com.example.R.string.ui_ee49b62ef84e), color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Switch(checked = autoRestart, onCheckedChange = onAutoRestartChange)
                }
            } else {
                Text(context.getString(com.example.R.string.ui_85026a616144), color = Color(0xFFCBD5E1), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    countdownOptions.take(4).forEach { option ->
                        DurationButton(option, selectedDuration == option) {
                            selectedDuration = option
                            if (option.durationMillis == 0L) showCustomDuration = true
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    countdownOptions.drop(4).forEach { option ->
                        DurationButton(option, selectedDuration == option) {
                            selectedDuration = option
                            if (option.durationMillis == 0L) showCustomDuration = true
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (selectedDuration.durationMillis > 0L) onStart(selectedDuration.durationMillis)
                        else showCustomDuration = true
                    },
                    enabled = isTrackingEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isTrackingEnabled) context.getString(com.example.R.string.ui_9d3a40b56ea6) else context.getString(com.example.R.string.ui_cdf0ecf21726))
                }
            }
        }
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text(context.getString(com.example.R.string.ui_8c4545c26d9d)) },
            text = { Text(context.getString(com.example.R.string.ui_4b8656bfac1f)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirmation = false
                    onCancel()
                }) { Text(context.getString(com.example.R.string.ui_0a390cb74aa0)) }
            },
            dismissButton = { TextButton(onClick = { showStopConfirmation = false }) { Text(context.getString(com.example.R.string.ui_e776b0209b50)) } }
        )
    }

    if (showCustomDuration) {
        AlertDialog(
            onDismissRequest = { showCustomDuration = false },
            title = { Text(context.getString(com.example.R.string.ui_1a9007b55b43)) },
            text = {
                Column {
                    Text(context.getString(com.example.R.string.ui_86a10ffe1dd0), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHours,
                        onValueChange = {
                            if (it.length <= 3 && it.all(Char::isDigit)) {
                                customHours = it
                                customError = null
                            }
                        },
                        label = { Text(context.getString(com.example.R.string.ui_7bdedbad7418)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    customError?.let { Text(it, color = Color(0xFFDC2626), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = customHours.toLongOrNull()
                    if (hours == null || hours !in 1..720) {
                        customError = context.getString(com.example.R.string.ui_a872f64d489c)
                    } else {
                        showCustomDuration = false
                        onStart(hours * 60 * 60 * 1000L)
                    }
                }) { Text(context.getString(com.example.R.string.ui_ab74e1258f2a)) }
            },
            dismissButton = { TextButton(onClick = { showCustomDuration = false }) { Text(context.getString(com.example.R.string.ui_e776b0209b50)) } }
        )
    }
}

@Composable
private fun RowScope.DurationButton(option: CountdownOption, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) CyanAccent else Color(0xFF475569)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected) CyanAccent else Color(0xFFCBD5E1))
    ) {
        Text(stringResource(option.labelRes), fontSize = 11.sp)
    }
}

@Composable
private fun formatRemaining(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> stringResource(com.example.R.string.ui_countdown_remaining_days, days, hours, minutes)
        else -> "%02d:%02d:%02d".format(hours, minutes, (millis / 1000L) % 60)
    }
}
