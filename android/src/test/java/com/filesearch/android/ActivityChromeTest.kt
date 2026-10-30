package com.filesearch.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the phone-verified blank-Search-tab bug: the activity
 * used the default device theme WITH a system ActionBar, and edge-to-edge
 * content started at y=0 — the whole DockedSearchBar (y 36..276) sat behind
 * the opaque 288px ActionBar (uiautomator-verified), so the body looked
 * completely blank while Status (taller content) looked fine. Compose/Paparazzi
 * tests render screens standalone with no activity chrome, so they stayed
 * green while the phone was broken — this manifest-level test pins the fix.
 */
class ActivityChromeTest {
    private fun manifestFile(): File {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("android/src/main/AndroidManifest.xml"),
            )
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found (tried $candidates)")
    }

    private fun chromeTheme(): String {
        val doc =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile())
        doc.documentElement.normalize()
        val app = doc.getElementsByTagName("application").item(0)
        val appTheme = app?.attributes?.getNamedItem("android:theme")?.nodeValue.orEmpty()
        val activities = doc.getElementsByTagName("activity")
        val activityThemes =
            (0 until activities.length).map {
                activities.item(it).attributes?.getNamedItem("android:theme")?.nodeValue.orEmpty()
            }
        return (listOf(appTheme) + activityThemes).joinToString(" ")
    }

    @Test
    fun activityHasNoActionBar() {
        assertTrue(
            "MainActivity must use a NoActionBar theme (else the system ActionBar " +
                "covers the search bar and the Search tab renders blank) — got: '${chromeTheme()}'",
            chromeTheme().contains("NoActionBar"),
        )
    }
}
