import test from "node:test";
import assert from "node:assert/strict";
import { validateSignal } from "../src/call-signaling.js";

const mediaSessionId="11111111-1111-4111-8111-111111111111";

test("SDP signal validation requires a bounded session-scoped payload", () => {
  assert.deepEqual(
    validateSignal("offer", {mediaSessionId,sdp:"v=0\\r\\n",extra:"ignored"}),
    {mediaSessionId,sdp:"v=0\\r\\n"}
  );
  assert.deepEqual(validateSignal("answer", {mediaSessionId,sdp:"valid"}), {mediaSessionId,sdp:"valid"});
  assert.equal(validateSignal("offer", {sdp:"valid"}), null);
  assert.equal(validateSignal("offer", {mediaSessionId:"not-a-uuid",sdp:"valid"}), null);
  assert.equal(validateSignal("offer", {mediaSessionId,sdp:""}), null);
  assert.equal(validateSignal("offer", {mediaSessionId,sdp:"x".repeat(12001)}), null);
  assert.equal(validateSignal("offer", {mediaSessionId,sdp:3}), null);
  assert.equal(validateSignal("offer", ["invalid"]), null);
  assert.equal(validateSignal("unknown", {mediaSessionId,sdp:"valid"}), null);
});

test("ICE validation preserves session id, drops unknown keys and rejects invalid candidates", () => {
  assert.deepEqual(validateSignal("ice", {
    mediaSessionId,candidate:"candidate:1",sdpMid:"0",sdpMLineIndex:0,extra:"never stored"
  }), {mediaSessionId,candidate:"candidate:1",sdpMid:"0",sdpMLineIndex:0});
  assert.deepEqual(validateSignal("ice",{mediaSessionId,candidate:""}),{
    mediaSessionId,candidate:"",sdpMid:null,sdpMLineIndex:null
  });
  assert.equal(validateSignal("ice",{candidate:"c"}),null);
  assert.equal(validateSignal("ice",{mediaSessionId,candidate:"x".repeat(2049)}),null);
  assert.equal(validateSignal("ice",{mediaSessionId,candidate:"c",sdpMLineIndex:-1}),null);
  assert.equal(validateSignal("ice",{mediaSessionId,candidate:"c",sdpMid:"x".repeat(129)}),null);
});
