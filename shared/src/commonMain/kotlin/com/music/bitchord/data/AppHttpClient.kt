package com.music.bitchord.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * A Ktor client on this platform's shared HTTP stack.
 *
 * On the JVM that is [Http.client], the one OkHttp instance the player also streams through: a
 * googlevideo URL is bound to the connection context of the `player` request that minted it, so
 * the request and the media fetch have to share DNS, address family and pooling (see [Http]).
 * On iOS it is the Darwin engine (URLSession).
 */
expect fun appHttpClient(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient
