/* ─────────────────────────────────────────────────────────────────────────────
 * Executive Department Dashboard — decoupled render layer.
 *
 * Hard split between DATA and PRESENTATION (the "no monolith" constraint):
 *   • DeptDashboard.fetch()            — data fetching only
 *   • DeptDashboard.renderRibbon()     — Insights Ribbon component
 *   • DeptDashboard.renderDepartments()— Department → Groups → Members tree
 *   • DeptDashboard.renderSkeleton()   — pulse placeholders
 *   • DeptDashboard.start()            — lifecycle: skeleton → fetch → render,
 *                                        plus a single 60s auto-refresh timer.
 *
 * Backs the #page-manager view. Data source: GET /api/manager/department-stats.
 * ───────────────────────────────────────────────────────────────────────────*/
(function () {
  'use strict';

  var REFRESH_MS = 60000;       // auto-refresh cadence (no manual button)
  var COUNTUP_MS = 700;         // count-up animation duration
  var _timer = null;            // single shared interval handle
  var _animTokens = {};         // per-element animation cancellation tokens
  var _sortMode = 'name';       // 'name' (server default) | 'compliance' (client re-sort)
  var _lastDepartments = null;  // cached last-fetched departments, for sort-toggle re-render without a new fetch

  // Brand + tier palette (subtle accents over a grayscale base).
  function tier(pct, hasReq) {
    if (!hasReq) return { bar: 'bg-gray-300 dark:bg-zinc-600', text: 'text-gray-400 dark:text-zinc-500' };
    if (pct >= 80) return { bar: 'bg-emerald-500', text: 'text-emerald-600 dark:text-emerald-400' };
    if (pct < 30) return { bar: 'bg-[#E30613]', text: 'text-[#E30613] dark:text-red-400' };
    return { bar: 'bg-amber-400', text: 'text-amber-600 dark:text-amber-400' };
  }

  var esc = function (s) {
    return (typeof escapeHtml === 'function') ? escapeHtml(String(s == null ? '' : s))
                                              : String(s == null ? '' : s);
  };

  /* ── Animations ──────────────────────────────────────────────────────────*/

  // Smooth integer count-up. Cancels any in-flight animation on the same node
  // so rapid refreshes never produce competing tweens (the "jarring jump" fix).
  function countUp(el, target, suffix) {
    if (!el) return;
    target = Number(target) || 0;
    suffix = suffix || '';
    var key = el.dataset.cuKey || (el.dataset.cuKey = String(Math.random()));
    var token = (_animTokens[key] = {});
    var from = parseInt(el.dataset.cuVal || '0', 10) || 0;
    if (from === target) { el.textContent = target + suffix; return; }
    var start = null;
    function step(ts) {
      if (_animTokens[key] !== token) return; // superseded — bail
      if (start === null) start = ts;
      var p = Math.min((ts - start) / COUNTUP_MS, 1);
      var eased = 1 - Math.pow(1 - p, 3); // easeOutCubic
      var val = Math.round(from + (target - from) * eased);
      el.textContent = val + suffix;
      if (p < 1) requestAnimationFrame(step);
      else el.dataset.cuVal = String(target);
    }
    requestAnimationFrame(step);
  }

  // Animate a progress bar width to pct% (CSS transition handles smoothing).
  function animateBar(el, pct) {
    if (!el) return;
    requestAnimationFrame(function () { el.style.width = Math.max(0, Math.min(100, pct)) + '%'; });
  }

  /* ── Render: Insights Ribbon ─────────────────────────────────────────────*/

  function ribbonTile(label, valueId, value, suffix, accentText, hint) {
    return '' +
      '<div class="rounded-2xl border border-gray-100 bg-white p-5 shadow-sm transition-colors dark:border-zinc-800 dark:bg-zinc-900/60">' +
        '<p class="text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-zinc-500">' + esc(label) + '</p>' +
        '<p class="mt-3 text-4xl font-semibold tabular-nums ' + accentText + '" id="' + valueId + '">0' + esc(suffix) + '</p>' +
        (hint ? '<p class="mt-1 text-xs text-gray-400 dark:text-zinc-500">' + esc(hint) + '</p>' : '') +
      '</div>';
  }

  function renderRibbon(insights) {
    var host = document.getElementById('insights-ribbon');
    if (!host || !insights) return;
    var critAccent = insights.critical_operators > 0
      ? 'text-[#E30613] dark:text-red-400' : 'text-gray-800 dark:text-zinc-100';
    host.innerHTML =
      ribbonTile('გლობალური შესრულება', 'ribbon-compliance', insights.global_compliance, '%',
                 'text-gray-800 dark:text-zinc-100', insights.total_members + ' თანამშრომელი') +
      ribbonTile('კრიტიკული ოპერატორები', 'ribbon-critical', insights.critical_operators, '',
                 critAccent, '< 30% შესრულება') +
      ribbonTile('სულ წაკითხული', 'ribbon-output', insights.total_output_volume, '',
                 'text-gray-800 dark:text-zinc-100', 'მთლიანი მოცულობა');
    countUp(document.getElementById('ribbon-compliance'), insights.global_compliance, '%');
    countUp(document.getElementById('ribbon-critical'), insights.critical_operators, '');
    countUp(document.getElementById('ribbon-output'), insights.total_output_volume, '');
  }

  /* ── Render: Department → Groups → Members ───────────────────────────────*/

  function groupRow(group, idx, deptKey, deptName) {
    var pct = Number(group.compliance) || 0;
    var hasReq = group.member_count > 0;
    var t = tier(pct, hasReq);
    var barId = 'gbar-' + deptKey + '-' + idx;
    var crit = group.critical_count > 0
      ? '<span class="ml-2 inline-flex items-center rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-semibold text-[#E30613] dark:bg-red-950/40 dark:text-red-400">' +
          group.critical_count + ' კრიტ.</span>'
      : '';
    return '' +
      '<div class="group-row cursor-pointer rounded-lg px-1 py-2.5 transition hover:bg-gray-50 dark:hover:bg-zinc-800/60" data-dept="' + esc(deptName) + '" data-group="' + esc(group.name) + '">' +
        '<div class="mb-1.5 flex items-center justify-between gap-2 text-sm">' +
          '<span class="truncate font-medium text-gray-700 dark:text-zinc-300">' + esc(group.name) +
            '<span class="ml-1.5 text-xs font-normal text-gray-400 dark:text-zinc-500">· ' + group.member_count + '</span>' + crit +
          '</span>' +
          '<span class="inline-flex shrink-0 items-center gap-1.5">' +
            '<span class="font-semibold tabular-nums" data-pct-color="' + pct + '">' + pct + '%</span>' +
            '<svg class="h-3.5 w-3.5 text-gray-300 transition-colors group-row-chevron dark:text-zinc-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7"/></svg>' +
          '</span>' +
        '</div>' +
        '<div class="h-1.5 w-full overflow-hidden rounded-full bg-gray-100 dark:bg-zinc-800">' +
          '<div id="' + barId + '" class="h-full rounded-full ' + t.bar + '" style="width:0%;transition:width .8s cubic-bezier(.22,1,.36,1)"></div>' +
        '</div>' +
      '</div>';
  }

  // Single source of truth for group order, so the DOM built by departmentCard()
  // and the post-render bar-animation loop in renderDepartments() never disagree
  // on which gbar-<deptKey>-<idx> id corresponds to which group.
  function _sortedGroups(dept) {
    var groups = (dept.groups || []).slice();
    if (_sortMode === 'compliance') {
      groups.sort(function (a, b) { return (Number(b.compliance) || 0) - (Number(a.compliance) || 0); });
    }
    return groups;
  }

  function departmentCard(dept, di) {
    var deptKey = 'd' + di;
    var pct = Number(dept.compliance) || 0;
    var t = tier(pct, !dept.is_empty);

    if (dept.is_empty) {
      return '' +
        '<div class="flex flex-col rounded-2xl border border-dashed border-gray-200 bg-gray-50/50 p-5 dark:border-zinc-800 dark:bg-zinc-900/30">' +
          '<div class="flex items-center justify-between">' +
            '<h3 class="text-lg font-semibold text-gray-500 dark:text-zinc-400">' + esc(dept.name) + '</h3>' +
            '<span class="text-xs text-gray-400 dark:text-zinc-600">0 ჯგუფი</span>' +
          '</div>' +
          '<div class="flex flex-1 items-center justify-center py-10 text-center">' +
            '<p class="text-sm text-gray-400 dark:text-zinc-600"><i class="fa-regular fa-folder-open mr-1.5"></i>თანამშრომლები არ არის მიბმული</p>' +
          '</div>' +
        '</div>';
    }

    var groupsHtml = _sortedGroups(dept).map(function (g, gi) { return groupRow(g, gi, deptKey, dept.name); }).join('');
    return '' +
      '<div class="flex flex-col rounded-2xl border border-gray-100 bg-white p-5 shadow-sm dark:border-zinc-800 dark:bg-zinc-900/60">' +
        '<div class="mb-1 flex items-start justify-between gap-2">' +
          '<h3 class="text-lg font-semibold text-gray-800 dark:text-zinc-100">' + esc(dept.name) + '</h3>' +
          '<span class="shrink-0 text-2xl font-semibold tabular-nums ' + t.text + '" id="dcomp-' + deptKey + '">0%</span>' +
        '</div>' +
        '<p class="mb-3 text-xs text-gray-400 dark:text-zinc-500">' + dept.group_count + ' ჯგუფი · ' + dept.member_count + ' თანამშრომელი' +
          (dept.critical_count > 0 ? ' · <span class="font-semibold text-[#E30613] dark:text-red-400">' + dept.critical_count + ' კრიტ.</span>' : '') +
        '</p>' +
        '<div class="mb-4 h-2 w-full overflow-hidden rounded-full bg-gray-100 dark:bg-zinc-800">' +
          '<div id="dbar-' + deptKey + '" class="h-full rounded-full ' + t.bar + '" style="width:0%;transition:width .8s cubic-bezier(.22,1,.36,1)"></div>' +
        '</div>' +
        '<div class="divide-y divide-gray-50 dark:divide-zinc-800/70">' + groupsHtml + '</div>' +
      '</div>';
  }

  function renderDepartments(departments) {
    var host = document.getElementById('dept-dashboard-body');
    if (!host) return;
    departments = departments || [];
    host.innerHTML = departments.map(departmentCard).join('');
    // Animate department headline numbers + every bar after DOM insertion.
    departments.forEach(function (dept, di) {
      var deptKey = 'd' + di;
      if (dept.is_empty) return;
      countUp(document.getElementById('dcomp-' + deptKey), dept.compliance, '%');
      animateBar(document.getElementById('dbar-' + deptKey), Number(dept.compliance) || 0);
      _sortedGroups(dept).forEach(function (g, gi) {
        animateBar(document.getElementById('gbar-' + deptKey + '-' + gi), Number(g.compliance) || 0);
      });
    });
  }

  /* ── Render: Skeleton / Error ────────────────────────────────────────────*/

  function ribbonSkeleton() {
    var s = '<div class="dept-ribbon-skeleton animate-pulse rounded-2xl border border-gray-100 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900/60"><div class="h-3 w-24 rounded bg-gray-200 dark:bg-zinc-800"></div><div class="mt-4 h-9 w-20 rounded bg-gray-200 dark:bg-zinc-800"></div></div>';
    return s + s + s;
  }
  function cardSkeleton() {
    return '<div class="dept-card-skeleton animate-pulse rounded-2xl border border-gray-100 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900/60">' +
      '<div class="h-5 w-32 rounded bg-gray-200 dark:bg-zinc-800"></div>' +
      '<div class="mt-3 h-2 w-full rounded bg-gray-200 dark:bg-zinc-800"></div>' +
      '<div class="mt-6 space-y-4"><div class="h-3 w-full rounded bg-gray-200 dark:bg-zinc-800"></div><div class="h-3 w-5/6 rounded bg-gray-200 dark:bg-zinc-800"></div><div class="h-3 w-4/6 rounded bg-gray-200 dark:bg-zinc-800"></div></div></div>';
  }
  function renderSkeleton() {
    var ribbon = document.getElementById('insights-ribbon');
    var body = document.getElementById('dept-dashboard-body');
    if (ribbon) ribbon.innerHTML = ribbonSkeleton();
    if (body) body.innerHTML = cardSkeleton() + cardSkeleton() + cardSkeleton();
  }
  function renderError(msg) {
    var body = document.getElementById('dept-dashboard-body');
    if (body) body.innerHTML =
      '<div class="col-span-full rounded-2xl border border-gray-100 bg-white p-8 text-center text-sm text-gray-500 dark:border-zinc-800 dark:bg-zinc-900/60 dark:text-zinc-400">' +
        '<i class="fa-solid fa-triangle-exclamation mr-1.5 text-[#E30613]"></i>' + esc(msg) + '</div>';
  }

  /* ── Data fetching ───────────────────────────────────────────────────────*/

  function fetch_(token) {
    return fetch('/api/manager/department-stats', {
      headers: { 'Authorization': 'Bearer ' + token }
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  /* ── Lifecycle ───────────────────────────────────────────────────────────*/

  function load(token, isInitial) {
    if (!token) { renderError('ავტორიზაცია საჭიროა.'); return; }
    
    var cacheStore = window.CacheStore;
    var cached = (isInitial && cacheStore) ? cacheStore.get('manager_stats') : null;
    
    if (cached) {
      _lastDepartments = cached.departments;
      renderRibbon(cached.insights);
      renderDepartments(cached.departments);
      applyPctColors();
      adaptGrid(cached.departments);
      bindCriticalCard();
      bindGroupRows();
    } else if (isInitial) {
      renderSkeleton();
    }
    
    fetch_(token).then(function (data) {
      _lastDepartments = data.departments;
      if (cacheStore) {
        cacheStore.set('manager_stats', data);
      }
      renderRibbon(data.insights);
      renderDepartments(data.departments);
      applyPctColors();
      adaptGrid(data.departments);
      bindCriticalCard();
      bindGroupRows();
    }).catch(function (err) {
      console.error('DeptDashboard load failed:', err);
      if (!cached && isInitial) {
        renderError('მონაცემების ჩატვირთვა ვერ მოხერხდა.');
      } else if (cached && typeof showToast === 'function') {
        // Cached data is still on screen — don't wipe a good view with a
        // full error block, but don't silently hide the failure either.
        showToast('განახლება ვერ მოხერხდა', 'ნაჩვენებია ბოლოს ჩატვირთული მონაცემები.', { variant: 'warning' });
      }
    });
  }

  // Public entry point. Idempotent: resets the single auto-refresh timer so
  // re-entering the page never stacks intervals.
  function start(token) {
    token = token || (window.Auth && Auth.getToken && Auth.getToken());
    load(token, true);
    if (_timer) clearInterval(_timer);
    _timer = setInterval(function () {
      // Only refresh while the manager page is actually visible.
      var page = document.getElementById('page-manager');
      if (page && !page.classList.contains('hidden')) {
        load(window.Auth && Auth.getToken ? Auth.getToken() : token, false);
      }
    }, REFRESH_MS);
  }

  function stop() { if (_timer) { clearInterval(_timer); _timer = null; } }

  // Client-side re-sort of already-fetched group data (no new network request).
  function setSortMode(mode) {
    _sortMode = (mode === 'compliance') ? 'compliance' : 'name';
    if (!_lastDepartments) return;
    renderDepartments(_lastDepartments);
    applyPctColors();
    adaptGrid(_lastDepartments);
    bindGroupRows();
  }

  function getSortMode() { return _sortMode; }

  // Dynamic color for group completion percentages (alarm-fatigue fix).
  function applyPctColors() {
    var els = document.querySelectorAll('[data-pct-color]');
    for (var i = 0; i < els.length; i++) {
      var pct = Number(els[i].getAttribute('data-pct-color')) || 0;
      els[i].className = els[i].className
        .replace(/text-(green|yellow|red|emerald|amber)-[46]00/g, '')
        .replace(/dark:text-(green|yellow|red|emerald|amber)-[34]00/g, '')
        .trim();
      if (pct >= 100)     { els[i].classList.add('text-green-600', 'dark:text-green-400'); }
      else if (pct > 50)  { els[i].classList.add('text-yellow-600', 'dark:text-yellow-400'); }
      else                { els[i].classList.add('text-red-600', 'dark:text-red-400'); }
    }
  }

  // Adaptive grid: always prefer up to 3 department cards (ტექ / საინფო / ოფისი).
  function adaptGrid(departments) {
    var host = document.getElementById('dept-dashboard-body');
    if (!host) return;
    var n = (departments || []).length;
    var nonEmpty = (departments || []).filter(function (d) { return !d.is_empty; });
    var count = Math.max(nonEmpty.length, n > 0 ? Math.min(n, 3) : 0);
    host.classList.remove('xl:grid-cols-3', 'lg:grid-cols-2', 'lg:grid-cols-1', 'lg:grid-cols-3');
    if (count >= 3) {
      host.classList.add('lg:grid-cols-2', 'xl:grid-cols-3');
    } else if (count === 2) {
      host.classList.add('lg:grid-cols-2');
    } else {
      host.classList.add('lg:grid-cols-1');
    }
  }

  // Clickable critical-operators card → opens modal.
  function bindCriticalCard() {
    var card = document.getElementById('ribbon-critical');
    if (!card) return;
    var tile = card.closest('div.rounded-2xl');
    if (!tile || tile.dataset.critBound) return;
    tile.dataset.critBound = '1';
    tile.classList.add('cursor-pointer', 'hover:bg-gray-50', 'dark:hover:bg-zinc-800/80', 'transition');
    tile.addEventListener('click', function () {
      var dlg = document.getElementById('critical-operators-modal');
      if (dlg && dlg.showModal) {
        dlg.showModal();
        fetchCriticalOperators();
      }
    });
  }

  function fetchCriticalOperators() {
    var body = document.getElementById('critical-modal-body');
    if (!body) return;
    body.innerHTML = '<i class="fa-solid fa-spinner fa-spin mr-1.5"></i>იტვირთება…';
    var token = window.Auth && Auth.getToken ? Auth.getToken() : '';
    try {
      fetch('/api/admin/critical-operators', {
        headers: { 'Authorization': 'Bearer ' + token }
      }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }).then(function (data) {
        if (!data.operators || data.operators.length === 0) {
          body.innerHTML = '<p class="text-sm text-gray-500 dark:text-zinc-400">კრიტიკული ოპერატორები არ მოიძებნა.</p>';
          return;
        }
        var rows = data.operators.map(function (op) {
          return '<tr class="border-b border-gray-50 dark:border-zinc-800">' +
            '<td class="py-2 pr-3 text-sm text-gray-800 dark:text-zinc-200">' + esc(op.first_name + ' ' + op.last_name) + '</td>' +
            '<td class="py-2 pr-3 text-xs text-gray-500 dark:text-zinc-400 truncate max-w-[160px]">' + esc(op.department || '—') + '</td>' +
            '<td class="py-2 text-sm font-semibold text-red-600 dark:text-red-400 text-right tabular-nums">' + op.overdue_count + '</td>' +
          '</tr>';
        }).join('');
        body.innerHTML =
          '<table class="w-full text-left">' +
            '<thead><tr class="border-b border-gray-100 dark:border-zinc-700 text-xs font-semibold uppercase text-gray-400 dark:text-zinc-500">' +
              '<th class="pb-2 pr-3">სახელი</th><th class="pb-2 pr-3">დეპარტამენტი</th><th class="pb-2 text-right">გამოტოვ.</th>' +
            '</tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
          '</table>' +
          '<p class="mt-3 text-xs text-gray-400 dark:text-zinc-500 text-right">სულ: ' + data.total + '</p>';
      }).catch(function (err) {
        console.error('fetchCriticalOperators failed:', err);
        body.innerHTML = '<p class="text-sm text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
      });
    } catch (err) {
      console.error('fetchCriticalOperators error:', err);
      body.innerHTML = '<p class="text-sm text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
    }
  }

  function pctColorClass(pct) {
    if (pct >= 100) return 'text-green-600 dark:text-green-400';
    if (pct > 50)   return 'text-yellow-600 dark:text-yellow-400';
    return 'text-red-600 dark:text-red-400';
  }

  function fetchGroupUsers(dept, groupName) {
    var title = document.getElementById('group-users-modal-title');
    var body  = document.getElementById('group-users-modal-body');
    if (title) title.textContent = groupName;
    if (!body) return;
    var errHtml = '<tr><td colspan="2" class="py-8 text-center text-sm text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
    body.innerHTML = '<tr><td colspan="2" class="py-8 text-center text-sm text-gray-500 dark:text-zinc-400"><i class="fa-solid fa-spinner fa-spin mr-1.5"></i>იტვირთება…</td></tr>';
    var token = window.Auth && Auth.getToken ? Auth.getToken() : '';
    var url = '/api/admin/departments/' + encodeURIComponent(dept) + '/groups/' + encodeURIComponent(groupName) + '/users';
    try {
      fetch(url, {
        headers: { 'Authorization': 'Bearer ' + token }
      }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      }).then(function (data) {
        if (!data.users || data.users.length === 0) {
          body.innerHTML = '<tr><td colspan="2" class="py-8 text-center text-sm text-gray-500 dark:text-zinc-400">თანამშრომლები არ მოიძებნა.</td></tr>';
          return;
        }
        body.innerHTML = data.users.map(function (u) {
          var cls = pctColorClass(u.completion_percentage);
          return '<tr class="border-b border-gray-50 dark:border-zinc-800">' +
            '<td class="py-2.5 pr-3 text-sm text-gray-800 dark:text-zinc-200">' + esc(u.first_name + ' ' + u.last_name) + '</td>' +
            '<td class="py-2.5 pr-3 text-right">' +
              '<div class="flex items-center justify-end gap-2">' +
                '<div class="h-1.5 w-16 overflow-hidden rounded-full bg-gray-100 dark:bg-zinc-800">' +
                  '<div class="h-full rounded-full ' + (u.completion_percentage >= 100 ? 'bg-emerald-500' : u.completion_percentage > 50 ? 'bg-amber-400' : 'bg-[#E30613]') + '" style="width:' + Math.min(100, u.completion_percentage) + '%"></div>' +
                '</div>' +
                '<span class="text-sm font-semibold tabular-nums ' + cls + '">' + u.completion_percentage + '%</span>' +
              '</div>' +
            '</td>' +
          '</tr>';
        }).join('');
      }).catch(function (err) {
        console.error('fetchGroupUsers failed:', err);
        body.innerHTML = errHtml;
      });
    } catch (err) {
      console.error('fetchGroupUsers error:', err);
      body.innerHTML = errHtml;
    }
  }

  function bindGroupRows() {
    var rows = document.querySelectorAll('.group-row[data-dept][data-group]');
    for (var i = 0; i < rows.length; i++) {
      (function (row) {
        if (row.dataset.grBound) return;
        row.dataset.grBound = '1';
        row.addEventListener('click', function () {
          var dlg = document.getElementById('group-users-modal');
          if (dlg && dlg.showModal) {
            dlg.showModal();
            fetchGroupUsers(row.dataset.dept, row.dataset.group);
          }
        });
      })(rows[i]);
    }
  }

  window.DeptDashboard = {
    start: start, stop: stop, load: load,
    renderRibbon: renderRibbon, renderDepartments: renderDepartments,
    renderSkeleton: renderSkeleton, countUp: countUp,
    applyPctColors: applyPctColors, adaptGrid: adaptGrid,
    bindCriticalCard: bindCriticalCard, bindGroupRows: bindGroupRows,
    setSortMode: setSortMode, getSortMode: getSortMode
  };
})();

/* ════════════════════════════════════════════════════════════════════════
   Bridge function — called from app-router.js switchMainPage('page-manager')
   to kick off the Department Dashboard lifecycle.
   ════════════════════════════════════════════════════════════════════════ */
function fetchAndRenderManagerStats(token) {
  if (typeof DeptDashboard !== 'undefined' && DeptDashboard.start) {
    DeptDashboard.start(token);
  } else {
    console.warn('DeptDashboard not initialized yet, retrying...');
    setTimeout(function() {
      if (typeof DeptDashboard !== 'undefined' && DeptDashboard.start) {
        DeptDashboard.start(token);
      }
    }, 100);
  }
}
window.fetchAndRenderManagerStats = fetchAndRenderManagerStats;
