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
            "STARTING" -> return "Iniciando sesión DAT. El audio sigue en el teléfono."
            "STARTED" -> return "Sesión DAT activa; el audio de los lentes no está habilitado. El modo teléfono sigue disponible."
            "PAUSED" -> return "Sesión DAT en pausa. El modo teléfono sigue disponible."
            "STOPPING" -> return "Cerrando sesión DAT."
        }

        return when (registrationState) {
            "UNAVAILABLE" ->
                "No hay lentes Meta disponibles. Empareja unos lentes compatibles en Meta AI; el modo teléfono sigue disponible."
            "AVAILABLE" ->
                "Hay lentes Meta disponibles. Registra esta app desde Meta AI."
            "REGISTERING" ->
                "Registrando en Meta AI…"
            "REGISTERED" -> if (connectedDevices > 0) {
                "DAT reporta $connectedDevices dispositivo(s) conectado(s). Se siguen usando el micrófono y la bocina del teléfono."
            } else {
                "Registrada, pero no hay lentes Meta compatibles conectados. El modo teléfono sigue disponible."
            }
            "UNREGISTERING" ->
                "Quitando el registro de Meta AI…"
            else ->
                "No se conoce el estado de Meta Wearables. El modo teléfono sigue disponible."
        }
    }
}
