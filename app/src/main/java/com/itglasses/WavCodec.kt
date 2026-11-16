package com.itglasses.itglassesandroid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV (RIFF) PCM encoding and validation.
 *
 * Produces and validates canonical mono 16-bit PCM WAV at 16 kHz, the format
 * expected by the backend Vosk transcription endpoint. No Android framework
 * types are used so this class can be unit-tested on the JVM without a device.
 */
object WavCodec {

    private const val FORMAT_PCM = 1
    private const val HEADER_SIZE = 44

    /**
     * Encodes mono/stereo 16-bit PCM samples into a complete WAV file in memory.
     */
    fun encodePcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        require(pcm.size % 2 == 0) { "PCM byte length must be even (16-bit samples)" }
        require(channels >= 1) { "Channels must be at least 1" }
        require(bitsPerSample == 16) { "Only 16-bit PCM is supported" }
        require(sampleRate > 0) { "Sample rate must be positive" }

        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        writeInt(out, 36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeInt(out, 16) // Subchunk1Size
        writeShort(out, FORMAT_PCM) // AudioFormat = PCM
        writeShort(out, channels)
        writeInt(out, sampleRate)
        writeInt(out, byteRate)
        writeShort(out, blockAlign)
        writeShort(out, bitsPerSample)
        out.write("data".toByteArray())
        writeInt(out, pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** Returns the sample rate declared in the WAV header, or -1 if unreadable. */
    fun sampleRate(wav: ByteArray): Int = readInt32(wav, 24)

    /** Returns the channel count declared in the WAV header, or -1 if unreadable. */
    fun channels(wav: ByteArray): Int = readInt16(wav, 22)

    /** Returns the bits-per-sample declared in the WAV header, or -1 if unreadable. */
    fun bitsPerSample(wav: ByteArray): Int = readInt16(wav, 34)

    /** Returns the declared PCM data size (the "data" chunk size), or -1 if unreadable. */
    fun pcmDataSize(wav: ByteArray): Int = readInt32(wav, 40)

    /**
     * True when the WAV header is complete, uncompressed PCM, mono, 16-bit, 16 kHz,
     * and the declared data size matches the available bytes.
     */
    fun isValid16kMono16Bit(wav: ByteArray): Boolean {
        if (wav.size < HEADER_SIZE) return false
        if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() ||
                wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()) return false
        if (wav[8] != 'W'.code.toByte() || wav[9] != 'A'.code.toByte() ||
                wav[10] != 'V'.code.toByte() || wav[11] != 'E'.code.toByte()) return false
        if (wav[12] != 'f'.code.toByte() || wav[13] != 'm'.code.toByte() ||
                wav[14] != 't'.code.toByte() || wav[15] != ' '.code.toByte()) return false
        if (readInt32(wav, 16) != 16) return false // Subchunk1Size
        if (readInt16(wav, 20) != FORMAT_PCM) return false // AudioFormat
        if (channels(wav) != 1) return false
        if (bitsPerSample(wav) != 16) return false
        if (sampleRate(wav) != 16000) return false
        val dataSize = pcmDataSize(wav)
        return dataSize >= 0 && wav.size >= HEADER_SIZE + dataSize
    }

    private fun readInt16(wav: ByteArray, offset: Int): Int {
        if (wav.size < offset + 2) return -1
        return ByteBuffer.wrap(wav, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
    }

    private fun readInt32(wav: ByteArray, offset: Int): Int {
        if (wav.size < offset + 4) return -1
        return ByteBuffer.wrap(wav, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
