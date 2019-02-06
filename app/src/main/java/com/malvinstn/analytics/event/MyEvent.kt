package com.malvinstn.analytics.event

import com.malvinstn.annotation.AnalyticsEvent

@AnalyticsEvent
sealed class MyEvent {
    data class ShareImage(val imageName: String, val fullString: String) : MyEvent()
    object ButtonTapped : MyEvent()
}