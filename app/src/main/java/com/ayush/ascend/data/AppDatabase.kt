package com.ayush.ascend.data

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
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One rule per watched app. [limitMinutes] null = gate only, no daily cap. */
@Entity(tableName = "app_rule")
data class AppRule(
    @PrimaryKey val pkg: String,
    val label: String,
    val limitMinutes: Int?,
    /** Trusted epoch-ms until which the limit can't be raised or removed (45-day cooldown). */
    val lockedUntil: Long,
    val gated: Boolean,
    val createdAt: Long,
)

/** A loosening change that only applies after 24 h ("future weak you" protection). */
@Entity(tableName = "pending_change")
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,       // see PendingKinds
    val target: String,     // pkg / domain / feature key
    val applyAt: Long,
    val createdAt: Long,
)

object PendingKinds {
    const val GATE_OFF = "gate_off"
    const val FEATURE_OFF = "feature_off"
    const val DOMAIN_REMOVE = "domain_remove"
    const val BATCH_APP_REMOVE = "batch_app_remove"
}

/** Every pass through the gate / stake unlock — drives escalating friction + reflection log. */
@Entity(tableName = "open_event")
data class OpenEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val ts: Long,
    val type: String,       // "gate" | "stake" | "blocked"
    val reason: String = "",
    val minutes: Int = 0,
    val frictionLevel: Int = 0,
)

@Entity(tableName = "held_notification")
data class HeldNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val delivered: Boolean = false,
)

@Entity(tableName = "blocked_domain")
data class BlockedDomain(
    @PrimaryKey val domain: String,
    val addedAt: Long,
)

@Entity(tableName = "batch_app")
data class BatchApp(
    @PrimaryKey val pkg: String,
    val label: String,
)

/** Feature snapshot taken at each unlock; labelled ~20 min later by TrainWorker. */
@Entity(tableName = "unlock_sample")
data class UnlockSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val features: String,   // CSV of doubles
    val label: Int = -1,    // -1 unlabeled, 0 fine, 1 binge
)

@Entity(tableName = "day_stat")
data class DayStat(
    @PrimaryKey val day: String,    // yyyy-MM-dd
    val totalMinutes: Int,
    val unlocks: Int,
    val summit: Boolean,            // all limits respected, no stake unlocks
)

/** How a distracting-app session felt, from the check-in notification. [mood]: 1 😊, 0 😐, -1 😞. */
@Entity(tableName = "mood_entry")
data class MoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val ts: Long,
    val mood: Int,
    val sessionMinutes: Int,
)

/** Sunday report card for the 7 days starting [weekStart]. Trends are % vs the 7 days before (+ = more). */
@Entity(tableName = "weekly_report")
data class WeeklyReport(
    @PrimaryKey val weekStart: String,  // yyyy-MM-dd
    val createdAt: Long,
    val grade: String,
    val totalMinutes: Int,
    val avgMinutes: Int,
    val bestDay: String,
    val bestMinutes: Int,
    val worstDay: String,
    val worstMinutes: Int,
    val topApps: String,                // "pkg=minutes;pkg=minutes"
    val trendPct: Int,
    val unlocks: Int,
    val unlocksTrendPct: Int,
    val daysUnderGoal: Int,
    val summitDays: Int,
    val streak: Int,
    val mood: String,
    val timeCost: String,
) {
    val apps: List<Pair<String, Int>> get() = topApps.split(';').filter { '=' in it }
        .map { it.substringBefore('=') to (it.substringAfter('=').toIntOrNull() ?: 0) }
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM app_rule ORDER BY label") fun observeAll(): Flow<List<AppRule>>
    @Query("SELECT * FROM app_rule") suspend fun all(): List<AppRule>
    @Query("SELECT * FROM app_rule WHERE pkg = :pkg") suspend fun get(pkg: String): AppRule?
    @Upsert suspend fun upsert(rule: AppRule)
    @Query("DELETE FROM app_rule WHERE pkg = :pkg") suspend fun delete(pkg: String)
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_change ORDER BY applyAt") fun observe(): Flow<List<PendingChange>>
    @Query("SELECT * FROM pending_change WHERE applyAt <= :now") suspend fun due(now: Long): List<PendingChange>
    @Query("SELECT * FROM pending_change WHERE kind = :kind AND target = :target LIMIT 1")
    suspend fun find(kind: String, target: String): PendingChange?
    @Insert suspend fun insert(c: PendingChange)
    @Query("DELETE FROM pending_change WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface OpenDao {
    @Insert suspend fun insert(e: OpenEvent)
    @Query("SELECT COUNT(*) FROM open_event WHERE pkg = :pkg AND ts >= :since AND type = 'gate'")
    suspend fun gateOpensSince(pkg: String, since: Long): Int
    @Query("SELECT COUNT(*) FROM open_event WHERE ts >= :from AND ts < :to AND type = 'stake'")
    suspend fun stakesBetween(from: Long, to: Long): Int
    @Query("SELECT * FROM open_event WHERE reason != '' ORDER BY ts DESC LIMIT :n")
    fun observeRecentReasons(n: Int): Flow<List<OpenEvent>>
}

@Dao
interface NotifDao {
    @Insert suspend fun insert(n: HeldNotification)
    @Query("SELECT * FROM held_notification WHERE delivered = 0 ORDER BY postedAt DESC")
    suspend fun undelivered(): List<HeldNotification>
    @Query("SELECT * FROM held_notification WHERE postedAt >= :since ORDER BY postedAt DESC")
    fun observeSince(since: Long): Flow<List<HeldNotification>>
    @Query("UPDATE held_notification SET delivered = 1 WHERE delivered = 0") suspend fun markAllDelivered()
    @Query("DELETE FROM held_notification WHERE postedAt < :before") suspend fun prune(before: Long)
    @Query("SELECT * FROM batch_app ORDER BY label") fun observeApps(): Flow<List<BatchApp>>
    @Query("SELECT pkg FROM batch_app") suspend fun appPkgs(): List<String>
    @Upsert suspend fun addApp(a: BatchApp)
    @Query("DELETE FROM batch_app WHERE pkg = :pkg") suspend fun removeApp(pkg: String)
}

@Dao
interface DomainDao {
    @Query("SELECT * FROM blocked_domain ORDER BY domain") fun observe(): Flow<List<BlockedDomain>>
    @Query("SELECT domain FROM blocked_domain") suspend fun all(): List<String>
    @Upsert suspend fun add(d: BlockedDomain)
    @Query("DELETE FROM blocked_domain WHERE domain = :d") suspend fun remove(d: String)
}

@Dao
interface SampleDao {
    @Insert suspend fun insert(s: UnlockSample)
    @Query("SELECT * FROM unlock_sample WHERE label = -1 AND ts < :before") suspend fun unlabeled(before: Long): List<UnlockSample>
    @Query("UPDATE unlock_sample SET label = :label WHERE id = :id") suspend fun setLabel(id: Long, label: Int)
    @Query("SELECT * FROM unlock_sample WHERE label >= 0 AND ts >= :since") suspend fun labeled(since: Long): List<UnlockSample>
    @Query("SELECT COUNT(*) FROM unlock_sample") fun observeCount(): Flow<Int>
    @Query("DELETE FROM unlock_sample WHERE ts < :before") suspend fun prune(before: Long)
}

@Dao
interface DayDao {
    @Upsert suspend fun upsert(d: DayStat)
    @Query("SELECT * FROM day_stat WHERE day = :day") suspend fun get(day: String): DayStat?
    @Query("SELECT * FROM day_stat ORDER BY day DESC LIMIT :n") fun observeRecent(n: Int): Flow<List<DayStat>>
    @Query("SELECT * FROM day_stat ORDER BY day DESC LIMIT :n") suspend fun recent(n: Int): List<DayStat>
}

@Dao
interface MoodDao {
    @Insert suspend fun insert(m: MoodEntry)
    @Query("SELECT * FROM mood_entry WHERE ts >= :since") suspend fun since(since: Long): List<MoodEntry>
    @Query("SELECT * FROM mood_entry WHERE ts >= :since") fun observeSince(since: Long): Flow<List<MoodEntry>>
}

@Dao
interface ReportDao {
    @Upsert suspend fun upsert(r: WeeklyReport)
    @Query("SELECT * FROM weekly_report WHERE weekStart = :week") suspend fun get(week: String): WeeklyReport?
    @Query("SELECT * FROM weekly_report ORDER BY weekStart DESC LIMIT 1") suspend fun latest(): WeeklyReport?
    @Query("SELECT * FROM weekly_report ORDER BY weekStart DESC") fun observeAll(): Flow<List<WeeklyReport>>
}

@Database(
    entities = [AppRule::class, PendingChange::class, OpenEvent::class, HeldNotification::class,
        BlockedDomain::class, BatchApp::class, UnlockSample::class, DayStat::class,
        MoodEntry::class, WeeklyReport::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun pending(): PendingDao
    abstract fun opens(): OpenDao
    abstract fun notifs(): NotifDao
    abstract fun domains(): DomainDao
    abstract fun samples(): SampleDao
    abstract fun days(): DayDao
    abstract fun moods(): MoodDao
    abstract fun reports(): ReportDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "ascend.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

/** v2: mood check-ins + weekly reports. Only adds tables, existing data is untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `mood_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pkg` TEXT NOT NULL, `ts` INTEGER NOT NULL, `mood` INTEGER NOT NULL, `sessionMinutes` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `weekly_report` (`weekStart` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `grade` TEXT NOT NULL, `totalMinutes` INTEGER NOT NULL, `avgMinutes` INTEGER NOT NULL, `bestDay` TEXT NOT NULL, `bestMinutes` INTEGER NOT NULL, `worstDay` TEXT NOT NULL, `worstMinutes` INTEGER NOT NULL, `topApps` TEXT NOT NULL, `trendPct` INTEGER NOT NULL, `unlocks` INTEGER NOT NULL, `unlocksTrendPct` INTEGER NOT NULL, `daysUnderGoal` INTEGER NOT NULL, `summitDays` INTEGER NOT NULL, `streak` INTEGER NOT NULL, `mood` TEXT NOT NULL, `timeCost` TEXT NOT NULL, PRIMARY KEY(`weekStart`))")
    }
}
