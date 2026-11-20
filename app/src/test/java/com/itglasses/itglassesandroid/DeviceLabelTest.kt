package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceLabelTest {
    private fun codePoints(value: String) = value.codePointCount(0, value.length)

    @Test fun modelJoinsCapitalisedManufacturer() {
        assertEquals("Google Pixel 7", DeviceLabel.model("Google", "Pixel 7"))
        assertEquals("Samsung SM-S911B", DeviceLabel.model("samsung", "SM-S911B"))
    }

    @Test fun modelDoesNotRepeatTheManufacturer() {
        assertEquals("OnePlus 9", DeviceLabel.model("OnePlus", "OnePlus 9"))
        assertEquals("xiaomi 13", DeviceLabel.model("Xiaomi", "xiaomi 13"))
        assertEquals("motorola", DeviceLabel.model("motorola", "motorola"))
    }

    @Test fun modelWithMissingParts() {
        assertEquals("Pixel 7", DeviceLabel.model(null, "Pixel 7"))
        assertEquals("Google", DeviceLabel.model("Google", "  "))
        assertNull(DeviceLabel.model(null, null))
        assertNull(DeviceLabel.model("", "​"))
    }

    @Test fun cleanRemovesControlFormatAndSeparatorCharacters() {
        assertEquals("Pixel 7", DeviceLabel.clean("Pixel‮ 7"))
        assertEquals("ABC", DeviceLabel.clean("A B C"))
        assertEquals("TabName", DeviceLabel.clean("Tab\tName\n"))
        assertEquals("Zero", DeviceLabel.clean("​Zero"))
        assertEquals("ab", DeviceLabel.clean("a\uD83Db"))
    }

    @Test fun cleanTrimsAndNormalisesToNfc() {
        assertEquals("Café", DeviceLabel.clean("  Café  "))
    }

    @Test fun cleanTruncatesTo64CodePointsWithoutSplittingCharacters() {
        assertEquals("b".repeat(64), DeviceLabel.clean("b".repeat(70)))
        val emoji = DeviceLabel.clean("a".repeat(63) + "😀😀")!!
        assertEquals("a".repeat(63) + "😀", emoji)
        assertEquals(64, codePoints(emoji))
        assertEquals("a".repeat(63), DeviceLabel.clean("a".repeat(63) + " b"))
    }

    @Test fun cleanReturnsNullWhenNothingIsLeft() {
        assertNull(DeviceLabel.clean(null))
        assertNull(DeviceLabel.clean("‮​  "))
    }

    @Test fun nameIsTheCleanedDeviceName() {
        assertEquals("Galaxy de Enrique", DeviceLabel.name("  Galaxy de Enrique "))
        assertNull(DeviceLabel.name(null))
    }

    @Test fun codeIsGroupedLikeTheWebPanel() {
        assertEquals("482 913", DeviceLabel.formatCode("482913"))
        assertEquals("12345", DeviceLabel.formatCode("12345"))
        assertEquals("48291a", DeviceLabel.formatCode("48291a"))
        assertEquals("٤٨٢٩١٣", DeviceLabel.formatCode("٤٨٢٩١٣"))
        assertEquals("4 8 2 9 1 3", DeviceLabel.spokenCode("482913"))
    }

    @Test fun countdownIsMinutesAndSecondsAndNeverNegative() {
        assertEquals("4:59", DeviceLabel.countdown(299))
        assertEquals("0:05", DeviceLabel.countdown(5))
        assertEquals("10:00", DeviceLabel.countdown(600))
        assertEquals("0:00", DeviceLabel.countdown(0))
        assertEquals("0:00", DeviceLabel.countdown(-5))
    }
}
