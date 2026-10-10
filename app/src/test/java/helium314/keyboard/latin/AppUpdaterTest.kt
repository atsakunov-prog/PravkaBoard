// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.utils.AppUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterTest {
    private val info = """
        versionName=4.0-pravka.23
        versionCode=10023
        apk=pravkaboard-debug.apk
        commit=43a3787ca6ba12248d763ca8a4d0354b4c133143
        branch=ccr-de51b1f7-43im53
        builtAt=2026-10-10T12:30Z
    """.trimIndent()

    @Test fun `parse build info`() {
        val build = AppUpdater.parseInfo(info)!!
        assertEquals("4.0-pravka.23", build.versionName)
        assertEquals(10023, build.versionCode)
        assertEquals("pravkaboard-debug.apk", build.apkName)
        assertEquals("43a3787", build.commit)
        assertEquals("ccr-de51b1f7-43im53", build.branch)
        assertEquals("2026-10-10T12:30Z", build.builtAt)
    }

    @Test fun `build info of the earlier format without version code is not usable`() {
        // what the claude/pravka-integration branch publishes
        assertNull(AppUpdater.parseInfo("build=22\ncommit=55d8884\nbranch=claude/pravka-integration\nbuiltAt=2026-08-18T15:36Z"))
        assertNull(AppUpdater.parseInfo(""))
        assertNull(AppUpdater.parseInfo("<html>404</html>"))
    }

    @Test fun `apk name must be a plain file name`() {
        assertEquals("pravkaboard-debug.apk", AppUpdater.parseInfo("versionCode=5\napk=../../evil.apk")!!.apkName)
        assertEquals("pravkaboard-debug.apk", AppUpdater.parseInfo("versionCode=5\napk=other/x.apk")!!.apkName)
        assertEquals("pravkaboard-debug.apk", AppUpdater.parseInfo("versionCode=5\napk=readme.txt")!!.apkName)
        assertEquals("other.apk", AppUpdater.parseInfo("versionCode=5\napk=other.apk")!!.apkName)
    }

    @Test fun `only newer builds of the own branch are updates`() {
        val build = AppUpdater.parseInfo(info)!!
        assertTrue(AppUpdater.isNewer(build, 10022, "ccr-de51b1f7-43im53"))
        assertFalse(AppUpdater.isNewer(build, 10023, "ccr-de51b1f7-43im53"))
        assertFalse(AppUpdater.isNewer(build, 10024, "ccr-de51b1f7-43im53"))
        // a build of another branch has a higher number, but lacks our work
        assertFalse(AppUpdater.isNewer(build, 10022, "claude/pravka-integration"))
        // local builds don't know their branch
        assertTrue(AppUpdater.isNewer(build, 4006, ""))
        assertTrue(AppUpdater.isNewer(build.copy(branch = ""), 10022, "main"))
    }
}
