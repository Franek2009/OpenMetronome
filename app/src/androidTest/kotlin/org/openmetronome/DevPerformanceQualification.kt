package org.openmetronome

/** Explicit offline qualification of the inactive DEV recorder, outside release regression. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class DevPerformanceQualification
