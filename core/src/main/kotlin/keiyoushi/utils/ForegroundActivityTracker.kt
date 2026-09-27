package keiyoushi.utils

import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the foreground activity through the PUBLIC
 * [Application.registerActivityLifecycleCallbacks] API.
 *
 * Why this exists: [keiyoushi.utils.runWebView] needs a real Activity to
 * attach its WebView to a window (a never-attached WebView reports
 * `visibilityState = "hidden"` and every visibility-sensitive challenge —
 * Cloudflare Turnstile, the managed-challenge widget, MKissa's captcha —
 * silently stalls forever). The previous lookup used ActivityThread
 * reflection, which hidden-API enforcement blocks on modern Android
 * (targetSdk 31+ throws NoSuchFieldException for `mActivities`) — so on
 * current devices the solver NEVER found an activity, the WebView stayed
 * unattached, and every automatic solve failed into a manual
 * "open in WebView" prompt. Lifecycle callbacks are public API and cannot
 * be blocked.
 *
 * Registration is idempotent and lazy: the first `applicationContext`
 * access (or the first [keiyoushi.utils.runWebView]) registers the
 * callbacks; the tracker then follows the app for the rest of the process.
 * The one blind spot is an activity resumed BEFORE registration — covered
 * by the reflection fallback in [keiyoushi.utils.findForegroundActivity].
 */
internal object ForegroundActivityTracker {

    private val registered = AtomicBoolean(false)

    /** Main-thread-written, AtomicReference-read: good enough for an attach hint. */
    private val current = AtomicReference<Activity?>(null)

    /**
     * True once ANY activity has resumed in this process. Distinguishes
     * "app is in the background" (an activity will appear again — wait for
     * it) from "there has never been one" (don't bother waiting).
     */
    @Volatile
    var everResumed: Boolean = false
        private set

    /** The tracked foreground activity, or null when none is usable. */
    fun peek(): Activity? = current.get()?.takeIf { activity ->
        !activity.isFinishing && !activity.isDestroyed && activity.window != null
    }

    /** Idempotently registers the lifecycle callbacks on [app]. */
    fun register(app: Application) {
        if (!registered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current.set(activity)
                everResumed = true
            }

            // Pause alone does NOT clear the tracker: a translucent activity
            // or a dialog over us still leaves the window visible — the
            // WebView renders and solves fine behind it. Only a STOP
            // (fully covered / backgrounded) ends usability.
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                if (current.get() === activity) current.set(null)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (current.compareAndSet(activity, null)) return
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        })
    }
}
