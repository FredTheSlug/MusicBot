'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const PANEL = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8').replace(/\r/g, '');
const SERVER = fs.readFileSync(path.join(__dirname, '../../main/java/com/jagrosh/jmusicbot/web/WebServer.java'), 'utf8')
  .replace(/\r/g, '');

function uploadFunction() {
  const start = PANEL.indexOf('async function uploadSingleFile(file) {');
  assert.ok(start >= 0, 'the panel uploads audio with uploadSingleFile');
  const end = PANEL.indexOf('\n        }\n', start);
  return PANEL.slice(start, end);
}

test('thePanelChecksTheSameSizeLimitAsTheServerBeforeUploading', () => {
  // the server refuses anything bigger unread; a browser that is still sending often can't read that answer,
  // so the panel has to say it first
  const server = SERVER.match(/MAX_AUDIO_UPLOAD_BYTES = ([^;]+);/);
  assert.ok(server, 'WebServer has MAX_AUDIO_UPLOAD_BYTES');
  const panel = PANEL.match(/AUDIO_UPLOAD_MAX_BYTES = ([^;]+);/);
  assert.ok(panel, 'the panel has AUDIO_UPLOAD_MAX_BYTES');
  assert.equal(panel[1].replace(/L/g, '').replace(/\s/g, ''), server[1].replace(/L/g, '').replace(/\s/g, ''));
  const body = uploadFunction();
  const check = body.indexOf('file.size > AUDIO_UPLOAD_MAX_BYTES');
  assert.ok(check >= 0, 'uploadSingleFile checks the file size');
  assert.ok(check < body.indexOf('authFetch('), 'the size is checked before anything is sent');
});

test('thePanelShowsTheServersReasonWhenAnUploadIsRefused', () => {
  const body = uploadFunction();
  assert.match(body, /if \(!resp\.ok\)/, 'a refused upload is handled before reading the answer as JSON');
  assert.ok(body.indexOf('if (!resp.ok)') < body.indexOf('resp.json()'));
  const server = SERVER.match(/AUDIO_TOO_LARGE = "([^"]+)";/);
  assert.ok(server, 'WebServer has AUDIO_TOO_LARGE');
  assert.ok(PANEL.includes(server[1]), 'the panel says the same words as the server');
});
