/**
 * Phase 0 端到端 API 冒烟(不依赖测试框架,只用 Node 内置 fetch)。
 *
 * 覆盖一条完整用户旅程:
 *   健康检查 → 协议门禁 → 注册 → 登录 → 未授权拦截 → 空档案 → 无档案生成计划报错
 *   → 提交档案 → 非法考试日期被拒 → 生成计划 → 阶段/预算/薄弱科目校验
 *   → 读取 active plan → 导出脱敏 → 注销确认门禁 → 注销 → 旧 token 失效
 *
 * 用法:
 *   npm run smoke:api                      # 默认打本机 http://127.0.0.1:3000
 *   SMOKE_BASE=https://api.example.com npm run smoke:api   # 切流后打线上域名
 *
 * 注意:登录/注册有限流(5 次/15 分钟,按 IP 分桶),反复重跑会撞 429。
 * 本机重跑前重启一次服务即可清零计数;线上冒烟请确认自己没占满配额。
 */
const BASE = process.env.SMOKE_BASE ?? 'http://127.0.0.1:3000'
const API = `${BASE}/api/v1`

const results = []
function record(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  :: ' + detail : ''}`)
}

async function call(method, path, { token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${API}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* 非 JSON 响应 */ }
  return { status: res.status, json, text, headers: res.headers }
}

const stamp = Date.now()
const username = `smoke_${stamp}`
const password = 'SmokePass123!'
const deviceGuid = '11111111-2222-3333-4444-555555555555'

function futureDate(days) {
  const d = new Date(Date.now() + days * 86400000)
  return d.toISOString().slice(0, 10)
}

// 排计划容量完全由课表净空闲决定:每天 19:00-22:00 共 180 分钟,无固定占用
const DAILY_WINDOW_MINUTES = 180
const availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '19:00', end: '22:00' }] }))

const profileBody = {
  targetType: '考研',
  examDate: futureDate(200),
  studyWindows: ['上午', '晚上'],
  foundation: '一般',
  weakSubjects: ['数学', '英语'],
  availability,
  fixedCommitments: [],
  availabilityConfirmed: true,
  commitmentsConfirmed: true,
}

async function main() {
  // 0. 健康检查
  const health = await fetch(`${BASE}/healthz`)
  const healthJson = await health.json()
  record('健康检查 /healthz', health.status === 200 && healthJson.ok === true, `status=${health.status}`)

  // 1. 未勾选协议注册应被拒绝
  const noConsent = await call('POST', '/auth/register', {
    body: { username, password, email: null, termsAccepted: false, privacyAccepted: true },
  })
  record('未同意协议注册被拒(LEGAL_CONSENT_REQUIRED)',
    noConsent.status === 400 && noConsent.json?.error === 'LEGAL_CONSENT_REQUIRED',
    `status=${noConsent.status} error=${noConsent.json?.error}`)

  // 2. 勾选协议后注册成功
  const reg = await call('POST', '/auth/register', {
    body: { username, password, email: null, termsAccepted: true, privacyAccepted: true },
  })
  record('同意协议后注册成功', reg.status === 201 && !!reg.json?.user?.guid,
    `status=${reg.status} guid=${reg.json?.user?.guid}`)

  // 3. 登录拿 access token
  const login = await call('POST', '/auth/login', {
    body: { username, password, deviceGuid, deviceName: 'SmokeDevice', platform: 'android' },
  })
  const access = login.json?.tokens?.access
  record('登录返回 access token', login.status === 200 && typeof access === 'string' && access.length > 20,
    `status=${login.status}`)
  if (!access) throw new Error('缺少 access token,后续用例无法继续(若为 429 请先重启服务或等限流窗口结束)')

  // 3b. 无 token 访问受保护接口应 401
  const noAuth = await call('GET', '/profile')
  record('无 token 访问 /profile 返回 401', noAuth.status === 401, `status=${noAuth.status}`)

  // 4. 首次读取档案为 null
  const profileEmpty = await call('GET', '/profile', { token: access })
  record('首次读取档案为空', profileEmpty.status === 200 && profileEmpty.json?.profile === null,
    `profile=${JSON.stringify(profileEmpty.json?.profile)}`)

  // 5. 没有档案时生成计划应报 PROFILE_INCOMPLETE
  const genNoProfile = await call('POST', '/plans/generate', { token: access })
  record('无档案生成计划报错', genNoProfile.status === 400, `status=${genNoProfile.status} error=${genNoProfile.json?.error}`)

  // 6. 写入档案
  const put = await call('PUT', '/profile', { token: access, body: profileBody })
  record('提交备考档案成功',
    put.status === 200 && put.json?.profile?.examDate === profileBody.examDate,
    `status=${put.status}`)

  // 6b. 非法考试日期应报 INVALID_EXAM_DATE
  const badDate = await call('PUT', '/profile', {
    token: access,
    body: { ...profileBody, examDate: futureDate(-5) },
  })
  record('过期考试日期被拒(INVALID_EXAM_DATE)',
    badDate.status === 400 && badDate.json?.error === 'INVALID_EXAM_DATE',
    `status=${badDate.status} error=${badDate.json?.error}`)

  // 7. 生成计划
  const gen = await call('POST', '/plans/generate', { token: access })
  const plan = gen.json?.plan
  const stages = plan?.stages ?? []
  record('生成计划返回 201 与阶段',
    gen.status === 201 && stages.length >= 1,
    `status=${gen.status} stages=${stages.length}`)
  console.log(`       阶段: ${stages.map(s => s.name ?? s.stage).join('/')}`)

  // 7b. 阶段应覆盖完整备考窗口
  const covered = stages.length > 0 && stages[0]?.startDate && stages[stages.length - 1]?.endDate
  record('阶段覆盖备考窗口', !!covered,
    `${String(stages[0]?.startDate).slice(0, 10)} → ${String(stages[stages.length - 1]?.endDate).slice(0, 10)}`)

  // 7c. 每日计划项时长合计不超过每日预算
  // 注意结构:计划项挂在 plan.items(带 stageId),stages 里没有 items;字段名是 minutes。
  const items = plan?.items ?? []
  const minutesByDay = new Map()
  for (const item of items) {
    const day = item?.planDate
    minutesByDay.set(day, (minutesByDay.get(day) ?? 0) + Number(item?.minutes ?? 0))
  }
  const overBudget = [...minutesByDay.entries()].filter(([, sum]) => sum > DAILY_WINDOW_MINUTES)
  record('每日计划项不超预算', items.length > 0 && overBudget.length === 0,
    `items=${items.length} days=${minutesByDay.size} over=${overBudget.length}`)

  // 7d. 薄弱科目全覆盖
  const plannedSubjects = new Set(items.map(i => i?.subject))
  const missingWeak = profileBody.weakSubjects.filter(s => !plannedSubjects.has(s))
  record('薄弱科目均被计划覆盖', missingWeak.length === 0,
    `weak=${profileBody.weakSubjects.join(',')} missing=${missingWeak.join(',') || '无'}`)

  // 8. 读取 active plan
  const active = await call('GET', '/plans/active', { token: access })
  record('读取 active plan 成功',
    active.status === 200 && !!active.json?.plan?.id,
    `planId=${active.json?.plan?.id}`)

  // 9. 导出数据且不泄露敏感字段
  const exp = await call('GET', '/account/export', { token: access })
  const leak = /passwordHash|resetTokenHash/.test(exp.text)
  record('导出包含账号与档案', exp.status === 200 && !!exp.json?.account?.username && !!exp.json?.profile,
    `status=${exp.status}`)
  record('导出不含密码哈希', !leak, leak ? '发现敏感字段' : '已脱敏')
  record('导出带协议版本与同意时间',
    !!exp.json?.policy?.version && !!exp.json?.account?.termsAcceptedAt,
    `policy=${exp.json?.policy?.version}`)

  // 10. 注销确认不匹配应被拒
  const badConfirm = await call('POST', '/account/delete', {
    token: access,
    body: { confirm: 'wrong-name', password },
  })
  record('注销确认名不匹配被拒(CONFIRM_MISMATCH)',
    badConfirm.status === 400 && badConfirm.json?.error === 'CONFIRM_MISMATCH',
    `status=${badConfirm.status} error=${badConfirm.json?.error}`)

  // 11. 正确确认后注销
  const del = await call('POST', '/account/delete', { token: access, body: { confirm: username, password } })
  record('注销账号成功', del.status === 200 && del.json?.deleted >= 1, `deleted=${del.json?.deleted}`)

  // 12. 注销后旧 token 立即失效
  const afterDel = await call('GET', '/profile', { token: access })
  record('注销后旧 token 失效', afterDel.status === 401, `status=${afterDel.status}`)

  // 13. 注销后无法再登录
  const relogin = await call('POST', '/auth/login', {
    body: { username, password, deviceGuid, deviceName: 'SmokeDevice', platform: 'android' },
  })
  record('注销后无法登录', relogin.status === 401, `status=${relogin.status}`)

  const failed = results.filter(r => !r.ok)
  console.log(`\n===== 冒烟结果: ${results.length - failed.length}/${results.length} 通过 =====`)
  if (failed.length) {
    console.log('失败项:')
    failed.forEach(f => console.log(`  - ${f.name} :: ${f.detail}`))
    process.exitCode = 1
  }
}

main().catch(err => {
  console.error('冒烟脚本异常:', err.message)
  process.exitCode = 1
})
