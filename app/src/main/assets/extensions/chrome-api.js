/* Chrome extension API layer. Runs in an extension's isolated world on websites
 * (content scripts) or in the main world of the extension's own pages
 * (background, popup, options, offscreen). Every privileged operation goes to
 * native code, which identifies the extension from the registered listener and
 * verified frame origin, never from values in these messages. */
function VoodooExtension(config, entries) {
  'use strict';
  const G = globalThis, bridge = config.context === 'content' ? G.VoodooExt : G.VoodooExtPage;
  if (!bridge) return;
  if (G.__voodooExtensionReady) return;
  Object.defineProperty(G, '__voodooExtensionReady', { value: true });
  const manifest = config.manifest, base = config.base, isContent = config.context === 'content';
  const pending = new Map(), ports = new Map();
  let seq = 0, lastError = null, tabId = -1, frameId = 0;

  function call(op, args) {
    const id = String(++seq);
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      try { bridge.postMessage(JSON.stringify(Object.assign({}, args || {}, { id, op, url: location.href, top: G.top === G }))); }
      catch (e) { pending.delete(id); reject(e); }
    });
  }
  function post(op, args) { try { bridge.postMessage(JSON.stringify(Object.assign({}, args || {}, { op, url: location.href, top: G.top === G }))); } catch (e) {} }
  const clone = x => x === undefined ? undefined : JSON.parse(JSON.stringify(x));

  /* Promise + callback styles, with runtime.lastError set during callbacks. */
  function api(fn) {
    return function (...args) {
      const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
      const p = Promise.resolve().then(() => fn(...args));
      if (!cb) return p;
      p.then(v => cb(v), e => { lastError = { message: String(e && e.message || e) }; try { cb(); } finally { lastError = null; } });
    };
  }
  class Event {
    constructor() { this.l = []; }
    addListener(f) { if (typeof f === 'function' && !this.l.includes(f)) this.l.push(f); }
    removeListener(f) { this.l = this.l.filter(x => x !== f); }
    hasListener(f) { return this.l.includes(f); }
    hasListeners() { return this.l.length > 0; }
    emit(...a) { for (const f of this.l.slice()) try { f(...a); } catch (e) { console.error(e); } }
  }
  const event = () => new Event();

  // ----- runtime messaging -----
  const onMessage = event(), onConnect = event(), onInstalled = event(), onStartup = event();
  function deliver(message, sender, respond) {
    let done = false, async = false;
    const sendResponse = r => { if (!done) { done = true; respond(r === undefined ? null : clone(r), false); } };
    for (const f of onMessage.l.slice()) {
      let r;
      try { r = f(clone(message), sender, sendResponse); } catch (e) { console.error(e); continue; }
      if (r === true) async = true;
      else if (r && typeof r.then === 'function') { async = true; r.then(sendResponse, e => sendResponse({ __voodooError: String(e && e.message || e) })); }
    }
    if (!done && !async) { done = true; respond(null, true); }
  }
  class Port {
    constructor(id, name, sender) {
      this.id = id; this.name = name; this.sender = sender; this.onMessage = event(); this.onDisconnect = event(); this.open = true;
      ports.set(id, this);
    }
    postMessage(m) { if (!this.open) throw Error('Attempting to use a disconnected port object'); post('port-post', { portId: this.id, message: clone(m) }); }
    disconnect() { if (!this.open) return; this.open = false; ports.delete(this.id); post('port-disconnect', { portId: this.id }); }
  }
  const newPortId = () => Math.random().toString(36).slice(2) + Date.now().toString(36);
  function connect(target, info) {
    const p = new Port(newPortId(), info && info.name || '', null);
    post('connect', Object.assign({ portId: p.id, name: p.name }, target));
    return p;
  }

  bridge.onmessage = ev => {
    let r; try { r = JSON.parse(ev.data); } catch { return; }
    if (r.event) { incoming(r); return; }
    const p = pending.get(r.id); if (!p) return; pending.delete(r.id);
    r.ok ? p.resolve(r.data === null ? undefined : r.data) : p.reject(Error(r.error || 'Extension operation failed.'));
  };
  function incoming(r) {
    switch (r.event) {
      case 'message':
        deliver(r.message, r.sender, (response, none) => post('respond', { mid: r.mid, response, none }));
        break;
      case 'connect': { const p = new Port(r.portId, r.name, r.sender); onConnect.emit(p); break; }
      case 'port-message': { const p = ports.get(r.portId); if (p) p.onMessage.emit(clone(r.message), p); break; }
      case 'port-disconnect': { const p = ports.get(r.portId); if (p) { p.open = false; ports.delete(r.portId); p.onDisconnect.emit(p); } break; }
      case 'storage': storageChanged.emit(r.changes, r.area); areas[r.area] && areas[r.area].onChanged.emit(r.changes); break;
      case 'tabs': (tabEvents[r.type] || event()).emit(...r.args); break;
      case 'installed': onInstalled.emit(r.details); break;
      case 'startup': onStartup.emit(); break;
      case 'action-clicked': actionClicked.emit(r.tab); break;
      case 'menu-clicked': menuClicked.emit(r.info, r.tab); { const f = menuHandlers.get(String(r.info.menuItemId)); if (f) try { f(r.info, r.tab); } catch (e) { console.error(e); } } break;
      case 'command': commandEvent.emit(r.command, r.tab); break;
      case 'exec': exec(r); break;
    }
  }
  function exec(r) {
    try { r.remove ? removeCss(r.css) : addCss(r.css); post('exec-result', { eid: r.eid, result: null }); }
    catch (e) { post('exec-result', { eid: r.eid, result: null, error: String(e && e.message || e) }); }
  }
  /* chrome.scripting.executeScript({func}) is run by native code in this world without eval; it reports here. */
  if (isContent) Object.defineProperty(G, '__voodooExecDone', { value: (eid, result, error) => post('exec-result', { eid, result: result === undefined ? null : clone(result), error: error || undefined }) });

  // ----- storage -----
  const storageChanged = event();
  function area(name) {
    return {
      get: api(keys => call('storage-get', { area: name, keys: keys === undefined ? null : keys })),
      getBytesInUse: api(keys => call('storage-bytes', { area: name, keys: keys === undefined ? null : keys })),
      set: api(items => call('storage-set', { area: name, items: clone(items) })),
      remove: api(keys => call('storage-remove', { area: name, keys: [].concat(keys) })),
      clear: api(() => call('storage-clear', { area: name })),
      setAccessLevel: api(() => undefined),
      getKeys: api(() => call('storage-get', { area: name, keys: null }).then(o => Object.keys(o || {}))),
      onChanged: event(), QUOTA_BYTES: 10485760
    };
  }
  const areas = { local: area('local'), sync: area('sync'), session: area('session'), managed: area('managed') };
  areas.sync.QUOTA_BYTES = 102400; areas.sync.QUOTA_BYTES_PER_ITEM = 8192; areas.sync.MAX_ITEMS = 512;

  // ----- i18n -----
  function getMessage(name, subs) {
    if (name === '@@extension_id') return config.id;
    if (name === '@@ui_locale') return config.uiLang.replace('-', '_');
    if (name === '@@bidi_dir') return 'ltr';
    if (name === '@@bidi_reversed_dir') return 'rtl';
    if (name === '@@bidi_start_edge') return 'left';
    if (name === '@@bidi_end_edge') return 'right';
    const m = config.messages[String(name).toLowerCase()]; if (!m) return '';
    const list = subs === undefined ? [] : [].concat(subs).map(String);
    let text = String(m.message || '');
    for (const [k, v] of Object.entries(m.placeholders || {})) text = text.replace(new RegExp('\\$' + k + '\\$', 'gi'), String(v.content || ''));
    return text.replace(/\$(\d)/g, (_, n) => list[n - 1] || '').replace(/\$\$/g, '$');
  }

  // ----- tabs, windows, action, menus -----
  const tabEvents = { created: event(), updated: event(), activated: event(), removed: event(), highlighted: event(), moved: event(), replaced: event(), attached: event(), detached: event(), zoomChange: event() };
  const actionClicked = event(), menuClicked = event(), commandEvent = event(), menuHandlers = new Map();
  function scriptTarget(details) { const t = details && details.target || {}; return { tabId: t.tabId, frameIds: t.frameIds, allFrames: !!t.allFrames }; }
  function tabsExecute(tab, details) {
    if (typeof tab === 'object' && tab !== null) { details = tab; tab = undefined; }
    return call('execute', { target: { tabId: tab, allFrames: !!(details && details.allFrames), frameIds: details && details.frameId !== undefined ? [details.frameId] : undefined }, code: details && details.code, files: details && details.file ? [details.file] : undefined })
      .then(results => results.map(r => r.result));
  }
  const action = {
    setBadgeText: api(d => call('action-set', { key: 'badgeText', value: d && d.text || '', tabId: d && d.tabId })),
    getBadgeText: api(d => call('action-get', { key: 'badgeText', tabId: d && d.tabId }).then(v => v || '')),
    setBadgeBackgroundColor: api(() => undefined), getBadgeBackgroundColor: api(() => [0, 0, 0, 0]),
    setBadgeTextColor: api(() => undefined), getBadgeTextColor: api(() => [255, 255, 255, 255]),
    setTitle: api(d => call('action-set', { key: 'title', value: d && d.title || '', tabId: d && d.tabId })),
    getTitle: api(d => call('action-get', { key: 'title', tabId: d && d.tabId }).then(v => v || ((manifest.action || manifest.browser_action || manifest.page_action || {}).default_title || manifest.name))),
    setIcon: api(() => undefined),
    setPopup: api(d => call('action-set', { key: 'popup', value: d && d.popup || '', tabId: d && d.tabId })),
    getPopup: api(d => call('action-get', { key: 'popup', tabId: d && d.tabId }).then(v => v || '')),
    enable: api(() => call('action-set', { key: 'enabled', value: true })), disable: api(() => call('action-set', { key: 'enabled', value: false })),
    isEnabled: api(() => call('action-get', { key: 'enabled' }).then(v => v !== false)),
    show: api(() => undefined), hide: api(() => undefined),
    openPopup: api(() => call('open-popup')), getUserSettings: api(() => ({ isOnToolbar: true })),
    onClicked: actionClicked
  };
  const menus = {
    create(props, cb) {
      const id = props.id !== undefined ? String(props.id) : 'm' + (++seq);
      if (props.onclick) menuHandlers.set(id, props.onclick);
      const safe = Object.assign({}, props, { id }); delete safe.onclick;
      call('menu-create', { props: safe }).then(() => cb && cb(), e => { lastError = { message: e.message }; try { cb && cb(); } finally { lastError = null; } });
      return id;
    },
    update: api((id, props) => { const s = Object.assign({}, props); if (s.onclick) { menuHandlers.set(String(id), s.onclick); delete s.onclick; } return call('menu-update', { menuId: String(id), props: s }); }),
    remove: api(id => { menuHandlers.delete(String(id)); return call('menu-remove', { menuId: String(id) }); }),
    removeAll: api(() => { menuHandlers.clear(); return call('menu-remove-all'); }),
    onClicked: menuClicked, onShown: event(), onHidden: event(), refresh: () => {},
    ContextType: { ALL: 'all', PAGE: 'page', FRAME: 'frame', SELECTION: 'selection', LINK: 'link', EDITABLE: 'editable', IMAGE: 'image', VIDEO: 'video', AUDIO: 'audio', ACTION: 'action', BROWSER_ACTION: 'browser_action', PAGE_ACTION: 'page_action' },
    ItemType: { NORMAL: 'normal', CHECKBOX: 'checkbox', RADIO: 'radio', SEPARATOR: 'separator' }
  };
  const inert = () => ({ addListener() {}, removeListener() {}, hasListener: () => false, hasListeners: () => false });

  const runtime = {
    get id() { return config.id; },
    get lastError() { return lastError || undefined; },
    getURL: path => base + String(path || '').replace(/^\//, ''),
    getManifest: () => clone(manifest),
    sendMessage: api((...a) => {
      let extId = null; if (typeof a[0] === 'string' && a.length > 1 && /^[a-p]{32}$/.test(a[0])) extId = a.shift();
      if (extId && extId !== config.id) throw Error('Cross-extension messaging is not supported.');
      return call('send-message', { target: 'runtime', message: clone(a[0]) }).then(unwrap);
    }),
    connect: (a, b) => { if (typeof a === 'string') a = b; return connect({ target: 'runtime' }, a); },
    onMessage, onConnect, onInstalled, onStartup,
    onMessageExternal: inert(), onConnectExternal: inert(), onSuspend: event(), onSuspendCanceled: event(), onUpdateAvailable: event(), onRestartRequired: event(), onUserScriptMessage: inert(),
    getPlatformInfo: api(() => ({ os: 'android', arch: 'arm64', nacl_arch: 'arm' })),
    getBrowserInfo: api(() => ({ name: 'Voodoo', vendor: 'Voodoo', version: '0.3.7' })),
    openOptionsPage: api(() => call('open-options')),
    setUninstallURL: api(() => undefined),
    reload: () => post('reload'),
    requestUpdateCheck: api(() => ({ status: 'no_update' })),
    getContexts: api(() => []),
    getBackgroundPage: api(() => config.context === 'background' ? G : null),
    getPackageDirectoryEntry: api(() => { throw Error('Not supported on Android.'); }),
    restart: () => {}, restartAfterDelay: api(() => undefined),
    PlatformOs: { ANDROID: 'android', LINUX: 'linux', MAC: 'mac', WIN: 'win', CROS: 'cros' },
    OnInstalledReason: { INSTALL: 'install', UPDATE: 'update', CHROME_UPDATE: 'chrome_update', SHARED_MODULE_UPDATE: 'shared_module_update' }
  };
  function unwrap(r) { if (r && r.__voodooError) throw Error(r.__voodooError); return r; }

  const chrome = {
    runtime,
    storage: Object.assign({ onChanged: storageChanged }, areas),
    i18n: {
      getMessage, getUILanguage: () => config.uiLang,
      getAcceptLanguages: api(() => [config.uiLang, config.uiLang.split('-')[0]]),
      detectLanguage: api(() => ({ isReliable: false, languages: [] }))
    },
    extension: {
      getURL: runtime.getURL, inIncognitoContext: false, lastError: undefined,
      getBackgroundPage: () => config.context === 'background' ? G : null,
      getViews: () => [G], isAllowedIncognitoAccess: api(() => false), isAllowedFileSchemeAccess: api(() => false),
      sendMessage: runtime.sendMessage, onMessage, onRequest: onMessage, sendRequest: runtime.sendMessage
    }
  };
  if (!isContent) Object.assign(chrome, {
    tabs: Object.assign({
      query: api(q => call('tabs-query', { query: q || {} })),
      get: api(id => call('tabs-get', { tabId: id })),
      getCurrent: api(() => call('tabs-current')),
      create: api(p => call('tabs-create', { props: p || {} })),
      update: api((id, p) => { if (typeof id === 'object') { p = id; id = undefined; } return call('tabs-update', { tabId: id, props: p || {} }); }),
      remove: api(ids => call('tabs-remove', { tabIds: [].concat(ids) })),
      reload: api((id, p) => { if (typeof id === 'object') { p = id; id = undefined; } return call('tabs-reload', { tabId: id, bypassCache: !!(p && p.bypassCache) }); }),
      duplicate: api(id => call('tabs-get', { tabId: id }).then(t => call('tabs-create', { props: { url: t.url } }))),
      sendMessage: api((id, message, options) => call('send-message', { target: 'tab', tabId: id, frameId: options && options.frameId, message: clone(message) }).then(unwrap)),
      sendRequest: api((id, message) => call('send-message', { target: 'tab', tabId: id, message: clone(message) }).then(unwrap)),
      connect: (id, info) => connect({ target: 'tab', tabId: id, frameId: info && info.frameId }, info),
      executeScript: api(tabsExecute),
      insertCSS: api((tab, d) => { if (typeof tab === 'object') { d = tab; tab = undefined; } return call('execute', { target: { tabId: tab, allFrames: !!(d && d.allFrames) }, css: d && d.code, cssFiles: d && d.file ? [d.file] : undefined }).then(() => undefined); }),
      removeCSS: api((tab, d) => { if (typeof tab === 'object') { d = tab; tab = undefined; } return call('execute', { target: { tabId: tab, allFrames: !!(d && d.allFrames) }, css: d && d.code, cssFiles: d && d.file ? [d.file] : undefined, remove: true }).then(() => undefined); }),
      captureVisibleTab: api(() => call('tabs-capture')),
      getZoom: api(() => 1), setZoom: api(() => undefined), getZoomSettings: api(() => ({ mode: 'automatic', scope: 'per-origin' })), setZoomSettings: api(() => undefined),
      detectLanguage: api(() => 'und'), discard: api(id => call('tabs-get', { tabId: id })), goBack: api(id => call('tabs-nav', { tabId: id, dir: -1 })), goForward: api(id => call('tabs-nav', { tabId: id, dir: 1 })),
      highlight: api(info => call('tabs-update', { tabId: [].concat(info.tabs)[0], props: { active: true } })), move: api(id => call('tabs-get', { tabId: id })),
      group: api(() => -1), ungroup: api(() => undefined), getSelected: api(() => call('tabs-query', { query: { active: true } }).then(t => t[0])),
      getAllInWindow: api(() => call('tabs-query', { query: {} })),
      TAB_ID_NONE: -1, MAX_CAPTURE_VISIBLE_TAB_CALLS_PER_SECOND: 2
    }, Object.fromEntries(Object.entries(tabEvents).map(([k, v]) => ['on' + k[0].toUpperCase() + k.slice(1), v])), { onSelectionChanged: tabEvents.activated, onActiveChanged: tabEvents.activated }),
    windows: {
      WINDOW_ID_NONE: -1, WINDOW_ID_CURRENT: -2,
      get: api((id, q) => call('windows-get', { populate: !!(q && q.populate) })),
      getCurrent: api(q => call('windows-get', { populate: !!(q && q.populate) })),
      getLastFocused: api(q => call('windows-get', { populate: !!(q && q.populate) })),
      getAll: api(q => call('windows-get', { populate: !!(q && q.populate) }).then(w => [w])),
      create: api(p => { const url = p && [].concat(p.url || [])[0]; return (url ? call('tabs-create', { props: { url } }) : Promise.resolve()).then(() => call('windows-get', { populate: true })); }),
      update: api(() => call('windows-get', { populate: false })), remove: api(() => undefined),
      onCreated: event(), onRemoved: event(), onFocusChanged: event(), onBoundsChanged: event()
    },
    scripting: {
      executeScript: api(d => call('execute', { target: scriptTarget(d), files: d.files, func: d.func ? String(d.func) : undefined, args: clone(d.args || []), world: d.world })),
      insertCSS: api(d => call('execute', { target: scriptTarget(d), css: d.css, cssFiles: d.files }).then(() => undefined)),
      removeCSS: api(d => call('execute', { target: scriptTarget(d), css: d.css, cssFiles: d.files, remove: true }).then(() => undefined)),
      registerContentScripts: api(list => call('scripts-register', { scripts: clone(list) })),
      updateContentScripts: api(list => call('scripts-update', { scripts: clone(list) })),
      unregisterContentScripts: api(f => call('scripts-unregister', { ids: f && f.ids || null })),
      getRegisteredContentScripts: api(f => call('scripts-get', { ids: f && f.ids || null })),
      ExecutionWorld: { ISOLATED: 'ISOLATED', MAIN: 'MAIN' }
    },
    action, browserAction: action, pageAction: action,
    contextMenus: menus, menus,
    alarms: alarms(),
    notifications: {
      create: api((id, opts) => { if (typeof id === 'object') { opts = id; id = ''; } id = id || 'n' + Date.now(); return call('notify', { title: opts.title || '', message: opts.message || '' }).then(() => id); }),
      update: api(() => true), clear: api(() => true), getAll: api(() => ({})), getPermissionLevel: api(() => 'granted'),
      onClicked: event(), onClosed: event(), onButtonClicked: event(), onShowSettings: event(), onPermissionLevelChanged: event()
    },
    downloads: {
      download: api(o => call('download', { url: o.url, filename: o.filename || '' })),
      search: api(() => []), pause: api(() => undefined), resume: api(() => undefined), cancel: api(() => undefined), erase: api(() => []), open: api(() => undefined), show: api(() => undefined), showDefaultFolder: () => {},
      setShelfEnabled: () => {}, setUiOptions: api(() => undefined),
      onCreated: event(), onChanged: event(), onErased: event(), onDeterminingFilename: event()
    },
    cookies: {
      get: api(d => call('cookies-get', { url: d.url, name: d.name })),
      getAll: api(d => call('cookies-all', { url: d.url || (d.domain ? 'https://' + d.domain.replace(/^\./, '') + '/' : ''), name: d.name })),
      set: api(d => call('cookies-set', { details: d })),
      remove: api(d => call('cookies-remove', { url: d.url, name: d.name })),
      getAllCookieStores: api(() => [{ id: '0', tabIds: [] }]), onChanged: event()
    },
    permissions: {
      contains: api(p => call('permissions-contains', { permissions: (p && p.permissions) || [], origins: (p && p.origins) || [] })),
      request: api(p => call('permissions-contains', { permissions: (p && p.permissions) || [], origins: (p && p.origins) || [] })),
      remove: api(() => false), getAll: api(() => call('permissions-all')),
      onAdded: event(), onRemoved: event(), addHostAccessRequest: api(() => undefined), removeHostAccessRequest: api(() => undefined)
    },
    declarativeNetRequest: {
      updateDynamicRules: api(o => call('dnr-update', { scope: 'dynamic', remove: o && o.removeRuleIds || [], add: o && o.addRules || [] })),
      getDynamicRules: api(f => call('dnr-get', { scope: 'dynamic', ids: f && f.ruleIds || null })),
      updateSessionRules: api(o => call('dnr-update', { scope: 'session', remove: o && o.removeRuleIds || [], add: o && o.addRules || [] })),
      getSessionRules: api(f => call('dnr-get', { scope: 'session', ids: f && f.ruleIds || null })),
      updateEnabledRulesets: api(o => call('dnr-rulesets', { enable: o && o.enableRulesetIds || [], disable: o && o.disableRulesetIds || [] })),
      getEnabledRulesets: api(() => call('dnr-enabled')),
      updateStaticRules: api(() => undefined), getDisabledRuleIds: api(() => []),
      getAvailableStaticRuleCount: api(() => 300000), isRegexSupported: api(o => { try { new RegExp(o.regex); return { isSupported: true }; } catch { return { isSupported: false, reason: 'syntaxError' }; } }),
      getMatchedRules: api(() => ({ rulesMatchedInfo: [] })), setExtensionActionOptions: api(() => undefined), testMatchOutcome: api(() => ({ matchedRules: [] })),
      onRuleMatchedDebug: event(),
      MAX_NUMBER_OF_DYNAMIC_RULES: 30000, MAX_NUMBER_OF_SESSION_RULES: 5000, MAX_NUMBER_OF_REGEX_RULES: 1000, MAX_NUMBER_OF_ENABLED_STATIC_RULESETS: 50, GUARANTEED_MINIMUM_STATIC_RULES: 30000,
      DYNAMIC_RULESET_ID: '_dynamic', SESSION_RULESET_ID: '_session'
    },
    webRequest: Object.fromEntries(['onBeforeRequest', 'onBeforeSendHeaders', 'onSendHeaders', 'onHeadersReceived', 'onAuthRequired', 'onResponseStarted', 'onBeforeRedirect', 'onCompleted', 'onErrorOccurred', 'onActionIgnored'].map(k => [k, event()])),
    webNavigation: {
      getFrame: api(d => call('tabs-get', { tabId: d.tabId }).then(t => ({ url: t.url, parentFrameId: -1, errorOccurred: false, frameId: 0 }))),
      getAllFrames: api(d => call('tabs-get', { tabId: d.tabId }).then(t => [{ url: t.url, parentFrameId: -1, errorOccurred: false, frameId: 0 }])),
      onBeforeNavigate: tabNav('loading'), onCommitted: tabNav('loading'), onDOMContentLoaded: tabNav('complete'), onCompleted: tabNav('complete'),
      onErrorOccurred: event(), onCreatedNavigationTarget: event(), onReferenceFragmentUpdated: event(), onTabReplaced: event(), onHistoryStateUpdated: event()
    },
    commands: { getAll: api(() => Object.entries(manifest.commands || {}).map(([name, c]) => ({ name, description: c.description || '', shortcut: '' }))), onCommand: commandEvent },
    idle: { queryState: api(() => 'active'), setDetectionInterval: () => {}, getAutoLockDelay: api(() => 0), onStateChanged: event() },
    offscreen: {
      createDocument: api(p => call('offscreen-create', { url: p.url })), closeDocument: api(() => call('offscreen-close')), hasDocument: api(() => call('offscreen-has')),
      Reason: new Proxy({}, { get: (_, k) => String(k) })
    },
    sidePanel: {
      open: api(() => call('open-panel')), setOptions: api(o => call('action-set', { key: 'panel', value: o && o.path || '' })), getOptions: api(() => call('action-get', { key: 'panel' }).then(path => ({ path, enabled: true }))),
      setPanelBehavior: api(() => undefined), getPanelBehavior: api(() => ({ openPanelOnActionClick: false }))
    },
    management: { getSelf: api(() => call('self')), getAll: api(() => call('self').then(s => [s])), onInstalled: event(), onUninstalled: event(), onEnabled: event(), onDisabled: event() },
    history: { search: api(() => []), getVisits: api(() => []), addUrl: api(() => undefined), deleteUrl: api(() => undefined), deleteRange: api(() => undefined), deleteAll: api(() => undefined), onVisited: event(), onVisitRemoved: event() },
    bookmarks: { getTree: api(() => [{ id: '0', title: '', children: [] }]), search: api(() => []), get: api(() => []), getChildren: api(() => []), getRecent: api(() => []), create: api(b => Object.assign({ id: String(Date.now()) }, b)), remove: api(() => undefined), update: api(() => ({})), onCreated: event(), onRemoved: event(), onChanged: event(), onMoved: event() },
    topSites: { get: api(() => []) },
    sessions: { getRecentlyClosed: api(() => []), getDevices: api(() => []), restore: api(() => undefined), onChanged: event(), MAX_SESSION_RESULTS: 25 },
    browsingData: new Proxy({}, { get: (_, k) => k === 'settings' ? api(() => ({ options: {}, dataToRemove: {}, dataRemovalPermitted: {} })) : api(() => undefined) }),
    privacy: { network: {}, services: {}, websites: {} },
    contentSettings: {},
    fontSettings: { getFontList: api(() => []) },
    tts: { speak: api(t => call('notify', { title: 'Speech', message: String(t) })), stop: () => {}, isSpeaking: api(() => false), getVoices: api(() => []), onEvent: event() },
    power: { requestKeepAwake: () => {}, releaseKeepAwake: () => {} },
    system: { cpu: { getInfo: api(() => ({ numOfProcessors: navigator.hardwareConcurrency || 4, archName: 'arm64', modelName: '', features: [], processors: [] })) }, memory: { getInfo: api(() => ({ capacity: 0, availableCapacity: 0 })) }, display: { getInfo: api(() => []) }, storage: { getInfo: api(() => []) } },
    userScripts: { register: api(() => undefined), getScripts: api(() => []), unregister: api(() => undefined), update: api(() => undefined), configureWorld: api(() => undefined) },
    tabGroups: { query: api(() => []), get: api(() => { throw Error('No tab groups.'); }), update: api(() => undefined), onCreated: event(), onUpdated: event(), onRemoved: event(), onMoved: event(), TAB_GROUP_ID_NONE: -1 },
    search: { query: api(q => call('tabs-create', { props: { url: 'https://www.google.com/search?q=' + encodeURIComponent(q.text) } })) },
    readingList: { query: api(() => []), addEntry: api(() => undefined), removeEntry: api(() => undefined), updateEntry: api(() => undefined) },
    dom: { openOrClosedShadowRoot: el => el.shadowRoot }
  });
  else chrome.dom = { openOrClosedShadowRoot: el => el.openOrClosedShadowRoot ? el.openOrClosedShadowRoot() : el.shadowRoot };
  function tabNav(status) { const e = event(); tabEvents.updated.addListener((tabId, info, tab) => { if (info.status === status) e.emit({ tabId, url: tab.url, frameId: 0, parentFrameId: -1, timeStamp: Date.now(), processId: 0 }); }); return e; }

  function alarms() {
    const all = new Map(), onAlarm = event();
    const fire = name => { const a = all.get(name); if (!a) return; onAlarm.emit({ name: a.name, scheduledTime: a.scheduledTime, periodInMinutes: a.periodInMinutes });
      if (a.periodInMinutes) { a.scheduledTime = Date.now() + a.periodInMinutes * 60000; a.timer = setTimeout(() => fire(name), a.periodInMinutes * 60000); } else all.delete(name); };
    return {
      create(name, info) {
        if (typeof name === 'object') { info = name; name = ''; } name = name || ''; info = info || {};
        const old = all.get(name); if (old) clearTimeout(old.timer);
        const when = info.when || Date.now() + (info.delayInMinutes !== undefined ? info.delayInMinutes : info.periodInMinutes || 0) * 60000;
        const a = { name, scheduledTime: when, periodInMinutes: info.periodInMinutes };
        a.timer = setTimeout(() => fire(name), Math.max(0, when - Date.now())); all.set(name, a);
        const cb = arguments[arguments.length - 1]; if (typeof cb === 'function') cb(); return Promise.resolve();
      },
      get: api(name => { const a = all.get(name || ''); return a ? { name: a.name, scheduledTime: a.scheduledTime, periodInMinutes: a.periodInMinutes } : undefined; }),
      getAll: api(() => [...all.values()].map(a => ({ name: a.name, scheduledTime: a.scheduledTime, periodInMinutes: a.periodInMinutes }))),
      clear: api(name => { const a = all.get(name || ''); if (a) { clearTimeout(a.timer); all.delete(name || ''); } return !!a; }),
      clearAll: api(() => { for (const a of all.values()) clearTimeout(a.timer); all.clear(); return true; }),
      onAlarm
    };
  }

  // Extension pages: cross-origin fetch to granted hosts goes through native code like Chrome's host permissions.
  if (!isContent && typeof G.fetch === 'function') {
    const pageFetch = G.fetch.bind(G);
    G.fetch = function (input, init) {
      let url; try { url = new URL(typeof input === 'string' ? input : input.url, location.href); } catch { return pageFetch(input, init); }
      if (url.origin === location.origin || !/^https?:$/.test(url.protocol) || url.protocol === 'blob:' || url.protocol === 'data:') return pageFetch(input, init);
      const req = new Request(input, init);
      return req.arrayBuffer().then(buf => call('fetch', {
        request: { url: url.href, method: req.method, headers: Object.fromEntries(req.headers.entries()), body: buf.byteLength ? btoa(String.fromCharCode(...new Uint8Array(buf))) : '', credentials: (init && init.credentials) || 'include' }
      })).then(r => {
        const bytes = Uint8Array.from(atob(r.base64), c => c.charCodeAt(0));
        const res = new Response([101, 204, 205, 304].includes(r.status) ? null : bytes, { status: r.status, statusText: r.statusText, headers: r.headers });
        Object.defineProperty(res, 'url', { value: r.url }); return res;
      }, e => { throw new TypeError('Failed to fetch: ' + e.message); });
    };
  }

  if (!isContent) {
    const close = G.close && G.close.bind(G);
    G.close = () => { post('close-page'); close && close(); };
  }
  if (config.context === 'background') {
    G.importScripts = (...urls) => { for (const u of urls) { const x = new XMLHttpRequest(); x.open('GET', new URL(u, location.href).href, false); x.send(); if (x.status >= 400) throw Error('importScripts failed: ' + u); (0, eval)(x.responseText + '\n//# sourceURL=' + u); } };
    G.registration = G.registration || { scope: base, showNotification: (title, o) => call('notify', { title, message: o && o.body || '' }), getNotifications: () => Promise.resolve([]), unregister: () => Promise.resolve(false) };
    G.clients = G.clients || { matchAll: () => Promise.resolve([]), claim: () => Promise.resolve(), openWindow: url => call('tabs-create', { props: { url } }) };
    G.skipWaiting = G.skipWaiting || (() => Promise.resolve());
    G.addEventListener('load', () => post('bg-ready'), { once: true });
  }

  Object.defineProperty(G, 'chrome', { value: Object.assign(G.chrome && typeof G.chrome === 'object' ? G.chrome : {}, chrome), configurable: true, writable: true });
  if (!G.browser) Object.defineProperty(G, 'browser', { value: G.chrome, configurable: true, writable: true });

  // ----- content script styles and execution -----
  const sheets = new Map();
  function addCss(css) {
    try { const s = new CSSStyleSheet(); s.replaceSync(css); document.adoptedStyleSheets = [...document.adoptedStyleSheets, s]; sheets.set(css, (sheets.get(css) || []).concat(s)); return; } catch (e) {}
    const el = document.createElement('style'); el.textContent = css; el.dataset.voodooExtension = config.id;
    (document.head || document.documentElement).append(el); sheets.set(css, (sheets.get(css) || []).concat(el));
  }
  function removeCss(css) { for (const s of sheets.get(css) || []) { if (s instanceof Node) s.remove(); else document.adoptedStyleSheets = document.adoptedStyleSheets.filter(x => x !== s); } sheets.delete(css); }
  function whenReady(runAt, fn) {
    if (runAt === 'document_start') { if (document.documentElement) fn(); else new MutationObserver((_, o) => { if (document.documentElement) { o.disconnect(); fn(); } }).observe(document, { childList: true }); }
    else if (runAt === 'document_end') { if (document.readyState !== 'loading') fn(); else document.addEventListener('DOMContentLoaded', fn, { once: true }); }
    else { if (document.readyState === 'complete') fn(); else { let done = false; const go = () => { if (!done) { done = true; fn(); } }; G.addEventListener('load', go, { once: true }); document.addEventListener('DOMContentLoaded', () => setTimeout(go, 200), { once: true }); } }
  }
  function entryMatches(e, url) {
    if (G.top !== G && !e.allFrames) return false;
    const test = list => (list || []).some(r => new RegExp(r).test(url));
    if (!test(e.matches) || test(e.excludeMatches)) return false;
    if (e.includeGlobs && e.includeGlobs.length && !test(e.includeGlobs)) return false;
    return !test(e.excludeGlobs);
  }
  function normalizedUrl() { const u = new URL(location.href); return u.protocol + '//' + u.hostname.toLowerCase().replace(/\.$/, '') + (u.port ? ':' + u.port : '') + u.pathname + u.search; }
  function runEntries() {
    if (!entries || !entries.length || !isContent) return;
    let url; try { url = normalizedUrl(); } catch { return; }
    if (/^about:|^blob:|^data:/.test(location.href) && G.top !== G) { try { url = G.parent.location.href; } catch { return; } }
    for (const e of entries) {
      if (!entryMatches(e, url)) continue;
      if (e.css) whenReady('document_start', () => addCss(e.css));
      if (e.run) whenReady(e.runAt || 'document_idle', () => { try { e.run.call(G); } catch (err) { console.error('[Voodoo extension ' + manifest.name + ']', err); } });
    }
  }
  const hello = () => call('hello').then(info => { tabId = info.tabId; frameId = info.frameId; }, () => {});
  G.addEventListener('pagehide', () => post('bye'));
  G.addEventListener('pageshow', e => { if (e.persisted) hello(); });
  hello();
  runEntries();
}
