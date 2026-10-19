const test = require('node:test');
const assert = require('node:assert/strict');

async function harness({animating = false, interactive = false, visible = true, onRender} = {}) {
  const {createRenderScheduler} = await import('../web/render-scheduler.mjs');
  const jobs = new Map(), frames = [];
  let time = 0, nextId = 1, rafCalls = 0;
  const state = {animating, interactive, visible};
  function add(callback, at, frame = false) { const id = nextId++; jobs.set(id, {callback, at, frame}); return id; }
  const scheduler = createRenderScheduler({
    render: (timestamp, dt) => { frames.push({timestamp, dt}); onRender?.(timestamp, state); },
    isAnimating: () => state.animating,
    isInteractive: () => state.interactive,
    canRender: () => state.visible,
    now: () => time,
    // Model a phone refreshing at 120 Hz.
    requestFrame: callback => add(callback, (Math.floor((time + .00001) / (1000 / 120)) + 1) * (1000 / 120), true),
    cancelFrame: id => jobs.delete(id),
    setTimer: (callback, delay) => add(callback, time + delay),
    clearTimer: id => jobs.delete(id),
  });
  function advance(until) {
    let steps = 0;
    while (jobs.size) {
      const [id, job] = [...jobs].sort((a, b) => a[1].at - b[1].at)[0];
      if (job.at > until + .00001) break;
      assert.ok(++steps < 10000, 'scheduler must not spin without advancing time');
      time = job.at; jobs.delete(id); if (job.frame) rafCalls++;
      job.callback(time);
    }
    time = until;
  }
  return {scheduler, state, frames, jobs, advance, rafCalls: () => rafCalls};
}

test('paused or reduced-motion globe renders once and then has no pending work', async () => {
  const h = await harness(); h.scheduler.invalidate(); h.advance(1000);
  assert.equal(h.frames.length, 1); assert.equal(h.jobs.size, 0);
  h.advance(2000); assert.equal(h.rafCalls(), 1);
  // Selection, a resize, or a pin update must still repaint while paused.
  h.scheduler.invalidate(); h.scheduler.invalidate(); h.advance(2100);
  assert.equal(h.frames.length, 2); assert.equal(h.jobs.size, 0);
});

test('automatic spin is limited to 30 fps on a 120 Hz display', async () => {
  const h = await harness({animating: true}); h.scheduler.invalidate(); h.advance(1000);
  assert.ok(h.frames.length >= 27 && h.frames.length <= 30, `rendered ${h.frames.length} frames`);
  for (let i = 1; i < h.frames.length; i++) assert.ok(h.frames[i].timestamp - h.frames[i - 1].timestamp >= 1000 / 30 - .001);
  assert.ok(h.rafCalls() <= 60, 'timer pacing should avoid waking on all 120 refreshes');
  h.state.animating = false; h.scheduler.refresh(); assert.equal(h.jobs.size, 0);
});

test('drag redraws are responsive but bounded to 60 fps, then return to idle', async () => {
  const h = await harness({interactive: true});
  for (let i = 0; i < 120; i++) { h.advance(i * (1000 / 120)); h.scheduler.invalidate(); }
  h.advance(1100);
  assert.ok(h.frames.length >= 50 && h.frames.length <= 61, `rendered ${h.frames.length} drag frames`);
  for (let i = 1; i < h.frames.length; i++) assert.ok(h.frames[i].timestamp - h.frames[i - 1].timestamp >= 1000 / 60 - .001);
  assert.equal(h.jobs.size, 0);
  // Releasing a captured pointer restarts a previously enabled spin.
  h.state.interactive = false; h.state.animating = true; h.scheduler.refresh(); h.advance(1200);
  assert.ok(h.frames.at(-1).timestamp > 1100);
});

test('hidden, offscreen, or inactive state cancels all work and preserves dirty changes', async () => {
  const h = await harness({animating: true}); h.scheduler.invalidate(); h.advance(12);
  assert.ok(h.jobs.size > 0);
  h.state.visible = false; h.scheduler.refresh(); assert.equal(h.jobs.size, 0);
  h.scheduler.invalidate(); h.advance(5000); assert.equal(h.frames.length, 1); assert.equal(h.jobs.size, 0);
  h.state.animating = false; h.state.visible = true; h.scheduler.refresh(); h.advance(5100);
  assert.equal(h.frames.length, 2); assert.equal(h.frames[1].dt, 0); assert.equal(h.jobs.size, 0);
  h.state.animating = true; h.scheduler.refresh(); h.advance(5200); assert.ok(h.frames.length > 2);
});

test('visibility changing before a queued frame suppresses it without losing resume', async () => {
  const h = await harness({animating: true}); h.scheduler.invalidate(); h.state.visible = false;
  h.advance(100); assert.equal(h.frames.length, 0); assert.equal(h.jobs.size, 0);
  h.state.visible = true; h.scheduler.refresh(); h.advance(200); assert.ok(h.frames.length > 0);
});

test('last insertion frame is drawn before scheduler sleeps and disposal cannot restart it', async () => {
  const h = await harness({animating: true, onRender: (time, state) => { if (time >= 650) state.animating = false; }});
  h.scheduler.invalidate(); h.advance(1000);
  assert.ok(h.frames.at(-1).timestamp >= 650); assert.equal(h.jobs.size, 0);
  const count = h.frames.length;
  h.state.animating = true; h.scheduler.refresh(); assert.ok(h.jobs.size > 0);
  h.scheduler.dispose(); h.scheduler.invalidate(); h.scheduler.refresh(); h.advance(2000);
  assert.equal(h.frames.length, count); assert.equal(h.jobs.size, 0);
});
