package com.mikaso.app.data

/**
 * An error that already carries a message worth showing the user.
 *
 * [message] is overridden as non-null so callers can pass it straight into UI state
 * without a "!!" or a null check.
 */
class ApiException(
    override val message: String,
    val code: String = "error",
) : Exception(message)
