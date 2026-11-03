// MGB chrome.* shim (S4). Wrapped by ContentInjector as:  <this file>(window, CFG)  -> chrome object (or null).
// CFG = { token, kind: 'content' | 'ext', base, id, manifest }
// Everything is forwarded to Kotlin (ChromeBridge) over the MGBBridge WebMessageListener as JSON:
//   out: { t: token, i: id, a: 'storage.local.get', p: [args] }       in: { i, r } | { i, err } | { e: event, p: [args], q?: reqId }
(function (G, CFG) {
  'use strict';
  var bridge = G.MGBBridge;
  if (!bridge || typeof bridge.postMessage !== 'function') return null;
  var post = bridge.postMessage.bind(bridge);
  var isExt = CFG.kind === 'ext';
  var seq = 0, pending = {}, listeners = {}, lastError;
  var slice = Array.prototype.slice;

  function send(obj) { obj.t = CFG.token; post(JSON.stringify(obj)); }
  function call(api, args) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = [resolve, reject];
      try { send({ i: id, a: api, p: args }); } catch (e) { delete pending[id]; reject(e); }
    });
  }
  function log(m) { try { send({ i: 0, a: '__log', p: [String(m)] }); } catch (e) { /* ignore */ } }

  // callback OR promise, like chrome.* in MV3
  function wrap(fn) {
    return function () {
      var args = slice.call(arguments), cb = null;
      if (args.length && typeof args[args.length - 1] === 'function') cb = args.pop();
      var pr = fn.apply(null, args);
      if (!cb) return pr;
      pr.then(function (r) {
        lastError = undefined;
        try { cb(r); } catch (e) { log('cb: ' + (e && e.stack || e)); }
      }, function (e) {
        lastError = { message: String((e && e.message) || e) };
        try { cb(); } catch (x) { log('cb: ' + (x && x.stack || x)); }
        lastError = undefined;
      });
    };
  }
  function method(api) { return wrap(function () { return call(api, slice.call(arguments)); }); }

  function makeEvent(name, notify) {
    var set = listeners[name] = listeners[name] || [];
    return {
      addListener: function (fn) {
        if (typeof fn !== 'function') return;
        if (set.indexOf(fn) < 0) set.push(fn);
        if (notify) call('event.add', [name].concat(slice.call(arguments, 1))).then(null, function () { /* ignore */ });
      },
      removeListener: function (fn) { var i = set.indexOf(fn); if (i >= 0) set.splice(i, 1); },
      hasListener: function (fn) { return set.indexOf(fn) >= 0; },
      hasListeners: function () { return set.length > 0; }
    };
  }

  function fire(name, args) {
    var set = (listeners[name] || []).slice();
    for (var i = 0; i < set.length; i++) { try { set[i].apply(null, args); } catch (e) { log('listener ' + name + ': ' + (e && e.stack || e)); } }
  }

  function deliverMessage(args, reqId) {
    var set = (listeners['runtime.onMessage'] || []).slice();
    var done = false, asyncResp = false;
    function respond(v) {
      if (done) return;
      done = true;
      try { send({ i: 0, a: '__reply', p: [reqId, v === undefined ? null : v] }); } catch (e) { /* ignore */ }
    }
    if (!set.length) { respond(); return; }
    for (var k = 0; k < set.length; k++) {
      try {
        var r = set[k](args[0], args[1] || {}, respond);
        if (r === true) asyncResp = true;
        else if (r && typeof r.then === 'function') { asyncResp = true; r.then(respond, function () { respond(); }); }
      } catch (e) { log('onMessage: ' + (e && e.stack || e)); }
    }
    if (!asyncResp && !done) respond();
  }

  function onNative(ev) {
    var m;
    try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (!m) return;
    if (m.e) {
      var a = m.p || [];
      if (m.e === 'runtime.onMessage' && m.q) { deliverMessage(a, m.q); return; }
      fire(m.e, a);
      if (m.e === 'storage.onChanged' && a[1]) fire('storage.' + a[1] + '.onChanged', [a[0]]);
      return;
    }
    var p = pending[m.i];
    if (!p) return;
    delete pending[m.i];
    if (m.err) p[1](new Error(m.err)); else p[0](m.r === null ? undefined : m.r);
  }
  if (typeof bridge.addEventListener === 'function') bridge.addEventListener('message', onNative);
  else bridge.onmessage = onNative;

  // ---------- runtime ----------
  var manifest = CFG.manifest || {};
  var runtime = {
    id: CFG.id,
    getURL: function (p) { return CFG.base + String(p == null ? '' : p).replace(/^\/+/, ''); },
    getManifest: function () { return JSON.parse(JSON.stringify(manifest)); },
    sendMessage: wrap(function (msg) { return call('runtime.sendMessage', [msg === undefined ? null : msg]); }),
    onMessage: makeEvent('runtime.onMessage', isExt)
  };
  Object.defineProperty(runtime, 'lastError', { get: function () { return lastError; }, enumerable: true });

  // ---------- storage ----------
  function area(n) {
    var p = 'storage.' + n + '.';
    return {
      get: wrap(function (k) { return call(p + 'get', [k === undefined ? null : k]); }),
      set: wrap(function (o) { return call(p + 'set', [o]); }),
      remove: wrap(function (k) { return call(p + 'remove', [k]); }),
      clear: wrap(function () { return call(p + 'clear', []); }),
      getBytesInUse: wrap(function (k) { return call(p + 'getBytesInUse', [k === undefined ? null : k]); }),
      onChanged: makeEvent('storage.' + n + '.onChanged', false)
    };
  }
  var storage = { local: area('local'), sync: area('sync'), session: area('session'), onChanged: makeEvent('storage.onChanged', false) };

  // ---------- i18n (S15): messages come from the extension's _locales (CFG.messages, built by Kotlin) ----------
  var msgs = CFG.messages || {};
  var i18n = {
    getMessage: function (key, subs) {
      var e = msgs[String(key).toLowerCase()];
      if (!e) return '';
      var s = String(e.m || '');
      s = s.replace(/\$([A-Za-z0-9_@]+)\$/g, function (x, n) { var c = e.p && e.p[n.toLowerCase()]; return c === undefined ? '' : c; });
      var a = subs === undefined ? [] : (Array.isArray(subs) ? subs : [subs]);
      s = s.replace(/\$([1-9])/g, function (x, d) { return a[d - 1] !== undefined ? String(a[d - 1]) : ''; });
      return s.replace(/\$\$/g, '$');
    },
    getUILanguage: function () { return (G.navigator && G.navigator.language) || 'en'; },
    getAcceptLanguages: wrap(function () { return Promise.resolve([(G.navigator && G.navigator.language) || 'en']); }),
    detectLanguage: wrap(function () { return Promise.resolve({ isReliable: false, languages: [] }); })
  };

  var chrome = { runtime: runtime, storage: storage, i18n: i18n, __mgbLog: log };

  // ---------- extension pages only: everything else is forwarded to Kotlin ----------
  if (isExt) {
    runtime.getPlatformInfo = wrap(function () { return Promise.resolve({ os: 'android', arch: 'arm64', nacl_arch: 'arm' }); });
    runtime.openOptionsPage = method('runtime.openOptionsPage');
    runtime.getContexts = method('runtime.getContexts');
    runtime.onInstalled = makeEvent('runtime.onInstalled', true);
    runtime.onStartup = makeEvent('runtime.onStartup', true);
    var NS = {
      tabs: [['sendMessage', 'create', 'update', 'getCurrent', 'remove', 'get', 'query', 'reload', 'captureVisibleTab', 'executeScript'], ['onRemoved', 'onUpdated', 'onCreated', 'onActivated']],
      downloads: [['search', 'download', 'show', 'resume', 'removeFile', 'pause', 'cancel', 'erase', 'open'], ['onChanged', 'onCreated']],
      declarativeNetRequest: [['updateSessionRules', 'updateDynamicRules', 'getSessionRules', 'getDynamicRules', 'getEnabledRulesets', 'updateEnabledRulesets'], []],
      scripting: [['executeScript', 'insertCSS', 'registerContentScripts', 'unregisterContentScripts', 'getRegisteredContentScripts'], []],
      sidePanel: [['open', 'setOptions', 'setPanelBehavior'], []],
      action: [['setBadgeText', 'setBadgeBackgroundColor', 'setIcon', 'setTitle'], []],
      notifications: [['create', 'clear', 'update'], ['onClicked', 'onClosed', 'onButtonClicked']],
      contextMenus: [['create', 'update', 'remove', 'removeAll'], ['onClicked']],
      alarms: [['create', 'get', 'getAll', 'clear', 'clearAll'], ['onAlarm']],
      windows: [['create', 'get', 'getCurrent', 'update'], []],
      permissions: [['contains', 'request', 'getAll'], []],
      offscreen: [['createDocument', 'closeDocument', 'hasDocument'], []],
      commands: [['getAll'], ['onCommand']],
      webRequest: [[], ['onBeforeRequest', 'onBeforeSendHeaders', 'onHeadersReceived', 'onCompleted', 'onErrorOccurred']],
      cookies: [['get', 'getAll', 'set', 'remove', 'getAllCookieStores'], ['onChanged']] // N1: Novel grabber
    };
    Object.keys(NS).forEach(function (ns) {
      var o = chrome[ns] = {};
      NS[ns][0].forEach(function (m) { o[m] = method(ns + '.' + m); });
      NS[ns][1].forEach(function (e) { o[e] = makeEvent(ns + '.' + e, true); });
    });
    // N1: a function cannot cross the JSON bridge -> executeScript sends the source text of `func` (Kotlin runs it in the tab).
    function injection(inj) {
      var o = {}, k; inj = inj || {};
      for (k in inj) o[k] = inj[k];
      if (typeof o.func === 'function') o.func = '(' + o.func.toString() + ')';
      return o;
    }
    chrome.scripting.executeScript = wrap(function (inj) { return call('scripting.executeScript', [injection(inj)]); });
    // MV2 style chrome.tabs.executeScript(tabId, {code|file}) -> same path
    chrome.tabs.executeScript = wrap(function (tabId, d) {
      d = d || {};
      var inj = { target: { tabId: tabId } };
      if (d.code) inj.func = '(function(){ return eval(' + JSON.stringify(String(d.code)) + '); })';
      else if (d.file) inj.files = [d.file];
      return call('scripting.executeScript', [injection(inj)]).then(function (r) { return (r || []).map(function (x) { return x.result; }); });
    });
  }

  try { send({ i: 0, a: '__hello', p: [CFG.kind] }); } catch (e) { return null; }
  try { G.addEventListener('pagehide', function () { try { send({ i: 0, a: '__bye', p: [] }); } catch (e) { /* ignore */ } }); } catch (e) { /* ignore */ }
  return chrome;
})
