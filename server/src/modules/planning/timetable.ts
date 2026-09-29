import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { normalizeBrief, type PlanBrief } from './document'

/**
 * 课表图片识别:把用户拍/截的课程表交给视觉模型,转成「每周固定占用」。
 *
 * 为什么落到 fixedCommitments 而不是直接算出空闲:
 * - netAvailableMinutes 已经按「窗口 - 占用」算净空闲,课表本质上就是「被占掉的时间」;
 * - 空闲窗口仍由问卷里的按星期时段给出,两者叠加即可,不需要给 free-time 再造一套结构;
 * - 单双周课程做保守处理 —— 统一按「每周都占」计入并取并集,label 标注 (单周)/(双周),
 *   宁可少算可用时间,也不能因为漏算占用导致计划排不下。
 */

export type TimetableImage = {
  /** data:image/...;base64,xxx 或 https 链接 */
  image: string
  mimeType?: string
}

export type TimetableResult = {
  /** 给用户看的一句话识别说明 */
  summary: string
  /** 看不清/存疑的地方,让用户知道哪里需要手工补 */
  warnings: string[]
  fixedCommitments: PlanBrief['fixedCommitments']
}

/** 与 koa-bodyparser 的 8mb 上限对齐:base64 膨胀约 1/3,这里留出余量 */
const MAX_IMAGE_CHARS = 7_000_000

const SYSTEM_PROMPT = `你是课程表识别助手。用户会上传一张课程表图片(可能是教务系统截图、Excel 截图或手写课表),
请把它转成结构化 JSON。

识别规则:
1. weekday 用 1–7 表示周一到周日;start/end 用 24 小时制的 HH:mm,不要写「第几节」。
2. 同一门课一周上两次就输出两条。单双周课程按「每周都占」输出一条,并在 label 末尾标注 (单周) 或 (双周);
   无法判断是否单双周的,不要标注。
3. label 写课程名;课程名看不清时写「课程(待确认)」并放进 warnings。
4. 严禁编造:图片里没有的课程、看不清的节次都不要猜,放进 warnings 让用户自己补。
5. 如果图片根本不是课程表,commitments 给空数组,summary 说明看不清是什么。

严格只输出一个 JSON 对象,不要解释文字、不要 Markdown 代码块:
{
  "summary": "一句话说明识别结果,如「识别到 12 条课程占用,其中 2 条单双周已按每周占用处理」",
  "warnings": ["周三 5-6 节课程名模糊,请确认"],
  "commitments": [{"weekday":1,"start":"08:00","end":"09:40","label":"高等数学"}]
}`

/** 归一为视觉模型能吃的 url:裸 base64 补上 data 前缀,超长直接拒绝(别把 8mb 请求打出去) */
function toImageUrl(image: unknown, mimeType?: string): string {
  const raw = String(image ?? '').trim()
  if (!raw) throw new AiUnavailable('没有收到课表图片')
  if (raw.length > MAX_IMAGE_CHARS) throw new AiUnavailable('课表图片太大,请压缩后再上传')
  if (/^https?:\/\//i.test(raw) || raw.startsWith('data:')) return raw
  const mime = mimeType && /^image\/(jpeg|png|webp)$/.test(mimeType) ? mimeType : 'image/jpeg'
  return `data:${mime};base64,${raw}`
}

function clip(value: unknown, max: number): string {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, max)
}

export async function parseTimetableImage(input: TimetableImage): Promise<TimetableResult> {
  const url = toImageUrl(input.image, input.mimeType)

  let content: string
  try {
    content = await chatComplete({
      vision: true,
      json: true,
      temperature: 0.2,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        {
          role: 'user',
          content: [
            { type: 'text', text: '这是我的课程表,请按约定格式识别。' },
            { type: 'image_url', image_url: { url } },
          ],
        },
      ],
    })
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw new AiUnavailable((error as Error)?.message ?? '调用视觉模型失败')
  }

  let parsed: any
  try {
    parsed = extractJson<any>(content)
  } catch (error) {
    throw new AiUnavailable((error as Error)?.message ?? '模型返回内容无法解析为 JSON')
  }

  // 复用面试简报的收敛逻辑:时间格式、weekday 范围、起止先后、label 非空都在那里兜底,
  // 脏数据只会被逐条丢掉,不会带着非法时间进入容量计算。
  const brief = normalizeBrief({
    fixedCommitments: parsed?.commitments,
    commitmentsConfirmed: parsed?.commitments !== undefined,
  })

  const warnings = (Array.isArray(parsed?.warnings) ? parsed.warnings : [])
    .map((item: unknown) => clip(item, 120))
    .filter(Boolean)
    .slice(0, 6)

  return {
    summary: clip(parsed?.summary, 200) || `识别到 ${brief.fixedCommitments.length} 条固定占用`,
    warnings,
    fixedCommitments: brief.fixedCommitments,
  }
}
