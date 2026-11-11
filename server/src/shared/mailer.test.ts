import test from 'node:test'
import assert from 'node:assert/strict'
import { describeMailError, verificationMail } from './mailer'

test('注册验证码邮件包含 6 位验证码与有效期', () => {
  const mail = verificationMail('123456', 'register')
  assert.match(mail.subject, /注册/)
  assert.match(mail.text, /123456/)
  assert.match(mail.text, /10 分钟/)
})

test('重置验证码邮件不泄露账号信息', () => {
  const mail = verificationMail('654321', 'reset')
  assert.match(mail.subject, /重置/)
  assert.match(mail.text, /654321/)
  assert.ok(!mail.text.includes('@'))
})

test('describeMailError 只保留错误码与响应码', () => {
  const err = Object.assign(new Error('550 mailbox unavailable for alice@example.com'), {
    code: 'EENVELOPE',
    responseCode: 550,
  })
  const text = describeMailError(err)
  assert.match(text, /EENVELOPE/)
  assert.match(text, /550/)
  assert.ok(!text.includes('alice@example.com'))
})
