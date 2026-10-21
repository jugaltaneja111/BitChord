package com.music.bitchord.ios.party

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Keeps the app running in the background while it is in a party but not playing.
 *
 * The phone holds its party socket in a foreground service; iOS has nothing like it, and suspends
 * an app within seconds of it leaving the screen unless it is playing audio. A suspended host's
 * socket goes quiet, the server lets the slot go after its grace period, and a party with nobody
 * left in it is deleted — so a host who paused and locked the phone came back to a party that no
 * longer existed, and friends joining by code were told there was no such party. A silent loop
 * through the same playback session is what keeps the app (and so the socket) alive meanwhile; it
 * stops the moment real music plays, which keeps the app alive on its own.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPartyKeepAlive {
    private var silence: AVAudioPlayer? = null

    fun watch(scope: CoroutineScope, isPlaying: StateFlow<Boolean>) {
        scope.launch {
            combine(IosListenTogether.state, isPlaying) { party, playing -> party.inParty && !playing }
                .distinctUntilChanged()
                .collect { needed -> if (needed) start() else stop() }
        }
    }

    private fun start() {
        val player = silence ?: AVAudioPlayer(data = silentWav(), error = null).also {
            it.numberOfLoops = -1
            silence = it
        }
        AVAudioSession.sharedInstance().setActive(true, error = null)
        player.play()
    }

    private fun stop() {
        silence?.stop()
    }

    /** One second of 8 kHz mono 16-bit silence, as a WAV the player loops. */
    private fun silentWav(): NSData {
        val rate = 8_000
        val dataBytes = rate * 2
        val bytes = ByteArray(44 + dataBytes)
        fun put(at: Int, text: String) = text.forEachIndexed { i, c -> bytes[at + i] = c.code.toByte() }
        fun int32(at: Int, value: Int) = repeat(4) { bytes[at + it] = (value shr (8 * it)).toByte() }
        fun int16(at: Int, value: Int) = repeat(2) { bytes[at + it] = (value shr (8 * it)).toByte() }
        put(0, "RIFF"); int32(4, 36 + dataBytes); put(8, "WAVE")
        put(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
        int32(24, rate); int32(28, rate * 2); int16(32, 2); int16(34, 16)
        put(36, "data"); int32(40, dataBytes)
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
}
