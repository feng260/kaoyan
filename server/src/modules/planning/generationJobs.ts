import { randomUUID } from 'crypto'
import type { PlanProgress } from './aiGenerator'

/**
 * 计划生成任务(进程内)。
 *
 * 生成一份 400+ 天的计划要跑骨架、并行细化、校验/修复、评审与文档,
 * 一次 HTTP 请求等上两三分钟既容易被网关掐断,客户端也没法告诉用户「走到哪一步了」。
 * 这里把生成改成后台任务:POST 立刻返回 jobId,GET 轮询状态,
 * 同时通过 WebSocket 推送 planGeneration 事件,前端可以只靠推送更新进度。
 *
 * 刻意不引入数据库/队列:生成是单实例、短时任务,进程重启后丢失任务可接受
 * (客户端轮询会收到 404,重新发起即可)。多实例部署时应换成共享队列。
 */

export type PlanGenerationJobStatus = 'running' | 'done' | 'failed'

export type PlanGenerationJob = {
  id: string
  userGuid: string
  status: PlanGenerationJobStatus
  progress: PlanProgress
  /** 成功后为草稿计划 id */
  planId?: number
  /** 失败时的业务错误码与面向用户的错误信息 */
  error?: { code: string; message: string }
  /** 从今天到考前的天数;客户端据此估算剩余时间(0 = 旧任务/未知) */
  horizonDays: number
  createdAt: number
  updatedAt: number
}

const JOB_TTL_MS = 30 * 60_000

export class GenerationJobStore {
  private jobs = new Map<string, PlanGenerationJob>()

  create(userGuid: string, horizonDays = 0): PlanGenerationJob {
    this.prune()
    const now = Date.now()
    const job: PlanGenerationJob = {
      id: randomUUID(),
      userGuid,
      status: 'running',
      progress: { percent: 0, phase: 'skeleton', message: '任务已创建,等待开始' },
      horizonDays: Math.max(0, Math.round(horizonDays)),
      createdAt: now,
      updatedAt: now,
    }
    this.jobs.set(job.id, job)
    return job
  }

  get(userGuid: string, id: string): PlanGenerationJob | null {
    const job = this.jobs.get(id)
    return job && job.userGuid === userGuid ? { ...job } : null
  }

  /** 同一用户正在生成时复用任务:连点两次「生成」不应产出两份草稿 */
  runningFor(userGuid: string): PlanGenerationJob | null {
    for (const job of this.jobs.values()) {
      if (job.userGuid === userGuid && job.status === 'running') return { ...job }
    }
    return null
  }

  update(id: string, patch: Partial<PlanGenerationJob>): PlanGenerationJob | null {
    const job = this.jobs.get(id)
    if (!job) return null
    Object.assign(job, patch, { updatedAt: Date.now() })
    if (patch.progress) job.progress = patch.progress
    return { ...job }
  }

  private prune(): void {
    const threshold = Date.now() - JOB_TTL_MS
    for (const [id, job] of this.jobs) {
      if (job.status !== 'running' && job.updatedAt < threshold) this.jobs.delete(id)
    }
  }
}
