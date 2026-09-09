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
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
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
    var blockedUntil by remember { mutableLongStateOf(prefs.getPinBlockedUntil()) }
    var isProcessing by remember { mutableStateOf(false) }
    var blockedRemainingSeconds by remember { mutableLongStateOf(0L) }
    val isBlocked = blockedUntil > System.currentTimeMillis()

    LaunchedEffect(blockedUntil) {
        while (blockedUntil > System.currentTimeMillis()) {
            blockedRemainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(250L)
        }
        blockedRemainingSeconds = 0L
        if (blockedUntil != 0L) blockedUntil = 0L
    }

    if (showResetWarning) {
        AlertDialog(
            onDismissRequest = { showResetWarning = false },
            title = { Text(context.getString(com.example.R.string.ui_d6b354ff91de)) },
            text = {
                Text(
                    context.getString(com.example.R.string.ui_258dec0e9a99)
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
                }) { Text(context.getString(com.example.R.string.ui_6d8288ba5097)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetWarning = false }) { Text(context.getString(com.example.R.string.ui_e776b0209b50)) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(com.example.R.string.ui_17b11cf37f25)) },
        text = {
            Column {
                PinField(currentPin, { currentPin = it; error = null }, context.getString(com.example.R.string.ui_c8522f0305c3))
                Text(
                    context.getString(com.example.R.string.ui_pin_current_rule),
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                PinField(newPin, { newPin = it; error = null }, context.getString(com.example.R.string.ui_58306554a47d))
                Text(
                    context.getString(com.example.R.string.ui_pin_new_rule),
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                PinField(confirmation, { confirmation = it; error = null }, context.getString(com.example.R.string.ui_bcf33093a0fa))
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = androidx.compose.ui.graphics.Color(0xFFDC2626), fontSize = 12.sp)
                }
                if (isBlocked) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        context.getString(com.example.R.string.ui_pin_try_again_seconds, blockedRemainingSeconds),
                        color = androidx.compose.ui.graphics.Color(0xFFFCD34D),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
                Button(enabled = !isBlocked && !isProcessing, onClick = {
                    if (isBlocked || isProcessing) return@Button
                    isProcessing = true
                    try {
                        when {
                        !currentPin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_e18e557fd5d6)
                        !newPin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_8dd08f154388)
                        newPin != confirmation -> error = context.getString(com.example.R.string.ui_8d10332dd5a1)
                        !prefs.changeAppPin(currentPin, newPin) -> {
                            error = context.getString(com.example.R.string.ui_35c16c4d4620)
                            val persistedBlockedUntil = prefs.registerPinFailure()
                            if (persistedBlockedUntil > 0L) {
                                blockedUntil = persistedBlockedUntil
                                error = context.getString(com.example.R.string.ui_9816b280d8ee)
                            }
                        }
                        else -> {
                            prefs.resetPinFailures()
                            onDismiss()
                        }
                    }
                    } finally {
                        isProcessing = false
                    }
                }) { Text(if (isProcessing) context.getString(com.example.R.string.ui_pin_checking) else if (isBlocked) context.getString(com.example.R.string.ui_7429493736f9) else context.getString(com.example.R.string.ui_f09f791e2ff8)) }
        },
        dismissButton = {
            TextButton(onClick = { showResetWarning = true }) { Text(context.getString(com.example.R.string.ui_5c2d36070240)) }
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
