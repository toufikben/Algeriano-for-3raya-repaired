package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@Entity(
    tableName = "security_events",
    indices = [Index(value = ["status"]), Index(value = ["updatedAt"])]
)
data class SecurityEventEntity(
    @androidx.room.PrimaryKey val id: String,
    val timestamp: Long,
    val failedAttempt: Int,
    val status: String,
    val updatedAt: Long,
    val recoveryAttempts: Int,
    val sendAttempts: Int,
    val photoPath: String?,
    val latitude: Double?,
    val longitude: Double?,
    val locationTimestamp: Long?,
    val photoState: String,
    val locationState: String,
    val emailState: String
)

@Entity(
    tableName = "intruder_logs",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["timestamp"])
    ]
)
data class IntruderLogEntity(
    @androidx.room.PrimaryKey val id: String,
    val eventId: String?,
    val photoCaptured: Boolean,
    val locationCaptured: Boolean,
    val timestamp: Long,
    val photoPath: String?,
    val latitude: Double?,
    val longitude: Double?,
    val address: String?,
    val emailSent: Boolean,
    val statusMessage: String,
    val photoState: String,
    val locationState: String,
    val emailState: String
)

@Dao
interface SecurityDao {
    @Query("SELECT * FROM security_events ORDER BY timestamp ASC")
    fun allEvents(): List<SecurityEventEntity>

    @Query("SELECT * FROM security_events WHERE id = :id LIMIT 1")
    fun event(id: String): SecurityEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEvents(events: List<SecurityEventEntity>)

    @Query("DELETE FROM security_events")
    fun clearEvents()

    @androidx.room.Transaction
    fun replaceEvents(events: List<SecurityEventEntity>) {
        clearEvents()
        upsertEvents(events)
    }

    @Query("SELECT * FROM intruder_logs ORDER BY timestamp DESC")
    fun allLogs(): List<IntruderLogEntity>

    @Query("SELECT * FROM intruder_logs WHERE id = :id LIMIT 1")
    fun log(id: String): IntruderLogEntity?

    @Query("SELECT * FROM intruder_logs WHERE eventId = :eventId LIMIT 1")
    fun logForEvent(eventId: String): IntruderLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLogs(logs: List<IntruderLogEntity>)

    @Query("DELETE FROM intruder_logs")
    fun clearLogs()

    @androidx.room.Transaction
    fun replaceLogs(logs: List<IntruderLogEntity>) {
        clearLogs()
        upsertLogs(logs)
    }
}

@Database(
    entities = [SecurityEventEntity::class, IntruderLogEntity::class],
    version = 4,
    exportSchema = false
)
abstract class SecurityDatabase : RoomDatabase() {
    abstract fun securityDao(): SecurityDao

    companion object {
        @Volatile private var instance: SecurityDatabase? = null

        fun getInstance(context: Context): SecurityDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SecurityDatabase::class.java,
                    "security_data.db"
                )
                    // WAL lets the service and UI read without serializing all
                    // readers behind a single rollback journal.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE security_events ADD COLUMN photoState TEXT NOT NULL DEFAULT 'NOT_REQUESTED'")
                database.execSQL("ALTER TABLE security_events ADD COLUMN locationState TEXT NOT NULL DEFAULT 'NOT_REQUESTED'")
                database.execSQL("ALTER TABLE security_events ADD COLUMN emailState TEXT NOT NULL DEFAULT 'NOT_REQUESTED'")
                backfillComponentStates(database)
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                backfillComponentStates(database)
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE intruder_logs ADD COLUMN photoState TEXT NOT NULL DEFAULT 'FAILED'")
                database.execSQL("ALTER TABLE intruder_logs ADD COLUMN locationState TEXT NOT NULL DEFAULT 'FAILED'")
                database.execSQL("ALTER TABLE intruder_logs ADD COLUMN emailState TEXT NOT NULL DEFAULT 'FAILED'")
                database.execSQL("UPDATE intruder_logs SET photoState = CASE WHEN photoCaptured = 1 THEN 'SUCCEEDED' ELSE 'FAILED' END")
                database.execSQL("UPDATE intruder_logs SET locationState = CASE WHEN locationCaptured = 1 THEN 'SUCCEEDED' ELSE 'FAILED' END")
                database.execSQL("UPDATE intruder_logs SET emailState = CASE WHEN emailSent = 1 THEN 'SUCCEEDED' ELSE 'FAILED' END")
            }
        }

        /**
         * Preserve facts already represented by the v1/v2 columns. A default
         * value alone would incorrectly turn old successful captures into
         * NOT_REQUESTED after upgrade.
         */
        private fun backfillComponentStates(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            database.execSQL(
                """
                UPDATE security_events
                SET photoState = CASE
                    WHEN photoPath IS NOT NULL AND TRIM(photoPath) <> '' THEN 'SUCCEEDED'
                    ELSE 'NOT_REQUESTED'
                END
                WHERE photoState = 'NOT_REQUESTED'
                """.trimIndent()
            )
            database.execSQL(
                """
                UPDATE security_events
                SET locationState = CASE
                    WHEN latitude IS NOT NULL AND longitude IS NOT NULL THEN 'SUCCEEDED'
                    ELSE 'NOT_REQUESTED'
                END
                WHERE locationState = 'NOT_REQUESTED'
                """.trimIndent()
            )
            database.execSQL(
                """
                UPDATE security_events
                SET emailState = CASE
                    WHEN status = 'SENT' THEN 'SUCCEEDED'
                    WHEN status = 'SEND_PENDING' OR status = 'FAILED_RETRYABLE' THEN 'PENDING'
                    ELSE 'NOT_REQUESTED'
                END
                WHERE emailState = 'NOT_REQUESTED'
                """.trimIndent()
            )
        }
    }
}

/** Synchronous facade kept at the existing call sites while the app migrates to Room. */
class SecurityStore(context: Context) {
    private val dao = SecurityDatabase.getInstance(context).securityDao()

    fun events(): List<SecurityEvent> = io { dao.allEvents().map(SecurityEventEntity::toModel) }
    fun event(id: String): SecurityEvent? = io { dao.event(id)?.toModel() }
    fun replaceEvents(events: List<SecurityEvent>) = io {
        dao.replaceEvents(events.map(SecurityEvent::toEntity))
    }

    fun logs(): List<IntruderLog> = io { dao.allLogs().map(IntruderLogEntity::toModel) }
    fun log(id: String): IntruderLog? = io { dao.log(id)?.toModel() }
    fun logForEvent(eventId: String): IntruderLog? = io { dao.logForEvent(eventId)?.toModel() }
    fun upsertLog(log: IntruderLog) = io { dao.upsertLogs(listOf(log.toEntity())) }
    fun replaceLogs(logs: List<IntruderLog>) = io {
        dao.replaceLogs(logs.map(IntruderLog::toEntity))
    }

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }
}

private fun SecurityEventEntity.toModel() = SecurityEvent(
    id, timestamp, failedAttempt, runCatching { SecurityEventStatus.valueOf(status) }
        .getOrDefault(SecurityEventStatus.FAILED), updatedAt, recoveryAttempts,
    sendAttempts, photoPath, latitude, longitude, locationTimestamp,
    runCatching { SecurityEventComponentState.valueOf(photoState) }
        .getOrDefault(SecurityEventComponentState.NOT_REQUESTED),
    runCatching { SecurityEventComponentState.valueOf(locationState) }
        .getOrDefault(SecurityEventComponentState.NOT_REQUESTED),
    runCatching { SecurityEventComponentState.valueOf(emailState) }
        .getOrDefault(SecurityEventComponentState.NOT_REQUESTED)
)

private fun SecurityEvent.toEntity() = SecurityEventEntity(
    id, timestamp, failedAttempt, status.name, updatedAt, recoveryAttempts,
    sendAttempts, photoPath, latitude, longitude, locationTimestamp,
    photoState.name, locationState.name, emailState.name
)

private fun IntruderLogEntity.toModel() = IntruderLog(
    id, eventId, photoCaptured, locationCaptured, timestamp, photoPath,
    latitude, longitude, address, emailSent, statusMessage,
    runCatching { SecurityEventComponentState.valueOf(photoState) }
        .getOrDefault(SecurityEventComponentState.FAILED),
    runCatching { SecurityEventComponentState.valueOf(locationState) }
        .getOrDefault(SecurityEventComponentState.FAILED),
    runCatching { SecurityEventComponentState.valueOf(emailState) }
        .getOrDefault(SecurityEventComponentState.FAILED)
)

private fun IntruderLog.toEntity() = IntruderLogEntity(
    id, eventId, photoCaptured, locationCaptured, timestamp, photoPath,
    latitude, longitude, address, emailSent, statusMessage,
    photoState.name, locationState.name, emailState.name
)
