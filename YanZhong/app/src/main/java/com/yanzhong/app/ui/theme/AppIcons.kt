package com.yanzhong.app.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CalendarDays
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.ChartPie
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.ClipboardList
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.CloudUpload
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.DatabaseBackup
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.GraduationCap
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Infinity
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Medal
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.VolumeX
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Repeat
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.Target
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.TimerReset
import com.composables.icons.lucide.TrendingUp
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Trophy
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Vibrate
import com.composables.icons.lucide.X

/**
 * 语义图标统一入口:所有 UI 图标一律通过此对象引用 Lucide 图标库,
 * 避免各处直接引用图标导致风格漂移。
 *
 * 来自 icons-lucide-android(24dp 网格 / 2px 圆头描边),
 * 图标扩展属性为 PascalCase(如 Lucide.Sun),新增图标时先确认库内存在再挂进来。
 */
object AppIcons {
    // ---- 底部导航 Tab ----
    val TabToday: ImageVector = Lucide.Sun
    val TabPlan: ImageVector = Lucide.CalendarDays
    val TabFocus: ImageVector = Lucide.Timer
    val TabStats: ImageVector = Lucide.ChartColumn
    val TabMine: ImageVector = Lucide.User

    // ---- 通用操作 ----
    val Check: ImageVector = Lucide.Check
    val CheckCircle: ImageVector = Lucide.CircleCheck
    val Add: ImageVector = Lucide.Plus
    val Delete: ImageVector = Lucide.Trash2
    val Edit: ImageVector = Lucide.Pencil
    val Play: ImageVector = Lucide.Play
    val Pause: ImageVector = Lucide.Pause
    val Stop: ImageVector = Lucide.Square
    val SkipForward: ImageVector = Lucide.SkipForward
    val Close: ImageVector = Lucide.X
    val Pin: ImageVector = Lucide.Pin
    val Flame: ImageVector = Lucide.Flame
    val Schedule: ImageVector = Lucide.CalendarClock
    val Copy: ImageVector = Lucide.Copy
    val ClipboardList: ImageVector = Lucide.ClipboardList
    val Undo: ImageVector = Lucide.Undo2
    val Search: ImageVector = Lucide.Search
    val Save: ImageVector = Lucide.Save
    val Share: ImageVector = Lucide.Share
    val Upload: ImageVector = Lucide.Upload
    val Download: ImageVector = Lucide.Download
    val Repeat: ImageVector = Lucide.Repeat
    val Calendar: ImageVector = Lucide.CalendarDays
    val InfinityLoop: ImageVector = Lucide.Infinity
    val Vibrate: ImageVector = Lucide.Vibrate
    val Music: ImageVector = Lucide.Music
    val VolumeX: ImageVector = Lucide.VolumeX

    // ---- 展开折叠 / 方向 ----
    val ChevronUp: ImageVector = Lucide.ChevronUp
    val ChevronDown: ImageVector = Lucide.ChevronDown
    val ChevronLeft: ImageVector = Lucide.ChevronLeft
    val ChevronRight: ImageVector = Lucide.ChevronRight
    val ArrowBack: ImageVector = Lucide.ArrowLeft

    // ---- 专注 / 学霸模式 ----
    val Timer: ImageVector = Lucide.Timer
    val TimerReset: ImageVector = Lucide.TimerReset
    val Hourglass: ImageVector = Lucide.Hourglass
    val AlarmClock: ImageVector = Lucide.AlarmClock
    val Lock: ImageVector = Lucide.Lock
    val LockOpen: ImageVector = Lucide.LockOpen
    val Shield: ImageVector = Lucide.Shield
    val ShieldCheck: ImageVector = Lucide.ShieldCheck
    val Smartphone: ImageVector = Lucide.Smartphone
    val AppWindow: ImageVector = Lucide.AppWindow

    // ---- 设置 / 我的 ----
    val Settings: ImageVector = Lucide.Settings
    val ThemeDark: ImageVector = Lucide.Moon
    val ThemeAuto: ImageVector = Lucide.SunMoon
    val Palette: ImageVector = Lucide.Palette
    val CloudUpload: ImageVector = Lucide.CloudUpload
    val CloudDownload: ImageVector = Lucide.CloudDownload
    val DatabaseBackup: ImageVector = Lucide.DatabaseBackup
    val RotateCcw: ImageVector = Lucide.RotateCcw
    val Info: ImageVector = Lucide.Info
    val Key: ImageVector = Lucide.Key
    val GraduationCap: ImageVector = Lucide.GraduationCap
    val Sliders: ImageVector = Lucide.SlidersHorizontal
    val Eye: ImageVector = Lucide.Eye
    val EyeOff: ImageVector = Lucide.EyeOff
    val Keyboard: ImageVector = Lucide.Keyboard

    // ---- 统计 / 目标 ----
    val ChartPie: ImageVector = Lucide.ChartPie
    val TrendingUp: ImageVector = Lucide.TrendingUp
    val Target: ImageVector = Lucide.Target
    val Trophy: ImageVector = Lucide.Trophy
    val Medal: ImageVector = Lucide.Medal
    val Activity: ImageVector = Lucide.Activity
    val Sparkles: ImageVector = Lucide.Sparkles

    // ---- 科目 ----
    val SubjectDefault: ImageVector = Lucide.BookOpen
}
