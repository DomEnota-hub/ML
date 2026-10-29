package com.domenota.medialoader.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateAccessMode
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

@Composable
fun PrivateAccessGate(manager: PrivateAccessManager, modifier: Modifier = Modifier, autoAuthenticate: Boolean = true) {
    val state by manager.state.collectAsState()
    if (state.unlocked) return
    if (state.mode == PrivateAccessMode.UNCONFIGURED) {
        Column(modifier) { PrivateProtectionSettings(manager, onDone = {}) }
        return
    }
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptedSystemEntry by rememberSaveable { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val authenticate = rememberSystemAuthentication(
        onSuccess = { manager.onSystemAuthenticationSucceeded(); busy = false },
        onFailure = { error = it; busy = false }
    )
    LaunchedEffect(state.mode, lifecycleState, autoAuthenticate) {
        if (autoAuthenticate && state.mode == PrivateAccessMode.BIOMETRIC &&
            lifecycleState == Lifecycle.State.RESUMED && !attemptedSystemEntry && manager.systemProtectionAvailable) {
            // Consume before launching: credential activities stop/resume our lifecycle.
            attemptedSystemEntry = true
            busy = true
            error = null
            authenticate()
        }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Приватный раздел", style = MaterialTheme.typography.headlineSmall)
        when (state.mode) {
            PrivateAccessMode.PIN -> {
                Text("Введите PIN из 6 цифр")
                PrivatePinField(pin, { pin = it }, "PIN", !busy)
                Button(enabled = !busy && pin.length == 6, onClick = {
                    busy = true
                    val entered = pin
                    pin = ""
                    scope.launch {
                        runCatching { manager.unlockWithPin(entered) }
                            .onSuccess { if (!it) error = "Неверный PIN или слишком много попыток. После 5 ошибок подождите не менее 30 секунд." }
                            .onFailure { error = it.message ?: "Ошибка входа" }
                        busy = false
                    }
                }) { Text("Открыть") }
                Text("Если вы забыли свой PIN, восстановить доступ к приватным файлам нельзя.", style = MaterialTheme.typography.bodySmall)
            }
            PrivateAccessMode.BIOMETRIC -> {
                Text("Подтвердите вход биометрией или блокировкой устройства")
                Button(enabled = !busy && manager.systemProtectionAvailable, onClick = { attemptedSystemEntry = true; busy = true; error = null; authenticate() }) { Text(if (attemptedSystemEntry) "Повторить вход" else "Подтвердить вход") }
                if (!manager.systemProtectionAvailable) Text("Блокировка устройства недоступна. Восстановите её в настройках Android.")
            }
            PrivateAccessMode.NONE -> Button(onClick = manager::unlockWithoutProtection) { Text("Открыть раздел") }
            else -> Unit
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun PrivateProtectionSettings(manager: PrivateAccessManager, onDone: () -> Unit) {
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<PrivateAccessMode?>(null) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(mode: PrivateAccessMode) {
        scope.launch {
            runCatching { manager.configure(mode, pin.takeIf { mode == PrivateAccessMode.PIN }) }
                .onSuccess { pin = ""; confirmation = ""; onDone() }
                .onFailure { error = it.message ?: "Не удалось сохранить защиту" }
            busy = false
        }
    }
    val authenticate = rememberSystemAuthentication(
        onSuccess = { save(PrivateAccessMode.BIOMETRIC) },
        onFailure = { error = it; busy = false }
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Защита приватного раздела", style = MaterialTheme.typography.headlineSmall)
        if (state.mode != PrivateAccessMode.UNCONFIGURED && !state.unlocked) {
            Text("Сначала разблокируйте приватный раздел")
            return@Column
        }
        Text("Выберите способ входа")
        listOf(PrivateAccessMode.NONE to "Без защиты", PrivateAccessMode.PIN to "Свой PIN из 6 цифр", PrivateAccessMode.BIOMETRIC to "Биометрия / блокировка устройства").forEach { (mode, title) ->
            Row {
                RadioButton(selected = selected == mode, enabled = !busy && (mode != PrivateAccessMode.BIOMETRIC || manager.systemProtectionAvailable), onClick = { selected = mode; pin = ""; confirmation = ""; error = null })
                TextButton(enabled = !busy && (mode != PrivateAccessMode.BIOMETRIC || manager.systemProtectionAvailable), onClick = { selected = mode; pin = ""; confirmation = ""; error = null }) { Text(title) }
            }
        }
        if (!manager.systemProtectionAvailable) Text("Для системной защиты сначала задайте PIN, пароль или рисунок в настройках Android.", style = MaterialTheme.typography.bodySmall)
        if (selected == PrivateAccessMode.PIN) {
            PrivatePinField(pin, { pin = it }, "Новый PIN", !busy)
            PrivatePinField(confirmation, { confirmation = it }, "Повторите PIN", !busy)
            Text("Забытый PIN восстановить нельзя. Сохраните его в безопасном месте.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Приватные файлы хранятся внутри приложения и будут удалены при его удалении.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && selected != null && (selected != PrivateAccessMode.PIN || (pin.length == 6 && pin == confirmation)), onClick = {
            busy = true
            error = null
            val mode = selected ?: return@Button
            // Existing settings were freshly authorized against the current mode before entry.
            // A second credential activity would background the app and revoke that authorization.
            if (mode == PrivateAccessMode.BIOMETRIC && state.mode == PrivateAccessMode.UNCONFIGURED) authenticate() else save(mode)
        }) { Text(if (selected == PrivateAccessMode.BIOMETRIC && state.mode == PrivateAccessMode.UNCONFIGURED) "Подтвердить и сохранить" else "Сохранить") }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PrivatePinField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 6 && it.all { c -> c in '0'..'9' }) onValueChange(it) },
        label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

/** Both paths use Android's real authentication UI; activity results alone never configure a mode. */
@Composable
private fun rememberSystemAuthentication(onSuccess: () -> Unit, onFailure: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val success by rememberUpdatedState(onSuccess)
    val failure by rememberUpdatedState(onFailure)
    var pendingCredential by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var active by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (active && pendingCredential) {
            pendingCredential = false
            if (result.resultCode == Activity.RESULT_OK) success() else failure("Подтверждение отменено")
        }
    }
    DisposableEffect(Unit) {
        active = true
        onDispose { active = false; pendingCredential = false; signal?.cancel() }
    }
    return {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val credential: () -> Unit = {
            val previousSignal = signal
            signal = null
            previousSignal?.cancel()
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("Приватный раздел", "Подтвердите вход")
            if (intent == null) failure("Блокировка устройства недоступна")
            else {
                pendingCredential = true
                runCatching { launcher.launch(intent) }.onFailure { pendingCredential = false; failure("Не удалось открыть подтверждение Android") }
            }
        }
        if (Build.VERSION.SDK_INT < 28) credential()
        else {
            signal?.cancel()
            val cancellation = CancellationSignal()
            signal = cancellation
            val executor = Executor { command -> android.os.Handler(android.os.Looper.getMainLooper()).post(command) }
            runCatching {
                @Suppress("DEPRECATION")
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle("Приватный раздел")
                    .setSubtitle("Подтвердите вход")
                    .setNegativeButton("Блокировка устройства", executor) { _, _ -> if (active) credential() }
                    .build()
                prompt.authenticate(cancellation, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (active && signal === cancellation) success() }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!active || signal !== cancellation) return
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT || errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) credential()
                        else failure(errString.toString())
                    }
                })
            }.onFailure { credential() }
        }
    }
}
