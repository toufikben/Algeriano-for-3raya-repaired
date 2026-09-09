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
import androidx.compose.ui.platform.LocalContext
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
    var isProcessing by remember { mutableStateOf(false) }
    var blockedRemainingSeconds by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

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

    LaunchedEffect(blockedUntil) {
        while (blockedUntil > System.currentTimeMillis()) {
            blockedRemainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(250L)
        }
        blockedRemainingSeconds = 0L
        if (blockedUntil != 0L) blockedUntil = 0L
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (setupMode) context.getString(com.example.R.string.ui_3f4f0bd01aa1) else context.getString(com.example.R.string.ui_0c3b6d325b25)) },
        text = {
            Column {
                Text(
                    if (setupMode) context.getString(com.example.R.string.ui_eff2c7b02500)
                    else context.getString(com.example.R.string.ui_70e664aca488),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = null } },
                    label = { Text(if (setupMode) context.getString(com.example.R.string.ui_58ec1cac3ac2) else "PIN") },
                    singleLine = true,
                    enabled = !isBlocked && !isProcessing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (setupMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { confirmation = it; error = null } },
                        label = { Text(context.getString(com.example.R.string.ui_e0b00366c09e)) },
                        singleLine = true,
                        enabled = !isBlocked && !isProcessing,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFDC2626), fontSize = 12.sp)
                }
                if (isBlocked) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        context.getString(com.example.R.string.ui_pin_try_again_seconds, blockedRemainingSeconds),
                        color = Color(0xFFFCD34D),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isBlocked || isProcessing) return@Button
                    isProcessing = true
                    try {
                        if (setupMode) {
                            when {
                                !pin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_8dd08f154388)
                                pin != confirmation -> error = context.getString(com.example.R.string.ui_1b0457e70ea6)
                                !prefs.setAppPin(pin) -> error = context.getString(com.example.R.string.ui_b385e9df7099)
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
                            error = context.getString(com.example.R.string.ui_c33e81392abf)
                            val persistedBlockedUntil = prefs.registerPinFailure()
                            if (persistedBlockedUntil > 0L) {
                                blockedUntil = persistedBlockedUntil
                                error = context.getString(com.example.R.string.ui_9816b280d8ee)
                            }
                        }
                    } finally {
                        isProcessing = false
                    }
                },
                enabled = !isBlocked && !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black)
            ) { Text(if (isProcessing) context.getString(com.example.R.string.ui_pin_checking) else if (isBlocked) context.getString(com.example.R.string.ui_7429493736f9) else if (setupMode) context.getString(com.example.R.string.ui_fcaa74c95b28) else context.getString(com.example.R.string.ui_4eeff8b9245e)) }
        },
        dismissButton = {
            TextButton(onClick = {}, enabled = false) { Text(context.getString(com.example.R.string.ui_4debd9959fb5)) }
        }
    )
}
