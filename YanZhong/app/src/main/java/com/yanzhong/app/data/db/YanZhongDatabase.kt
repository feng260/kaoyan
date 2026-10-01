package com.yanzhong.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.ZoneId
import java.time.ZonedDateTime

@Database(
    entities = [
        SubjectEntity::class,
        CountdownNodeEntity::class,
        TaskEntity::class,
        PomodoroSessionEntity::class,
        DailyReviewEntity::class,
        WeeklyReviewEntity::class,
        MonthlyReviewEntity::class,
        SyncTombstoneEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class YanZhongDatabase : RoomDatabase() {
    abstract fun subjectDao(): SubjectDao
    abstract fun countdownNodeDao(): CountdownNodeDao
    abstract fun taskDao(): TaskDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailyReviewDao(): DailyReviewDao
    abstract fun weeklyReviewDao(): WeeklyReviewDao
    abstract fun monthlyReviewDao(): MonthlyReviewDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {
        @Volatile
        private var instance: YanZhongDatabase? = null

        fun get(context: Context): YanZhongDatabase = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context) = Room.databaseBuilder(
            context.applicationContext,
            YanZhongDatabase::class.java,
            "yanzhong.db"
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    seed(db)
                }
            })
            .build()

        /** v2:任务已完成番茄数 + 专注记录放弃原因 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task ADD COLUMN completedPomodoros INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pomodoro_session ADD COLUMN abandonReason TEXT")
            }
        }

        /** v3:每日复盘三问 + 周复盘(作战计划的雷打不动动作落地) */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_review (" +
                        "epochDay INTEGER NOT NULL PRIMARY KEY, " +
                        "q1Done TEXT NOT NULL, " +
                        "q2Weak TEXT NOT NULL, " +
                        "q3Tomorrow TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS weekly_review (" +
                        "weekStartEpochDay INTEGER NOT NULL PRIMARY KEY, " +
                        "weakPoints TEXT NOT NULL, " +
                        "nextWeekTop1 TEXT NOT NULL, " +
                        "nextWeekTop2 TEXT NOT NULL, " +
                        "nextWeekTop3 TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v4:重复模板完成实例的来源标记(「今日已完成」过滤 + 取消勾选回滚) */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task ADD COLUMN repeatParentId INTEGER")
            }
        }

        /** v5:月复盘(作战计划:月底一次,月初 epochDay 为键) */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS monthly_review (" +
                        "monthStartEpochDay INTEGER NOT NULL PRIMARY KEY, " +
                        "summary TEXT NOT NULL, " +
                        "nextMonthTop1 TEXT NOT NULL, " +
                        "nextMonthTop2 TEXT NOT NULL, " +
                        "nextMonthTop3 TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v6:云同步三列(clientGuid/updatedAt/dirty)+ 墓碑表。
         * 存量行回填 36 位 UUID(SQL 内联生成 v4 格式)与可信时间戳,
         * dirty 默认 1 = 首次同步时全量推一遍,避免遗漏本地存量数据。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val uuid =
                    "lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
                        "substr(hex(randomblob(2)),2)||'-'||" +
                        "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
                        "hex(randomblob(6)))"
                val now = "CAST(strftime('%s','now') AS INTEGER)*1000"
                listOf("subject", "countdown_node", "task", "pomodoro_session").forEach { t ->
                    db.execSQL("ALTER TABLE $t ADD COLUMN clientGuid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $t ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $t ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("UPDATE $t SET clientGuid = $uuid WHERE clientGuid = ''")
                }
                db.execSQL("UPDATE task SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("UPDATE pomodoro_session SET updatedAt = startedAt WHERE updatedAt = 0")
                db.execSQL("UPDATE subject SET updatedAt = $now WHERE updatedAt = 0")
                db.execSQL("UPDATE countdown_node SET updatedAt = $now WHERE updatedAt = 0")
                listOf("daily_review", "weekly_review", "monthly_review").forEach { t ->
                    db.execSQL("ALTER TABLE $t ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_tombstone (" +
                        "clientGuid TEXT NOT NULL PRIMARY KEY, " +
                        "resource TEXT NOT NULL, " +
                        "deletedAt INTEGER NOT NULL)"
                )
            }
        }

        /** v7:计划投影来源键;存量任务保持手工任务语义。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task ADD COLUMN accountGuid TEXT")
                db.execSQL("ALTER TABLE task ADD COLUMN planId INTEGER")
                db.execSQL("ALTER TABLE task ADD COLUMN planItemId INTEGER")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_task_accountGuid_planId_planItemId " +
                        "ON task (accountGuid, planId, planItemId)"
                )
            }
        }

        /** v8:内置参考计划包标记;存量行默认非内置,升级后由计划包补导(PLAN_PACK_VERSION)回填。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task ADD COLUMN builtin INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 预置 2028 考研初试置顶节点(PRD 3.3);clientGuid 内联生成,首启即可被云同步推送。
         *
         * 刻意**不再种入预置科目**(原 PRD 3.1 的考研四科):科目是用户选了考公还是考研、
         * 法考还是专升本的核心表达,替用户预置 408 四科曾导致问卷科目选项与
         * 「我的」页科目标志永远是考研那几项。科目完全由问卷确认结果决定
         * (OnboardingViewModel 保存时经 StudyRepository.syncLocalSubjects 创建)。
         * 节点名与日期也会在问卷保存时被 syncExamCountdown 改成用户自己的目标与考期。
         */
        private fun seed(db: SupportSQLiteDatabase) {
            val uuid =
                "lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||" +
                    "substr(hex(randomblob(2)),2)||'-'||" +
                    "substr('89ab',1+abs(random())%4,1)||substr(hex(randomblob(2)),2)||'-'||" +
                    "hex(randomblob(6)))"
            val now = System.currentTimeMillis()
            val exam = ZonedDateTime.of(2027, 12, 18, 8, 30, 0, 0, ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            db.execSQL(
                "INSERT INTO countdown_node (name, type, targetAt, pinned, sort, clientGuid, updatedAt, dirty) " +
                    "VALUES ('2028 考研初试', 0, $exam, 1, 0, $uuid, $now, 1)"
            )
        }
    }
}
