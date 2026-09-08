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
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
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
    var blockedUntil by remember { mutableLongStateOf(prefs.getPinBlockedUntil()) }
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
        title = { Text(if (setupMode) tr(com.example.R.string.ui_3f4f0bd01aa1) else tr(com.example.R.string.ui_0c3b6d325b25)) },
        text = {
            Column {
                Text(
                    if (setupMode) tr(com.example.R.string.ui_eff2c7b02500)
                    else tr(com.example.R.string.ui_70e664aca488),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = null } },
                    label = { Text(if (setupMode) tr(com.example.R.string.ui_58ec1cac3ac2) else "PIN") },
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
                        label = { Text(tr(com.example.R.string.ui_e0b00366c09e)) },
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
                            !pin.matches(Regex("\\d{6,8}")) -> error = tr(com.example.R.string.ui_8dd08f154388)
                            pin != confirmation -> error = tr(com.example.R.string.ui_1b0457e70ea6)
                            !prefs.setAppPin(pin) -> error = tr(com.example.R.string.ui_b385e9df7099)
                            else -> {
                                prefs.resetPinFailures()
                                unlocked = true
                                pin = ""
                                confirmation = ""
                            }
                        }
                    } else if (prefs.verifyAppPin(pin)) {
                        unlocked = true
                        pin = ""
                        prefs.resetPinFailures()
                    } else {
                        pin = ""
                        error = tr(com.example.R.string.ui_c33e81392abf)
                        val persistedBlockedUntil = prefs.registerPinFailure()
                        if (persistedBlockedUntil > 0L) {
                            blockedUntil = persistedBlockedUntil
                            error = tr(com.example.R.string.ui_9816b280d8ee)
                        }
                    }
                },
                enabled = !isBlocked,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black)
            ) { Text(if (setupMode) tr(com.example.R.string.ui_fcaa74c95b28) else tr(com.example.R.string.ui_4eeff8b9245e)) }
        },
        dismissButton = {
            TextButton(onClick = {}, enabled = false) { Text(tr(com.example.R.string.ui_4debd9959fb5)) }
        }
    )
}


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
