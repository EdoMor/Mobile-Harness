package com.jarves.mh.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.R
import com.jarves.mh.runtime.WirelessAdbManager
import com.jarves.mh.runtime.WirelessAdbPairingNotification
import kotlinx.coroutines.launch

/**
 * Settings → Wireless ADB: install adb into the Linux environment, pair it with this phone's
 * Wireless debugging, connect, and choose whether agents may use it.
 */
@Composable
fun WirelessAdbSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { WirelessAdbManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    var showManual by rememberSaveable { mutableStateOf(false) }
    var manualPort by rememberSaveable { mutableStateOf("") }
    var manualCode by rememberSaveable { mutableStateOf("") }
    var confirmAccess by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { manager.refresh() }

    fun beginPairing() {
        WirelessAdbPairingNotification.showCodePrompt(context)
        openWirelessDebugging(context)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginPairing()
        } else {
            showManual = true
            context.adbToast(context.getString(R.string.adb_notifications_needed))
        }
    }

    if (confirmAccess) {
        AlertDialog(
            onDismissRequest = { confirmAccess = false },
            title = { Text(stringResource(R.string.adb_agent_access_confirm_title)) },
            text = { Text(stringResource(R.string.adb_agent_access_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAccess = false
                    manager.setAgentAccess(true)
                }) { Text(stringResource(R.string.adb_agent_access_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAccess = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Text(stringResource(R.string.adb_intro), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
            Text(stringResource(R.string.adb_requires_android_11), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)

        !state.runtimeInstalled ->
            Text(stringResource(R.string.adb_runtime_needed), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        !state.installed -> {
            Button(
                onClick = { scope.launch { manager.install() } },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.adb_install)) }
            state.installFraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }

        else -> {
            val serial = state.connectedSerial
            Text(
                if (serial != null) {
                    stringResource(R.string.adb_status_connected, serial, state.shellUid ?: "?")
                } else {
                    stringResource(R.string.adb_status_not_connected)
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = if (serial != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else beginPairing()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.adb_pair_button)) }
                OutlinedButton(
                    onClick = { scope.launch { if (serial != null) manager.disconnect() else manager.connect() } },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (serial != null) R.string.adb_disconnect_button else R.string.adb_connect_button))
                }
            }
            Text(stringResource(R.string.adb_pair_steps), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            TextButton(onClick = { showManual = !showManual }) { Text(stringResource(R.string.adb_manual_toggle)) }
            if (showManual) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { value -> manualPort = value.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.adb_manual_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { value -> manualCode = value.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(R.string.adb_manual_code)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull()
                            if (port == null || port !in 1..65_535) {
                                context.adbToast(context.getString(R.string.adb_invalid_port))
                            } else {
                                scope.launch {
                                    val result = manager.pair(manualCode, port)
                                    if (result.success) manualCode = ""
                                }
                            }
                        },
                        enabled = !state.busy && manualCode.length == 6 && manualPort.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.adb_manual_pair)) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.adb_agent_access), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(stringResource(R.string.adb_agent_access_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.agentAccess,
                    onCheckedChange = { enabled -> if (enabled) confirmAccess = true else manager.setAgentAccess(false) },
                )
            }
        }
    }

    if (state.busy && state.installFraction == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.adb_working), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.message?.let { message ->
        Text(
            message,
            fontSize = 12.sp,
            color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Composer button for long runs that use adb: powers the display panel off so an OLED screen
 * cannot burn in, while the phone stays awake and unlocked for the agent. Renders nothing
 * unless Wireless ADB is connected. The screen comes back when the run ends.
 */
@Composable
fun AdbScreenOffButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { WirelessAdbManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { manager.refresh() }
    if (state.connectedSerial == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.adb_screen_off_title)) },
            text = { Text(stringResource(R.string.adb_screen_off_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val result = manager.turnScreenOff()
                        if (!result.success) context.adbToast(result.message)
                    }
                }) { Text(stringResource(R.string.adb_screen_off_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
            .clickable(enabled = !state.busy) {
                if (state.screenOff) scope.launch { manager.turnScreenOn() } else confirm = true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (state.busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.PhonelinkOff,
                contentDescription = stringResource(
                    if (state.screenOff) R.string.adb_screen_on_button else R.string.adb_screen_off_button,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    Spacer(Modifier.width(6.dp))
}

/** Opens Developer options, scrolled to Wireless debugging where the Settings app supports it. */
private fun openWirelessDebugging(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { context.adbToast(context.getString(R.string.adb_open_settings_failed)) }
}

private fun Context.adbToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
