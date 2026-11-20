package com.itglasses.itglassesandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itglasses.itglassesandroid.databinding.ActivityMainBinding
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class MetaWearablesIntegration(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) : WearablesIntegration {

    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<String>
    private var pendingPermissionAction: (() -> Unit)? = null
    private var registrationStateName = "UNKNOWN"
    private var registrationError: String? = null
    private var connectedDevices = 0
    private val deviceStates = mutableMapOf<DeviceIdentifier, Device>()
    private var currentSession: DeviceSession? = null
    private var currentSessionState: String? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var sessionError: String? = null

    private val metaApplication: ITGlassesApplication
        get() = activity.application as ITGlassesApplication

    override fun onCreate(intent: Intent) {
        bluetoothPermissionLauncher =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val action = pendingPermissionAction
                pendingPermissionAction = null
                if (granted) {
                    action?.invoke()
                } else {
                    registrationError = "Se negó el permiso de Bluetooth. El modo teléfono sigue disponible."
                    renderStatus()
                }
            }

        if (metaApplication.metaSdkReady) {
            observeMetaState()
            handleRegistrationIntent(intent)
        } else {
            registrationError = metaApplication.metaInitializationError
                ?: "El SDK de Meta Wearables no está disponible. El modo teléfono sigue disponible."
            renderStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        handleRegistrationIntent(intent)
    }

    private fun handleRegistrationIntent(intent: Intent) {
        if (!metaApplication.metaSdkReady) return
        Wearables.handleIntent(intent) { request ->
            request.continueRegistration(activity).onFailure { error, _ ->
                registrationError = error.description
                renderStatus()
            }
        }.onFailure { error, _ ->
            registrationError = error.description
            renderStatus()
        }
    }

    private fun observeMetaState() {
        activity.lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                registrationStateName = state.name
                if (state == RegistrationState.REGISTERED) registrationError = null
                renderStatus()
            }
        }
        activity.lifecycleScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                registrationError = error.description
                renderStatus()
            }
        }
        activity.lifecycleScope.launch {
            Wearables.devices.collectLatest { identifiers ->
                deviceStates.clear()
                coroutineScope {
                    identifiers.forEach { identifier ->
                        val metadata = Wearables.devicesMetadata[identifier] ?: return@forEach
                        deviceStates[identifier] = metadata.value
                        launch {
                            metadata.collect { device ->
                                deviceStates[identifier] = device
                                connectedDevices = deviceStates.values.count {
                                    it.linkState == LinkState.CONNECTED
                                }
                                renderStatus()
                            }
                        }
                    }
                    connectedDevices = deviceStates.values.count {
                        it.linkState == LinkState.CONNECTED
                    }
                    renderStatus()
                    awaitCancellation()
                }
            }
        }
    }

    override fun register() {
        withBluetoothPermission {
            if (!metaApplication.metaSdkReady) {
                registrationError = "El SDK de Meta Wearables no está disponible. El modo teléfono sigue disponible."
                renderStatus()
                return@withBluetoothPermission
            }
            registrationError = null
            renderStatus()
            Wearables.startRegistration(activity)
        }
    }

    override fun startSession() {
        withBluetoothPermission {
            if (registrationStateName != RegistrationState.REGISTERED.name) {
                registrationError = "Registra esta app en Meta AI antes de iniciar la sesión DAT."
                renderStatus()
                return@withBluetoothPermission
            }
            if (connectedDevices == 0) {
                registrationError = "No hay lentes Meta conectados. El modo teléfono sigue disponible."
                renderStatus()
                return@withBluetoothPermission
            }
            if (currentSession != null) return@withBluetoothPermission

            registrationError = null
            sessionError = null
            Wearables.createSession(AutoDeviceSelector())
                .onFailure { error, _ ->
                    registrationError = if (error == DeviceSessionError.NO_ELIGIBLE_DEVICE) {
                        "No hay lentes Meta compatibles conectados."
                    } else {
                        error.description
                    }
                    renderStatus()
                }
                .onSuccess { session ->
                    currentSession = session
                    sessionStateJob = activity.lifecycleScope.launch {
                        session.state.collect { state ->
                            currentSessionState = state.name
                            if (state == DeviceSessionState.STOPPED) {
                                currentSession = null
                                sessionErrorJob?.cancel()
                                sessionErrorJob = null
                            }
                            renderStatus()
                        }
                    }
                    sessionErrorJob = activity.lifecycleScope.launch {
                        session.errors.collect { error ->
                            sessionError = when (error) {
                                DeviceSessionError.NO_ELIGIBLE_DEVICE ->
                                    "No hay lentes Meta compatibles conectados."
                                DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
                                    "Actualiza la app a una versión compatible del SDK de Meta Wearables."
                                DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
                                    "Actualiza la integración DAT desde las conexiones de la app Meta AI."
                                DeviceSessionError.DWA_UNAVAILABLE ->
                                    "La integración DAT no está disponible. Revisa la configuración de Meta AI."
                                else -> error.description
                            }
                            renderStatus()
                        }
                    }
                    session.start()
                }
        }
    }

    override fun stopSession() {
        currentSession?.stop()
    }

    override fun onDestroy() {
        currentSession?.stop()
        currentSession = null
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
    }

    private fun withBluetoothPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionAction = action
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            action()
        }
    }

    private fun renderStatus() {
        val registered = registrationStateName == RegistrationState.REGISTERED.name
        val sessionActive = currentSession != null
        binding.tvMetaStatus.text = if (!metaApplication.metaSdkReady) {
            metaApplication.metaInitializationError
                ?: "El SDK de Meta Wearables no está disponible. El modo teléfono sigue disponible."
        } else {
            MetaConnectionStatus.message(
                registrationState = registrationStateName,
                connectedDevices = connectedDevices,
                sessionState = currentSessionState,
                error = sessionError ?: registrationError,
            )
        }
        binding.btnMetaRegister.isEnabled =
            metaApplication.metaSdkReady && registrationStateName == RegistrationState.AVAILABLE.name
        binding.btnMetaSession.isEnabled =
            metaApplication.metaSdkReady && registered && connectedDevices > 0 && !sessionActive
        binding.btnMetaStop.isEnabled = sessionActive
    }
}
