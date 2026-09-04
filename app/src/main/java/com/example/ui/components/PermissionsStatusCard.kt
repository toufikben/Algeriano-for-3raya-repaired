package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberCardBorder
import com.example.ui.theme.CyberNavySurfaceVariant
import com.example.ui.theme.EmeraldActive

@Composable
fun PermissionsStatusCard(
    isAdminActive: Boolean,
    hasCameraPermission: Boolean,
    hasLocationPermission: Boolean,
    hasNotificationPermission: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    onRequestAdmin: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestLocation: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("permissions_status_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CyberNavySurfaceVariant.copy(alpha = 0.85f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "صلاحيات النظام المطلوبة للعمل التلقائي",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Device Admin
            PermissionItemRow(
                icon = Icons.Filled.AdminPanelSettings,
                title = "صلاحية مدير الجهاز (Device Admin)",
                description = "إلزامية لرصد محاولات إدخال الرمز أو النمط الخاطئ",
                isGranted = isAdminActive,
                actionLabel = "تفعيل مدير الجهاز",
                onAction = onRequestAdmin,
                testTag = "admin_permission_item"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Camera Permission
            PermissionItemRow(
                icon = Icons.Filled.CameraAlt,
                title = "إذن الكاميرا الأمامية",
                description = "لالتقاط صورة المتسلل بصمت عند المحاولة الخاطئة",
                isGranted = hasCameraPermission,
                actionLabel = "منح إذن الكاميرا",
                onAction = onRequestCamera,
                testTag = "camera_permission_item"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Location Permission
            PermissionItemRow(
                icon = Icons.Filled.LocationOn,
                title = "إذن الموقع الجغرافي (GPS)",
                description = "لتحديد موقع الهاتف وإرسال إحداثياته عبر الخريطة",
                isGranted = hasLocationPermission,
                actionLabel = "منح إذن الموقع",
                onAction = onRequestLocation,
                testTag = "location_permission_item"
            )

            Spacer(modifier = Modifier.height(10.dp))

            PermissionItemRow(
                icon = Icons.Filled.Notifications,
                title = "إذن الإشعارات",
                description = "لعرض تحذير اقتراب انتهاء المؤقت ونتيجة الإرسال",
                isGranted = hasNotificationPermission,
                actionLabel = "منح إذن الإشعارات",
                onAction = onRequestNotifications,
                testTag = "notification_permission_item"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Battery Optimization
            PermissionItemRow(
                icon = Icons.Filled.BatteryAlert,
                title = "استثناء من توفير البطارية",
                description = "لضمان استمرار عمل الخدمة في الخلفية دون إيقافها من النظام",
                isGranted = isBatteryOptimizationIgnored,
                actionLabel = "استثناء التطبيق",
                onAction = onRequestBatteryExemption,
                testTag = "battery_permission_item"
            )
        }
    }
}

@Composable
private fun PermissionItemRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    val statusColor = if (isGranted) EmeraldActive else AmberWarning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.6f))
            .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldActive.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldActive,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ممنوح",
                        color = EmeraldActive,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = Color.Black
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
