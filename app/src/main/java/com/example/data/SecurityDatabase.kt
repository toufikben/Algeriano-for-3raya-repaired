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
    val locationTimestamp: Long?
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
    val statusMessage: String
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
}

@Database(
    entities = [SecurityEventEntity::class, IntruderLogEntity::class],
    version = 1,
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
                    .build().also { instance = it }
            }
    }
}

/** Synchronous facade kept at the existing call sites while the app migrates to Room. */
class SecurityStore(context: Context) {
    private val dao = SecurityDatabase.getInstance(context).securityDao()

    fun events(): List<SecurityEvent> = io { dao.allEvents().map(SecurityEventEntity::toModel) }
    fun event(id: String): SecurityEvent? = io { dao.event(id)?.toModel() }
    fun replaceEvents(events: List<SecurityEvent>) = io {
        dao.clearEvents()
        dao.upsertEvents(events.map(SecurityEvent::toEntity))
    }

    fun logs(): List<IntruderLog> = io { dao.allLogs().map(IntruderLogEntity::toModel) }
    fun log(id: String): IntruderLog? = io { dao.log(id)?.toModel() }
    fun logForEvent(eventId: String): IntruderLog? = io { dao.logForEvent(eventId)?.toModel() }
    fun upsertLog(log: IntruderLog) = io { dao.upsertLogs(listOf(log.toEntity())) }
    fun replaceLogs(logs: List<IntruderLog>) = io {
        dao.clearLogs()
        dao.upsertLogs(logs.map(IntruderLog::toEntity))
    }

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }
}

private fun SecurityEventEntity.toModel() = SecurityEvent(
    id, timestamp, failedAttempt, runCatching { SecurityEventStatus.valueOf(status) }
        .getOrDefault(SecurityEventStatus.FAILED), updatedAt, recoveryAttempts,
    sendAttempts, photoPath, latitude, longitude, locationTimestamp
)

private fun SecurityEvent.toEntity() = SecurityEventEntity(
    id, timestamp, failedAttempt, status.name, updatedAt, recoveryAttempts,
    sendAttempts, photoPath, latitude, longitude, locationTimestamp
)

private fun IntruderLogEntity.toModel() = IntruderLog(
    id, eventId, photoCaptured, locationCaptured, timestamp, photoPath,
    latitude, longitude, address, emailSent, statusMessage
)

private fun IntruderLog.toEntity() = IntruderLogEntity(
    id, eventId, photoCaptured, locationCaptured, timestamp, photoPath,
    latitude, longitude, address, emailSent, statusMessage
)
