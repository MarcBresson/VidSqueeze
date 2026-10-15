package app.squeeze.feature.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The About screen opens notices by resource name; a library upgrade must not silently drop one. */
@RunWith(AndroidJUnit4::class)
class LicensesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(name: String): String {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        assertTrue("res/raw/$name is missing from the build", id != 0)
        return context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    }

    @Test
    fun everyListedNoticeIsBundledAndNotEmpty() {
        (Licenses.ffmpeg.map { it.rawName } + Licenses.APP + Licenses.SOURCE_OFFER).forEach {
            assertTrue("$it is empty", text(it).isNotBlank())
        }
    }

    @Test
    fun theAppLicenceIsTheGpl() {
        assertTrue(text(Licenses.APP).contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(text(Licenses.APP).contains("Version 3"))
    }

    @Test
    fun noEntryIsListedTwice() {
        val names = Licenses.ffmpeg.map { it.rawName }
        assertTrue(names.size == names.toSet().size)
    }
}
