package com.itglasses.itglassesandroid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Half a second of silence as media, so Android hands the media buttons to this app's session (it gives them to the
 * app that played media most recently). Used by VoiceForegroundService when it claims the glasses' tap.
 * (MediaButtonProbe keeps its own copy: the diagnostic stays as it was tested.)
 */
internal object MediaClaim {
    fun playSilence(): Boolean = try {
        val rate = 16_000
        val silence = ShortArray(rate / 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(silence.size * 2)
            .build()
        track.write(silence, 0, silence.size)
        track.setNotificationMarkerPosition(silence.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) { t.release() }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
        true
    } catch (e: Exception) {
        GlassesLog.d("claim silence result=error type=${GlassesLog.safe(e.javaClass.simpleName)}")
        false
    }
}
