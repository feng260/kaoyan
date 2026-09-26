import { z } from 'zod'
import { ApiError } from '../../middlewares/error'

/**
 * 注册同意相关的常量、校验与门禁。
 * 刻意不引用 Prisma —— 这样在没有数据库的开发机上也能跑通「不勾选就不给注册」这条测试。
 */

/** 当前生效的协议版本:注册时留时间戳,版本号在协议页和导出里标明 */
export const POLICY_VERSION = '2026-09'
/** 协议生效日期(协议页展示用) */
export const POLICY_EFFECTIVE_DATE = '2026-09-25'

/**
 * 同意字段单独成 schema,而不是塞进 registerSchema 里内联:
 * 客户端用 kotlinx.serialization 且默认 explicitNulls=true,
 * 未勾选时下发的是 {"termsAccepted": null} 而非省略键,所以必须同时放行 null/undefined,
 * 再由 assertLegalConsent 统一回 LEGAL_CONSENT_REQUIRED —— 否则用户看到的是泛泛的「参数校验失败」。
 */
export const legalConsentSchema = z.object({
  termsAccepted: z.boolean().nullish(),
  privacyAccepted: z.boolean().nullish(),
})

export type LegalConsentInput = z.infer<typeof legalConsentSchema>

/**
 * 注册前的同意校验:必须是「显式勾选」,缺失或为 false 一律拒绝。
 * 不做默认同意的兜底 —— 这条如果放水,后面所有数据合规的说法都不成立。
 */
export function assertLegalConsent(input: LegalConsentInput | undefined | null): void {
  if (input?.termsAccepted !== true || input?.privacyAccepted !== true) {
    throw new ApiError(400, 'LEGAL_CONSENT_REQUIRED', '请先阅读并同意《用户协议》与《隐私政策》')
  }
}
