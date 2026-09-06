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

private data class CountdownOption(val label: String, val durationMillis: Long)

private val countdownOptions = listOf(
    CountdownOption("ساعة", 60 * 60 * 1000L),
    CountdownOption("ساعتان", 2 * 60 * 60 * 1000L),
    CountdownOption("6 ساعات", 6 * 60 * 60 * 1000L),
    CountdownOption("يوم", 24 * 60 * 60 * 1000L),
    CountdownOption("يومان", 2 * 24 * 60 * 60 * 1000L),
    CountdownOption("مخصص", 0L)
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
    var selectedDuration by remember(selectedDurationMillis) {
        mutableStateOf(
            countdownOptions.firstOrNull { it.durationMillis == selectedDurationMillis }
                ?: countdownOptions.first()
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
                    Text("العد التنازلي للحماية", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (countdownEnabled) "عند انتهاء الوقت سيتم التقاط الصورة وإرسال الموقع"
                        else "خطة احتياطية إذا لم يتم فتح الجهاز",
                        color = Color(0xFFCBD5E1), fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (countdownEnabled) {
                Text("الوقت المتبقي", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
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
                    Text("إعادة ضبط بنفس المدة")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showStopConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5))
                ) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "إيقاف المؤقت")
                    Spacer(Modifier.width(8.dp))
                    Text("إيقاف المؤقت")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("إعادة التشغيل تلقائيًا", color = Color.White, fontSize = 14.sp)
                        Text("بعد الإرسال يعاد المؤقت بنفس المدة", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Switch(checked = autoRestart, onCheckedChange = onAutoRestartChange)
                }
            } else {
                Text("اختر مدة المؤقت", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    countdownOptions.take(3).forEach { option ->
                        DurationButton(option, selectedDuration == option) {
                            selectedDuration = option
                            if (option.durationMillis == 0L) showCustomDuration = true
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    countdownOptions.drop(3).forEach { option ->
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
                    Text(if (isTrackingEnabled) "تشغيل المؤقت" else "شغّل الحماية أولاً")
                }
            }
        }
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("إيقاف مؤقت الحماية؟") },
            text = { Text("لن يتم إرسال الصورة والموقع عند انتهاء المؤقت الحالي.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirmation = false
                    onCancel()
                }) { Text("إيقاف") }
            },
            dismissButton = { TextButton(onClick = { showStopConfirmation = false }) { Text("إلغاء") } }
        )
    }

    if (showCustomDuration) {
        AlertDialog(
            onDismissRequest = { showCustomDuration = false },
            title = { Text("مدة مخصصة") },
            text = {
                Column {
                    Text("أدخل عدد الساعات من 1 إلى 720 ساعة.", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHours,
                        onValueChange = {
                            if (it.length <= 3 && it.all(Char::isDigit)) {
                                customHours = it
                                customError = null
                            }
                        },
                        label = { Text("عدد الساعات") },
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
                        customError = "أدخل قيمة بين 1 و720 ساعة"
                    } else {
                        showCustomDuration = false
                        onStart(hours * 60 * 60 * 1000L)
                    }
                }) { Text("تشغيل") }
            },
            dismissButton = { TextButton(onClick = { showCustomDuration = false }) { Text("إلغاء") } }
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
        Text(option.label, fontSize = 11.sp)
    }
}

private fun formatRemaining(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "%d يوم و %02d:%02d".format(days, hours, minutes)
        else -> "%02d:%02d:%02d".format(hours, minutes, (millis / 1000L) % 60)
    }
}
