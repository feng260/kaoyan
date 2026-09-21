import Router from '@koa/router'
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import { z } from 'zod'
import { prisma, num } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { rateLimit } from '../../middlewares/ratelimit'
import { env } from '../../config/env'

/**
 * 应用更新分发(自部署 OTA):
 * - POST /api/v1/admin/app/upload   管理员上传 APK(流式落盘 releases/) + 登记
 * - GET  /api/v1/app/latest?current=  最新版本(current < latest → updateAvailable)
 * - GET  /api/v1/app/download/:versionCode  APK 下载(支持 Range 断点续传)
 * 鉴权:管理员通过 X-Admin-Token(env ADMIN_TOKEN)上传;下载/检查公开。
 */
const router = new Router({ prefix: '/api/v1' })

// tsx dev 与 dist 层级不同,统一用 cwd(= server/)解析
const RELEASES_DIR = path.resolve(process.cwd(), 'releases')
if (!fs.existsSync(RELEASES_DIR)) fs.mkdirSync(RELEASES_DIR, { recursive: true })

function adminGuard(ctx: any, next: () => Promise<any>) {
  const token = ctx.get('X-Admin-Token')
  if (!env.adminToken || token !== env.adminToken) {
    throw new ApiError(403, 'FORBIDDEN', '管理员令牌无效')
  }
  return next()
}

/** 管理员上传:multipart 过于复杂,用原始流:PUT APK 二进制 + query 元数据 */
router.put('/admin/app/upload', adminGuard, async ctx => {
  const q = z.object({
    versionCode: z.coerce.number().int(),
    versionName: z.string().min(1).max(32),
    notes: z.string().max(4000).optional().default(''),
    forced: z.coerce.boolean().optional().default(false),
  }).parse(ctx.query)

  const exists = await prisma.appRelease.findUnique({ where: { versionCode: q.versionCode } })
  if (exists) throw new ApiError(409, 'CONFLICT', `versionCode ${q.versionCode} 已存在`)

  // 流式写盘:bodyparser 只处理 JSON content-type,二进制原样进入 ctx.req
  const fileName = `yanzhong-v${q.versionCode}.apk`
  const filePath = path.join(RELEASES_DIR, fileName)
  const hash = crypto.createHash('sha256')
  let size = 0
  await new Promise<void>((resolve, reject) => {
    const out = fs.createWriteStream(filePath)
    ctx.req.on('data', (chunk: Buffer) => { size += chunk.length; hash.update(chunk) })
    ctx.req.pipe(out)
    out.on('finish', () => resolve())
    out.on('error', reject)
    ctx.req.on('error', reject)
  })
  if (size < 1024) {
    fs.rmSync(filePath, { force: true })
    throw new ApiError(400, 'INVALID_PARAMS', '上传内容过小,不是有效 APK')
  }
  await prisma.appRelease.create({
    data: {
      versionCode: q.versionCode,
      versionName: q.versionName,
      fileName,
      fileSize: BigInt(size),
      sha256: hash.digest('hex'),
      notes: q.notes,
      forced: q.forced,
      createdAt: Date.now(),
    },
  })
  ctx.status = 201
  ctx.body = { versionCode: q.versionCode, versionName: q.versionName, fileSize: size }
})

/** 最新版本检查(公开):current=客户端当前 versionCode */
router.get('/app/latest', rateLimit({ name: 'update-check', max: 60, windowMs: 60_000 }), async ctx => {
  const current = Number(ctx.query.current ?? 0)
  const latest = await prisma.appRelease.findFirst({ orderBy: { versionCode: 'desc' } })
  if (!latest) {
    ctx.body = { updateAvailable: false, latest: null }
    return
  }
  ctx.body = {
    updateAvailable: Number.isFinite(current) && current < latest.versionCode,
    latest: {
      versionCode: latest.versionCode,
      versionName: latest.versionName,
      notes: latest.notes.split('\n').filter(Boolean),
      fileSize: num(latest.fileSize),
      sha256: latest.sha256,
      forced: latest.forced && Number.isFinite(current) && current > 0 && current < latest.versionCode,
      downloadUrl: `/api/v1/app/download/${latest.versionCode}`,
    },
  }
})

/** APK 下载(公开,支持 Range 断点续传) */
router.get('/app/download/:versionCode', async ctx => {
  const code = Number(ctx.params.versionCode)
  const release = await prisma.appRelease.findUnique({ where: { versionCode: code } })
  if (!release) throw new ApiError(404, 'NOT_FOUND', '版本不存在')
  const filePath = path.join(RELEASES_DIR, release.fileName)
  if (!fs.existsSync(filePath)) throw new ApiError(404, 'NOT_FOUND', 'APK 文件缺失')
  const stat = fs.statSync(filePath)
  const total = stat.size
  ctx.set('Content-Type', 'application/vnd.android.package-archive')
  ctx.set('Content-Disposition', `attachment; filename="${release.fileName}"`)
  ctx.set('Accept-Ranges', 'bytes')
  const range = ctx.get('Range')
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range)
    const start = m && m[1] ? Number(m[1]) : 0
    const end = m && m[2] ? Math.min(Number(m[2]), total - 1) : total - 1
    if (start >= total || start > end) {
      ctx.status = 416
      ctx.set('Content-Range', `bytes */${total}`)
      return
    }
    ctx.status = 206
    ctx.set('Content-Range', `bytes ${start}-${end}/${total}`)
    ctx.length = end - start + 1
    ctx.body = fs.createReadStream(filePath, { start, end })
  } else {
    ctx.length = total
    ctx.body = fs.createReadStream(filePath)
  }
})

export default router
