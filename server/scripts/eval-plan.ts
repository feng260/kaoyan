/**
 * 计划质量评测:不需要模型,用样例输入跑一遍 buildPlanSpec → compilePlanSpec。
 *
 *   npm run eval:plan            # 默认 468 天
 *   PLAN_DAYS=180 npm run eval:plan
 *
 * 关注指标:
 * - utilization:排入分钟 / 真实空闲,过低说明计划太薄
 * - subjects[].deviation:科目配额偏离,超过 ±0.4 要警惕
 * - repeatedWeeks:完全相同的周,长阶段应接近 0
 * - slotCoverage:带开始时刻的任务占比,新编译器应恒为 1
 * - errorCount:确定性校验的硬错误,上线前必须为 0
 */
import { runSampleEval } from '../src/modules/planning/eval'

const days = Number(process.env.PLAN_DAYS ?? 468)
if (!Number.isFinite(days) || days < 30) {
  console.error('PLAN_DAYS 必须是 >= 30 的整数')
  process.exit(1)
}

const metrics = runSampleEval(Math.round(days))
console.log(JSON.stringify(metrics, null, 2))

const subjectDrift = metrics.subjects.filter(subject => Math.abs(subject.deviation) > 0.4)
if (metrics.errorCount > 0 || metrics.slotCoverage < 1 || subjectDrift.length > 0) {
  console.error('\n评测未通过:存在硬错误、缺失时段或科目配额严重偏离')
  process.exit(1)
}
