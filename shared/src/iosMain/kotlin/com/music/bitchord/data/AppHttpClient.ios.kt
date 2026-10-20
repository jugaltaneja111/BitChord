package com.music.bitchord.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration

actual fun appHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        engine {
            // The session's callbacks run on a queue of its own. Left to the engine they arrive on
            // the queue that created the client, which is often the main one: a request then waits
            // on the main thread, and hangs outright whenever that thread is blocked.
            val delegate = KtorNSURLSessionDelegate()
            val session = NSURLSession.sessionWithConfiguration(
                NSURLSessionConfiguration.defaultSessionConfiguration,
                delegate,
                delegateQueue = NSOperationQueue(),
            )
            usePreconfiguredSession(session, delegate)
        }
        configure()
    }
