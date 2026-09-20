package me.timschneeberger.rootlessjamesdsp.utils.notifications

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Run first on a fresh emulator install so another notification cannot mask missing setup. */
class ServiceNotificationHelperTest {
    @Test fun legacyNotificationCreatesItsChannelBeforeFirstUse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull("This regression requires a fresh install with no service-status channel",
            manager.getNotificationChannel(Notifications.CHANNEL_SERVICE_STATUS))

        val notification = ServiceNotificationHelper.createServiceNotificationLegacy(context)

        assertEquals(Notifications.CHANNEL_SERVICE_STATUS, notification.channelId)
        assertNotNull("Legacy/root service needs its channel before startForeground",
            manager.getNotificationChannel(notification.channelId))
        assertEquals(notification.channelId,
            ServiceNotificationHelper.createServiceNotification(context, emptyArray()).channelId)
    }
}
