import { Context } from 'koa'
import fs from 'fs'
import path from 'path'

const MIME: Record<string, string> = {
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.map': 'application/json',
  '.png': 'image/png',
  '.yaml': 'text/yaml; charset=utf-8',
}

/** 只读静态文件服务:仅服务基准目录内的常规文件,路径穿越防护 */
export function serveStatic(ctx: Context, baseDir: string, file: string) {
  const base = path.resolve(baseDir)
  const target = path.resolve(base, file)
  // 规范化后必须仍位于 base 内(防 ../ 及编码斜杠穿越)
  if (target !== base && !target.startsWith(base + path.sep)) { ctx.status = 403; return }
  if (!fs.existsSync(target) || !fs.statSync(target).isFile()) { ctx.status = 404; return }
  // 直接 set Content-Type(带 charset 的完整值;koa 的 ctx.type 不接受带参数写法)
  ctx.set('Content-Type', MIME[path.extname(target).toLowerCase()] ?? 'application/octet-stream')
  ctx.body = fs.createReadStream(target)
}
