/* ─────────────────────────────────────────────────────────────────────────────
 * Audit Log Dashboard — decoupled render layer, backing #admin-audit.
 *
 * Same hard DATA/PRESENTATION split as dept-dashboard.js:
 *   • AuditDashboard.load()            — data fetching + cache
 *   • AuditDashboard.renderGrid / renderPagination / renderSkeleton
 *   • AuditDashboard.openDetailDrawer  — slide-over detail panel (#audit-detail-panel)
 *   • AuditDashboard.verifyRow         — hash-chain verification, GET /verify
 *   • AuditDashboard.start()           — lifecycle: bind toolbar/grid once, load
 *
 * Supersedes the old renderAuditLogTable/fetchAndRenderAuditLog/
 * toggleAuditRowDetails/getActionBadgeClass in frontend_api.js — those are
 * removed, not kept alongside this. formatAuditDetails' diff logic is ported
 * here verbatim (relocated, not reimplemented).
 * ───────────────────────────────────────────────────────────────────────────*/
(function () {
  'use strict';

  var _state = { query: '', startDate: null, endDate: null, offset: 0, limit: 50 };
  var _lastRows = [];
  var _searchDebounce = null;

  var esc = function (s) {
    return (typeof escapeHtml === 'function') ? escapeHtml(String(s == null ? '' : s))
                                              : String(s == null ? '' : s);
  };

  // A manager holds the same system:audit permission as content_admin, but
  // main.py hard-pins their query results to their own department and 403s
  // export/verify/chain-health outright — this mirrors that on the client so
  // the UI never offers controls that would just come back 403.
  function isManager() {
    return !!(window.currentUser && window.currentUser.role === 'manager');
  }

  /* ── Category badge map — same convention as window.statusStylesMap
     (app-renderers.js), but for audit CATEGORY, not content status. ──────── */
  window.auditCategoryStylesMap = {
    SECURITY: { text: 'უსაფრთხოება',   badge: 'bg-rose-50 text-rose-700 border-rose-200/60',       dot: 'bg-rose-500' },
    USER:     { text: 'მომხმარებელი',  badge: 'bg-blue-50 text-blue-700 border-blue-200/60',        dot: 'bg-blue-500' },
    CONTENT:  { text: 'კონტენტი',      badge: 'bg-emerald-50 text-emerald-700 border-emerald-200/60', dot: 'bg-emerald-500' },
    SYSTEM:   { text: 'სისტემა',       badge: 'bg-amber-50 text-amber-700 border-amber-200/60',     dot: 'bg-amber-500' }
  };
  var CATEGORY_FALLBACK = { text: '—', badge: 'bg-gray-50 text-gray-500 border-gray-200/60', dot: 'bg-gray-400' };

  function categoryBadge(cat) {
    var s = (window.auditCategoryStylesMap || {})[cat] || CATEGORY_FALLBACK;
    return '<span class="inline-flex items-center gap-1 rounded-lg border px-2 py-0.5 text-[11px] font-bold ' + s.badge + '">' +
      '<span class="h-1.5 w-1.5 rounded-full ' + s.dot + '"></span>' + esc(s.text) + '</span>';
  }

  /* ── Tokenized search: actor:x / category:x / action:x, rest is free text.
     Hand-rolled, no parsing library — matches this codebase's existing
     hand-rolled-small-utility convention (every debounced search call site
     reimplements its own few lines rather than sharing a helper). ────────── */
  function parseQuery(raw) {
    var tokens = (raw || '').trim().split(/\s+/).filter(Boolean);
    var out = { actor: null, category: null, action: null, free: [] };
    tokens.forEach(function (t) {
      var m = t.match(/^(actor|category|action):(.+)$/i);
      if (!m) { out.free.push(t); return; }
      out[m[1].toLowerCase()] = m[2];
    });
    out.q = out.free.join(' ');
    return out;
  }

  function buildParams(state) {
    var parsed = parseQuery(state.query);
    var p = new URLSearchParams();
    if (parsed.actor) {
      if (/^\d+$/.test(parsed.actor)) p.append('user_id', parsed.actor);
      else p.append('user_name', parsed.actor);
    }
    if (parsed.category) p.append('category', parsed.category.toUpperCase());
    if (parsed.action) p.append('action', parsed.action.toUpperCase());
    if (parsed.q) p.append('q', parsed.q);
    if (state.startDate) p.append('start_date', state.startDate);
    if (state.endDate) p.append('end_date', state.endDate);
    p.append('limit', state.limit);
    p.append('offset', state.offset);
    return p;
  }

  // Exposed so the CSV export button (frontend_api.js) can forward exactly
  // what the grid is currently showing instead of re-reading DOM elements
  // that no longer exist in the old shape.
  function currentParams() { return buildParams(_state); }

  /* ── Data fetching ───────────────────────────────────────────────────────*/

  function currentDateRange() {
    var el = document.getElementById('log-date-range');
    var fp = el && el._flatpickr;
    if (fp && fp.selectedDates.length === 2) {
      return {
        start: fp.formatDate(fp.selectedDates[0], 'Y-m-d'),
        end: fp.formatDate(fp.selectedDates[1], 'Y-m-d')
      };
    }
    return { start: null, end: null };
  }

  function fetchPage(token, state) {
    return fetch('/api/audit-logs?' + buildParams(state).toString(), {
      headers: { Authorization: 'Bearer ' + token }
    }).then(function (r) {
      if (window.handleSessionExpiry && handleSessionExpiry(r)) throw new Error('session expired');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var total = parseInt(r.headers.get('X-Total-Count') || '0', 10);
      return r.json().then(function (rows) { return { rows: rows, total: total }; });
    });
  }

  /* ── Render: grid ────────────────────────────────────────────────────────*/

  function integrityCell(log) {
    if (isManager()) {
      return '<span class="text-gray-300" title="მთლიანობის შემოწმება ხელმისაწვდომია მხოლოდ ადმინისტრატორებისთვის"><i class="fa-solid fa-minus"></i></span>';
    }
    if (!log.row_hash) {
      return '<span class="text-gray-300" title="მიგრაციამდელი ჩანაწერი — ჯაჭვი არ გააჩნია"><i class="fa-solid fa-minus"></i></span>';
    }
    return '<button type="button" class="audit-verify-btn text-gray-400 hover:text-emerald-600 transition-colors" ' +
      'data-log-id="' + esc(log.id) + '" title="მთლიანობის შემოწმება"><i class="fa-solid fa-link"></i></button>';
  }

  function rowHtml(log) {
    var actionLabel = (typeof actionMap !== 'undefined' && actionMap[(log.action || '').toLowerCase()]) || log.action;
    var typeLabel = (typeof typeMap !== 'undefined' && typeMap[(log.item_type || '').toLowerCase()]) || log.item_type;
    var objectLabel = log.item_name ? (typeLabel + ': ' + log.item_name) : typeLabel;
    return '<tr class="audit-row cursor-pointer hover:bg-gray-50/80 transition-colors" data-log-id="' + esc(log.id) + '">' +
      '<td class="px-5 py-2.5 whitespace-nowrap text-xs text-gray-500">' + esc(new Date(log.timestamp).toLocaleString('ka-GE')) + '</td>' +
      '<td class="px-5 py-2.5 text-gray-800 font-medium">' + esc(log.admin_name || 'უცნობი') + '</td>' +
      '<td class="px-5 py-2.5">' + categoryBadge(log.category) + '</td>' +
      '<td class="px-5 py-2.5"><span class="rounded-lg px-2 py-0.5 text-[11px] font-bold border bg-gray-50 text-gray-600 border-gray-200/60">' + esc(actionLabel) + '</span></td>' +
      '<td class="px-5 py-2.5 text-gray-700">' + esc(objectLabel) + '</td>' +
      '<td class="px-5 py-2.5 text-center">' + integrityCell(log) + '</td>' +
    '</tr>';
  }

  function renderGrid(rows) {
    var host = document.getElementById('audit-grid-host');
    if (!host) return;
    if (!rows.length) {
      host.innerHTML = '<div class="px-5 py-10 text-center text-sm text-gray-400">ლოგები არ მოიძებნა</div>';
      return;
    }
    host.innerHTML =
      '<table class="w-full text-left text-sm">' +
        '<thead><tr class="border-b border-gray-200 bg-gray-50 text-xs font-semibold uppercase tracking-wider text-gray-500">' +
          '<th class="px-5 py-3">დრო</th><th class="px-5 py-3">ვინ</th><th class="px-5 py-3">კატეგორია</th>' +
          '<th class="px-5 py-3">ქმედება</th><th class="px-5 py-3">ობიექტი</th>' +
          '<th class="px-5 py-3 w-14 text-center" title="მთლიანობის შემოწმება"><i class="fa-solid fa-shield-halved"></i></th>' +
        '</tr></thead>' +
        '<tbody class="divide-y divide-gray-100">' + rows.map(rowHtml).join('') + '</tbody>' +
      '</table>';
  }

  function renderSkeleton() {
    var host = document.getElementById('audit-grid-host');
    if (!host) return;
    var cell = '<td class="px-5 py-4"><div class="h-4 rounded bg-gray-200/60"></div></td>';
    var row = '<tr class="animate-pulse">' + cell + cell + cell + cell + cell + cell + '</tr>';
    host.innerHTML = '<table class="w-full text-left text-sm"><tbody>' + row.repeat(6) + '</tbody></table>';
  }

  function renderPagination(total, offset, limit) {
    var host = document.getElementById('audit-pagination');
    if (!host) return;
    if (!total) { host.innerHTML = ''; return; }
    var from = offset + 1;
    var to = Math.min(offset + limit, total);
    host.innerHTML =
      '<span>' + from + '–' + to + ' / ' + total + '</span>' +
      '<div class="flex items-center gap-2">' +
        '<button type="button" ' + (offset <= 0 ? 'disabled' : '') +
          ' onclick="AuditDashboard.gotoOffset(' + Math.max(0, offset - limit) + ')" ' +
          'class="rounded-lg border border-gray-200 px-3 py-1.5 font-semibold text-gray-600 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">წინა</button>' +
        '<button type="button" ' + (to >= total ? 'disabled' : '') +
          ' onclick="AuditDashboard.gotoOffset(' + (offset + limit) + ')" ' +
          'class="rounded-lg border border-gray-200 px-3 py-1.5 font-semibold text-gray-600 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed">შემდეგი</button>' +
      '</div>';
  }

  /* ── Render: detail drawer ───────────────────────────────────────────────*/

  // Ported verbatim from the old formatAuditDetails (frontend_api.js) — this
  // already produces the red-old/green-new field diff; not reimplemented.
  function formatAuditDetails(detailsStr) {
    if (!detailsStr) return '<span class="text-gray-400">დეტალები არ არის</span>';
    try {
      var data = typeof detailsStr === 'string' ? JSON.parse(detailsStr) : detailsStr;
      if (data.changed) {
        var html = '<div class="space-y-2 font-sans py-1 text-xs">';
        for (var key in data.changed) {
          if (!Object.prototype.hasOwnProperty.call(data.changed, key)) continue;
          var val = data.changed[key];
          var oldVal = (val.old !== null && val.old !== undefined) ? esc(String(val.old)) : 'NULL';
          var newVal = (val.new !== null && val.new !== undefined) ? esc(String(val.new)) : 'NULL';
          html += '<div class="flex flex-wrap items-center gap-1.5 leading-relaxed">' +
            '<span class="font-bold text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded">' + esc(key) + ':</span>' +
            '<span class="bg-red-50 text-red-700 px-2 py-0.5 rounded border border-red-100 line-through">' + oldVal + '</span>' +
            '<span class="text-gray-400 mx-0.5"><i class="fa-solid fa-arrow-right"></i></span>' +
            '<span class="bg-green-50 text-green-700 px-2 py-0.5 rounded border border-green-100 font-bold">' + newVal + '</span>' +
          '</div>';
        }
        html += '</div>';
        return html;
      }
      return '<pre class="bg-slate-100 p-2.5 rounded-xl text-gray-700 text-[11px] font-mono overflow-x-auto border border-slate-200/50 max-h-48 leading-normal">' +
        esc(JSON.stringify(data, null, 2)) + '</pre>';
    } catch (e) {
      return '<span class="text-gray-500 font-mono">' + esc(detailsStr) + '</span>';
    }
  }

  // Coarse, honest substring-based browser/OS detection — not a real
  // User-Agent parsing library (none is installed; this codebase has no
  // npm pipeline to add one through). Good enough for an admin glance.
  function parseUserAgent(ua) {
    if (!ua) return null;
    var browser = 'უცნობი ბრაუზერი';
    if (/Edg\//.test(ua)) browser = 'Edge';
    else if (/Chrome\//.test(ua) && !/Chromium/.test(ua)) browser = 'Chrome';
    else if (/Firefox\//.test(ua)) browser = 'Firefox';
    else if (/Safari\//.test(ua) && !/Chrome/.test(ua)) browser = 'Safari';
    var os = 'უცნობი OS';
    if (/Windows/.test(ua)) os = 'Windows';
    else if (/Mac OS X/.test(ua)) os = 'macOS';
    else if (/Android/.test(ua)) os = 'Android';
    else if (/iPhone|iPad/.test(ua)) os = 'iOS';
    else if (/Linux/.test(ua)) os = 'Linux';
    return { browser: browser, os: os };
  }

  function verifyStatusBadge(result) {
    var MAP = {
      ok:        { icon: 'fa-shield-halved',      text: 'ჯაჭვი დამოწმებულია',                       cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
      tampered:  { icon: 'fa-triangle-exclamation', text: 'მთლიანობის დარღვევა აღმოჩენილია!',        cls: 'bg-red-50 text-red-700 border-red-200' },
      unchained: { icon: 'fa-minus',              text: 'მიგრაციამდელი ჩანაწერი — ვერიფიკაცია მიუწვდომელია', cls: 'bg-gray-50 text-gray-500 border-gray-200' },
      error:     { icon: 'fa-circle-exclamation', text: 'შემოწმება ვერ შესრულდა',                    cls: 'bg-gray-50 text-gray-500 border-gray-200' }
    };
    var s = MAP[result && result.status] || MAP.error;
    return '<div class="flex items-center gap-2 rounded-xl border px-3 py-2 text-sm font-semibold transition-all duration-300 ' + s.cls + '">' +
      '<i class="fa-solid ' + s.icon + '"></i><span>' + esc(s.text) + '</span></div>';
  }

  function findRow(logId) {
    logId = String(logId);
    for (var i = 0; i < _lastRows.length; i++) {
      if (String(_lastRows[i].id) === logId) return _lastRows[i];
    }
    return null;
  }

  function openDetailDrawer(logId, opts) {
    opts = opts || {};
    var log = findRow(logId);
    var panel = document.getElementById('audit-detail-panel');
    var body = document.getElementById('audit-detail-body');
    if (!panel || !body || !log) return;

    var actionLabel = (typeof actionMap !== 'undefined' && actionMap[(log.action || '').toLowerCase()]) || log.action;
    var typeLabel = (typeof typeMap !== 'undefined' && typeMap[(log.item_type || '').toLowerCase()]) || log.item_type;
    var ua = parseUserAgent(log.user_agent);

    body.innerHTML =
      '<div class="flex flex-wrap items-center gap-2">' +
        categoryBadge(log.category) +
        '<span class="rounded-lg px-2 py-0.5 text-[11px] font-bold border bg-gray-50 text-gray-600 border-gray-200/60">' + esc(actionLabel) + '</span>' +
        '<span class="text-xs text-gray-400">' + esc(new Date(log.timestamp).toLocaleString('ka-GE')) + '</span>' +
      '</div>' +

      '<div class="grid grid-cols-2 gap-3 rounded-xl border border-gray-100 bg-gray-50/60 p-4 text-xs">' +
        '<div><span class="block text-gray-400">მოქმედი პირი</span><span class="font-medium text-gray-800">' + esc(log.admin_name || 'უცნობი') + '</span></div>' +
        '<div><span class="block text-gray-400">ობიექტი</span><span class="font-medium text-gray-800">' + esc(typeLabel) + (log.item_name ? ': ' + esc(log.item_name) : '') + ' (#' + esc(log.item_id) + ')</span></div>' +
        '<div><span class="block text-gray-400">IP მისამართი</span><span class="font-mono text-gray-800">' + esc(log.ip_address || '—') + '</span></div>' +
        '<div><span class="block text-gray-400">მოწყობილობა</span><span class="font-medium text-gray-800">' + (ua ? esc(ua.browser + ' · ' + ua.os) : '—') + '</span></div>' +
      '</div>' +
      (log.user_agent ? '<p class="text-[11px] font-mono text-gray-400 break-all">' + esc(log.user_agent) + '</p>' : '') +

      '<div>' +
        '<h4 class="mb-2 flex items-center gap-1.5 text-xs font-bold text-gray-800"><i class="fa-solid fa-square-poll-horizontal text-[#E30613]"></i>ცვლილებების დეტალები</h4>' +
        '<div class="rounded-xl border border-gray-200/60 bg-white p-4 shadow-sm">' + formatAuditDetails(log.details) + '</div>' +
      '</div>' +

      // Managers don't get this section at all — no dangling row_hash/prev_hash
      // display and no verify button that would just 403 (main.py blocks
      // /verify for role=manager outright; see verify_audit_log's guard).
      (isManager() ? '' :
      '<div>' +
        '<h4 class="mb-2 flex items-center gap-1.5 text-xs font-bold text-gray-800"><i class="fa-solid fa-link text-[#E30613]"></i>მთლიანობის ჯაჭვი</h4>' +
        '<div class="rounded-xl border border-gray-200/60 bg-white p-4 shadow-sm space-y-2">' +
          '<div class="grid grid-cols-1 gap-1 text-[11px] font-mono text-gray-500 break-all">' +
            '<div><span class="text-gray-400">row_hash:</span> ' + esc(log.row_hash || '—') + '</div>' +
            '<div><span class="text-gray-400">prev_hash:</span> ' + esc(log.prev_hash || '—') + '</div>' +
          '</div>' +
          // Unchained (pre-migration) rows get the neutral badge directly —
          // no button that leads to a foregone API round-trip.
          (log.row_hash
            ? '<div id="audit-verify-result"></div>' +
              '<button type="button" onclick="AuditDashboard.verifyRow(\'' + esc(String(log.id)) + '\')" ' +
                'class="w-full rounded-xl border border-gray-200 bg-white px-4 py-2 text-xs font-semibold text-gray-700 hover:bg-gray-50 transition-colors">' +
                '<i class="fa-solid fa-shield-halved mr-1"></i>ჯაჭვის შემოწმება</button>'
            : verifyStatusBadge({ status: 'unchained' })) +
        '</div>' +
      '</div>');

    panel.classList.remove('hidden');
    setTimeout(function () { panel.classList.remove('translate-x-full'); }, 10);
    var backdrop = document.getElementById('admin-panel-backdrop');
    if (backdrop) backdrop.classList.remove('hidden');

    if (opts.autoVerify && log.row_hash && !isManager()) verifyRow(log.id);
  }

  function verifyRow(logId) {
    var resultHost = document.getElementById('audit-verify-result');
    var token = window.Auth && Auth.getToken ? Auth.getToken() : '';
    if (resultHost) resultHost.innerHTML = '<div class="text-xs text-gray-400"><i class="fa-solid fa-spinner fa-spin mr-1"></i>მოწმდება…</div>';
    fetch('/api/audit-logs/' + encodeURIComponent(logId) + '/verify', {
      headers: { Authorization: 'Bearer ' + token }
    }).then(function (r) {
      if (window.handleSessionExpiry && handleSessionExpiry(r)) throw new Error('session expired');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (result) {
      if (resultHost) resultHost.innerHTML = verifyStatusBadge(result);
    }).catch(function (err) {
      console.error('AuditDashboard.verifyRow failed:', err);
      if (resultHost) resultHost.innerHTML = verifyStatusBadge({ status: 'error' });
    });
  }

  /* ── Chain health: batch validation of the last N chained rows ───────────
     One passive GET on mount + manual refresh — replaces click-per-row
     verification as the routine health signal. Deliberately NOT routed
     through CacheStore: a security indicator must never show a stale "ok",
     and it's one cheap call per mount, not per pagination. ────────────────*/

  function chainHealthPill(state, health) {
    var MAP = {
      loading:     { icon: 'fa-spinner fa-spin',        text: 'ჯაჭვი მოწმდება…',              cls: 'bg-gray-50 text-gray-400 border-gray-200' },
      ok:          { icon: 'fa-shield-halved',          text: 'ჯაჭვი გამართულია · ბოლო ' + (health && health.checked || 0), cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
      empty:       { icon: 'fa-shield-halved',          text: 'ჯაჭვი ჯერ ცარიელია',           cls: 'bg-gray-50 text-gray-500 border-gray-200' },
      tampered:    { icon: 'fa-triangle-exclamation',   text: 'მთლიანობის დარღვევა აღმოჩენილია!', cls: 'bg-red-50 text-red-700 border-red-200' },
      unavailable: { icon: 'fa-circle-info',            text: 'შემოწმება მიუწვდომელია (dev)',  cls: 'bg-gray-50 text-gray-400 border-gray-200' },
      error:       { icon: 'fa-circle-exclamation',     text: 'ჯაჭვის შემოწმება ვერ შესრულდა', cls: 'bg-gray-50 text-gray-500 border-gray-200' }
    };
    var s = MAP[state] || MAP.error;
    // Predating the schema is not an integrity failure — the unchained count
    // is informational, tooltip-only, never styled as a warning.
    var title = (health && health.unchained_total > 0)
      ? ' title="' + health.unchained_total + ' ჩანაწერი წინ უსწრებს ჯაჭვს"' : '';
    return '<span class="inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-[11px] font-bold ' + s.cls + '"' + title + '>' +
      '<i class="fa-solid ' + s.icon + '"></i>' + esc(s.text) + '</span>';
  }

  function renderChainHealth(health) {
    var pill = document.getElementById('audit-chain-health');
    var banner = document.getElementById('audit-chain-banner');
    if (banner) { banner.classList.add('hidden'); banner.innerHTML = ''; }
    if (!pill) return;
    if (!health) { pill.innerHTML = chainHealthPill('error'); return; }
    var state = health.status;
    if (state === 'ok' && !health.checked) state = 'empty';
    pill.innerHTML = chainHealthPill(state, health);
    if (state === 'tampered' && banner) {
      banner.innerHTML =
        '<div class="flex flex-wrap items-center gap-2 text-sm font-semibold text-red-700">' +
          '<i class="fa-solid fa-triangle-exclamation"></i>' +
          '<span>მთლიანობის დარღვევა ბოლო ' + esc(health.checked) + ' ჩანაწერში: ' +
            esc(health.hash_mismatches) + ' შეცვლილი, ' + esc(health.link_breaks) + ' გაწყვეტილი რგოლი.</span>' +
        '</div>' +
        (health.bad_ids && health.bad_ids.length
          ? '<p class="mt-1 text-xs text-red-600 font-mono">ID: ' + health.bad_ids.map(esc).join(', ') + '</p>' : '') +
        '<p class="mt-1 text-xs text-red-500">დეტალებისთვის გახსენით ჩანაწერი და გამოიყენეთ „ჯაჭვის შემოწმება".</p>';
      banner.classList.remove('hidden');
    }
  }

  // Shown instead of the chain-health pill for a manager, whose queries are
  // always hard-pinned server-side to their own department (see main.py's
  // _audit_scope_department) — display-only, the real restriction is
  // enforced by the backend regardless of what this text says.
  function renderScopeNote() {
    var note = document.getElementById('audit-scope-note');
    if (!note) return;
    if (!isManager()) { note.classList.add('hidden'); return; }
    var dept = (window.currentUser && window.currentUser.department) || '—';
    note.textContent = 'თქვენ ხედავთ მხოლოდ თქვენი ჯგუფის (' + dept + ') ლოგებს';
    note.classList.remove('hidden');
  }

  function fetchChainHealth(token) {
    token = token || (window.Auth && Auth.getToken && Auth.getToken());
    if (!token) return;
    var pill = document.getElementById('audit-chain-health');
    if (pill && !pill.innerHTML) pill.innerHTML = chainHealthPill('loading');
    fetch('/api/audit-logs/chain-health', {
      headers: { Authorization: 'Bearer ' + token }
    }).then(function (r) {
      if (window.handleSessionExpiry && handleSessionExpiry(r)) throw new Error('session expired');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (health) {
      renderChainHealth(health);
    }).catch(function (err) {
      console.error('AuditDashboard.fetchChainHealth failed:', err);
      renderChainHealth(null);
    });
  }

  /* ── Lifecycle ───────────────────────────────────────────────────────────*/

  function load(token) {
    token = token || (window.Auth && Auth.getToken && Auth.getToken());
    if (!token) return;
    var dr = currentDateRange();
    _state.startDate = dr.start;
    _state.endDate = dr.end;

    var cacheStore = window.CacheStore;
    var cacheKey = 'audit_' + JSON.stringify(_state);
    var cached = cacheStore ? cacheStore.get(cacheKey) : null;
    if (cached) {
      _lastRows = cached.rows;
      renderGrid(cached.rows);
      renderPagination(cached.total, _state.offset, _state.limit);
    } else {
      renderSkeleton();
    }
    fetchPage(token, _state).then(function (data) {
      if (cacheStore) cacheStore.set(cacheKey, data);
      _lastRows = data.rows;
      renderGrid(data.rows);
      renderPagination(data.total, _state.offset, _state.limit);
    }).catch(function (err) {
      console.error('AuditDashboard load failed:', err);
      if (!cached) {
        var host = document.getElementById('audit-grid-host');
        if (host) host.innerHTML = '<div class="px-5 py-10 text-center text-sm text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</div>';
      }
    });
  }

  function reload() { _state.offset = 0; load(); }
  function gotoOffset(offset) { _state.offset = Math.max(0, offset); load(); }
  function refresh() {
    if (window.CacheStore) CacheStore.clear();
    load();
    if (!isManager()) fetchChainHealth(); // would just 403 for a manager
  }

  function setDatePreset(days) {
    var el = document.getElementById('log-date-range');
    var fp = el && el._flatpickr;
    if (!fp) return;
    var end = new Date();
    var start = new Date();
    start.setDate(end.getDate() - days);
    fp.setDate([start, end], true); // true => fires onChange => reload() (see frontend_api.js's flatpickr init)
  }

  // Dropdown convenience over the tokenizer: rewrites the category: token in
  // the search box rather than keeping a second, parallel filter state.
  function setCategoryFilter(value) {
    var input = document.getElementById('audit-search');
    if (!input) return;
    var withoutCategory = (input.value || '').replace(/\bcategory:\S+/i, '').trim();
    input.value = value ? (withoutCategory + ' category:' + value).trim() : withoutCategory;
    _state.query = input.value;
    reload();
  }

  function bindToolbarEvents() {
    var search = document.getElementById('audit-search');
    if (search && !search.dataset.auditBound) {
      search.dataset.auditBound = '1';
      search.addEventListener('input', function () {
        var val = search.value;
        clearTimeout(_searchDebounce);
        _searchDebounce = setTimeout(function () { _state.query = val; reload(); }, 300);
      });
    }
  }

  function bindGridEvents() {
    var host = document.getElementById('audit-grid-host');
    if (!host || host.dataset.auditBound) return;
    host.dataset.auditBound = '1';
    host.addEventListener('click', function (ev) {
      var verifyBtn = ev.target.closest('.audit-verify-btn');
      if (verifyBtn) {
        ev.stopPropagation();
        openDetailDrawer(verifyBtn.getAttribute('data-log-id'), { autoVerify: true });
        return;
      }
      var row = ev.target.closest('.audit-row');
      if (row) openDetailDrawer(row.getAttribute('data-log-id'), { autoVerify: false });
    });
  }

  // Public entry point, called from app-router.js's switchAdmin('audit').
  // No default auto-refresh polling (unlike DeptDashboard's 60s ribbon
  // timer): this view is read by someone actively investigating a specific
  // row, and silently re-rendering under them mid-read is worse here than on
  // a stats ribbon. Manual refresh button instead (AuditDashboard.refresh).
  function start(token) {
    bindToolbarEvents();
    bindGridEvents();
    load(token);

    var manager = isManager();
    // Export + chain-health/verify are admin/content_admin-only (main.py
    // 403s a manager on all three) — hide the controls rather than let a
    // manager discover the 403 by clicking.
    var exportBtn = document.getElementById('btn-export-audit-csv');
    var healthPill = document.getElementById('audit-chain-health');
    var healthBanner = document.getElementById('audit-chain-banner');
    if (exportBtn) exportBtn.classList.toggle('hidden', manager);
    if (healthPill) healthPill.classList.toggle('hidden', manager);
    if (healthBanner) healthBanner.classList.add('hidden'); // reset on every (re)start
    renderScopeNote();
    if (manager) return;
    fetchChainHealth(token); // parallel — the grid never waits on the health call
  }

  window.AuditDashboard = {
    start: start, load: load, reload: reload, refresh: refresh, gotoOffset: gotoOffset,
    setDatePreset: setDatePreset, setCategoryFilter: setCategoryFilter,
    openDetailDrawer: openDetailDrawer, verifyRow: verifyRow, fetchChainHealth: fetchChainHealth,
    parseQuery: parseQuery, currentParams: currentParams
  };
})();
