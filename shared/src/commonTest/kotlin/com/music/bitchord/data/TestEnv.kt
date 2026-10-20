package com.music.bitchord.data

/** An environment variable of the test process. On the iOS simulator, set it as `SIMCTL_CHILD_<name>`. */
expect fun testEnv(name: String): String?
