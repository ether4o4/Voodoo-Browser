/* Private mode fingerprint shield. Injected by native code into every frame of private
 * tabs before page scripts run. It reduces what a site can learn about this device.
 * It cannot make WebView indistinguishable from Tor Browser.
 *
 * The same code also protects:
 *  - same-origin child frames (including about:blank frames a page creates to borrow
 *    unpatched APIs such as WebRTC), as soon as they become reachable;
 *  - dedicated and shared workers, by starting them through a prelude that runs this
 *    shield first.
 * Service workers are disabled in private mode, as in Tor Browser.
 * The seed is a random per-session number, so canvas/audio noise changes every session. */
(function voodooPrivacyShield(seed) {
  'use strict';
  seed = seed >>> 0;
  const MARK = Symbol.for('voodoo.privacy.shielded');
  const fnToString = Function.prototype.toString;
  const SOURCE = '(' + fnToString.call(voodooPrivacyShield) + ')(' + seed + ');';
  const native = new WeakMap();
  const disguise = (fake, real) => { try { native.set(fake, fnToString.call(real)); } catch (e) {} return fake; };

  function shield(W) {
    try { if (!W || W[MARK]) return; Object.defineProperty(W, MARK, { value: true }); } catch (e) { return; }

    try {
      const FP = W.Function.prototype, realToString = FP.toString;
      FP.toString = disguise(function toString() { return native.has(this) ? native.get(this) : realToString.call(this); }, realToString);
    } catch (e) {}
    const getter = (proto, key, value) => {
      if (!proto) return;
      try {
        const d = Object.getOwnPropertyDescriptor(proto, key);
        const get = disguise(function () { return value; }, d && d.get || function () {});
        Object.defineProperty(proto, key, { get, configurable: true, enumerable: d ? d.enumerable : true });
      } catch (e) {}
    };
    const method = (proto, key, make) => {
      try { if (!proto) return; const real = proto[key]; if (typeof real !== 'function') return; proto[key] = disguise(make(real), real); } catch (e) {}
    };
    const remove = name => { try { delete W[name]; } catch (e) {} try { Object.defineProperty(W, name, { value: undefined, configurable: false, writable: false }); } catch (e) {} };
    const denied = () => { try { return W.Promise.reject(new W.DOMException('Not allowed in private mode', 'NotAllowedError')); } catch (e) { return Promise.reject(new Error('Not allowed in private mode')); } };
    const proto = name => W[name] && W[name].prototype;

    // Common, low-entropy device answers (window and worker navigators).
    for (const N of [proto('Navigator'), proto('WorkerNavigator')]) {
      if (!N) continue;
      getter(N, 'language', 'en-US');
      getter(N, 'languages', Object.freeze(['en-US', 'en']));
      getter(N, 'hardwareConcurrency', 4);
      getter(N, 'deviceMemory', 4);
      getter(N, 'maxTouchPoints', 5);
      getter(N, 'doNotTrack', '1');
      getter(N, 'globalPrivacyControl', true);
      getter(N, 'webdriver', false);
      for (const k of ['getBattery', 'requestMIDIAccess', 'requestMediaKeySystemAccess']) method(N, k, () => function () { return denied(); });
      method(N, 'getGamepads', () => function () { return []; });
      for (const k of ['bluetooth', 'usb', 'serial', 'hid', 'xr', 'connection', 'keyboard', 'storage']) getter(N, k, undefined);
    }
    const S = proto('Screen');
    if (S) {
      getter(S, 'width', 412); getter(S, 'height', 915);
      getter(S, 'availWidth', 412); getter(S, 'availHeight', 915);
      getter(S, 'colorDepth', 24); getter(S, 'pixelDepth', 24);
    }

    // WebRTC sends UDP outside the proxy and can reveal local and public IP addresses.
    for (const k of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription', 'RTCIceCandidate', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver', 'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport', 'RTCCertificate', 'RTCEncodedAudioFrame', 'RTCEncodedVideoFrame', 'RTCRtpScriptTransform']) remove(k);
    const MD = proto('MediaDevices');
    if (MD) {
      method(MD, 'enumerateDevices', () => function () { return W.Promise.resolve([]); });
      method(MD, 'getUserMedia', () => function () { return denied(); });
      method(MD, 'getDisplayMedia', () => function () { return denied(); });
    }

    // Service workers outlive the page and run unshielded; disable them, as Tor Browser does.
    const SW = proto('ServiceWorkerContainer');
    if (SW) {
      method(SW, 'register', () => function () { return denied(); });
      method(SW, 'getRegistrations', () => function () { return W.Promise.resolve([]); });
      method(SW, 'getRegistration', () => function () { return W.Promise.resolve(undefined); });
    }

    // Motion, orientation and ambient sensors.
    for (const k of ['DeviceMotionEvent', 'DeviceOrientationEvent', 'Accelerometer', 'LinearAccelerationSensor', 'GravitySensor', 'Gyroscope', 'Magnetometer', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor', 'AmbientLightSensor', 'Sensor']) remove(k);
    const blocked = new Set(['devicemotion', 'deviceorientation', 'deviceorientationabsolute', 'compassneedscalibration']);
    method(proto('EventTarget'), 'addEventListener', real => function (type, ...rest) { if (blocked.has(String(type).toLowerCase())) return; return real.call(this, type, ...rest); });
    const G = proto('Geolocation');
    if (G) {
      const deny = (ok, fail) => { if (typeof fail === 'function') W.setTimeout(() => fail({ code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }), 0); return 0; };
      method(G, 'getCurrentPosition', () => deny);
      method(G, 'watchPosition', () => deny);
    }

    // Time zone: everything reports UTC.
    try {
      const I = W.Intl, DTF = I.DateTimeFormat;
      const Fake = disguise(function DateTimeFormat(locales, options) {
        return new DTF(locales === undefined ? 'en-US' : locales, Object.assign({ timeZone: 'UTC' }, options || {}));
      }, DTF);
      Fake.prototype = DTF.prototype; Fake.supportedLocalesOf = DTF.supportedLocalesOf;
      Object.defineProperty(DTF.prototype, 'constructor', { value: Fake, configurable: true, writable: true });
      I.DateTimeFormat = Fake;
      const D = W.Date.prototype;
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

    // Canvas, OffscreenCanvas and WebGL: per-session noise and generic GPU strings.
    const noise = data => { for (let i = 0; i < data.length; i += 4 * 61) data[i] ^= (seed >>> ((i >> 2) % 24)) & 1; return data; };
    const rawGets = new Map();
    for (const name of ['CanvasRenderingContext2D', 'OffscreenCanvasRenderingContext2D']) {
      const P = proto(name); if (!P || typeof P.getImageData !== 'function') continue;
      rawGets.set(name, P.getImageData);
      method(P, 'getImageData', real => function (...a) { const img = real.apply(this, a); noise(img.data); return img; });
    }
    const noisyCopy = canvas => {
      const raw = rawGets.get('CanvasRenderingContext2D');
      if (!canvas.width || !canvas.height || !raw || !W.document) return null;
      const c = W.document.createElement('canvas'); c.width = canvas.width; c.height = canvas.height;
      const x = c.getContext('2d'); if (!x) return null;
      x.drawImage(canvas, 0, 0); const img = raw.call(x, 0, 0, c.width, c.height); noise(img.data); x.putImageData(img, 0, 0); return c;
    };
    const HC = proto('HTMLCanvasElement');
    if (HC) {
      method(HC, 'toDataURL', real => function (...a) { try { const c = noisyCopy(this); if (c) return real.apply(c, a); } catch (e) {} return real.apply(this, a); });
      method(HC, 'toBlob', real => function (...a) { try { const c = noisyCopy(this); if (c) return real.apply(c, a); } catch (e) {} return real.apply(this, a); });
    }
    const OC = proto('OffscreenCanvas'), rawOff = rawGets.get('OffscreenCanvasRenderingContext2D');
    if (OC && rawOff) method(OC, 'convertToBlob', real => function (...a) {
      try {
        if (this.width && this.height) {
          const c = new W.OffscreenCanvas(this.width, this.height), x = c.getContext('2d');
          x.drawImage(this, 0, 0); const img = rawOff.call(x, 0, 0, c.width, c.height); noise(img.data); x.putImageData(img, 0, 0);
          return real.apply(c, a);
        }
      } catch (e) {}
      return real.apply(this, a);
    });
    for (const name of ['WebGLRenderingContext', 'WebGL2RenderingContext']) {
      const P = proto(name); if (!P) continue;
      method(P, 'getParameter', real => function (p) { if (p === 37445) return 'Google Inc.'; if (p === 37446) return 'ANGLE (Generic, OpenGL ES)'; return real.call(this, p); });
      method(P, 'readPixels', real => function (...a) { const r = real.apply(this, a); const out = a[6]; if (out && out.length) noise(out); return r; });
    }

    // Audio fingerprinting.
    const touched = new WeakSet();
    method(proto('AudioBuffer'), 'getChannelData', real => function (...a) {
      const data = real.apply(this, a);
      if (!touched.has(data)) { touched.add(data); for (let i = seed % 97; i < data.length; i += 97) data[i] += ((seed >>> (i % 24)) & 1 ? 1 : -1) * 1e-7; }
      return data;
    });
    method(proto('AnalyserNode'), 'getFloatFrequencyData', real => function (arr) { real.call(this, arr); for (let i = 0; i < arr.length; i += 53) arr[i] += ((seed >>> (i % 24)) & 1) * 1e-4; });

    // Workers: start each one through a prelude that runs this shield first.
    let base = ''; try { base = W.location.href; } catch (e) {}
    const shared = new Map();
    const prelude = (abs, module) => SOURCE + '\n' + (module ? 'import ' + JSON.stringify(abs) + ';' :
      // Classic workers load from a blob: URL; resolve the script's relative URLs against its real address.
      '(function(b){const r=u=>{try{return new URL(String(u),b).href}catch(e){return u}};' +
      'const i=self.importScripts;self.importScripts=function(...u){return i.apply(this,u.map(r))};' +
      'if(self.fetch){const f=self.fetch;self.fetch=function(x,o){return f.call(this,typeof x==="string"||x instanceof URL?r(x):x,o)}}' +
      'if(self.XMLHttpRequest){const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u,...a){return o.call(this,m,r(u),...a)}}' +
      '})(' + JSON.stringify(abs) + ');importScripts(' + JSON.stringify(abs) + ');');
    for (const name of ['Worker', 'SharedWorker']) {
      const Real = W[name]; if (typeof Real !== 'function') continue;
      const Fake = disguise(function (url, options) {
        if (!new.target) return Real(url, options);
        let target = url;
        try {
          const abs = new W.URL(String(url), base).href, module = options && typeof options === 'object' && options.type === 'module';
          const key = name + '|' + module + '|' + abs;
          target = shared.get(key);
          if (!target) { target = W.URL.createObjectURL(new W.Blob([prelude(abs, module)], { type: 'text/javascript' })); if (name === 'SharedWorker') shared.set(key, target); }
          return Reflect.construct(Real, [target, options], new.target === Fake ? Real : new.target);
        } catch (e) {
          return Reflect.construct(Real, [url, options], new.target === Fake ? Real : new.target);
        }
      }, Real);
      Fake.prototype = Real.prototype;
      try { Object.defineProperty(W, name, { value: Fake, writable: true, configurable: true }); } catch (e) {}
    }

    // Child frames: shield every same-origin frame the moment it becomes reachable.
    if (W.document) {
      let known = 0;
      const sweep = () => { let n = 0; try { n = W.length; } catch (e) {} if (n === known) return; for (let i = 0; i < n; i++) { try { shield(W[i]); } catch (e) {} } known = n; };
      for (const [name, props] of [['HTMLIFrameElement', ['contentWindow', 'contentDocument']], ['HTMLFrameElement', ['contentWindow', 'contentDocument']], ['HTMLObjectElement', ['contentWindow', 'contentDocument']]]) {
        const P = proto(name); if (!P) continue;
        for (const prop of props) {
          const d = Object.getOwnPropertyDescriptor(P, prop); if (!d || !d.get) continue;
          const get = disguise(function () { const v = d.get.call(this); try { shield(prop === 'contentWindow' ? v : v && v.defaultView); } catch (e) {} return v; }, d.get);
          try { Object.defineProperty(P, prop, { get, configurable: true, enumerable: d.enumerable }); } catch (e) {}
        }
      }
      const after = real => function (...a) { try { return real.apply(this, a); } finally { sweep(); } };
      for (const [name, keys] of [['Node', ['appendChild', 'insertBefore', 'replaceChild']], ['Element', ['append', 'prepend', 'before', 'after', 'replaceWith', 'replaceChildren', 'insertAdjacentElement', 'insertAdjacentHTML']], ['Document', ['write', 'writeln', 'append', 'prepend', 'replaceChildren']], ['DocumentFragment', ['append', 'prepend', 'replaceChildren']]]) {
        for (const k of keys) method(proto(name), k, after);
      }
      for (const [name, prop] of [['Element', 'innerHTML'], ['Element', 'outerHTML'], ['ShadowRoot', 'innerHTML']]) {
        const P = proto(name), d = P && Object.getOwnPropertyDescriptor(P, prop); if (!d || !d.set) continue;
        const set = disguise(function (v) { try { d.set.call(this, v); } finally { sweep(); } }, d.set);
        try { Object.defineProperty(P, prop, { get: d.get, set, configurable: true, enumerable: d.enumerable }); } catch (e) {}
      }
      method(W, 'open', real => function (...a) { const w = real.apply(this, a); try { shield(w); } catch (e) {} return w; });
    }
  }
  shield(self);
})(__SEED__);
