const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(process.argv[2], 'utf8');
const initialStatus = JSON.parse(fs.readFileSync(process.argv[2] + '.status.json', 'utf8'));

function browser() {
  let now = 1000, sequence = 0;
  const timers = new Map(), events = {}, requests = [];
  const image = {
    style: {}, src: '/video',
    getAttribute(name) { const found = html.match(new RegExp(name + '=\"([^\"]*)\"')); return found ? found[1] : null; },
    removeAttribute(name) { if (name === 'src') this.src = ''; }
  };
  const message = { textContent: '' };
  function setTimeout(fn, ms) { const id = ++sequence; timers.set(id, { fn, at: now + ms }); return id; }
  function advance(ms) {
    const until = now + ms;
    for (;;) {
      const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > until) break;
      now = next[1].at; timers.delete(next[0]); next[1].fn();
    }
    now = until;
  }
  function XMLHttpRequest() { requests.push(this); }
  XMLHttpRequest.prototype.open = function(method, url) { this.url = url; };
  XMLHttpRequest.prototype.send = function() {};
  XMLHttpRequest.prototype.abort = function() { this.aborted = true; };
  const context = {
    document: { getElementById(id) { return id === 'stream' ? image : message; } },
    window: { addEventListener(name, callback) { events[name] = callback; } },
    XMLHttpRequest, setTimeout, clearTimeout(id) { timers.delete(id); },
    Date: { now() { return now; } }, JSON
  };
  const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)];
  for (const script of scripts) vm.runInNewContext(script[1], context);
  function status(value) {
    const req = requests[requests.length - 1];
    assert.ok(req, 'page must check stream health');
    req.status = 200; req.responseText = JSON.stringify(value); req.onload();
  }
  return { image, message, requests, advance, status, events, timers };
}

function test(name, run) { run(); console.log('PASS: ' + name); }

test('failed stream clears the displayed frame and reconnects with a fresh URL', () => {
  const b = browser();
  assert.equal(typeof b.image.onerror, 'function', 'stream must handle disconnects');
  const original = b.image.src;
  b.image.onerror();
  assert.equal(b.image.style.visibility, 'hidden');
  assert.equal(b.image.src, '');
  b.advance(1200);
  assert.match(b.image.src, /^\/video\?/);
  assert.notEqual(b.image.src, original);
});

test('heartbeat detects a restarted server even when image error is absent', () => {
  const b = browser();
  b.advance(2000);
  b.status({ stream_id: initialStatus.stream_id, has_frame: true, frames: 9 });
  b.image.onload();
  assert.equal(b.image.style.visibility, 'visible');
  b.advance(2000);
  b.status({ stream_id: 'after', has_frame: true, frames: 10 });
  assert.equal(b.image.style.visibility, 'hidden');
  assert.equal(b.image.src, '');
  b.advance(1200);
  assert.match(b.image.src, /^\/video\?/);
});

test('restart before the first heartbeat cannot preserve the previous stream image', () => {
  const b = browser();
  b.image.onload();
  b.advance(2000);
  b.status({ stream_id: 'already-restarted', has_frame: true, frames: 1 });
  assert.equal(b.image.style.visibility, 'hidden');
  assert.equal(b.image.src, '');
});

test('failed heartbeat hides stale frames and page exit cancels retries and requests', () => {
  const b = browser();
  b.image.onload();
  b.advance(2000);
  const request = b.requests[b.requests.length - 1];
  request.onerror();
  assert.equal(b.image.style.visibility, 'hidden');
  b.events.pagehide();
  assert.equal(b.image.src, '');
  assert.equal(b.timers.size, 0);
  b.advance(10000);
  assert.equal(b.image.src, '');
});
