export const AVATAR_PRESET_KEYS = [
  'fox', 'panda', 'owl', 'rocket', 'book', 'moon', 'star', 'clover', 'cat', 'target',
] as const

export type AvatarPresetKey = typeof AVATAR_PRESET_KEYS[number]

/** 自定义头像解码后的字节上限(1MB);客户端已压到最长边 512,这里兜底防滥用 */
export const AVATAR_MAX_BYTES = 1_048_576