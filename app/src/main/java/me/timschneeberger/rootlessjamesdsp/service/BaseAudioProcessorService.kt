package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.lang.ref.WeakReference

abstract class BaseAudioProcessorService : Service() {
    private val binder = LocalBinder(this)

    /**
     * Binder instances can outlive a destroyed Service in Android's native Binder registry.
     * Keep only a weak reference so a stale local Binder cannot retain the Service instance.
     */
    class LocalBinder(service: BaseAudioProcessorService) : Binder() {
        private val serviceReference = WeakReference(service)

        val service: BaseAudioProcessorService
            get() = checkNotNull(serviceReference.get()) {
                "Audio processor service is no longer available"
            }

        internal fun clearServiceReference() {
            serviceReference.clear()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        activeServices++
        super.onCreate()
    }

    override fun onDestroy() {
        binder.clearServiceReference()
        activeServices--
        super.onDestroy()
    }

    companion object {
        var activeServices: Int = 0
            private set
    }
}
