import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import { normalizeIdentityPatch, normalizeAvatar } from './identity'
import { AVATAR_PRESET_KEYS } from './avatarPresets'

const TODAY = '2026-11-09'
// 1x1 JPEG 的裸 base64(魔数 FF D8 FF 开头),足够过魔数与大小校验
const JPEG = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=='

test('nickname/signature trim 且空串归 null', () => {
  const patch = normalizeIdentityPatch({ nickname: '  小明  ', signature: '   ' }, TODAY)
  assert.equal(patch.nickname, '小明')
  assert.equal(patch.signature, null)
})

test('未传的字段不出现在 patch 里', () => {
  const patch = normalizeIdentityPatch({ nickname: 'A' }, TODAY)
  assert.deepEqual(Object.keys(patch), ['nickname'])
})

test('昵称超过 32 码点报 400', () => {
  assert.throws(() => normalizeIdentityPatch({ nickname: '字'.repeat(33) }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
})

test('性别枚举外的值报 400', () => {
  assert.throws(() => normalizeIdentityPatch({ gender: 'unknown' }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
  assert.equal(normalizeIdentityPatch({ gender: 'secret' }, TODAY).gender, 'secret')
})

test('生日:假日期/未来/早于1900 报 400,合法落库', () => {
  for (const bad of ['2026-02-30', '2027-01-01', '1899-12-31', '2026-13-01', 'abc']) {
    assert.throws(() => normalizeIdentityPatch({ birthday: bad }, TODAY),
      (e: unknown) => e instanceof ApiError && e.status === 400, bad)
  }
  assert.equal(normalizeIdentityPatch({ birthday: '2003-05-01' }, TODAY).birthday, '2003-05-01')
  assert.equal(normalizeIdentityPatch({ birthday: '' }, TODAY).birthday, null)
})

test('预设 key 白名单:未知报 400,合法且清自定义', () => {
  assert.throws(() => normalizeIdentityPatch({ avatarPreset: 'dragon' }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
  const patch = normalizeIdentityPatch({ avatarPreset: 'fox' }, TODAY)
  assert.equal(patch.avatarPreset, 'fox')
  assert.equal(patch.avatarData, null)
  assert.equal(AVATAR_PRESET_KEYS.length, 10)
})

test('自定义头像合法 base64 归一为裸串且清预设', () => {
  const patch = normalizeIdentityPatch({ avatar: `data:image/jpeg;base64,${JPEG}` }, TODAY)
  assert.equal(patch.avatarData, JPEG)
  assert.equal(patch.avatarPreset, null)
})

test('头像非法魔数报 400', () => {
  const notImage = Buffer.from('hello world not an image').toString('base64')
  assert.throws(() => normalizeIdentityPatch({ avatar: notImage }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
})

test('预设与自定义同请求非空报 AVATAR_CONFLICT', () => {
  assert.throws(() => normalizeIdentityPatch({ avatarPreset: 'fox', avatar: JPEG }, TODAY),
    (e: unknown) => e instanceof ApiError && e.code === 'AVATAR_CONFLICT')
})

test('avatar=null 清空自定义', () => {
  assert.equal(normalizeIdentityPatch({ avatar: null }, TODAY).avatarData, null)
})