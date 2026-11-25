package com.trevornk.ramblr

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PauseNearApps]: which app coming to the front turns the accessibility service off, and when
 * the watcher decides the user has left it.
 */
class PauseNearAppsTest {

    private val bank = "com.example.bank"
    private val broker = "com.example.broker"
    private val listed = setOf(bank, broker)

    // --- when to pause -------------------------------------------------------------------------

    @Test fun `a listed app in front pauses, any other does not`() {
        assertTrue(PauseNearApps.shouldPause(bank, listed, canReEnable = true))
        assertFalse(PauseNearApps.shouldPause("com.example.chat", listed, canReEnable = true))
        assertFalse(PauseNearApps.shouldPause(null, listed, canReEnable = true))
        assertFalse(PauseNearApps.shouldPause("", listed, canReEnable = true))
        assertFalse(PauseNearApps.shouldPause(bank, emptySet(), canReEnable = true))
    }

    @Test fun `without the means to turn itself back on, ramblr never pauses`() {
        assertFalse(PauseNearApps.shouldPause(bank, listed, canReEnable = false))
    }

    // --- when the user has left ----------------------------------------------------------------

    @Test fun `another app in front for the settle time means the user left`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500)
        assertFalse(detector.hasLeft(bank, 0))
        assertFalse(detector.hasLeft("com.example.launcher", 1000))
        assertFalse(detector.hasLeft("com.example.launcher", 2000))
        assertTrue(detector.hasLeft("com.example.launcher", 2500))
    }

    @Test fun `coming back to the listed app before the settle time keeps the pause`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500)
        assertFalse(detector.hasLeft("com.example.launcher", 0))
        assertFalse(detector.hasLeft(bank, 1000))
        // The clock starts again from the next time the user is away.
        assertFalse(detector.hasLeft("com.example.launcher", 2000))
        assertFalse(detector.hasLeft("com.example.launcher", 3000))
        assertTrue(detector.hasLeft("com.example.launcher", 3500))
    }

    @Test fun `going from one listed app to another keeps the pause`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500)
        assertFalse(detector.hasLeft(bank, 0))
        assertFalse(detector.hasLeft(broker, 5000))
        assertFalse(detector.hasLeft(broker, 50_000))
    }

    @Test fun `a prompt over the listed app is not leaving it`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500)
        for (overlay in PauseLeaveDetector.TRANSIENT_PACKAGES) {
            assertFalse(overlay, detector.hasLeft(overlay, 0))
            assertFalse(overlay, detector.hasLeft(overlay, 60_000))
        }
    }

    @Test fun `an unknown foreground changes nothing`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500)
        assertFalse(detector.hasLeft(null, 0))
        assertFalse(detector.hasLeft(null, 60_000))
        // Nor does it interrupt a wait already under way.
        assertFalse(detector.hasLeft("com.example.launcher", 60_000))
        assertFalse(detector.hasLeft(null, 61_000))
        assertTrue(detector.hasLeft("com.example.launcher", 61_500))
    }

    // --- a pause made on the tap that launches the app ------------------------------------------

    @Test fun `an anticipated pause holds until the app shows up, then follows the usual rule`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500, confirmByMs = 8000)
        // Still the launcher in front, or nothing known yet: the app is starting.
        assertFalse(detector.hasLeft(null, 500))
        assertFalse(detector.hasLeft("com.example.launcher", 3000))
        assertFalse(detector.hasLeft(bank, 4000))
        // From here it is an ordinary pause.
        assertFalse(detector.hasLeft(bank, 60_000))
        assertFalse(detector.hasLeft("com.example.launcher", 61_000))
        assertTrue(detector.hasLeft("com.example.launcher", 62_500))
    }

    @Test fun `an anticipated launch that never happens ends the pause at the deadline`() {
        val detector = PauseLeaveDetector(listed, settleMs = 1500, confirmByMs = 8000)
        assertFalse(detector.hasLeft("com.example.launcher", 7999))
        assertTrue(detector.hasLeft("com.example.launcher", 8000))
        val unknown = PauseLeaveDetector(listed, settleMs = 1500, confirmByMs = 8000)
        assertFalse(unknown.hasLeft(null, 7999))
        assertTrue(unknown.hasLeft(null, 8000))
    }

    @Test fun `the tapped icon or recent-apps card is matched to a listed app by the name shown`() {
        val labels = LaunchLabels.of(listed) { pkg ->
            if (pkg == bank) listOf("Example Bank", "Bank") else listOf("Broker Pro")
        }
        assertEquals(bank, labels.packageFor(listOf("Example Bank", "Example Bank")))
        assertEquals(bank, labels.packageFor(listOf(null, "bank")))
        // A home-screen description with a badge count.
        assertEquals(bank, labels.packageFor(listOf("Example Bank, 2 notifications")))
        assertEquals(broker, labels.packageFor(listOf("", " broker pro ")))
        assertEquals(null, labels.packageFor(listOf("Chat", "Chat, 8 notifications")))
        assertEquals(null, labels.packageFor(listOf(null, "")))
        assertEquals(null, labels.packageFor(emptyList()))
    }

    // --- the list ------------------------------------------------------------------------------

    @Test fun `the list is empty by default and round-trips, trimmed`() {
        val prefs = FakeStringPrefs()
        assertTrue(PauseNearAppsStore.packages(prefs).isEmpty())
        PauseNearAppsStore.setPackages(prefs, setOf(" $bank ", broker, "", "  "))
        assertEquals(listed, PauseNearAppsStore.packages(prefs))
        PauseNearAppsStore.setPackages(prefs, emptySet())
        assertTrue(PauseNearAppsStore.packages(prefs).isEmpty())
    }

    // --- what the service listens to -----------------------------------------------------------

    @Test fun `the extra events are added to the subscription whatever the ring mode, and dropped again`() {
        var info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED
            notificationTimeout = 100
        }
        var extra = PauseNearApps.WINDOW_EVENTS
        val subscription = RingEventSubscription(getInfo = { info }, setInfo = { info = it }, extraEventTypes = { extra })
        assertTrue(subscription.apply(RingVisibilityMode.ALWAYS))
        assertEquals(AccessibilityEvent.TYPE_VIEW_FOCUSED or PauseNearApps.WINDOW_EVENTS, info.eventTypes)
        // Delivered at once while the feature listens, with the configured delay again after.
        assertEquals(0L, info.notificationTimeout)
        extra = 0
        assertTrue(subscription.apply(RingVisibilityMode.ALWAYS))
        assertEquals(AccessibilityEvent.TYPE_VIEW_FOCUSED, info.eventTypes)
        assertEquals(100L, info.notificationTimeout)
        assertFalse(subscription.apply(RingVisibilityMode.ALWAYS))
    }

    // --- the screen's status line --------------------------------------------------------------

    @Test fun `the status says what is missing and how to grant it`() {
        val pkg = "com.trevornk.ramblr"
        val noWrite = PauseNearAppsActivity.statusText(canWrite = false, usageAccess = true, packageName = pkg)
        assertTrue(noWrite.startsWith("Not active yet"))
        assertTrue(noWrite.contains("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"))
        val noUsage = PauseNearAppsActivity.statusText(canWrite = true, usageAccess = false, packageName = pkg)
        assertTrue(noUsage.contains("appops set $pkg GET_USAGE_STATS allow"))
        assertTrue(PauseNearAppsActivity.statusText(canWrite = true, usageAccess = true, packageName = pkg).startsWith("Active:"))
    }
}
