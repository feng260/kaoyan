import { ApiError } from '../../middlewares/error'
import { AVATAR_PRESET_KEYS, AVATAR_MAX_BYTES } from './avatarPresets'

export interface IdentityInput {
  nickname?: string | null
  signature?: string | null
  gender?: string | null
  birthday?: string | null
  avatarPreset?: string | null
  avatar?: string | null
}

export interface IdentityFields {
  nickname: string | null
  signature: string | null
  gender: string | null
  birthday: string | null
  avatarPreset: string | null
  avatarData: string | null
}

const GENDERS = ['male', 'female', 'other', 'secret']
const invalid = (msg: string) => new ApiError(400, 'INVALID_PARAMS', msg)
const codePoints = (s: string) => Array.from(s).length

/** 剥 data URL 前缀 → 解码校验魔数与大小 → 返回裸 base64 */
export function normalizeAvatar(value: string): string {
  const raw = value.includes(',') && value.trim().startsWith('data:')
    ? value.slice(value.indexOf(',') + 1) : value
  const clean = raw.trim()
  const bytes = Buffer.from(clean, 'base64')
  if (bytes.length === 0 || bytes.length > AVATAR_MAX_BYTES) throw invalid('头像图片过大或为空')
  const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
  const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
  const isWebp = bytes.slice(0, 4).toString('ascii') === 'RIFF' && bytes.slice(8, 12).toString('ascii') === 'WEBP'
  if (!isJpeg && !isPng && !isWebp) throw invalid('头像仅支持 JPEG/PNG/WebP')
  return bytes.toString('base64')
}

function validBirthday(value: string, today: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [y, m, d] = value.split('-').map(Number)
  if (y < 1900 || value > today) return false
  const dt = new Date(Date.UTC(y, m - 1, d))
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d
}

/**
 * 局部更新归一:只处理 input 里出现的键(undefined=未传,跳过)。
 * 预设与自定义互斥:同请求两者非空 → AVATAR_CONFLICT;设其一清其二。
 */
export function normalizeIdentityPatch(input: IdentityInput, today: string): Partial<IdentityFields> {
  if (input.avatar != null && input.avatarPreset != null) {
    throw new ApiError(400, 'AVATAR_CONFLICT', '预设头像和自定义头像只能选一个')
  }
  const patch: Partial<IdentityFields> = {}
  if (input.nickname !== undefined) {
    const v = input.nickname === null ? '' : input.nickname.trim()
    if (codePoints(v) > 32) throw invalid('昵称最多 32 个字符')
    patch.nickname = v === '' ? null : v
  }
  if (input.signature !== undefined) {
    const v = input.signature === null ? '' : input.signature.trim()
    if (codePoints(v) > 100) throw invalid('签名最多 100 个字符')
    patch.signature = v === '' ? null : v
  }
  if (input.gender !== undefined) {
    if (input.gender !== null && !GENDERS.includes(input.gender)) throw invalid('性别取值非法')
    patch.gender = input.gender ?? null
  }
  if (input.birthday !== undefined) {
    const v = input.birthday === null ? '' : input.birthday.trim()
    if (v !== '' && !validBirthday(v, today)) throw invalid('生日格式或范围非法')
    patch.birthday = v === '' ? null : v
  }
  if (input.avatar !== undefined) {
    if (input.avatar === null) {
      patch.avatarData = null
    } else {
      patch.avatarData = normalizeAvatar(input.avatar)
      patch.avatarPreset = null
    }
  }
  if (input.avatarPreset !== undefined) {
    if (input.avatarPreset === null || input.avatarPreset === '') {
      patch.avatarPreset = null
    } else {
      if (!(AVATAR_PRESET_KEYS as readonly string[]).includes(input.avatarPreset)) throw invalid('未知的预设头像')
      patch.avatarPreset = input.avatarPreset
      patch.avatarData = null
    }
  }
  return patch
}