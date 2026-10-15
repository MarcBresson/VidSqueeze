package app.squeeze.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import app.squeeze.data.BatchDao
import app.squeeze.data.BatchItemDao
import app.squeeze.data.PresetDao
import app.squeeze.data.SqueezeDatabase
import app.squeeze.engine.FfmpegKitEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Scope for work that must outlive a screen (cancel actions from notifications, for example). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): SqueezeDatabase =
        Room.databaseBuilder(context, SqueezeDatabase::class.java, "squeeze.db")
            .addMigrations(*SqueezeDatabase.MIGRATIONS)
            .build()

    @Provides fun batchDao(db: SqueezeDatabase): BatchDao = db.batchDao()

    @Provides fun batchItemDao(db: SqueezeDatabase): BatchItemDao = db.batchItemDao()

    @Provides fun presetDao(db: SqueezeDatabase): PresetDao = db.presetDao()

    @Provides @Singleton
    fun engine(@ApplicationContext context: Context): FfmpegKitEngine = FfmpegKitEngine(context)

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
