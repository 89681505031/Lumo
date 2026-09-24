import test from "node:test";
import assert from "node:assert/strict";
import {
  genericPushMessage,
  invalidPushTokenCode,
  retrySeconds
} from "../src/push-outbox.js";

test("generic push envelope cannot reveal direct/group metadata or content",()=>{
  const payload=genericPushMessage("temporary-test-token");
  assert.equal(payload.token,"temporary-test-token");
  assert.deepEqual(payload.data,{kind:"lumo_message"});
  assert.equal(payload.notification,undefined,
    "notification payload would bypass Android's local consent gate");
  assert.equal(payload.android.ttl,600000);
  assert.equal(payload.android.collapseKey,"lumo_generic_message");
  assert.deepEqual(Object.keys(payload).sort(),["android","data","token"]);
  const encoded=JSON.stringify(payload);
  for(const forbidden of [
    "messageId","groupId","sender","recipient","text","title","body"
  ]) assert.equal(encoded.includes(forbidden),false,forbidden+" must not leak");
});

test("FCM invalid token detection only removes definitive invalid registrations",()=>{
  assert.equal(invalidPushTokenCode("messaging/registration-token-not-registered"),true);
  assert.equal(invalidPushTokenCode("messaging/invalid-registration-token"),true);
  assert.equal(invalidPushTokenCode("messaging/unavailable"),false);
  assert.equal(invalidPushTokenCode("messaging/mismatched-credential"),false);
});

test("push retry backoff is bounded",()=>{
  assert.equal(retrySeconds(1),40);
  assert.equal(retrySeconds(2),80);
  assert.equal(retrySeconds(4),300);
  assert.equal(retrySeconds(100),300);
});
