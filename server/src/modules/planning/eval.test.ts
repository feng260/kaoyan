import test from 'node:test'
import assert from 'node:assert/strict'
import { runSampleEval } from './eval'

test('eval: 样例计划没有硬错误、时段齐全、周不重复、科目配额不漂移', () => {
  const metrics = runSampleEval(180)
  assert.equal(metrics.errorCount, 0)
  assert.equal(metrics.slotCoverage, 1)
  assert.equal(metrics.repeatedWeeks, 0)
  assert.ok(metrics.utilization >= 0.7, `utilization=${metrics.utilization}`)
  for (const subject of metrics.subjects) {
    assert.ok(Math.abs(subject.deviation) <= 0.4,
      `${subject.subject} 配额偏离 ${subject.deviation}`)
  }
})
