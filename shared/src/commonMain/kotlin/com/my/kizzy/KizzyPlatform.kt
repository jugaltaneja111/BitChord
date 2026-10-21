package com.my.kizzy

/**
 * Milliseconds since boot, counting time the device spent asleep: what the gateway compares to
 * notice that a lot of time has passed with nothing heard from Discord.
 */
internal expect fun elapsedRealtimeMs(): Long

/** The gateway's log: java.util.logging on the JVM, as it always was; the console on iOS. */
internal expect class KizzyLogger(name: String) {
    fun info(message: String)
    fun warning(message: String)
}
