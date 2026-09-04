package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SecurityPrefs

@Composable
fun PinManagementDialog(
    context: Context,
    prefs: SecurityPrefs,
    onDismiss: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showResetWarning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableStateOf(0) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    val isBlocked = blockedUntil > System.currentTimeMillis()

    LaunchedEffect(isBlocked) {
        if (isBlocked) {
            kotlinx.coroutines.delay((blockedUntil - System.currentTimeMillis()).coerceAtLeast(1L))
            blockedUntil = 0L
        }
    }

    if (showResetWarning) {
        AlertDialog(
            onDismissRequest = { showResetWarning = false },
            title = { Text("إعادة ضبط التطبيق بالكامل؟") },
            text = {
                Text(
                    "لأمانك لا يوجد تجاوز لـ PIN. سيتم فتح إعدادات أندرويد، ومن هناك يمكنك اختيار مسح بيانات التطبيق. هذا سيحذف PIN والسجلات والإعدادات وقد يوقف الحماية."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetWarning = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) { Text("فتح إعدادات أندرويد") }
            },
            dismissButton = {
                TextButton(onClick = { showResetWarning = false }) { Text("إلغاء") }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إدارة PIN التطبيق") },
        text = {
            Column {
                PinField(currentPin, { currentPin = it; error = null }, "PIN الحالي")
                Spacer(Modifier.height(8.dp))
                PinField(newPin, { newPin = it; error = null }, "PIN الجديد")
                Spacer(Modifier.height(8.dp))
                PinField(confirmation, { confirmation = it; error = null }, "تأكيد PIN الجديد")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = androidx.compose.ui.graphics.Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
                Button(enabled = !isBlocked, onClick = {
                    when {
                    !currentPin.matches(Regex("\\d{4,8}")) -> error = "أدخل PIN الحالي بشكل صحيح"
                    !newPin.matches(Regex("\\d{4,8}")) -> error = "يجب أن يتكون PIN الجديد من 4 إلى 8 أرقام"
                    newPin != confirmation -> error = "رمزا PIN الجديد غير متطابقين"
                    !prefs.changeAppPin(currentPin, newPin) -> {
                        failedAttempts += 1
                        error = "PIN الحالي غير صحيح"
                        if (failedAttempts >= 5) {
                            failedAttempts = 0
                            blockedUntil = System.currentTimeMillis() + 30_000L
                            error = "تم إيقاف المحاولات مؤقتاً لمدة 30 ثانية"
                        }
                    }
                    else -> {
                        failedAttempts = 0
                        onDismiss()
                    }
                }
            }) { Text(if (isBlocked) "المحاولة متوقفة مؤقتاً" else "حفظ PIN الجديد") }
        },
        dismissButton = {
            TextButton(onClick = { showResetWarning = true }) { Text("نسيت PIN؟") }
        }
    )
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}
