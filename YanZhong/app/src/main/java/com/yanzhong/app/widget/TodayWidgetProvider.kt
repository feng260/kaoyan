package com.yanzhong.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.yanzhong.app.MainActivity
import com.yanzhong.app.R
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 桌面小组件:今日番茄 / 待办数 / 距置顶节点倒计时,点击进入 App。30 分钟自动刷新。 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        val app = context.applicationContext as YanZhongApp
        app.appScope.launch {
            try {
                val views = buildTodayViews(context, app)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (_: Exception) {
                // 数据库未就绪等情况:保留旧内容,下个刷新周期重试
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 由 App 内数据变化(番茄落库等)时主动刷新所有实例 */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TodayWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val app = context.applicationContext as YanZhongApp
            app.appScope.launch {
                try {
                    val views = buildTodayViews(context, app)
                    ids.forEach { manager.updateAppWidget(it, views) }
                } catch (_: Exception) {
                }
            }
        }
    }
}

private suspend fun buildTodayViews(context: Context, app: YanZhongApp): RemoteViews {
    val now = System.currentTimeMillis()
    val dayStart = TimeUtils.dayStartOf(now)
    val dayEnd = TimeUtils.dayEndOf(now)
    val pomodoros = app.repository.observeCount(dayStart, dayEnd).first()
    val openCount = app.repository.observeTodayView().first().open.size
    val node = app.repository.observePinnedNode().first()
        ?: app.repository.allNodes().firstOrNull()
    val countdown = node?.let { n ->
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val target = Instant.ofEpochMilli(n.targetAt).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, target)
        "距「${n.name}」${if (days <= 0) 0 else days} 天"
    } ?: "添加倒计时节点"

    val views = RemoteViews(context.packageName, R.layout.widget_today)
    views.setTextViewText(R.id.widget_pomodoros, "$pomodoros 🍅")
    views.setTextViewText(R.id.widget_open_count, "待办 $openCount 项")
    views.setTextViewText(R.id.widget_countdown, countdown)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pi = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    views.setOnClickPendingIntent(R.id.widget_root, pi)
    return views
}