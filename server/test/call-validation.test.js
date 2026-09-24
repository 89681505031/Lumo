import test from "node:test";
import assert from "node:assert/strict";
import { validateSignal } from "../src/call-signaling.js";

test("SDP signal validation accepts bounded plain text only", () => {
  assert.deepEqual(validateSignal("offer", {sdp:"v=0\\r\\n", extra:"ignored"}), {sdp:"v=0\\r\\n"});
  assert.deepEqual(validateSignal("answer", {sdp:"valid"}), {sdp:"valid"});
  assert.equal(validateSignal("offer", {sdp:""}), null);
  assert.equal(validateSignal("offer", {sdp:"x".repeat(12001)}), null);
  assert.equal(validateSignal("offer", {sdp:3}), null);
  assert.equal(validateSignal("offer", ["invalid"]), null);
  assert.equal(validateSignal("unknown", {sdp:"valid"}), null);
});

test("ICE signal validation drops unknown keys and rejects invalid candidates", () => {
  assert.deepEqual(validateSignal("ice", {
    candidate:"candidate:1",sdpMid:"0",sdpMLineIndex:0,extra:"never stored"
  }), {candidate:"candidate:1",sdpMid:"0",sdpMLineIndex:0});
  assert.deepEqual(validateSignal("ice",{candidate:""}),{
    candidate:"",sdpMid:null,sdpMLineIndex:null
  });
  assert.equal(validateSignal("ice",{candidate:"x".repeat(2049)}),null);
  assert.equal(validateSignal("ice",{candidate:"c",sdpMLineIndex:-1}),null);
  assert.equal(validateSignal("ice",{candidate:"c",sdpMid:"x".repeat(129)}),null);
});
