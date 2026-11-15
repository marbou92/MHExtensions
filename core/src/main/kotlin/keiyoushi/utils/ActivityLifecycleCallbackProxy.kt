package keiyoushi.utils

import android.app.Activity
import android.app.Application

/**
 * Installs the lifecycle callbacks that feed [ForegroundActivityTracker].
 *
 * This sits in its own file on purpose. The callback type is
 * `Application.ActivityLifecycleCallbacks`, a nested Android interface that
 * desktop hosts stub out - Tsunagu's sandbox has an `android.app.Application`
 * without it. Referencing the interface from here means such a host fails
 * while loading THIS class, which [ForegroundActivityTracker.register]
 * catches, instead of failing while verifying the tracker itself and taking
 * the whole extension down with it.
 */
internal object ActivityLifecycleCallbackProxy {
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = ForegroundActivityTracker.onResumed(activity)

                // Pause alone does NOT clear the tracker: a translucent activity
                // or a dialog over us still leaves the window visible - the
                // WebView renders and solves fine behind it. Only a STOP
                // (fully covered / backgrounded) ends usability.
                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = ForegroundActivityTracker.onStopped(activity)

                override fun onActivityDestroyed(activity: Activity) = ForegroundActivityTracker.onStopped(activity)

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: android.os.Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: android.os.Bundle,
                ) = Unit
            },
        )
    }
}
