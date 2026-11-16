package com.itglasses.itglassesandroid

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for WAV generation/validation. Runs without a phone or ADB.
 */
class WavCodecTest {

    private fun pcm(samples: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(samples * 2) { out.write(0) }
        return out.toByteArray()
    }

    @Test
    fun `valid 16 kHz mono 16-bit WAV header is accepted`() {
        val pcm = pcm(1600) // 0.1 s
        val wav = WavCodec.encodePcmToWav(pcm, 16000, 1, 16)

        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16)))
        assertEquals("data", String(wav.copyOfRange(36, 40)))
        assertEquals(16000, WavCodec.sampleRate(wav))
        assertEquals(1, WavCodec.channels(wav))
        assertEquals(16, WavCodec.bitsPerSample(wav))
        assertEquals(pcm.size, WavCodec.pcmDataSize(wav))
        // Full file size = 44-byte header + PCM data; the RIFF size field stores fileSize - 8.
        assertEquals(44 + pcm.size, wav.size)
        assertTrue(WavCodec.isValid16kMono16Bit(wav))
        assertTrue(WavCodec.isValid16kMono16Bit(wav))
    }

    @Test
    fun `unsupported sample rate is rejected`() {
        val wav = WavCodec.encodePcmToWav(pcm(1600), 8000, 1, 16)
        assertEquals(8000, WavCodec.sampleRate(wav))
        assertFalse(WavCodec.isValid16kMono16Bit(wav))
    }

    @Test
    fun `stereo WAV is rejected for the 16 kHz mono pipeline`() {
        val wav = WavCodec.encodePcmToWav(pcm(1600), 16000, 2, 16)
        assertEquals(2, WavCodec.channels(wav))
        assertFalse(WavCodec.isValid16kMono16Bit(wav))
    }

    @Test
    fun `non-16-bit PCM is rejected at encoding time`() {
        var thrown = false
        try {
            WavCodec.encodePcmToWav(pcm(1600), 16000, 1, 8)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `empty PCM still yields a structurally valid (but empty) WAV header`() {
        val wav = WavCodec.encodePcmToWav(byteArrayOf(), 16000, 1, 16)
        assertEquals(44, wav.size)
        assertEquals(0, WavCodec.pcmDataSize(wav))
        assertTrue(WavCodec.isValid16kMono16Bit(wav))
    }

    @Test
    fun `truncated malformed WAV is rejected`() {
        val valid = WavCodec.encodePcmToWav(pcm(1600), 16000, 1, 16)
        assertFalse(WavCodec.isValid16kMono16Bit(valid.copyOfRange(0, 30)))
        assertFalse(WavCodec.isValid16kMono16Bit(byteArrayOf()))
    }

    @Test
    fun `wrong RIFF and WAVE magic is rejected`() {
        val valid = WavCodec.encodePcmToWav(pcm(1600), 16000, 1, 16)
        val corrupted = valid.copyOfRange(0, valid.size)
        corrupted[0] = 'X'.code.toByte()
        assertFalse(WavCodec.isValid16kMono16Bit(corrupted))
    }

    @Test
    fun `declared data size larger than file is rejected`() {
        val valid = WavCodec.encodePcmToWav(pcm(100), 16000, 1, 16)
        val corrupted = valid.copyOfRange(0, valid.size)
        // Corrupt the data chunk size to a value larger than the file.
        val tooLarge = corrupted.size + 1000
        corrupted[40] = (tooLarge and 0xFF).toByte()
        corrupted[41] = ((tooLarge shr 8) and 0xFF).toByte()
        corrupted[42] = ((tooLarge shr 16) and 0xFF).toByte()
        corrupted[43] = ((tooLarge shr 24) and 0xFF).toByte()
        assertFalse(WavCodec.isValid16kMono16Bit(corrupted))
    }
}
