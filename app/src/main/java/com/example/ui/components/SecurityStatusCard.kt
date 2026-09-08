package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberNavySurfaceVariant
import com.example.ui.theme.EmeraldActive

@Composable
fun SecurityStatusCard(
    isTrackingEnabled: Boolean,
    isAdminActive: Boolean,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    emailConfigured: Boolean,
    modifier: Modifier = Modifier
) {
    val issues = buildList {
        if (!isAdminActive) add(tr(com.example.R.string.ui_2dd35163a536))
        if (!hasCameraPermission) add(tr(com.example.R.string.ui_ded5acc6b588))
        if (!hasLocationPermission) add(tr(com.example.R.string.ui_8f0baebbee2d))
        if (!hasNotificationPermission) add(tr(com.example.R.string.ui_569c6f29ee1a))
        if (!emailConfigured) add(tr(com.example.R.string.ui_8ccd3c5fd924))
    }
    val healthy = isTrackingEnabled && issues.isEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberNavySurfaceVariant.copy(alpha = 0.9f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (healthy) Icons.Filled.Security else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (healthy) EmeraldActive else Color(0xFFFBBF24)
                )
                Spacer(Modifier.width(8.dp))
                Text(tr(com.example.R.string.ui_15711b903550), color = Color.White, fontSize = 16.sp)
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                text = when {
                    healthy -> tr(com.example.R.string.ui_625d96b54288)
                    !isTrackingEnabled -> tr(com.example.R.string.ui_f8395ec2db0e)
                    else -> issues.joinToString("\n") { "• $it" }
                },
                color = if (healthy) EmeraldActive else Color(0xFFFCD34D),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
