import Router from '@koa/router'
import path from 'path'
import fs from 'fs'
import { serveStatic } from './static'

/**
 * API 文档:/docs(swagger-ui)/openapi.yaml(规范文件)。
 * 静态服务不依赖 koa-static(避免间接 Express 生态),自实现只读文件服务。
 */
const router = new Router()

// tsx dev 与编译后 dist 的 __dirname 层级不同,统一用 cwd(= server/)解析,两种模式都正确
const docsDir = path.resolve(process.cwd(), 'docs')
const swaggerDist = path.resolve(process.cwd(), 'node_modules/swagger-ui-dist')

router.get('/docs', ctx => {
  ctx.type = 'html'
  // 页面初始化脚本是内联的:覆盖 helmet 默认 CSP(script-src 'self' 会静默拦截内联脚本,
  // 表现为 Swagger 白屏),仅此页面放宽 inline
  ctx.set('Content-Security-Policy',
    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")
  ctx.body = `<!DOCTYPE html>
<html><head><title>研钟 API</title>
<link rel="stylesheet" href="/docs/swagger-ui.css"></head>
<body><div id="swagger-ui"></div>
<script src="/docs/swagger-ui-bundle.js"></script>
<script>SwaggerUIBundle({ url: '/openapi.yaml', dom_id: '#swagger-ui' })</script>
</body></html>`
})

router.get('/openapi.yaml', ctx => {
  const file = path.join(docsDir, 'openapi.yaml')
  if (!fs.existsSync(file)) { ctx.status = 404; return }
  ctx.type = 'text/yaml' // koa type 只接受 mime 简称,自动补 charset
  ctx.body = fs.createReadStream(file)
})

// swagger-ui 静态资源:/docs/swagger-ui.css 等
router.get('/docs/:file', ctx => {
  serveStatic(ctx, swaggerDist, ctx.params.file)
})

export default router
