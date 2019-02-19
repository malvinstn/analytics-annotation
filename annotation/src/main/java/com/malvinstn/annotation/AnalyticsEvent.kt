package com.malvinstn.annotation

// Set Target as class since we want this annotation to be added to a class element
@Target(AnnotationTarget.CLASS)
// Set retention as Source since we only need this annotation
// during annotation processing process and
// we don't need this class at runtime.
@Retention(AnnotationRetention.SOURCE)
annotation class AnalyticsEvent
