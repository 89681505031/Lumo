import test from "node:test";
import { randomUUID } from "node:crypto";
import assert from "node:assert/strict";
import { hashPassword, verifyPassword, validPassword } from "../src/password.js";

test("scrypt hashes use random salts and verify credentials", async () => {
  const password = "TestOnly-"+randomUUID();
  const first = await hashPassword(password);
  const second = await hashPassword(password);
  assert.notEqual(first, second);
  assert.ok(first.startsWith("scrypt$"));
  assert.equal(await verifyPassword(password, first), true);
  assert.equal(await verifyPassword(password + "!", first), false);
  assert.equal(await verifyPassword(password, "malformed"), false);
  assert.equal(await verifyPassword(password, null), false);
});

test("reject short, oversized and non-string passwords", async () => {
  for (const input of ["short", "x".repeat(129), 12345, null]) {
    assert.equal(validPassword(input), false);
    await assert.rejects(() => hashPassword(input), /invalid_password/);
  }
});
