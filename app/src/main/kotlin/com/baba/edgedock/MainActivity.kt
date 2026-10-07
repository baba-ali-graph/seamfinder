package com.baba.edgedock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baba.edgedock.data.AppsLayout
import com.baba.edgedock.data.UserPreferences
import com.baba.edgedock.overlay.OverlayService
import com.baba.edgedock.ui.theme.EdgeDockTheme
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import kotlinx.coroutines.launch

private enum class Screen { Settings, About, Privacy, Licenses }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EdgeDockTheme {
                var screen by remember { mutableStateOf(Screen.Settings) }
                when (screen) {
                    Screen.Settings -> SettingsScreen(
                        onOpenAbout = { screen = Screen.About },
                    )
                    Screen.About -> AboutScreen(
                        onBack = { screen = Screen.Settings },
                        onOpenPrivacy = { screen = Screen.Privacy },
                        onOpenLicenses = { screen = Screen.Licenses },
                    )
                    Screen.Privacy -> PrivacyScreen(onBack = { screen = Screen.About })
                    Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.About })
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val appRepository = remember { (context.applicationContext as EdgeDockApp).appRepository }
    val preferences = remember { UserPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val appsLayout by preferences.appsLayout.collectAsStateWithLifecycle(initialValue = AppsLayout.LIST)
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var usageAccessGranted by remember { mutableStateOf(appRepository.hasUsageAccess()) }
    var dockRunning by remember {
        mutableStateOf(OverlayService.isRunning() || preferences.isDockEnabledBlocking())
    }
    var notificationDenied by remember { mutableStateOf(false) }
    var batteryRestricted by remember {
        mutableStateOf(!isIgnoringBatteryOptimizations(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                usageAccessGranted = appRepository.hasUsageAccess()
                dockRunning = OverlayService.isRunning()
                batteryRestricted = !isIgnoringBatteryOptimizations(context)
                if (!overlayGranted && dockRunning) {
                    OverlayService.stop(context)
                    dockRunning = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            notificationDenied = false
            OverlayService.start(context)
            dockRunning = true
        } else {
            notificationDenied = true
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (overlayGranted) {
                    stringResource(R.string.overlay_permission_granted)
                } else {
                    stringResource(R.string.overlay_permission_rationale)
                }
            )
            Button(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                } else {
                    overlayGranted = true
                }
            }) {
                Text(
                    text = if (overlayGranted) {
                        stringResource(R.string.overlay_permission_granted)
                    } else {
                        stringResource(R.string.grant_overlay_permission)
                    }
                )
            }
            Text(
                text = if (usageAccessGranted) {
                    stringResource(R.string.usage_access_granted)
                } else {
                    stringResource(R.string.usage_access_rationale)
                }
            )
            Button(onClick = {
                if (!usageAccessGranted) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else {
                    usageAccessGranted = true
                }
            }) {
                Text(
                    text = if (usageAccessGranted) {
                        stringResource(R.string.usage_access_granted)
                    } else {
                        stringResource(R.string.enable_usage_access)
                    }
                )
            }
            Button(
                enabled = overlayGranted,
                onClick = {
                    if (dockRunning) {
                        OverlayService.stop(context)
                        dockRunning = false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationDenied = false
                        OverlayService.start(context)
                        dockRunning = true
                    }
                },
            ) {
                Text(
                    text = if (dockRunning) {
                        stringResource(R.string.stop_dock)
                    } else {
                        stringResource(R.string.start_dock)
                    }
                )
            }
            if (notificationDenied) {
                Text(
                    text = stringResource(R.string.notification_permission_needed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (batteryRestricted && dockRunning) {
                Text(
                    text = stringResource(R.string.battery_nudge_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = stringResource(R.string.battery_nudge_body))
                Button(onClick = { openAppDetailsSettings(context) }) {
                    Text(stringResource(R.string.open_app_settings))
                }
            }
            Text(text = stringResource(R.string.apps_layout_label))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = appsLayout == AppsLayout.LIST,
                    onClick = { scope.launch { preferences.setAppsLayout(AppsLayout.LIST) } },
                    label = { Text(stringResource(R.string.apps_layout_list)) },
                )
                FilterChip(
                    selected = appsLayout == AppsLayout.GRID,
                    onClick = { scope.launch { preferences.setAppsLayout(AppsLayout.GRID) } },
                    label = { Text(stringResource(R.string.apps_layout_grid)) },
                )
            }
            TextButton(onClick = onOpenAbout) {
                Text(text = stringResource(R.string.about_title))
            }
        }
    }
}

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            TextButton(onClick = onOpenPrivacy) {
                Text(stringResource(R.string.privacy_policy))
            }
            TextButton(onClick = onOpenLicenses) {
                Text(stringResource(R.string.open_source_licenses))
            }
        }
    }
}

@Composable
private fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.privacy_policy),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(text = stringResource(R.string.privacy_policy_body))
        }
    }
}

@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.back))
            }
            val context = LocalContext.current
            val libraries by produceLibraries {
                context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
            }
            LibrariesContainer(libraries = libraries, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppDetailsSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}
