/* Cartes NNN — appli Android.
 * Ce petit script est chargé par la page de l'appli (app.html).
 * Il remplace Tampermonkey : il fournit au jeu les mêmes fonctions (GM_getValue, GM_setValue, GM_xmlhttpRequest…)
 * puis charge le jeu, qui est exactement le même fichier que le script du PC. */
(function () {
  if (window.top !== window || window.__nnnBoot) return;
  if (location.hostname !== 'www.jeuxvideo.com' || location.pathname.indexOf('/forums/') !== 0) return;
  var B = window.NNNApp;
  if (!B || !B.postMessage) return;
  window.__nnnBoot = 1;
  var P = 'nnnapp:', cbs = {}, seq = 0, restoreCb = null;

  function send(o) { try { B.postMessage(JSON.stringify(o)); } catch (e) {} }

  // ---- stockage : dans la page (lecture immédiate) + copie de secours dans l'appli ----
  window.GM_getValue = function (k, d) {
    try { var v = localStorage.getItem(P + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; }
  };
  window.GM_setValue = function (k, v) {
    var j = JSON.stringify(v === undefined ? null : v);
    try { localStorage.setItem(P + k, j); } catch (e) {}
    send({ t: 'set', k: String(k), v: j });
  };
  window.GM_deleteValue = function (k) { try { localStorage.removeItem(P + k); } catch (e) {} send({ t: 'del', k: String(k) }); };
  window.GM_registerMenuCommand = function () {};
  window.GM_info = { script: { name: 'Cartes NNN', version: '' }, scriptHandler: 'CartesNNN-Android' };

  // ---- requêtes réseau : faites par l'appli (pas de blocage CORS, comme Tampermonkey) ----
  function b64(blob) {
    return new Promise(function (res) {
      var r = new FileReader();
      r.onload = function () { res(String(r.result).split(',')[1] || ''); };
      r.onerror = function () { res(''); };
      r.readAsDataURL(blob);
    });
  }
  function done(m) {
    var o = cbs[m.id]; delete cbs[m.id];
    if (!o) return;
    if (m.err) {
      var f = m.err === 'timeout' ? (o.ontimeout || o.onerror) : o.onerror;
      if (f) try { f({ status: 0, error: m.err, responseText: '' }); } catch (e) { console.error(e); }
      return;
    }
    var r = { status: m.status, statusText: '', responseText: m.text || '', finalUrl: m.url || o.url, responseHeaders: m.headers || '', readyState: 4 };
    if (m.b64 != null) {
      var bin = atob(m.b64), u = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
      r.response = o.responseType === 'arraybuffer' ? u.buffer : new Blob([u], { type: m.type || '' });
      r.responseText = '';
    } else r.response = r.responseText;
    if (o.onload) try { o.onload(r); } catch (e) { console.error(e); }
  }
  window.GM_xmlhttpRequest = function (o) {
    var id = ++seq; cbs[id] = o;
    var q = { t: 'xhr', id: id, method: String(o.method || 'GET').toUpperCase(), url: String(o.url), headers: o.headers || {}, rt: o.responseType || '', timeout: o.timeout || 30000 };
    var d = o.data;
    if (d != null && typeof FormData !== 'undefined' && d instanceof FormData) {
      var parts = [], jobs = [];
      d.forEach(function (v, k) {
        if (typeof v === 'string') parts.push({ n: k, v: v });
        else { var p = { n: k, f: v.name || 'fichier', type: v.type || 'application/octet-stream' }; parts.push(p); jobs.push(b64(v).then(function (x) { p.b64 = x; })); }
      });
      Promise.all(jobs).then(function () { q.form = parts; send(q); });
    } else {
      if (d != null) q.body = String(d);
      send(q);
    }
    return { abort: function () { delete cbs[id]; } };
  };

  B.onmessage = function (ev) {
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m.t === 'xhr') done(m);
    else if (m.t === 'restore' && restoreCb) restoreCb(m.vals || {});
  };

  // ---- téléchargements (sauvegarde, image du classeur) : enregistrés dans « Téléchargements » ----
  function grab(a) {
    var href = a.href || '', name = a.getAttribute('download') || 'fichier';
    if (!/^(blob|data):/.test(href)) return false;
    fetch(href).then(function (r) { return r.blob(); }).then(b64).then(function (x) { send({ t: 'save', name: name, b64: x }); });
    return true;
  }
  var oc = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () { if (this.hasAttribute('download') && grab(this)) return; return oc.apply(this, arguments); };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a[download]');
    if (a && grab(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // ---- bouton retour du téléphone ----
  window.nnnAppBack = function () {
    var ov = document.querySelector('.bb-ov');
    if (!ov) return 'nav';
    var info = ov.querySelector('.du-info, .du-pick .du-cancel');
    if (info) { if (info.classList.contains('du-cancel')) info.click(); else info.remove(); return 'ok'; }
    if (ov.querySelector('.bb-duel')) return 'ok';   // pendant un duel : on quitte avec le ✕ de l'arène
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    return 'ok';
  };

  // ---- chargement du jeu ----
  function load() {
    if (window.__nnnGameStarted) return;
    var s = document.createElement('script'), ok = false;
    s.src = '/__nnnapp__/game.js';
    s.onload = function () { ok = true; };
    var w = setInterval(function () { if (window.__nnnGameRan) { clearInterval(w); if (window.nnnAfterGame) window.nnnAfterGame(); } }, 300);
    s.onerror = function () { ok = true; if (!window.__nnnGameStarted) send({ t: 'inject' }); };
    (document.body || document.documentElement).appendChild(s);
    setTimeout(function () { if (!ok && !window.__nnnGameStarted) send({ t: 'inject' }); }, 45000);
  }
  function boot() {
    // si la page a perdu ses données (cache vidé…), on les reprend dans la copie de l'appli avant de lancer le jeu
    restoreCb = function (vals) {
      restoreCb = null;
      try { for (var k in vals) if (localStorage.getItem(P + k) == null) localStorage.setItem(P + k, vals[k]); } catch (e) {}
      if (window.nnnBeforeGame) window.nnnBeforeGame(load); else load();
    };
    send({ t: 'restore' });
    setTimeout(function () { if (restoreCb) restoreCb({}); }, 2500);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
