package dev.finalish.core.transfer

import java.io.IOException

/** Worth retrying later: the network dropped, the server failed (5xx) or asked us to slow down (429). */
internal fun Throwable.isTransient(): Boolean =
    this is IOException || (this is retrofit2.HttpException && (code() >= 500 || code() == 429))
