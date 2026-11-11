package com.yanzhong.app.ui.guide

import com.yanzhong.app.ui.nav.Routes

enum class GuidePage(val key: String, val route: String) {
    HOME("home", Routes.HOME),
    PLAN("plan", Routes.PLAN),
    FOCUS("focus", Routes.FOCUS),
    STATS("stats", Routes.STATS),
    MINE("mine", Routes.MINE);

    companion object {
        fun fromRoute(route: String?): GuidePage? = entries.firstOrNull { it.route == route }
    }
}

data class GuideStep(val target: String, val title: String, val body: String)

data class GuidePageSpec(val page: GuidePage, val steps: List<GuideStep>)

object GuideAnchors {
    const val NAV_BAR = "nav_bar"
    const val HOME_COUNTDOWN = "home_countdown"
    const val HOME_PROGRESS = "home_progress"
    const val HOME_PLAN_GUIDE = "home_plan_guide"
    const val PLAN_ACTION_CARD = "plan_action_card"
    const val PLAN_HISTORY_ACTION = "plan_history_action"
    const val PLAN_OVERVIEW = "plan_overview"
    const val PLAN_PRIMARY_ACTION = "plan_primary_action"
    const val FOCUS_START = "focus_start"
    const val FOCUS_TASK_ROW = "focus_task_row"
    const val FOCUS_MODE = "focus_mode"
    const val STATS_RANGE = "stats_range"
    const val STATS_KPI = "stats_kpi"
    const val MINE_HEADER = "mine_header"
    const val MINE_ACCOUNT_GROUP = "mine_account_group"
    const val MINE_GUIDE_ROW = "mine_guide_row"

    val ALL: Set<String> = setOf(
        NAV_BAR, HOME_COUNTDOWN, HOME_PROGRESS, HOME_PLAN_GUIDE,
        PLAN_ACTION_CARD, PLAN_HISTORY_ACTION, PLAN_OVERVIEW, PLAN_PRIMARY_ACTION,
        FOCUS_START, FOCUS_TASK_ROW, FOCUS_MODE,
        STATS_RANGE, STATS_KPI,
        MINE_HEADER, MINE_ACCOUNT_GROUP, MINE_GUIDE_ROW
    )
}

fun homeGuide(hasActivePlan: Boolean, tablet: Boolean): GuidePageSpec = GuidePageSpec(
    page = GuidePage.HOME,
    steps = buildList {
        add(
            GuideStep(
                GuideAnchors.HOME_COUNTDOWN,
                "考试倒计时",
                "左右滑可以切换节点；点「添加节点」或「管理」记下模考、报名、复试这些日子。"
            )
        )
        if (hasActivePlan) {
            add(
                GuideStep(
                    GuideAnchors.HOME_PROGRESS,
                    "今日进度",
                    "今天的完成情况和本周目标都在这张卡里。下面的今日待办右滑完成、左滑顺延，点「开始」直接开专注。"
                )
            )
        } else {
            add(
                GuideStep(
                    GuideAnchors.HOME_PLAN_GUIDE,
                    "先有一份计划",
                    "还没有计划时，这里会带你去和 AI 排一份；有了计划，今日待办会按天送到这里。"
                )
            )
        }
        add(
            GuideStep(
                GuideAnchors.NAV_BAR,
                "主入口",
                if (tablet) {
                    "今日、计划、统计、我的在左侧；上面的专注按钮随时开钟。"
                } else {
                    "今日、计划、统计、我的在底部；中间这个按钮随时开专注。"
                }
            )
        )
    }
)

fun planGuide(hasPlan: Boolean): GuidePageSpec = GuidePageSpec(
    page = GuidePage.PLAN,
    steps = if (hasPlan) {
        listOf(
            GuideStep(
                GuideAnchors.PLAN_OVERVIEW,
                "全程计划",
                "当前阶段、总进度和每天的安排都从这张卡往下看。"
            ),
            GuideStep(
                GuideAnchors.PLAN_PRIMARY_ACTION,
                "改档案或调近期",
                "档案变了（科目、课表、目标）用这里重新生成；临时有事就用下面的「调整近期计划」，说一句就重排。"
            )
        )
    } else {
        listOf(
            GuideStep(
                GuideAnchors.PLAN_ACTION_CARD,
                "让 AI 排计划",
                "点「开始制定」，和 AI 聊几分钟，它会按你的档案排一份全程计划。"
            ),
            GuideStep(
                GuideAnchors.PLAN_HISTORY_ACTION,
                "历史版本",
                "右上角可以回看以前生成的计划版本。"
            )
        )
    }
)

fun focusGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.FOCUS,
    steps = listOf(
        GuideStep(
            GuideAnchors.FOCUS_START,
            "直接开始",
            "点这里开一次自由专注，不用先选任务。"
        ),
        GuideStep(
            GuideAnchors.FOCUS_TASK_ROW,
            "记到任务上",
            "想把这次专注记到某个任务上，先「选择任务」，番茄数会记进那个任务。"
        ),
        GuideStep(
            GuideAnchors.FOCUS_MODE,
            "三种计时",
            "番茄钟、正计时、倒计时；番茄方案和更多设置在下面。"
        )
    )
)

fun statsGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.STATS,
    steps = listOf(
        GuideStep(
            GuideAnchors.STATS_RANGE,
            "先选范围",
            "今日、本周、本月、累计，下面的数字跟着变。"
        ),
        GuideStep(
            GuideAnchors.STATS_KPI,
            "四个关键数",
            "净专注、番茄数、任务完成率、连续打卡都在这；往下还有科目占比和长期趋势。"
        )
    )
)

fun mineGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.MINE,
    steps = listOf(
        GuideStep(
            GuideAnchors.MINE_HEADER,
            "个人资料",
            "点头像和昵称可以改个人资料；账号和云端数据在下面这一组。"
        ),
        GuideStep(
            GuideAnchors.MINE_ACCOUNT_GROUP,
            "账号与档案",
            "备考档案变了（科目、课表、目标）从这里改，之后可以重新生成计划。"
        ),
        GuideStep(
            GuideAnchors.MINE_GUIDE_ROW,
            "随时重看",
            "以后想再看这套引导，点这里重置。"
        )
    )
)
