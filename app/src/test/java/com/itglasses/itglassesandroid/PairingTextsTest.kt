package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every PairingText key has its own approved Spanish text in res/values/strings.xml. */
class PairingTextsTest {
    private val strings: Map<String, String> by lazy {
        val path = "src/main/res/values/strings.xml"
        val file = listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: throw AssertionError("No se encontró $path desde ${File(".").absolutePath}")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun resourceName(id: Int): String = R.string::class.java.fields.single { it.getInt(null) == id }.name
    private fun textOf(key: PairingText): String = strings[resourceName(PairingTexts.of(key))]
        ?: throw AssertionError("$key no tiene texto en strings.xml")

    private val approved = mapOf(
        PairingText.REGISTERING_TITLE to "Registrando este celular…",
        PairingText.REGISTERING_DETAIL to "Conectando con el servidor de ITGlasses.",
        PairingText.EXPIRED_TITLE to "La solicitud caducó. Generando un código nuevo…",
        PairingText.REMOVED_TITLE to "Este celular se eliminó del panel. Registrándolo de nuevo…",
        PairingText.PENDING_TITLE to "Dispositivo pendiente de aprobación",
        PairingText.PENDING_DETAIL to "Aprueba en el panel de ITGlasses el dispositivo que muestre este mismo código.",
        PairingText.PENDING_EXPIRES to "La solicitud caduca en %1\$s.",
        PairingText.CODE_SPOKEN to "Código %1\$s",
        PairingText.CONNECTED_TITLE to "Conectado",
        PairingText.CONNECTED_DETAIL to "Este celular está aprobado: %1\$s.",
        PairingText.CONNECTED_DETAIL_UNKNOWN to "Este celular está aprobado.",
        PairingText.BLOCKED_TITLE to "Dispositivo bloqueado",
        PairingText.BLOCKED_DETAIL to "Un administrador bloqueó este celular. Cuando lo desbloquee, pulsa «Comprobar de nuevo».",
        PairingText.DENIED_TITLE to "Solicitud rechazada",
        PairingText.DENIED_DETAIL to "Un administrador rechazó esta solicitud.",
        PairingText.OFFLINE_TITLE to "Sin conexión con el servidor",
        PairingText.OFFLINE_DETAIL to "Reintentando en %1\$s s. Comprueba que el celular esté en la misma Wi-Fi que la PC.",
        PairingText.RATE_LIMITED_TITLE to "Demasiadas solicitudes pendientes",
        PairingText.RATE_LIMITED_DETAIL to "Reintentando en %1\$s s.",
        PairingText.CERTIFICATE_TITLE to "No se pudo verificar el servidor",
        PairingText.CERTIFICATE_DETAIL to "El certificado HTTPS no es de confianza para esta APK.",
        PairingText.CLOCK_TITLE to "La hora del celular no coincide",
        PairingText.CLOCK_DETAIL to "Activa «Fecha y hora automáticas» en Ajustes y pulsa «Reintentar».",
        PairingText.FAILED_TITLE to "No se pudo completar el emparejamiento",
        PairingText.FAILED_DETAIL to "Inténtalo de nuevo en unos segundos.",
        PairingText.UNCONFIGURED_DETAIL to "Esta APK no tiene configurada la dirección HTTPS del servidor.",
        PairingText.CALL_CHECKING to "Comprobando el estado del dispositivo…",
        PairingText.FORBIDDEN_CALL to "Esta función no está disponible para el celular.",
        PairingText.BUTTON_CHECK_AGAIN to "Comprobar de nuevo",
        PairingText.BUTTON_REQUEST_AGAIN to "Solicitar de nuevo",
        PairingText.BUTTON_RETRY to "Reintentar",
    )

    @Test fun everyKeyHasItsOwnPairingString() {
        val names = PairingText.entries.map { resourceName(PairingTexts.of(it)) }
        assertEquals("Dos claves comparten texto", names.size, names.toSet().size)
        for ((key, name) in PairingText.entries.zip(names)) {
            assertTrue("$key -> $name", name.startsWith("pairing_"))
            assertFalse("$key está vacío", textOf(key).isBlank())
        }
    }

    @Test fun everyKeyHasTheApprovedText() {
        assertEquals(PairingText.entries.toSet(), approved.keys)
        for (key in PairingText.entries) assertEquals(key.name, approved.getValue(key), textOf(key))
    }

    @Test fun noPairingStringIsLeftWithoutAKey() {
        val used = PairingText.entries.map { resourceName(PairingTexts.of(it)) }.toSet()
        assertEquals(used, strings.keys.filter { it.startsWith("pairing_") }.toSet())
    }

    @Test fun pairingStringsUseTheEllipsisCharacter() {
        for ((name, text) in strings.filterKeys { it.startsWith("pairing_") }) {
            assertFalse(name, text.contains("..."))
        }
    }
}
