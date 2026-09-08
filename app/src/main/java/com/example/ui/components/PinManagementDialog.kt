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
            title = { Text(tr(com.example.R.string.ui_d6b354ff91de)) },
            text = {
                Text(
                    tr(com.example.R.string.ui_258dec0e9a99)
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
                }) { Text(tr(com.example.R.string.ui_6d8288ba5097)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetWarning = false }) { Text(tr(com.example.R.string.ui_e776b0209b50)) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(com.example.R.string.ui_17b11cf37f25)) },
        text = {
            Column {
                PinField(currentPin, { currentPin = it; error = null }, tr(com.example.R.string.ui_c8522f0305c3))
                Spacer(Modifier.height(8.dp))
                PinField(newPin, { newPin = it; error = null }, tr(com.example.R.string.ui_58306554a47d))
                Spacer(Modifier.height(8.dp))
                PinField(confirmation, { confirmation = it; error = null }, tr(com.example.R.string.ui_bcf33093a0fa))
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = androidx.compose.ui.graphics.Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
                Button(enabled = !isBlocked, onClick = {
                    when {
                    !currentPin.matches(Regex("\\d{4,8}")) -> error = tr(com.example.R.string.ui_e18e557fd5d6)
                    !newPin.matches(Regex("\\d{6,8}")) -> error = tr(com.example.R.string.ui_8dd08f154388)
                    newPin != confirmation -> error = tr(com.example.R.string.ui_8d10332dd5a1)
                    !prefs.changeAppPin(currentPin, newPin) -> {
                        error = tr(com.example.R.string.ui_35c16c4d4620)
                        val persistedBlockedUntil = prefs.registerPinFailure()
                        if (persistedBlockedUntil > 0L) {
                            blockedUntil = persistedBlockedUntil
                            error = tr(com.example.R.string.ui_9816b280d8ee)
                        }
                    }
                    else -> {
                        prefs.resetPinFailures()
                        onDismiss()
                    }
                }
            }) { Text(if (isBlocked) tr(com.example.R.string.ui_7429493736f9) else tr(com.example.R.string.ui_f09f791e2ff8)) }
        },
        dismissButton = {
            TextButton(onClick = { showResetWarning = true }) { Text(tr(com.example.R.string.ui_5c2d36070240)) }
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


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
