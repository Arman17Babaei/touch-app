package app.touch.communication

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "inbox_touches")
internal data class InboxTouchEntity(
    @PrimaryKey val id: String,
    val clientMessageId: String,
    val senderUsername: String,
    val samplePeriodMillis: Int,
    val amplitudes: ByteArray,
    val createdAt: Long,
    val receivedAt: Long,
    val playedAt: Long? = null,
    val audioCodec: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannelCount: Int? = null,
    val audioDurationMs: Int? = null,
    val audioData: ByteArray? = null,
    val audioPlayedAt: Long? = null,
)

@Entity(tableName = "outbox_touches")
internal data class OutboxTouchEntity(
    @PrimaryKey val clientMessageId: String,
    val recipientUsername: String,
    val samplePeriodMillis: Int,
    val amplitudes: ByteArray,
    val createdAt: Long,
    val status: String = OutboxStatus.QUEUED.name,
    val error: String? = null,
    val serverTouchId: String? = null,
    val audioCodec: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannelCount: Int? = null,
    val audioDurationMs: Int? = null,
    val audioData: ByteArray? = null,
)

@Dao
internal interface TouchDao {
    @Query("SELECT * FROM inbox_touches ORDER BY createdAt DESC LIMIT 100")
    fun observeInbox(): Flow<List<InboxTouchEntity>>

    @Query("SELECT * FROM outbox_touches ORDER BY createdAt DESC LIMIT 100")
    fun observeOutbox(): Flow<List<OutboxTouchEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInbox(item: InboxTouchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(item: OutboxTouchEntity)

    @Query("SELECT * FROM outbox_touches WHERE status IN ('QUEUED', 'SENDING') ORDER BY createdAt ASC")
    suspend fun pendingOutbox(): List<OutboxTouchEntity>

    @Query("UPDATE outbox_touches SET status = :status, error = :error WHERE clientMessageId = :id")
    suspend fun updateOutbox(id: String, status: String, error: String? = null)
    @Query("UPDATE outbox_touches SET status = :status, error = :error, serverTouchId = :serverTouchId WHERE clientMessageId = :id")
    suspend fun updateOutboxSent(id: String, status: String, serverTouchId: String?, error: String? = null)
    @Query("SELECT * FROM inbox_touches ORDER BY createdAt DESC LIMIT 100") suspend fun allInbox(): List<InboxTouchEntity>
    @Query("SELECT * FROM outbox_touches ORDER BY createdAt DESC LIMIT 100") suspend fun allOutbox(): List<OutboxTouchEntity>

    @Query("SELECT * FROM inbox_touches WHERE playedAt IS NULL AND createdAt >= :cutoff ORDER BY createdAt ASC")
    suspend fun freshUnplayed(cutoff: Long): List<InboxTouchEntity>

    @Query("SELECT * FROM inbox_touches WHERE id = :id")
    suspend fun inboxById(id: String): InboxTouchEntity?

    @Query("UPDATE inbox_touches SET playedAt = :playedAt WHERE id = :id")
    suspend fun markPlayed(id: String, playedAt: Long)
    @Query("UPDATE inbox_touches SET audioPlayedAt = :playedAt WHERE id = :id")
    suspend fun markAudioPlayed(id: String, playedAt: Long)

    @Query("DELETE FROM inbox_touches")
    suspend fun clearInbox()

    @Query("DELETE FROM inbox_touches WHERE id NOT IN (SELECT id FROM inbox_touches ORDER BY createdAt DESC LIMIT 100)")
    suspend fun trimInbox()

    @Query("DELETE FROM outbox_touches WHERE status = 'SENT' AND clientMessageId NOT IN (SELECT clientMessageId FROM outbox_touches ORDER BY createdAt DESC LIMIT 100)")
    suspend fun trimOutbox()
}

@Database(
    entities = [InboxTouchEntity::class, OutboxTouchEntity::class],
    version = 3,
    exportSchema = true,
)
internal abstract class TouchDatabase : RoomDatabase() {
    abstract fun touchDao(): TouchDao

    companion object {
        @Volatile private var instance: TouchDatabase? = null

        fun get(context: Context): TouchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TouchDatabase::class.java,
                "touch-communication.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE inbox_touches ADD COLUMN clientMessageId TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE outbox_touches ADD COLUMN serverTouchId TEXT") } }
        private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioCodec TEXT")
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioSampleRateHz INTEGER")
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioChannelCount INTEGER")
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioDurationMs INTEGER")
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioData BLOB")
            db.execSQL("ALTER TABLE inbox_touches ADD COLUMN audioPlayedAt INTEGER")
            db.execSQL("ALTER TABLE outbox_touches ADD COLUMN audioCodec TEXT")
            db.execSQL("ALTER TABLE outbox_touches ADD COLUMN audioSampleRateHz INTEGER")
            db.execSQL("ALTER TABLE outbox_touches ADD COLUMN audioChannelCount INTEGER")
            db.execSQL("ALTER TABLE outbox_touches ADD COLUMN audioDurationMs INTEGER")
            db.execSQL("ALTER TABLE outbox_touches ADD COLUMN audioData BLOB")
        } }
    }
}

internal fun InboxTouchEntity.toModel() = InboxTouch(
    id = id,
    senderUsername = senderUsername,
    touch = amplitudes.toTouch(samplePeriodMillis),
    createdAt = createdAt,
    receivedAt = receivedAt,
    playedAt = playedAt,
    audio = audioAttachment(audioCodec, audioSampleRateHz, audioChannelCount, audioDurationMs, audioData),
    audioPlayedAt = audioPlayedAt,
)

internal fun OutboxTouchEntity.toModel() = OutboxTouch(
    clientMessageId = clientMessageId,
    recipientUsername = recipientUsername,
    touch = amplitudes.toTouch(samplePeriodMillis),
    createdAt = createdAt,
    status = OutboxStatus.valueOf(status),
    error = error,
    audio = audioAttachment(audioCodec, audioSampleRateHz, audioChannelCount, audioDurationMs, audioData),
)

internal fun audioAttachment(codec: String?, rate: Int?, channels: Int?, duration: Int?, data: ByteArray?): AudioAttachment? =
    if (codec == null || rate == null || channels == null || duration == null || data == null) null else AudioAttachment(codec, rate, channels, duration, data)
