package keiyoushi.utils

import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the foreground activity through the public
 * [Application.registerActivityLifecycleCallbacks] API.
 *
 * [keiyoushi.utils.runWebView] needs a real Activity to attach its WebView to
 * a window. A never-attached WebView reports `visibilityState = "hidden"`,
 * and every visibility-sensitive challenge (Cloudflare Turnstile, the
 * managed-challenge widget, MKissa's captcha) then stalls forever. The
 * previous lookup used ActivityThread reflection, which hidden-API
 * enforcement blocks on modern Android, so on current devices the solver
 * never found an activity, the WebView stayed unattached, and every
 * automatic solve failed into a manual "open in WebView" prompt. Lifecycle
 * callbacks are public API and cannot be blocked.
 *
 * Registration is lazy and idempotent: the first `applicationContext` access
 * registers the callbacks, and the tracker then follows the app for the rest
 * of the process. An activity resumed before registration is covered by the
 * reflection fallback in WebView.kt.
 */
internal object ForegroundActivityTracker {

    private val registered = AtomicBoolean(false)

    /** Main-thread-written, AtomicReference-read: good enough for an attach hint. */
    private val current = AtomicReference<Activity?>(null)

    /**
     * True once any activity has resumed in this process. Distinguishes "the
     * app is in the background" (an activity will appear again, wait for it)
     * from "there has never been one" (don't bother waiting).
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
        try {
            ActivityLifecycleCallbackProxy.install(app)
        } catch (e: LinkageError) {
            // The host has no ActivityLifecycleCallbacks, nothing to track.
        }
    }

    /** Called when an activity comes to the foreground. */
    internal fun onResumed(activity: Activity) {
        current.set(activity)
        everResumed = true
    }

    /** Called when the tracked activity is stopped or destroyed. */
    internal fun onStopped(activity: Activity) {
        current.compareAndSet(activity, null)
    }
}
