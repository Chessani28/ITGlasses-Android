package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.junit.Test

/** Avatar beside every assistant answer (and "Generando respuesta"), drawn only with palette colours. */
class AssistantAvatarTest {
    @Test fun avatarResourcesUseThePalette() {
        val background = ModuleFiles.file("src/main/res/drawable/avatar_assistant.xml").readText()
        assertTrue(background.contains("@color/itg_surface_raised"))
        assertTrue(background.contains("@color/itg_outline"))
        val glyph = ModuleFiles.file("src/main/res/drawable/ic_assistant.xml").readText()
        assertTrue(glyph.contains("@color/itg_accent"))
    }

    @Test fun bubblesWithAvatarGiveUpItsSpace() {
        val code = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText()
        assertTrue(code.contains("R.drawable.ic_assistant"))
        assertTrue(code.contains("IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(code.contains("const val AVATAR_DP = 28"))
        assertTrue(code.contains("const val AVATAR_GAP_DP = 8"))
        assertTrue(code.contains("reserved = reserved"))
        // Assistant answers and "Generando respuesta" carry the avatar; "Comprobando" does not.
        assertTrue(code.contains("Row(column(Gravity.START, withAvatar(bubble))"))
        assertTrue(code.contains("avatar = true)"))
        assertTrue(code.contains("full, avatar = false)"))
    }
}
