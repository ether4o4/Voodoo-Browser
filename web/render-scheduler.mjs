// Keep WebGL asleep between changes, and bound animation work on high-refresh phones.
// Injected clocks let the lifecycle and rate limit be checked without a GPU.
export function createRenderScheduler({
  render,
  isAnimating = () => false,
  isInteractive = () => false,
  canRender = () => true,
  now = () => performance.now(),
  requestFrame = callback => requestAnimationFrame(callback),
  cancelFrame = id => cancelAnimationFrame(id),
  setTimer = (callback, delay) => setTimeout(callback, delay),
  clearTimer = id => clearTimeout(id),
}) {
  let frame = null, timer = null, lastRendered = null, dirty = true, disposed = false;
  const interval = () => 1000 / (isInteractive() ? 60 : 30);
  const hasWork = () => dirty || isAnimating();

  function cancelPending() {
    if (frame !== null) cancelFrame(frame);
    if (timer !== null) clearTimer(timer);
    frame = timer = null;
  }

  function schedule() {
    if (disposed) return;
    if (!canRender()) {
      cancelPending();
      lastRendered = null;
      return;
    }
    if (!hasWork() || frame !== null || timer !== null) return;
    const remaining = lastRendered === null ? 0 : interval() - (now() - lastRendered);
    // Wake shortly before the target refresh; tick still enforces the frame cap.
    // This avoids a RAF callback on every 120 Hz refresh while spinning at 30 fps.
    if (remaining > 9) {
      timer = setTimer(() => { timer = null; schedule(); }, remaining - 8);
    } else {
      frame = requestFrame(tick);
    }
  }

  function tick(timestamp) {
    frame = null;
    if (disposed) return;
    if (!canRender()) { lastRendered = null; return; }
    if (!hasWork()) return;
    if (lastRendered !== null && timestamp - lastRendered + 0.001 < interval()) {
      schedule();
      return;
    }
    const dt = lastRendered === null ? 0 : Math.min((timestamp - lastRendered) / 1000, 0.05);
    lastRendered = timestamp;
    dirty = false;
    render(timestamp, dt);
    schedule();
  }

  return {
    invalidate() { dirty = true; cancelPending(); schedule(); },
    // Re-evaluate visibility or animation flags even if there is no dirty frame.
    refresh() { cancelPending(); schedule(); },
    dispose() { disposed = true; cancelPending(); },
  };
}
