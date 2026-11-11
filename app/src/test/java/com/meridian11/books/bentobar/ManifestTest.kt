package com.meridian11.books.bentobar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the app promises in its manifest, pinned: which permissions it has, that its notification
 * listener asks for no notifications, that nothing goes out in clear text, and what stays out of a
 * backup. A change to any of these is a decision, so it has to be made here too.
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement
    private val manifest = xml("src/main/AndroidManifest.xml")
    private fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.attr(name: String): String = getAttributeNS(android, name)
    private val application = manifest.all("application").single()

    @Test fun theOtherAppsBentoBarLooksForAreTheLaunchersAndTheFeedbackApp() {
        // Package visibility, not a permission: the apps in the launcher (folders, app shortcuts) and, by name, the
        // Googlebook's Feedback app (the Shortcut item's Report a bug). Nothing else is looked for.
        val queries = manifest.all("queries").single()
        assertEquals(listOf(com.meridian11.books.bentobar.items.ShortcutRules.FEEDBACK_PACKAGE), queries.all("package").map { it.attr("name") })
        assertEquals(listOf("android.intent.action.MAIN"), queries.all("intent").flatMap { it.all("action") }.map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), queries.all("intent").flatMap { it.all("category") }.map { it.attr("name") })
    }

    @Test fun thePermissionsAreTheseAndNoOthers() {
        // No Bluetooth, no precise or background location, no reading of notifications as a permission: a new one is added here on purpose.
        // Approximate location is Weather's My location, asked for only when an item chooses it.
        assertEquals(setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.POST_PROMOTED_NOTIFICATIONS",
            "android.permission.READ_CALENDAR",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.QUERY_ADVANCED_PROTECTION_MODE",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        ), manifest.all("uses-permission").map { it.attr("name") }.toSet())
    }

    /**
     * The same of what is built: a library can bring a permission along in a manifest of its own, and
     * the app's file would not show it. This is the manifest Gradle merged for the build under test.
     */
    @Test fun noLibraryBringsAPermissionAlong() {
        val type = BuildConfig.BUILD_TYPE
        val merged = File("build/intermediates/merged_manifests/$type/process${type.replaceFirstChar { it.uppercase() }}Manifest/AndroidManifest.xml")
        assertTrue("the merged manifest is not where it was (a newer Android Gradle Plugin?): ${merged.path}", merged.isFile)
        val own = manifest.all("uses-permission").map { it.attr("name") }.toSet()
        // AndroidX adds one of the app's own making, for receivers registered in code that no other app may reach.
        assertEquals(own + "${manifest.getAttribute("package").ifEmpty { BuildConfig.APPLICATION_ID }}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            xml(merged.path).all("uses-permission").map { it.attr("name") }.toSet())
    }

    @Test fun theNotificationListenerAsksForNoNotificationsAndIsNotStartedByAndroid() {
        val listener = application.all("service").single { it.attr("name") == ".items.MediaAccess" }
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.attr("permission"))
        val meta = listener.all("meta-data").associate { it.attr("name") to it.attr("value") }
        // "A present but empty value means 'allow no types'" (NotificationListenerService).
        assertTrue(listener.all("meta-data").any { it.attr("name") == "android.service.notification.default_filter_types" && it.hasAttributeNS(android, "value") })
        assertEquals("", meta["android.service.notification.default_filter_types"])
        // Conversations 1, alerting 2, silent 4, ongoing 8: all four types, never wanted.
        assertEquals(1 or 2 or 4 or 8, meta["android.service.notification.disabled_filter_types"]!!.toInt())
        assertEquals("false", meta["android.service.notification.default_autobind_listenerservice"])
        // And it is the only notification listener.
        assertEquals(1, application.all("service").count { s -> s.all("action").any { it.attr("name") == "android.service.notification.NotificationListenerService" } })
    }

    @Test fun nothingIsEverSentInClearText() {
        assertEquals("false", application.attr("usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", application.attr("networkSecurityConfig"))
        val config = xml("src/main/res/xml/network_security_config.xml")
        val base = config.all("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        // No domain gets an exception, and only debug builds trust anything but the system's authorities.
        assertEquals(0, config.all("domain-config").size)
        assertEquals(listOf("system"), base.all("certificates").map { it.getAttribute("src") })
        assertEquals(listOf("user"), config.all("debug-overrides").single().all("certificates").map { it.getAttribute("src") })
    }

    @Test fun whatBelongsToOneInstallStaysOutOfBackupsAndTransfers() {
        assertEquals("@xml/data_extraction_rules", application.attr("dataExtractionRules"))
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val s = rules.all(section).single()
            assertTrue(section, s.all("exclude").any { it.getAttribute("domain") == "root" && it.getAttribute("path") == "no_backup" })
            // Nothing is named for inclusion, which would turn the rest of the rules into "only this".
            assertEquals(section, 0, s.all("include").size)
            // A running timer and keep awake are this install's: restored elsewhere, a timer would ring on a device it was never set on.
            val sharedPrefs = s.all("exclude").filter { it.getAttribute("domain") == "sharedpref" }.map { it.getAttribute("path") }.toSet()
            assertEquals(section, setOf("timers.xml", "caffeine.xml"), sharedPrefs)
        }
    }

    @Test fun theExportedPartsAreTheOnesAndroidNeeds() {
        // The launcher's two entries, the accessibility service, the three tiles and the notification listener:
        // each is guarded by a system permission or is an activity the user starts.
        val exported = (application.all("activity") + application.all("service") + application.all("receiver") + application.all("provider"))
            .filter { it.attr("exported") == "true" }.map { it.attr("name") }.toSet()
        assertEquals(setOf(".ui.MainActivity", ".ui.BarMenuActivity", ".bar.BarService", ".tile.BarTile", ".tile.AwakeTile", ".tile.TimerTile",
            ".items.MediaAccess"), exported)
        for (service in application.all("service")) assertNotNull(service.attr("name"), service.attr("permission").takeIf { it.isNotEmpty() })
    }

    /**
     * The same of what is built: a library can bring an exported activity or service along (ui-tooling's PreviewActivity
     * once did, in debug), and src/main's manifest would not show it. An exported part is one marked so, or with an
     * intent filter and no say either way.
     */
    @Test fun noLibraryBringsAnExportedPartAlong() {
        val type = BuildConfig.BUILD_TYPE
        val merged = xml("build/intermediates/merged_manifests/$type/process${type.replaceFirstChar { it.uppercase() }}Manifest/AndroidManifest.xml")
        val app = merged.all("application").single()
        val prefix = BuildConfig.APPLICATION_ID
        val exported = (app.all("activity") + app.all("activity-alias") + app.all("service") + app.all("receiver") + app.all("provider"))
            .filter { it.attr("exported") == "true" || (!it.hasAttributeNS(android, "exported") && it.all("intent-filter").isNotEmpty()) }
            .map { it.attr("name").removePrefix(prefix) }.toSet()
        // Debug builds add the adb test hooks from src/debug, guarded by the shell's DUMP permission; a release has none.
        val hooks = if (type == "debug") setOf(".util.DebugReceiver", ".util.BarFlipActivity") else emptySet()
        assertEquals(setOf(".ui.MainActivity", ".ui.BarMenuActivity", ".bar.BarService", ".tile.BarTile", ".tile.AwakeTile", ".tile.TimerTile",
            ".items.MediaAccess", "androidx.profileinstaller.ProfileInstallReceiver") + hooks, exported)
        // The one a library adds: AndroidX's profile installer, which only the shell (it holds DUMP) can start.
        val installer = app.all("receiver").single { it.attr("name") == "androidx.profileinstaller.ProfileInstallReceiver" }
        assertEquals("android.permission.DUMP", installer.attr("permission"))
    }

    // The accessibility service's own config (res/xml/accessibility_service.xml). AGENTS.md "Least privilege" and the Play
    // declaration both rest on it, so each rule below is one a change has to make on purpose.
    private val serviceConfig = xml("src/main/res/xml/accessibility_service.xml")
    private fun Element.flags(name: String) = attr(name).split("|").filter { it.isNotEmpty() }.toSet()

    @Test fun theAccessibilityServiceListensToWindowChangesOnly() {
        // No content, key or motion events: with them the service would read what apps show.
        assertEquals(setOf("typeWindowsChanged"), serviceConfig.flags("accessibilityEventTypes"))
    }

    @Test fun theAccessibilityServiceHasTheThreeFlagsItNeedsAndNoOthers() {
        assertEquals(setOf("flagRetrieveInteractiveWindows", "flagReportViewIds", "flagIncludeNotImportantViews", "flagRequestFilterKeyEvents", "flagRequestMultiFingerGestures"), serviceConfig.flags("accessibilityFlags"))
    }

    @Test fun theAccessibilityServiceNotificationTimeoutIsZero() {
        // With 200 Android merges window events, and our own strip's resize could swallow the status bar's "hidden" event.
        assertEquals("0", serviceConfig.attr("notificationTimeout"))
    }

    @Test fun theAccessibilityServiceSaysItIsNotAnAccessibilityTool() {
        assertEquals("false", serviceConfig.attr("isAccessibilityTool"))
    }

    @Test fun theAccessibilityServiceAsksForOnlyItsTwoCapabilities() {
        assertEquals("true", serviceConfig.attr("canRetrieveWindowContent"))
        assertEquals("true", serviceConfig.attr("canTakeScreenshot"))
        val attributes = (0 until serviceConfig.attributes.length).map { serviceConfig.attributes.item(it) }
            .filter { it.namespaceURI == android }.map { it.localName }.toSet()
        assertEquals(setOf("canRetrieveWindowContent", "canTakeScreenshot", "canRequestFilterKeyEvents"), attributes.filter { it.startsWith("can") }.toSet())
    }

    @Test fun theBarServiceIsDeclaredWithThatConfigAndTheSystemsPermission() {
        val service = application.all("service").single { it.attr("name") == ".bar.BarService" }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.attr("permission"))
        val meta = service.all("meta-data").single { it.attr("name") == "android.accessibilityservice" }
        assertEquals("@xml/accessibility_service", meta.attr("resource"))
        assertEquals(1, application.all("service").count { s -> s.all("action").any { it.attr("name") == "android.accessibilityservice.AccessibilityService" } })
    }

    @Test fun theServiceNeverActsOnAnotherAppsField() {
        val acts = listOf("ACTION_SET_TEXT", "ACTION_PASTE", "findFocus", "FOCUS_INPUT", "pasteOnClick", "performAction")
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> acts.any { it in f.readText() } }.map { it.path }
            .filter { !it.endsWith("CloudClipboardItem.kt") && !it.endsWith("BarService.kt") }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun noCodeChangesTheServiceInfoAtRun() {
        // setServiceInfo would let code widen what the XML promises (events, flags, timeout) without this test seeing it.
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "setServiceInfo" in it.readText() }.map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
