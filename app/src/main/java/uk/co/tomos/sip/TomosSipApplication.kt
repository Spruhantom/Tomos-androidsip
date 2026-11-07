package uk.co.tomos.sip

import android.app.Application

/**
 * Owns the single Linphone Core for the lifetime of the app process.
 *
 * Activities are UI only: rotating, navigating away, or recreating the activity
 * must not tear down SIP registration or an active call.
 */
class TomosSipApplication : Application() {
    lateinit var sipEngine: TomSipEngine
        private set

    override fun onCreate() {
        super.onCreate()
        sipEngine = TomSipEngine(applicationContext)
    }
}
