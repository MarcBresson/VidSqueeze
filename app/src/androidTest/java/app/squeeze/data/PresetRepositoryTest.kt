package app.squeeze.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.squeeze.engine.BuiltInPresets
import app.squeeze.engine.ExportMode
import app.squeeze.engine.ExportOptions
import app.squeeze.engine.FfmpegParams
import app.squeeze.engine.RateControl
import app.squeeze.engine.VideoCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: SqueezeDatabase
    private lateinit var repo: PresetRepository
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SqueezeDatabase::class.java).allowMainThreadQueries().build()
        repo = PresetRepository(db.presetDao()).apply { newId = { "user.${++ids}" } }
    }

    @After
    fun tearDown() = db.close()

    private val params = FfmpegParams(videoCodec = VideoCodec.AV1, rateControl = RateControl.Crf(35), customArgs = "-tune film")
    private val export = ExportOptions(mode = ExportMode.SAVE_COPIES, nameTemplate = "{preset}-{original_file_name}.{output_format}", keepLocation = false)

    @Test
    fun builtInsComeFirstAndUserPresetsRoundTrip() = runBlocking {
        val created = repo.create("Night archive", params, export)
        val all = repo.observeAll().first()

        assertEquals(BuiltInPresets.all, all.take(BuiltInPresets.all.size))
        val stored = all.last()
        assertEquals(created, stored)
        assertEquals(params, stored.params)
        assertEquals(export, stored.export)
        assertFalse(stored.builtIn)
    }

    @Test
    fun namesStayUniqueAcrossBuiltInsAndUserPresets() = runBlocking {
        val a = repo.create(BuiltInPresets.share.name, params, export)
        val b = repo.create(BuiltInPresets.share.name, params, export)
        assertEquals("${BuiltInPresets.share.name} (2)", a.name)
        assertEquals("${BuiltInPresets.share.name} (3)", b.name)
        assertEquals("Preset", repo.create("   ", params, export).name)
    }

    @Test
    fun updateAndRenameChangeUserPresetsOnly() = runBlocking {
        val p = repo.create("Mine", params, export)
        assertTrue(repo.update(p.copy(params = params.copy(rateControl = RateControl.Crf(20)))))
        assertEquals(RateControl.Crf(20), repo.get(p.id)!!.params.rateControl)

        assertTrue(repo.rename(p.id, "Renamed"))
        assertEquals("Renamed", repo.get(p.id)!!.name)
        // Renaming to its own name must not produce "Renamed (2)".
        assertTrue(repo.rename(p.id, "Renamed"))
        assertEquals("Renamed", repo.get(p.id)!!.name)

        assertFalse(repo.update(BuiltInPresets.share.copy(name = "Hacked")))
        assertFalse(repo.rename(BuiltInPresets.share.id, "Hacked"))
        assertEquals(BuiltInPresets.share, repo.get(BuiltInPresets.SHARE_ID))
    }

    @Test
    fun deleteAndFallback() = runBlocking {
        val p = repo.create("Temp", params, export)
        repo.delete(p.id)
        assertNull(repo.get(p.id))
        assertEquals(BuiltInPresets.share, repo.getOrDefault(p.id))
        assertEquals(BuiltInPresets.balanced, repo.getOrDefault(BuiltInPresets.BALANCED_ID))
    }

    @Test
    fun duplicateCopiesBuiltInIntoEditablePreset() = runBlocking {
        val copy = requireNotNull(repo.duplicate(BuiltInPresets.maxSavings.id))
        assertFalse(copy.builtIn)
        assertEquals(BuiltInPresets.maxSavings.params, copy.params)
        assertEquals("${BuiltInPresets.maxSavings.name} copy", copy.name)
        assertNotNull(repo.get(copy.id))
    }

    @Test
    fun unreadableRowsAreSkipped() = runBlocking {
        repo.create("Good", params, export)
        db.presetDao().upsert(PresetEntity("user.bad", "Bad", "{not json", "{}", 1, 1))
        val names = repo.observeAll().first().map { it.name }
        assertTrue("Good" in names)
        assertFalse("Bad" in names)
    }
}
