import test from 'node:test'
import assert from 'node:assert/strict'
import { isGenericSubjectName, subjectContextLine } from './aiCoach'

// 旧问卷把「英语/数学/专业课」这种泛称当成已确认科目,面谈又被禁止再问,
// 泛科目就这样锁死进了计划。这一版:具体科目仍锁定,泛称必须放行让面谈澄清。

test('泛称科目被识别出来', () => {
  assert.equal(isGenericSubjectName('英语'), true)
  assert.equal(isGenericSubjectName('数学'), true)
  assert.equal(isGenericSubjectName('专业课'), true)
  assert.equal(isGenericSubjectName(' 英语 '), true)
})

test('具体科目不算泛称', () => {
  assert.equal(isGenericSubjectName('英语一'), false)
  assert.equal(isGenericSubjectName('英语二'), false)
  assert.equal(isGenericSubjectName('数学三'), false)
  assert.equal(isGenericSubjectName('396经济类联考'), false)
  assert.equal(isGenericSubjectName('408计算机学科专业基础'), false)
  assert.equal(isGenericSubjectName('政治'), false)
})

test('全是具体科目时锁定不问', () => {
  const line = subjectContextLine(['政治', '英语二', '数学三', '金融综合'])
  assert.match(line, /已确认/)
  assert.match(line, /不要再问/)
  assert.doesNotMatch(line, /澄清/)
})

test('含泛称时要求面谈先澄清', () => {
  const line = subjectContextLine(['政治', '英语', '数学', '专业课'])
  assert.match(line, /澄清/)
  assert.doesNotMatch(line, /不要再问/)
})

test('具体与泛称混合时也要求澄清', () => {
  const line = subjectContextLine(['政治', '英语二', '专业课'])
  assert.match(line, /澄清/)
})