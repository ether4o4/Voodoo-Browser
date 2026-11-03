/* Private mode fingerprint shield. Injected by native code into every frame of private
 * tabs before page scripts run. It reduces what a site can learn about this device.
 * It cannot make WebView indistinguishable from Tor Browser. The seed below is a random
 * per-session number, so canvas/audio noise changes every private session. */
(function () {
  'use strict';
  const W = window, seed = __SEED__ >>> 0;
  const native = new WeakMap();
  const fnToString = Function.prototype.toString;
  const disguise = (fake, real) => { try { native.set(fake, fnToString.call(real)); } catch (e) {} return fake; };
  try {
    Function.prototype.toString = disguise(function toString() { return native.has(this) ? native.get(this) : fnToString.call(this); }, fnToString);
  } catch (e) {}
  const getter = (proto, key, value) => {
    try {
      const d = Object.getOwnPropertyDescriptor(proto, key);
      const get = disguise(function () { return typeof value === 'function' ? value.call(this) : value; }, d && d.get || function () {});
      Object.defineProperty(proto, key, { get, configurable: true, enumerable: d ? d.enumerable : true });
    } catch (e) {}
  };
  const method = (proto, key, make) => {
    try { const real = proto[key]; if (typeof real !== 'function') return; proto[key] = disguise(make(real), real); } catch (e) {}
  };
  const remove = name => { try { delete W[name]; } catch (e) {} try { Object.defineProperty(W, name, { value: undefined, configurable: false, writable: false }); } catch (e) {} };

  // Common, low-entropy device answers.
  const N = W.Navigator && Navigator.prototype;
  if (N) {
    getter(N, 'language', 'en-US');
    getter(N, 'languages', Object.freeze(['en-US', 'en']));
    getter(N, 'hardwareConcurrency', 4);
    getter(N, 'deviceMemory', 4);
    getter(N, 'maxTouchPoints', 5);
    getter(N, 'doNotTrack', '1');
    getter(N, 'globalPrivacyControl', true);
    getter(N, 'webdriver', false);
    for (const k of ['getBattery', 'getGamepads', 'requestMIDIAccess', 'requestMediaKeySystemAccess']) {
      method(N, k, () => function () { return k === 'getGamepads' ? [] : Promise.reject(new DOMException('Not allowed in private mode', 'NotAllowedError')); });
    }
    for (const k of ['bluetooth', 'usb', 'serial', 'hid', 'xr', 'connection', 'keyboard', 'storage']) getter(N, k, undefined);
  }
  const S = W.Screen && Screen.prototype;
  if (S) {
    getter(S, 'width', 412); getter(S, 'height', 915);
    getter(S, 'availWidth', 412); getter(S, 'availHeight', 915);
    getter(S, 'colorDepth', 24); getter(S, 'pixelDepth', 24);
  }

  // WebRTC sends UDP outside the proxy and can reveal local and public IP addresses.
  for (const k of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription', 'RTCIceCandidate', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver']) remove(k);
  const MD = W.MediaDevices && MediaDevices.prototype;
  if (MD) {
    method(MD, 'enumerateDevices', () => function () { return Promise.resolve([]); });
    method(MD, 'getUserMedia', () => function () { return Promise.reject(new DOMException('Not allowed in private mode', 'NotAllowedError')); });
    method(MD, 'getDisplayMedia', () => function () { return Promise.reject(new DOMException('Not allowed in private mode', 'NotAllowedError')); });
  }

  // Motion, orientation and ambient sensors.
  for (const k of ['DeviceMotionEvent', 'DeviceOrientationEvent', 'Accelerometer', 'LinearAccelerationSensor', 'GravitySensor', 'Gyroscope', 'Magnetometer', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor', 'AmbientLightSensor', 'Sensor']) remove(k);
  const blocked = new Set(['devicemotion', 'deviceorientation', 'deviceorientationabsolute', 'compassneedscalibration']);
  method(EventTarget.prototype, 'addEventListener', real => function (type, ...rest) { if (blocked.has(String(type).toLowerCase())) return; return real.call(this, type, ...rest); });
  if (W.Geolocation) {
    const deny = (ok, fail) => { if (typeof fail === 'function') setTimeout(() => fail({ code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }), 0); return 0; };
    method(Geolocation.prototype, 'getCurrentPosition', () => deny);
    method(Geolocation.prototype, 'watchPosition', () => deny);
  }

  // Time zone: everything reports UTC.
  try {
    const DTF = Intl.DateTimeFormat;
    const Fake = disguise(function DateTimeFormat(locales, options) {
      return new DTF(locales === undefined ? 'en-US' : locales, Object.assign({ timeZone: 'UTC' }, options || {}));
    }, DTF);
    Fake.prototype = DTF.prototype; Fake.supportedLocalesOf = DTF.supportedLocalesOf;
    Object.defineProperty(DTF.prototype, 'constructor', { value: Fake, configurable: true, writable: true });
    Intl.DateTimeFormat = Fake;
    const D = Date.prototype;
    method(D, 'getTimezoneOffset', () => function () { return 0; });
    for (const unit of ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds']) {
      method(D, 'get' + unit, () => { const utc = D['getUTC' + unit]; return function () { return utc.call(this); }; });
      if (unit !== 'Day') method(D, 'set' + unit, () => { const utc = D['setUTC' + unit]; return function (...a) { return utc.apply(this, a); }; });
    }
    for (const k of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString']) {
      method(D, k, real => function (locales, options) { return real.call(this, locales === undefined ? 'en-US' : locales, Object.assign({ timeZone: 'UTC' }, options || {})); });
    }
    const pad = n => String(n).padStart(2, '0');
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'], months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const datePart = d => days[d.getUTCDay()] + ' ' + months[d.getUTCMonth()] + ' ' + pad(d.getUTCDate()) + ' ' + d.getUTCFullYear();
    const timePart = d => pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds()) + ' GMT+0000 (Coordinated Universal Time)';
    method(D, 'toString', real => function () { return isNaN(this) ? real.call(this) : datePart(this) + ' ' + timePart(this); });
    method(D, 'toDateString', real => function () { return isNaN(this) ? real.call(this) : datePart(this); });
    method(D, 'toTimeString', real => function () { return isNaN(this) ? real.call(this) : timePart(this); });
  } catch (e) {}

  // Canvas and WebGL: per-session noise and generic GPU strings.
  const noise = data => { for (let i = 0; i < data.length; i += 4 * 61) data[i] ^= (seed >>> ((i >> 2) % 24)) & 1; return data; };
  const C2D = W.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  const rawGetImageData = C2D && C2D.getImageData;
  if (C2D) method(C2D, 'getImageData', real => function (...a) { const img = real.apply(this, a); noise(img.data); return img; });
  const noisyCopy = canvas => {
    if (!canvas.width || !canvas.height || !rawGetImageData) return null;
    const c = document.createElement('canvas'); c.width = canvas.width; c.height = canvas.height;
    const x = c.getContext('2d'); if (!x) return null;
    x.drawImage(canvas, 0, 0); const img = rawGetImageData.call(x, 0, 0, c.width, c.height); noise(img.data); x.putImageData(img, 0, 0); return c;
  };
  if (W.HTMLCanvasElement) {
    const HC = HTMLCanvasElement.prototype;
    method(HC, 'toDataURL', real => function (...a) { try { const c = noisyCopy(this); if (c) return real.apply(c, a); } catch (e) {} return real.apply(this, a); });
    method(HC, 'toBlob', real => function (...a) { try { const c = noisyCopy(this); if (c) return real.apply(c, a); } catch (e) {} return real.apply(this, a); });
  }
  for (const name of ['WebGLRenderingContext', 'WebGL2RenderingContext']) {
    const P = W[name] && W[name].prototype; if (!P) continue;
    method(P, 'getParameter', real => function (p) { if (p === 37445) return 'Google Inc.'; if (p === 37446) return 'ANGLE (Generic, OpenGL ES)'; return real.call(this, p); });
    method(P, 'readPixels', real => function (...a) { const r = real.apply(this, a); const out = a[6]; if (out && out.length) noise(out); return r; });
  }

  // Audio fingerprinting.
  const touched = new WeakSet();
  if (W.AudioBuffer) method(AudioBuffer.prototype, 'getChannelData', real => function (...a) {
    const data = real.apply(this, a);
    if (!touched.has(data)) { touched.add(data); for (let i = seed % 97; i < data.length; i += 97) data[i] += ((seed >>> (i % 24)) & 1 ? 1 : -1) * 1e-7; }
    return data;
  });
  if (W.AnalyserNode) method(AnalyserNode.prototype, 'getFloatFrequencyData', real => function (arr) { real.call(this, arr); for (let i = 0; i < arr.length; i += 53) arr[i] += ((seed >>> (i % 24)) & 1) * 1e-4; });
})();
