package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CrimsonAlertDark
import com.example.ui.theme.CyberNavySurfaceVariant
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.EmeraldActiveDark

@Composable
fun ProtectionToggleCard(
    isTrackingEnabled: Boolean,
    isDeviceAdminActive: Boolean,
    isTesting: Boolean,
    onToggleTracking: (Boolean) -> Unit,
    onTestAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetColor = if (isTrackingEnabled) EmeraldActive else CrimsonAlert
    val animatedStatusColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 400),
        label = "statusColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTrackingEnabled) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("protection_toggle_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = CyberNavySurfaceVariant.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            Brush.linearGradient(
                listOf(
                    animatedStatusColor.copy(alpha = 0.6f),
                    animatedStatusColor.copy(alpha = 0.1f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Status Header Badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(animatedStatusColor.copy(alpha = 0.15f))
                    .border(1.dp, animatedStatusColor.copy(alpha = 0.4f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(animatedStatusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTrackingEnabled) tr(com.example.R.string.ui_d4a1d92f7a68) else tr(com.example.R.string.ui_edb2324aba4d),
                    color = animatedStatusColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main Central Shield Button with Glow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .scale(if (isTrackingEnabled) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                animatedStatusColor.copy(alpha = 0.35f),
                                animatedStatusColor.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
                    .clickable {
                        onToggleTracking(!isTrackingEnabled)
                    }
                    .testTag("main_power_button"),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                if (isTrackingEnabled) listOf(EmeraldActive, EmeraldActiveDark)
                                else listOf(CrimsonAlert, CrimsonAlertDark)
                            )
                        )
                ) {
                    Icon(
                        imageVector = if (isTrackingEnabled) Icons.Filled.Shield else Icons.Filled.PowerSettingsNew,
                        contentDescription = tr(com.example.R.string.ui_3d9e78dd04a7),
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isTrackingEnabled) tr(com.example.R.string.ui_b60bfe569ba1) else tr(com.example.R.string.ui_2bbe3b683d12),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = if (isTrackingEnabled)
                    tr(com.example.R.string.ui_323408b91800)
                else
                    tr(com.example.R.string.ui_c2fadb3ac9a4),
                fontSize = 13.sp,
                color = Color(0xFFCBD5E1),
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Switch Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isTrackingEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = animatedStatusColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = tr(com.example.R.string.ui_0b934d91473e),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isTrackingEnabled) tr(com.example.R.string.ui_1a7235262e3e) else tr(com.example.R.string.ui_a824491814b1),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Switch(
                    checked = isTrackingEnabled,
                    onCheckedChange = onToggleTracking,
                    modifier = Modifier.testTag("tracking_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = EmeraldActive,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = CrimsonAlert
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Test Alert Button
            Button(
                onClick = onTestAlert,
                enabled = !isTesting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("test_alert_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF38BDF8)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF38BDF8)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(tr(com.example.R.string.ui_9caa8b48d322), fontSize = 13.sp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr(com.example.R.string.ui_8f304ba307b2),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
