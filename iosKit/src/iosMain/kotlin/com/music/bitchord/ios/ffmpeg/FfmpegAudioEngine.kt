@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.bitchord.ios.ffmpeg

import com.music.bitchord.data.TrackLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock

/**
 * The one AVAudioEngine every [IosFfmpegPlayer] plays through, each on its own player node into
 * the main mixer. Started lazily, when a player first wants sound, and restarted after the route
 * or the hardware changes (AVAudioEngineConfigurationChangeNotification, which stops it), after an
 * interruption ends, and — rebuilt from scratch — after the media services reset. Each time the
 * players are told, and re-schedule from where they were, since a stopped engine drops what was
 * queued.
 *
 * Graph changes and starts happen under [lock], from the main thread and the players' decode
 * threads alike. Lock order: a player's transport lock, then this one; never the other way.
 */
@OptIn(ExperimentalForeignApi::class)
internal object FfmpegAudioEngine {
    private const val TAG = "IosFfmpeg"

    val lock = NSRecursiveLock()

    private var current: AVAudioEngine? = null

    /** Bumped whenever the engine is replaced, so a node attached to an old one is re-attached. */
    var generation = 0
        private set

    private val listeners = LinkedHashSet<() -> Unit>()
    private var observing = false

    private fun engine(): AVAudioEngine = current ?: AVAudioEngine().also { engine ->
        current = engine
        generation++
        // Touching the mixer builds the output chain the engine needs before it can start.
        engine.mainMixerNode
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioEngineConfigurationChangeNotification, engine, NSOperationQueue.mainQueue,
        ) { _ -> if (engine === current) recover("configuration change") }
        observeSession()
    }

    private fun observeSession() {
        if (observing) return
        observing = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeEnded) recover("interruption ended")
        }
        center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, NSOperationQueue.mainQueue) { _ ->
            lock.lock()
            try {
                // The old engine is dead; nodes attach to a new one when next scheduled.
                current = null
            } finally {
                lock.unlock()
            }
            recover("media services reset")
        }
    }

    /** Main thread: the engine stopped (or died) behind our back; players rebuild what they had queued. */
    private fun recover(why: String) {
        TrackLog.d(TAG, "engine: $why")
        val toTell = locked { listeners.toList() }
        toTell.forEach { it() }
    }

    /** [onReset] is called on the main thread whenever the engine lost what was scheduled. */
    fun register(onReset: () -> Unit) {
        locked { listeners += onReset }
    }

    fun unregister(onReset: () -> Unit) {
        locked { listeners -= onReset }
    }

    /** The players that want sound now; when none do, the engine pauses so the hardware can rest. */
    private val wanting = HashSet<Any>()

    /** [player] wants sound ([wants]) or doesn't any more. Any thread. */
    fun want(player: Any, wants: Boolean) = locked {
        if (wants) {
            wanting += player
        } else if (wanting.remove(player) && wanting.isEmpty()) {
            current?.takeIf { it.running }?.pause()
        }
    }

    /** Starts the engine if it isn't running; the error when it can't be. */
    fun ensureRunning(): String? = locked {
        val engine = engine()
        if (engine.running) return@locked null
        engine.prepare()
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (engine.startAndReturnError(error.ptr)) null
            else (error.value?.localizedDescription ?: "audio engine failed to start").also { TrackLog.w(TAG, "engine: $it") }
        }
    }

    /**
     * Attaches [node] (to the current engine) and connects it to the main mixer in [format],
     * replacing any earlier connection. The node must be stopped. [attachedTo] is the engine
     * [generation] the node was last attached in (or -1); the one it is attached in now comes back.
     *
     * Attachment is tracked by generation, never by comparing `node.engine` with the engine: the
     * Kotlin wrappers of one ObjC object aren't reliably `===`, and a check that always failed
     * re-attached the node for every buffer, which dropped each one unheard.
     */
    fun connect(node: AVAudioPlayerNode, format: AVAudioFormat, attachedTo: Int): Int = locked {
        val engine = engine()
        if (attachedTo != generation) {
            node.engine?.let { old -> runCatching { old.detachNode(node) } }
            engine.attachNode(node)
        } else {
            engine.disconnectNodeOutput(node)
        }
        engine.connect(node, to = engine.mainMixerNode, format = format)
        generation
    }

    /** Whether a node attached in engine generation [attachedTo] is attached to the engine in use now. */
    fun isAttached(attachedTo: Int): Boolean = locked { current != null && attachedTo == generation }

    fun detach(node: AVAudioPlayerNode) = locked {
        node.engine?.let { engine -> runCatching { engine.detachNode(node) } }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
