import jwt from 'jsonwebtoken'
import crypto from 'crypto'
import { env } from '../config/env'

export interface JwtPayload {
  sub: string // user guid
  did: number // device id
  jti: string
}

export function signAccessToken(payload: JwtPayload): string {
  return jwt.sign(payload, env.jwtSecret, { expiresIn: env.jwtAccessTtl as jwt.SignOptions['expiresIn'] })
}

export function verifyAccessToken(token: string): JwtPayload | null {
  try {
    return jwt.verify(token, env.jwtSecret) as JwtPayload
  } catch {
    return null
  }
}

/** refresh token:明文仅存于客户端,服务端只存 SHA-256 */
export function sha256(v: string): string {
  return crypto.createHash('sha256').update(v).digest('hex')
}

export function randomToken(bytes = 48): string {
  return crypto.randomBytes(bytes).toString('base64url')
}

export function uuid(): string {
  return crypto.randomUUID()
}
