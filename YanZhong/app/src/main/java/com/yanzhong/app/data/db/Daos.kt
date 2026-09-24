package com.yanzhong.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subject WHERE archived = 0 ORDER BY sort, id")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subject ORDER BY sort, id")
    suspend fun getAll(): List<SubjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subject: SubjectEntity): Long

    @Update
    suspend fun update(subject: SubjectEntity)

    @Delete
    suspend fun delete(subject: SubjectEntity)

    @Query("DELETE FROM subject")
    suspend fun deleteAll()

    // ---------- 云同步 ----------

    @Query("SELECT * FROM subject WHERE clientGuid = :guid LIMIT 1")
    suspend fun getByClientGuid(guid: String): SubjectEntity?

    @Query("SELECT * FROM subject WHERE dirty = 1")
    suspend fun getDirty(): List<SubjectEntity>

    @Query("UPDATE subject SET dirty = 0 WHERE clientGuid IN (:guids)")
    suspend fun markClean(guids: List<String>)

    @Query("DELETE FROM subject WHERE clientGuid = :guid")
    suspend fun deleteByClientGuid(guid: String)

    @Query(
        "UPDATE subject SET clientGuid = lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
            "substr(hex(randomblob(2)),2)||'-'||" +
            "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
            "hex(randomblob(6))) WHERE clientGuid = ''"
    )
    suspend fun fillEmptyGuids()
}

@Dao
interface CountdownNodeDao {
    @Query("SELECT * FROM countdown_node ORDER BY pinned DESC, sort, targetAt")
    fun observeAll(): Flow<List<CountdownNodeEntity>>

    @Query("SELECT * FROM countdown_node ORDER BY pinned DESC, sort, targetAt")
    suspend fun getAll(): List<CountdownNodeEntity>

    @Query("SELECT * FROM countdown_node WHERE pinned = 1 ORDER BY sort, targetAt LIMIT 1")
    fun observePinned(): Flow<CountdownNodeEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: CountdownNodeEntity): Long

    @Update
    suspend fun update(node: CountdownNodeEntity)

    @Delete
    suspend fun delete(node: CountdownNodeEntity)

    @Query("DELETE FROM countdown_node")
    suspend fun deleteAll()

    @Query("UPDATE countdown_node SET pinned = 0, updatedAt = :now, dirty = 1")
    suspend fun clearPinned(now: Long)

    @Query("UPDATE countdown_node SET pinned = 1, updatedAt = :now, dirty = 1 WHERE id = :id")
    suspend fun setPinned(id: Long, now: Long)

    @Query("SELECT COUNT(*) FROM countdown_node")
    suspend fun count(): Int

    // ---------- 云同步 ----------

    @Query("SELECT * FROM countdown_node WHERE clientGuid = :guid LIMIT 1")
    suspend fun getByClientGuid(guid: String): CountdownNodeEntity?

    @Query("SELECT * FROM countdown_node WHERE dirty = 1")
    suspend fun getDirty(): List<CountdownNodeEntity>

    @Query("UPDATE countdown_node SET dirty = 0 WHERE clientGuid IN (:guids)")
    suspend fun markClean(guids: List<String>)

    @Query("DELETE FROM countdown_node WHERE clientGuid = :guid")
    suspend fun deleteByClientGuid(guid: String)

    @Query(
        "UPDATE countdown_node SET clientGuid = lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
            "substr(hex(randomblob(2)),2)||'-'||" +
            "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
            "hex(randomblob(6))) WHERE clientGuid = ''"
    )
    suspend fun fillEmptyGuids()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM task WHERE status = 0 ORDER BY priority, dueAt")
    fun observeOpenTasks(): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM task WHERE status = 0 AND dueAt IS NOT NULL AND dueAt <= :dayEnd " +
            "ORDER BY priority, dueAt"
    )
    fun observeTodayOneShot(dayEnd: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE repeatRule != 0 AND status = 0 ORDER BY priority")
    fun observeRepeatTemplates(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE status = 1 ORDER BY completedAt")
    fun observeDoneTasks(): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM task WHERE status = 1 AND completedAt BETWEEN :dayStart AND :dayEnd " +
            "ORDER BY completedAt"
    )
    fun observeDoneBetween(dayStart: Long, dayEnd: Long): Flow<List<TaskEntity>>

    @Query(
        "SELECT * FROM task WHERE status = 0 AND dueAt IS NOT NULL AND dueAt BETWEEN :from AND :to " +
            "ORDER BY dueAt, priority"
    )
    fun observeDueBetween(from: Long, to: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM task ORDER BY id")
    suspend fun getAllTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE task SET status = 1, completedAt = :at, updatedAt = :at, dirty = 1 WHERE id = :id")
    suspend fun complete(id: Long, at: Long)

    @Query("UPDATE task SET status = 0, completedAt = NULL, updatedAt = :at, dirty = 1 WHERE id = :id")
    suspend fun uncomplete(id: Long, at: Long)

    @Query(
        "UPDATE task SET dueAt = :newDue, postponeCount = postponeCount + 1, " +
            "updatedAt = :now, dirty = 1 WHERE id = :id"
    )
    suspend fun postpone(id: Long, newDue: Long, now: Long)

    @Query(
        "UPDATE task SET dueAt = :oldDue, " +
            "postponeCount = MAX(postponeCount - 1, 0), updatedAt = :now, dirty = 1 WHERE id = :id"
    )
    suspend fun undoPostpone(id: Long, oldDue: Long?, now: Long)

    @Query(
        "UPDATE task SET completedPomodoros = completedPomodoros + 1, " +
            "updatedAt = :now, dirty = 1 WHERE id = :id"
    )
    suspend fun incrementPomodoro(id: Long, now: Long)

    /** 取消勾选重复模板的当日完成:删除其实例(不产生幽灵待办) */
    @Query(
        "DELETE FROM task WHERE repeatParentId = :parentId AND completedAt BETWEEN :from AND :to"
    )
    suspend fun deleteRepeatDoneInstance(parentId: Long, from: Long, to: Long)

    /** 重复模板当日完成实例(删除前取出来打墓碑,供云同步传播删除) */
    @Query(
        "SELECT * FROM task WHERE repeatParentId = :parentId AND completedAt BETWEEN :from AND :to"
    )
    suspend fun getRepeatDoneInstances(parentId: Long, from: Long, to: Long): List<TaskEntity>

    @Query("UPDATE task SET status = 3 WHERE id = :id")
    suspend fun drop(id: Long)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM task")
    suspend fun deleteAll()

    // ---------- 云同步 ----------

    @Query("SELECT * FROM task WHERE clientGuid = :guid LIMIT 1")
    suspend fun getByClientGuid(guid: String): TaskEntity?

    @Query("SELECT * FROM task WHERE dirty = 1")
    suspend fun getDirty(): List<TaskEntity>

    @Query("UPDATE task SET dirty = 0 WHERE clientGuid IN (:guids)")
    suspend fun markClean(guids: List<String>)

    @Query("DELETE FROM task WHERE clientGuid = :guid")
    suspend fun deleteByClientGuid(guid: String)

    @Query(
        "UPDATE task SET clientGuid = lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
            "substr(hex(randomblob(2)),2)||'-'||" +
            "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
            "hex(randomblob(6))) WHERE clientGuid = ''"
    )
    suspend fun fillEmptyGuids()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: PomodoroSessionEntity): Long

    @Update
    suspend fun update(session: PomodoroSessionEntity)

    @Delete
    suspend fun delete(session: PomodoroSessionEntity)

    @Query("DELETE FROM pomodoro_session")
    suspend fun deleteAll()

    @Query(
        "SELECT COALESCE(SUM(durationMin), 0) FROM pomodoro_session " +
            "WHERE valid = 1 AND startedAt BETWEEN :from AND :to"
    )
    fun observeDurationMin(from: Long, to: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM pomodoro_session WHERE valid = 1 AND startedAt BETWEEN :from AND :to"
    )
    fun observeCount(from: Long, to: Long): Flow<Int>

    @Query(
        "SELECT subjectId AS subjectId, SUM(durationMin) AS totalMin FROM pomodoro_session " +
            "WHERE valid = 1 AND startedAt BETWEEN :from AND :to AND subjectId IS NOT NULL " +
            "GROUP BY subjectId"
    )
    fun observePerSubject(from: Long, to: Long): Flow<List<SubjectDuration>>

    @Query(
        "SELECT date(startedAt / 1000, 'unixepoch', 'localtime') AS day, " +
            "SUM(durationMin) AS totalMin FROM pomodoro_session " +
            "WHERE valid = 1 AND startedAt BETWEEN :from AND :to " +
            "GROUP BY day ORDER BY day"
    )
    fun observeDailyTrend(from: Long, to: Long): Flow<List<DayDuration>>

    @Query(
        "SELECT CAST(strftime('%H', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, " +
            "SUM(durationMin) AS totalMin " +
            "FROM pomodoro_session WHERE valid = 1 AND startedAt BETWEEN :from AND :to " +
            "GROUP BY hour ORDER BY hour"
    )
    fun observeHourlyHistogram(from: Long, to: Long): Flow<List<HourDuration>>

    @Query(
        "SELECT DISTINCT date(startedAt / 1000, 'unixepoch', 'localtime') AS day " +
            "FROM pomodoro_session WHERE valid = 1 AND startedAt >= :from ORDER BY day DESC"
    )
    fun observeActiveDays(from: Long): Flow<List<String>>

    @Query(
        "SELECT * FROM pomodoro_session WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt"
    )
    suspend fun getBetween(from: Long, to: Long): List<PomodoroSessionEntity>

    @Query("SELECT * FROM pomodoro_session WHERE taskId = :taskId ORDER BY startedAt DESC")
    suspend fun getForTask(taskId: Long): List<PomodoroSessionEntity>

    @Query(
        "SELECT COUNT(*) FROM pomodoro_session " +
            "WHERE taskId = :taskId AND valid = 1 AND startedAt >= :from"
    )
    fun observeCountForTaskSince(taskId: Long, from: Long): Flow<Int>

    @Query("SELECT * FROM pomodoro_session ORDER BY startedAt DESC")
    suspend fun getAll(): List<PomodoroSessionEntity>

    @Query("SELECT * FROM pomodoro_session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<PomodoroSessionEntity>>

    // ---------- 云同步 ----------

    @Query("SELECT * FROM pomodoro_session WHERE clientGuid = :guid LIMIT 1")
    suspend fun getByClientGuid(guid: String): PomodoroSessionEntity?

    @Query("SELECT * FROM pomodoro_session WHERE dirty = 1")
    suspend fun getDirty(): List<PomodoroSessionEntity>

    @Query("UPDATE pomodoro_session SET dirty = 0 WHERE clientGuid IN (:guids)")
    suspend fun markClean(guids: List<String>)

    @Query("DELETE FROM pomodoro_session WHERE clientGuid = :guid")
    suspend fun deleteByClientGuid(guid: String)

    @Query(
        "UPDATE pomodoro_session SET clientGuid = lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
            "substr(hex(randomblob(2)),2)||'-'||" +
            "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
            "hex(randomblob(6))) WHERE clientGuid = ''"
    )
    suspend fun fillEmptyGuids()
}

data class SubjectDuration(val subjectId: Long, val totalMin: Int)
data class DayDuration(val day: String, val totalMin: Int)
data class HourDuration(val hour: Int, val totalMin: Int)

@Dao
interface DailyReviewDao {
    @Query("SELECT * FROM daily_review WHERE epochDay = :epochDay")
    fun observeByDay(epochDay: Int): Flow<DailyReviewEntity?>

    @Query("SELECT * FROM daily_review WHERE epochDay = :epochDay")
    suspend fun getByDay(epochDay: Int): DailyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: DailyReviewEntity)

    @Query("SELECT * FROM daily_review ORDER BY epochDay")
    suspend fun getAll(): List<DailyReviewEntity>

    @Query("DELETE FROM daily_review")
    suspend fun deleteAll()

    @Query("SELECT * FROM daily_review ORDER BY epochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyReviewEntity>>

    // ---------- 云同步(自然键 epochDay,无 clientGuid) ----------

    @Query("SELECT * FROM daily_review WHERE dirty = 1")
    suspend fun getDirty(): List<DailyReviewEntity>

    @Query("UPDATE daily_review SET dirty = 0 WHERE epochDay IN (:keys)")
    suspend fun markClean(keys: List<Int>)
}

@Dao
interface WeeklyReviewDao {
    @Query("SELECT * FROM weekly_review WHERE weekStartEpochDay = :weekStartEpochDay")
    fun observeByWeek(weekStartEpochDay: Int): Flow<WeeklyReviewEntity?>

    @Query("SELECT * FROM weekly_review WHERE weekStartEpochDay = :weekStartEpochDay")
    suspend fun getByWeek(weekStartEpochDay: Int): WeeklyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: WeeklyReviewEntity)

    @Query("SELECT * FROM weekly_review ORDER BY weekStartEpochDay")
    suspend fun getAll(): List<WeeklyReviewEntity>

    @Query("DELETE FROM weekly_review")
    suspend fun deleteAll()

    @Query("SELECT * FROM weekly_review ORDER BY weekStartEpochDay DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WeeklyReviewEntity>>

    // ---------- 云同步(自然键 weekStartEpochDay,无 clientGuid) ----------

    @Query("SELECT * FROM weekly_review WHERE dirty = 1")
    suspend fun getDirty(): List<WeeklyReviewEntity>

    @Query("UPDATE weekly_review SET dirty = 0 WHERE weekStartEpochDay IN (:keys)")
    suspend fun markClean(keys: List<Int>)
}

/** 云同步墓碑:本地删除的行,推送时转成 isDeleted=true 行上云 */
@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SyncTombstoneEntity>)

    @Query("SELECT * FROM sync_tombstone")
    suspend fun getAll(): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstone WHERE clientGuid IN (:guids)")
    suspend fun deleteByGuids(guids: List<String>)

    @Query("DELETE FROM sync_tombstone")
    suspend fun deleteAll()
}

@Dao
interface MonthlyReviewDao {
    @Query("SELECT * FROM monthly_review WHERE monthStartEpochDay = :monthStartEpochDay")
    fun observeByMonth(monthStartEpochDay: Int): Flow<MonthlyReviewEntity?>

    @Query("SELECT * FROM monthly_review WHERE monthStartEpochDay = :monthStartEpochDay")
    suspend fun getByMonth(monthStartEpochDay: Int): MonthlyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: MonthlyReviewEntity)

    @Query("SELECT * FROM monthly_review ORDER BY monthStartEpochDay")
    suspend fun getAll(): List<MonthlyReviewEntity>

    @Query("SELECT * FROM monthly_review WHERE dirty = 1")
    suspend fun getDirty(): List<MonthlyReviewEntity>

    @Query("UPDATE monthly_review SET dirty = 0 WHERE monthStartEpochDay IN (:keys)")
    suspend fun markClean(keys: List<Int>)

    @Query("DELETE FROM monthly_review")
    suspend fun deleteAll()
}
