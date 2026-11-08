package app.squeeze.data

import android.net.Uri
import app.squeeze.engine.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceImporterKindTest {
    @Test
    fun mediaStoreVideoUriKind() {
        assertEquals(42L to MediaKind.VIDEO, SourceImporter.mediaStoreId("content://media/external/video/media/42"))
    }

    @Test
    fun mediaStoreImageUriKind() {
        assertEquals(7L to MediaKind.IMAGE, SourceImporter.mediaStoreId("content://media/external/image/media/7"))
    }

    @Test
    fun pickerAndUnknownUrisHaveNoMediaStoreId() {
        assertNull(SourceImporter.mediaStoreId("content://media/picker/42"))
        assertNull(SourceImporter.mediaStoreId("content://media/external/audio/media/1"))
        assertNull(SourceImporter.mediaStoreId("file:///tmp/x.jpg"))
        assertNull(SourceImporter.mediaStoreId("content://media/external/video/media/notanumber"))
    }

    @Test
    fun kindFromExtension() {
        assertEquals(MediaKind.IMAGE, SourceImporter.kindFromExtension("IMG_0001.jpg"))
        assertEquals(MediaKind.IMAGE, SourceImporter.kindFromExtension("shot.HEIC"))
        assertEquals(MediaKind.IMAGE, SourceImporter.kindFromExtension("anim.webp"))
        assertEquals(MediaKind.VIDEO, SourceImporter.kindFromExtension("clip.MP4"))
        assertEquals(MediaKind.VIDEO, SourceImporter.kindFromExtension("no_extension"))
    }
}
