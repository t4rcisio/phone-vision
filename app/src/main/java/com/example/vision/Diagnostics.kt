package com.example.vision

/** Debug-only exception details. Production builds never emit app stack traces or user content. */
internal fun logFailure(message: String, cause: Throwable) {
    if (BuildConfig.DEBUG) android.util.Log.e("PhoneVision", message, cause)
}
