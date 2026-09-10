package com.example

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.content.res.Configuration
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.receiver.MyDeviceAdminReceiver
import com.example.ui.components.AppPasswordGuideDialog
import com.example.ui.components.AppPinGate
import com.example.ui.components.CredentialsCard
import com.example.ui.components.CountdownCard
import com.example.ui.components.CountdownDiagnosticsCard
import com.example.ui.components.DeveloperInfoDialog
import com.example.ui.components.IntruderLogsCard
import com.example.ui.components.PermissionsStatusCard
import com.example.ui.components.PinManagementDialog
import com.example.ui.components.ProtectionToggleCard
import com.example.ui.components.SecurityStatusCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyberNavyBg
import com.example.ui.theme.CyberNavySurface
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SecurityViewModel
import com.example.data.SecurityPrefs
import com.example.i18n.AppLanguage
import com.example.i18n.LanguageStore
import com.example.i18n.isRtl

class MainActivity : ComponentActivity() {

    private val viewModel: SecurityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val languageStore = LanguageStore(this)
        applyLanguage(languageStore.get())

        setContent {
            MyApplicationTheme {
                var selectedLanguage by remember { mutableStateOf(languageStore.get()) }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (selectedLanguage.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    AppPinGate(SecurityPrefs.getInstance(this@MainActivity)) {
                        SecurityMainScreen(
                            viewModel = viewModel,
                            onRequestAdmin = { requestDeviceAdmin() },
                            onRequestBatteryExemption = { requestBatteryExemption() },
                            language = selectedLanguage,
                            onLanguageChanged = { language ->
                                languageStore.set(language)
                                applyLanguage(language)
                                selectedLanguage = language
                                recreate()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeProtectionFromVisibleActivity()
    }

    private fun applyLanguage(language: AppLanguage) {
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(com.example.R.string.ui_81f1f7a15d28)
                )
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(com.example.R.string.ui_98f5fc225503), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, getString(com.example.R.string.ui_09e9d58c3f8a), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityMainScreen(
    viewModel: SecurityViewModel,
    onRequestAdmin: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeveloperInfo by remember { mutableStateOf(false) }
    var showAppPasswordGuide by remember { mutableStateOf(false) }
    var showPinManagement by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    // Lifecycle observer to refresh statuses when returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Permissions launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshStatuses()
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(uiState.bannerMessage) {
        uiState.bannerMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissBanner()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        containerColor = CyberNavyBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(CyanAccent, EmeraldActive))
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Phone Fortress",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLanguagePicker = true },
                        modifier = Modifier.testTag("language_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = tr(com.example.R.string.ui_9a6e57604b4d),
                            tint = CyanAccent
                        )
                    }
                    IconButton(
                        onClick = { showPinManagement = true },
                        modifier = Modifier.testTag("pin_management_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = tr(com.example.R.string.ui_29cc79599c5c),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { showAppPasswordGuide = true },
                        modifier = Modifier.testTag("guide_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HelpOutline,
                            contentDescription = tr(com.example.R.string.ui_c2d7f7559f21),
                            tint = CyanAccent
                        )
                    }
                    IconButton(
                        onClick = { showDeveloperInfo = true },
                        modifier = Modifier.testTag("developer_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = tr(com.example.R.string.ui_cd24acb10110),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = CyberNavySurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_content_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warning banner if Device Admin is not enabled
            if (!uiState.isAdminActive) {
                item {
                    AdminWarningBanner(
                        onActivateAdmin = onRequestAdmin
                    )
                }
            }

            // Top Hero Illustration
            item {
                SecurityHeroHeader(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    attemptsCount = uiState.logs.size
                )
            }

            item {
                SecurityStatusCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    isAdminActive = uiState.isAdminActive,
                    hasCameraPermission = uiState.hasCameraPermission,
                    hasLocationPermission = uiState.hasLocationPermission,
                    hasNotificationPermission = uiState.hasNotificationPermission,
                    emailConfigured = uiState.email.isNotBlank() && uiState.hasPassword
                )
            }

            // 1. Protection Switch & Live Toggle
            item {
                ProtectionToggleCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    isDeviceAdminActive = uiState.isAdminActive,
                    isTesting = uiState.isTesting,
                    onToggleTracking = { enabled ->
                        if (enabled && !uiState.isAdminActive) {
                            onRequestAdmin()
                        } else {
                            viewModel.toggleTracking(enabled)
                        }
                    },
                    onTestAlert = {
                        viewModel.testAlert()
                    }
                )
            }

            // 2. Countdown fallback protection
            item {
                CountdownCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    countdownEnabled = uiState.countdownEnabled,
                    remainingMillis = uiState.countdownRemainingMillis,
                    selectedDurationMillis = uiState.countdownDurationMillis,
                    autoRestart = uiState.countdownAutoRestart,
                    onStart = viewModel::startCountdown,
                    onReset = viewModel::resetCountdown,
                    onCancel = viewModel::cancelCountdown,
                    onAutoRestartChange = viewModel::setCountdownAutoRestart
                )
            }

            item {
                CountdownDiagnosticsCard(events = uiState.countdownDiagnostics)
            }

            // 3. Email & App Password Configuration
            item {
                CredentialsCard(
                    email = uiState.email,
                    password = uiState.password,
                    onEmailChange = viewModel::onEmailChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onSave = viewModel::saveCredentials,
                    onOpenGuide = { showAppPasswordGuide = true },
                    saveFeedback = uiState.saveFeedback
                )
            }

            // 3. System Permissions Status Card
            item {
                PermissionsStatusCard(
                    isAdminActive = uiState.isAdminActive,
                    hasCameraPermission = uiState.hasCameraPermission,
                    hasLocationPermission = uiState.hasLocationPermission,
                    hasNotificationPermission = uiState.hasNotificationPermission,
                    isBatteryOptimizationIgnored = uiState.isBatteryOptimizationIgnored,
                    onRequestAdmin = onRequestAdmin,
                    onRequestCamera = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    },
                    onRequestLocation = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    },
                    onRequestBatteryExemption = onRequestBatteryExemption
                )
            }

            // 4. Intruder Logs & History Card
            item {
                IntruderLogsCard(
                    logs = uiState.logs,
                    onClearLogs = viewModel::clearLogs
                )
            }

            // Bottom Developer Info trigger button
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    TextButton(
                        onClick = { showDeveloperInfo = true },
                        modifier = Modifier.testTag("footer_developer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr(com.example.R.string.ui_9eb289360bce),
                            color = CyanAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Developer Info Modal Dialog
    if (showDeveloperInfo) {
        DeveloperInfoDialog(onDismiss = { showDeveloperInfo = false })
    }

    // App Password Guide Dialog
    if (showAppPasswordGuide) {
        AppPasswordGuideDialog(onDismiss = { showAppPasswordGuide = false })
    }

    if (showPinManagement) {
        PinManagementDialog(
            context = context,
            prefs = SecurityPrefs.getInstance(context),
            onDismiss = { showPinManagement = false }
        )
    }
    if (showLanguagePicker) {
        LanguagePickerDialog(
            language = language,
            onLanguageChanged = {
                onLanguageChanged(it)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(com.example.R.string.ui_de636443d0c9)) },
        text = {
            Column {
                AppLanguage.entries.forEach { option ->
                    TextButton(
                        onClick = { onLanguageChanged(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = context.getString(option.displayNameRes),
                            modifier = Modifier.fillMaxWidth(),
                            color = if (option == language) CyanAccent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (option == language) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(com.example.R.string.ui_5bf826c5e57c))
            }
        }
    )
}

@Composable
fun SecurityHeroHeader(
    isTrackingEnabled: Boolean,
    attemptsCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CyberNavySurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            AsyncImage(
                model = R.drawable.security_hero_banner_1787338876086,
                contentDescription = tr(com.example.R.string.ui_51ae4e30735b),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF090D1A).copy(alpha = 0.9f),
                                Color(0xFF090D1A).copy(alpha = 0.4f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isTrackingEnabled) EmeraldActive else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isTrackingEnabled) tr(com.example.R.string.ui_6239de7ab740) else tr(com.example.R.string.ui_8ed3ffdd1ed5),
                        color = if (isTrackingEnabled) EmeraldActive else Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = tr(com.example.R.string.ui_12d1334739f6),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = tr(com.example.R.string.ui_b557180d4541),
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun AdminWarningBanner(
    onActivateAdmin: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_warning_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color(0xFF451A03)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = tr(com.example.R.string.ui_2dd35163a536),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Text(
                        text = tr(com.example.R.string.ui_24caf782e9fc),
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            androidx.compose.material3.Button(
                onClick = onActivateAdmin,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(tr(com.example.R.string.ui_c98273f24670), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
