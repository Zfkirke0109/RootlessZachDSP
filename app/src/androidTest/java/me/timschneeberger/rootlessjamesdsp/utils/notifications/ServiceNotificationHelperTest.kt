package me.timschneeberger.rootlessjamesdsp.utils.notifications

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression for the legacy/root service notification path: it must declare its channel before
 * the first notification is built, because Application.onCreate no longer does so.
 *
 * The test recreates the "first use" state itself instead of relying on a fresh install. On the
 * CI emulator the system delivers BOOT_COMPLETED to the freshly started app process, and
 * BootCompletedReceiver then posts the permission prompt, which already declares every channel
 * before any test method runs.
 */
class ServiceNotificationHelperTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun removeServiceStatusChannel() {
        manager.deleteNotificationChannel(Notifications.CHANNEL_SERVICE_STATUS)
        Notifications.resetForTesting()
        assertNull("The service-status channel must be absent before the legacy builder runs",
            manager.getNotificationChannel(Notifications.CHANNEL_SERVICE_STATUS))
    }

    @Test fun legacyNotificationCreatesItsChannelBeforeFirstUse() {
        val notification = ServiceNotificationHelper.createServiceNotificationLegacy(context)

        assertEquals(Notifications.CHANNEL_SERVICE_STATUS, notification.channelId)
        assertNotNull("Legacy/root service needs its channel before startForeground",
            manager.getNotificationChannel(notification.channelId))
    }

    @Test fun rootlessNotificationCreatesItsChannelBeforeFirstUse() {
        val notification = ServiceNotificationHelper.createServiceNotification(context, emptyArray())

        assertEquals(Notifications.CHANNEL_SERVICE_STATUS, notification.channelId)
        assertNotNull("Rootless service needs its channel before startForeground",
            manager.getNotificationChannel(notification.channelId))
    }
}
