package app.squeeze.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceImporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "importer-test").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        File(context.filesDir, "imports").deleteRecursively()
    }

    @Test
    fun copiesNonMediaStoreSourcesIntoAppStorage() = runBlocking {
        val original = File(dir, "from-elsewhere.mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        val source = SourceImporter(context).resolve(listOf(Uri.fromFile(original))).single()

        assertTrue(source.isImported)
        assertNull(source.mediaStoreId)
        assertEquals("from-elsewhere.mp4", source.displayName)
        val copy = File(requireNotNull(Uri.parse(source.uri).path))
        assertTrue(copy.path.startsWith(context.filesDir.path))
        assertArrayEquals(original.readBytes(), copy.readBytes())
        assertEquals(4096L, source.size)
    }

    @Test
    fun recognisesMediaStoreVideoUrisOnly() {
        assertEquals(42L, SourceImporter.mediaStoreVideoId(Uri.parse("content://media/external/video/media/42")))
        assertEquals(7L, SourceImporter.mediaStoreVideoId(Uri.parse("content://media/external_primary/video/media/7")))
        assertNull(SourceImporter.mediaStoreVideoId(Uri.parse("content://media/external/images/media/42")))
        assertNull(SourceImporter.mediaStoreVideoId(Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")))
        assertNull(SourceImporter.mediaStoreVideoId(Uri.parse("content://com.google.android.apps.photos.contentprovider/1/video/media/42")))
        assertNull(SourceImporter.mediaStoreVideoId(Uri.parse("file:///sdcard/a.mp4")))
    }
}
