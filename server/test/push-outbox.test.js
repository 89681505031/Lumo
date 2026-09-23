import test from "node:test";
import assert from "node:assert/strict";
import { genericPushMessage, invalidPushTokenCode, retrySeconds } from "../src/push-outbox.js";

test("push payload contains only generic text, never message content or identity",()=>{
  const payload=genericPushMessage("temporary-test-token");
  assert.equal(payload.notification.title,"Lumo");
  assert.equal(payload.notification.body,"Новое сообщение");
  assert.equal(payload.token,"temporary-test-token");
  assert.deepEqual(Object.keys(payload).sort(),["android","notification","token"]);
  assert.equal(JSON.stringify(payload).includes("messageId"),false);
  assert.equal(JSON.stringify(payload).includes("sender"),false);
});

test("FCM invalid token detection revokes only definitive registration failures",()=>{
  assert.equal(invalidPushTokenCode("messaging/registration-token-not-registered"),true);
  assert.equal(invalidPushTokenCode("messaging/invalid-registration-token"),true);
  assert.equal(invalidPushTokenCode("messaging/unavailable"),false);
  assert.equal(invalidPushTokenCode("messaging/mismatched-credential"),false);
});

test("bounded exponential backoff avoids immediate repeated push attempts",()=>{
  assert.equal(retrySeconds(1),40);
  assert.equal(retrySeconds(2),80);
  assert.equal(retrySeconds(4),300);
  assert.equal(retrySeconds(100),300);
});
