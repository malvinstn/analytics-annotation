package com.malvinstn.tracker

import android.os.Bundle
import android.util.Log

object EventTracker {
    private const val TAG = "EventTracker"
    fun logEvent(name: String, params: Bundle) {
        Log.d(TAG, "Logging event name: $name; params: $params")
    }
}