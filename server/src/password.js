import { randomBytes, scrypt as callbackScrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(callbackScrypt);
const HASH_BYTES = 64;

export function validPassword(password) {
  return typeof password === "string" &&
    password.length >= 10 && password.length <= 128 &&
    Buffer.byteLength(password, "utf8") <= 256;
}

export async function hashPassword(password) {
  if (!validPassword(password)) throw new Error("invalid_password");
  const salt = randomBytes(16);
  const hash = await scrypt(password, salt, HASH_BYTES);
  return `scrypt$${salt.toString("base64url")}$${hash.toString("base64url")}`;
}

export async function verifyPassword(password, stored) {
  if (!validPassword(password) || typeof stored !== "string") return false;
  const parts = stored.split("$");
  if (parts.length !== 3 || parts[0] !== "scrypt") return false;
  try {
    const salt = Buffer.from(parts[1], "base64url");
    const expected = Buffer.from(parts[2], "base64url");
    if (salt.length !== 16 || expected.length !== HASH_BYTES) return false;
    const actual = await scrypt(password, salt, HASH_BYTES);
    return timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}
