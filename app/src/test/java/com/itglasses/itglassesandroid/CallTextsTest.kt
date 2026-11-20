package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every CallText key has its own approved Spanish text in res/values/strings.xml. */
class CallTextsTest {
    private val strings: Map<String, String> by lazy {
        val path = "src/main/res/values/strings.xml"
        val file = listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: throw AssertionError("No se encontró $path desde ${File(".").absolutePath}")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun resourceName(id: Int): String = R.string::class.java.fields.single { it.getInt(null) == id }.name
    private fun textOf(key: CallText): String = strings[resourceName(CallTexts.of(key))]
        ?: throw AssertionError("$key no tiene texto en strings.xml")

    private val approved = mapOf(
        CallText.NO_NETWORK to "Sin conexión con el servidor. Comprueba que el celular esté en la misma Wi-Fi que la PC e inténtalo de nuevo.",
        CallText.NO_RESPONSE to "No se obtuvo respuesta del servidor. Comprueba la red o inténtalo en un momento.",
        CallText.TIMEOUT to "El asistente tardó demasiado en responder. Inténtalo de nuevo.",
        CallText.CERTIFICATE to "No se pudo verificar el servidor (certificado HTTPS).",
        CallText.CLOCK to "La hora del celular no coincide con la del servidor. Activa «Fecha y hora automáticas».",
        CallText.UNAUTHORIZED to "La autorización del celular se está renovando. Inténtalo de nuevo.",
        CallText.UNAUTHORIZED_REPEATED to "Si persiste, revisa la fecha y hora del celular o avisa al administrador.",
        CallText.REMOVED to "Este celular se eliminó del panel y se está registrando de nuevo. Revisa la tarjeta.",
        CallText.AUDIO_TOO_LONG to "El audio es demasiado largo (máximo 30 segundos).",
        CallText.REJECTED to "El servidor no aceptó el mensaje. Revisa que no supere los 4000 caracteres.",
        CallText.RATE_LIMITED to "Demasiadas peticiones seguidas. Espera unos segundos e inténtalo de nuevo.",
        CallText.SERVER_ERROR to "El servidor de ITGlasses tuvo un error. Inténtalo de nuevo.",
        CallText.UNEXPECTED_CODE to "Respuesta inesperada del servidor (código %1\$s).",
        CallText.FAILED to "No se pudo completar la petición. Inténtalo de nuevo.",
        CallText.GENERATING to "Generando respuesta…",
    )

    @Test fun everyKeyHasItsOwnCallString() {
        val names = CallText.entries.map { resourceName(CallTexts.of(it)) }
        assertEquals("Dos claves comparten texto", names.size, names.toSet().size)
        for ((key, name) in CallText.entries.zip(names)) {
            assertTrue("$key -> $name", name.startsWith("call_"))
            assertFalse("$key está vacío", textOf(key).isBlank())
        }
    }

    @Test fun everyKeyHasTheApprovedText() {
        assertEquals(CallText.entries.toSet(), approved.keys)
        for (key in CallText.entries) assertEquals(key.name, approved.getValue(key), textOf(key))
    }

    @Test fun noCallStringIsLeftWithoutAKey() {
        val used = CallText.entries.map { resourceName(CallTexts.of(it)) }.toSet()
        assertEquals(used, strings.keys.filter { it.startsWith("call_") }.toSet())
    }

    @Test fun onlyTheUnexpectedCodeTakesAnArgumentAndEllipsisIsTheCharacter() {
        for (key in CallText.entries) {
            assertEquals(key.name, key == CallText.UNEXPECTED_CODE, textOf(key).contains("%1\$s"))
            assertFalse(key.name, textOf(key).contains("..."))
        }
    }
}
