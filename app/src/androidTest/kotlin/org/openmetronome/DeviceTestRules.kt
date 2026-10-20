package org.openmetronome

import android.app.KeyguardManager
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresAudio

private fun Description.requiresAudio() = getAnnotation(RequiresAudio::class.java) != null ||
    testClass?.isAnnotationPresent(RequiresAudio::class.java) == true

/** Outer rule: skips hardware tests before Activity fixtures can run. */
class AudioPermissionRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val needsAudio = description.requiresAudio()
            assumeTrue("Audio not authorized: requires explicit consent and allowAudio=true",
                !needsAudio || InstrumentationRegistry.getArguments().getString("allowAudio") == "true")
            base.evaluate()
        }
    }
}

/** Do not turn a sleeping/locked phone into a misleading Compose assertion failure. */
class UnlockedScreenRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assumeTrue("ENVIRONMENT: screen must be awake and unlocked before UI setup",
                context.getSystemService(PowerManager::class.java).isInteractive &&
                    !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            // Do not interfere with real-audio tests asserting screen flags or intentionally
            // turning the display off. Never wake/unlock here, or alter system timeout settings.
            if (description.requiresAudio()) {
                base.evaluate()
                return
            }
            val application = context.applicationContext as Application
            val screen = QuietScreenSession()
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(screen) }
            try { base.evaluate() } finally {
                instrumentation.runOnMainSync {
                    application.unregisterActivityLifecycleCallbacks(screen)
                    screen.restore()
                }
            }
        }
    }
}

/** Test-only view flags, restored even on failure; no production Activity changes. */
private class QuietScreenSession : Application.ActivityLifecycleCallbacks {
    private val previous = java.util.IdentityHashMap<View, Boolean>()
    override fun onActivityResumed(activity: Activity) {
        val view = activity.window.decorView
        if (!previous.containsKey(view)) previous[view] = view.keepScreenOn
        view.keepScreenOn = true
    }
    fun restore() {
        previous.forEach { (view, original) -> view.keepScreenOn = original }
        previous.clear()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
