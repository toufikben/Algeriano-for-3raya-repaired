package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.SecurityPrefs

@Composable
fun AppPinGate(
    prefs: SecurityPrefs,
    content: @Composable () -> Unit
) {
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableStateOf(0) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, unlocked) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && unlocked) {
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked) {
        content()
        return
    }

    val setupMode = !prefs.hasAppPin
    val isBlocked = blockedUntil > System.currentTimeMillis()

    LaunchedEffect(isBlocked) {
        if (isBlocked) {
            kotlinx.coroutines.delay((blockedUntil - System.currentTimeMillis()).coerceAtLeast(1L))
            blockedUntil = 0L
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (setupMode) "إنشاء رمز PIN للتطبيق" else "فتح تطبيق الحماية") },
        text = {
            Column {
                Text(
                    if (setupMode) "أنشئ رمزاً من 4 إلى 8 أرقام لحماية إعدادات التطبيق. لا تحفظ التطبيق الرمز نفسه، بل يخزن بصمة آمنة له."
                    else "أدخل رمز PIN للوصول إلى إعدادات الحماية والصورة والموقع.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = null } },
                    label = { Text(if (setupMode) "PIN جديد" else "PIN") },
                    singleLine = true,
                    enabled = !isBlocked,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (setupMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { confirmation = it; error = null } },
                        label = { Text("تأكيد PIN") },
                        singleLine = true,
                        enabled = !isBlocked,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isBlocked) return@Button
                    if (setupMode) {
                        when {
                            !pin.matches(Regex("\\d{4,8}")) -> error = "يجب أن يتكون PIN من 4 إلى 8 أرقام"
                            pin != confirmation -> error = "رمزا PIN غير متطابقين"
                            !prefs.setAppPin(pin) -> error = "تعذر حفظ PIN، حاول مرة أخرى"
                            else -> { unlocked = true; pin = ""; confirmation = "" }
                        }
                    } else if (prefs.verifyAppPin(pin)) {
                        unlocked = true
                        pin = ""
                        failedAttempts = 0
                    } else {
                        pin = ""
                        failedAttempts += 1
                        error = "رمز PIN غير صحيح"
                        if (failedAttempts >= 5) {
                            failedAttempts = 0
                            blockedUntil = System.currentTimeMillis() + 30_000L
                            error = "تم إيقاف المحاولات مؤقتاً لمدة 30 ثانية"
                        }
                    }
                },
                enabled = !isBlocked,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black)
            ) { Text(if (setupMode) "حفظ وفتح التطبيق" else "فتح التطبيق") }
        },
        dismissButton = {
            TextButton(onClick = {}, enabled = false) { Text("الحماية إلزامية") }
        }
    )
}
