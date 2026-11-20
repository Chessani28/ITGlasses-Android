package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the voice reads: the answer without its sources, links or markdown. */
class SpokenTextTest {
    @Test fun inlineCitationsAreNotRead() {
        assertEquals("Paso 1: reinicia el equipo.",
            SpokenText.of("Paso 1: reinicia el equipo. [Fuente: Training Setup Guideline, página 3, versión 2]"))
        assertEquals("Archivo guia.pdf, página 4.",
            SpokenText.of("Archivo guia.pdf, página 4. [Fuente: Guía, página 4, cita c-17]"))
    }

    @Test fun sourceBlocksAndOfficialSourcesAreNotRead() {
        for (header in listOf("Fuentes consultadas:", "Fuentes públicas consultadas:", "Fuentes públicas consultadas (extractos):")) {
            assertEquals(header, "Usa un cable cruzado.",
                SpokenText.of("Usa un cable cruzado.\n\n$header\n- [Guía](https://example.com/g) (consultada: hoy)"))
        }
        assertEquals("Activa el modo avión.",
            SpokenText.of("Activa el modo avión.\n\nFuente oficial: [Ayuda](https://example.com/a) (consultada: hoy)"))
    }

    @Test fun listsGetAPauseAndMarkdownIsDropped() {
        assertEquals("Pasos: 1. Abre el panel. 2. Pulsa Guardar.",
            SpokenText.of("Pasos:\n1. Abre el panel\n2. Pulsa Guardar"))
        assertEquals("Importante: apaga el equipo. Consulta la Guía.",
            SpokenText.of("- **Importante**: apaga el equipo\n* Consulta la [Guía](https://example.com/g)."))
        assertEquals("Visita el sitio.", SpokenText.of("Visita el sitio https://example.com/x"))
    }

    @Test fun plainAnswersAreReadAsTheyAreAndNothingIsNeverSilent() {
        assertEquals("Hola, ¿en qué te ayudo?", SpokenText.of("Hola, ¿en qué te ayudo?"))
        assertEquals("[Fuente: Guía]", SpokenText.of("[Fuente: Guía]"))
    }
}
