package com.itglasses.itglassesandroid

internal object MetaConnectionStatus {

    fun message(
        registrationState: String,
        connectedDevices: Int,
        sessionState: String?,
        error: String?,
    ): String {
        if (!error.isNullOrBlank()) return "Meta Wearables: $error"

        when (sessionState) {
            "STARTING" -> return "DAT session starting. Phone audio remains unchanged."
            "STARTED" -> return "DAT session active; glasses audio is not enabled. Phone mode remains available."
            "PAUSED" -> return "DAT session paused. Phone mode remains available."
            "STOPPING" -> return "DAT session stopping."
        }

        return when (registrationState) {
            "UNAVAILABLE" ->
                "No Meta wearable is available. Pair supported glasses in Meta AI; phone mode remains available."
            "AVAILABLE" ->
                "A Meta wearable is available. Register this app through Meta AI."
            "REGISTERING" ->
                "Registering with Meta AI…"
            "REGISTERED" -> if (connectedDevices > 0) {
                "DAT reports $connectedDevices connected device(s). Phone microphone and speaker remain selected."
            } else {
                "Registered, but no compatible Meta device is connected. Phone mode remains available."
            }
            "UNREGISTERING" ->
                "Unregistering from Meta AI…"
            else ->
                "Meta Wearables status is unavailable. Phone mode remains available."
        }
    }
}
