package no.stormberry.sunapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The two editions of SunApp, and the manifest overlay that makes the Google Play one.
 *
 * The Play edition (`-PplayBuild=true`) must declare NO permission and contain NO alarm
 * component. It gets there by a manifest overlay of removals (`src/edition-play`). An overlay can
 * drift from the manifest it removes things from: a permission added to `expected-permissions.txt`
 * and the main manifest but not to the overlay would ship to Play unnoticed. These tests read the
 * three files and compare them, in either edition, so the drift fails in CI and not on Play.
 *
 * They check the SOURCE of the overlay. What the built bundle really declares is checked by
 * `android/tools/check-play-bundle.sh`, on the built `.aab`.
 */
class EditionTest {

    @Test
    fun `the Play overlay removes exactly the permissions the sovereign build is allowed to declare`() {
        val expected = expectedPermissions()
        val overlay = overlay()
        val removed = Regex("""<uses-permission\s+android:name="([^"]+)"\s+tools:node="remove"\s*/>""")
            .findAll(overlay).map { it.groupValues[1] }.toSet()
        assertEquals(expected, removed)
        assertEquals(9, removed.size)
    }

    @Test
    fun `the Play overlay removes every alarm component of the main manifest, and nothing else`() {
        val main = mainManifest()
        val declared = Regex("""<(?:activity|service|receiver|provider)\s+android:name="(\.alarm\.[A-Za-z0-9_.]+)"""")
            .findAll(main).map { "no.stormberry.sunapp" + it.groupValues[1] }.toSet()
        assertEquals("the main manifest's alarm components", 5, declared.size)
        val removed = Regex("""<(?:activity|service|receiver|provider)\s+android:name="([^"]+)"\s+tools:node="remove"\s*/>""")
            .findAll(overlay()).map { it.groupValues[1] }.toSet()
        assertEquals(declared, removed)
        // MainActivity is the sun-times screen: the Play edition keeps it.
        assertFalse(removed.any { it.endsWith(".MainActivity") })
    }

    @Test
    fun `the Play overlay only removes, so it can never add a permission or a component`() {
        val body = overlay().replace(Regex("<!--[\\s\\S]*?-->"), "")
        val tags = Regex("""<(uses-permission|permission|activity|service|receiver|provider|uses-feature|queries)\b[^>]*>""")
            .findAll(body).map { it.value }.toList()
        assertTrue("the overlay has nothing to test", tags.isNotEmpty())
        for (tag in tags) assertTrue(tag, tag.contains("tools:node=\"remove\""))
    }

    @Test
    fun `the main manifest still declares the nine permissions and the alarm components`() {
        val main = mainManifest()
        for (name in expectedPermissions()) {
            assertTrue(name, main.contains("<uses-permission\n        android:name=\"$name\"") ||
                main.contains("<uses-permission android:name=\"$name\""))
        }
        assertTrue(main.contains("android:name=\".alarm.AlarmService\""))
        assertTrue(main.contains("android:name=\".alarm.SystemEventReceiver\""))
    }

    @Test
    fun `an edition without alarms is the one that says so, and the sovereign one has them`() {
        // Edition.ALARMS is a constant of the edition being tested; what is asserted here is
        // that the two edition files agree with the build switch's own naming.
        val sovereign = repoFile("android/app/src/edition-sovereign/kotlin/no/stormberry/sunapp/Edition.kt")
        val play = repoFile("android/app/src/edition-play/kotlin/no/stormberry/sunapp/Edition.kt")
        assumeTrue("edition sources not reachable from the test's working directory", sovereign != null && play != null)
        assertTrue(sovereign!!.readText().contains("const val ALARMS: Boolean = true"))
        assertTrue(play!!.readText().contains("const val ALARMS: Boolean = false"))
        // The two files differ in nothing but the value and the words around it.
        val strip = { f: File -> f.readText().lines().filter { it.startsWith("package ") || it.startsWith("object ") || it.trim().startsWith("const val ") } }
        assertEquals(strip(sovereign).map { it.replace("true", "X").replace("false", "X") }, strip(play).map { it.replace("true", "X").replace("false", "X") })
    }

    private fun expectedPermissions(): Set<String> =
        requireFile("android/expected-permissions.txt").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    private fun overlay(): String = requireFile("android/app/src/edition-play/AndroidManifest.xml").readText()

    private fun mainManifest(): String = requireFile("android/app/src/main/AndroidManifest.xml").readText()

    private fun requireFile(path: String): File {
        val f = repoFile(path)
        assumeTrue("$path not reachable from the test's working directory", f != null)
        return f!!
    }

    /** A file in the repository, found from the module directory the JVM test runs in. */
    private fun repoFile(path: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .map { File(it, path) }
            .firstOrNull { it.isFile }
}
