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

  function groupRow(group, idx, deptKey) {
    var pct = Number(group.compliance) || 0;
    var hasReq = group.member_count > 0;
    var t = tier(pct, hasReq);
    var barId = 'gbar-' + deptKey + '-' + idx;
    var crit = group.critical_count > 0
      ? '<span class="ml-2 inline-flex items-center rounded-full bg-red-50 px-2 py-0.5 text-[10px] font-semibold text-[#E30613] dark:bg-red-950/40 dark:text-red-400">' +
          group.critical_count + ' კრიტ.</span>'
      : '';
    return '' +
      '<div class="py-2.5">' +
        '<div class="mb-1.5 flex items-center justify-between gap-2 text-sm">' +
          '<span class="truncate font-medium text-gray-700 dark:text-zinc-300">' + esc(group.name) +
            '<span class="ml-1.5 text-xs font-normal text-gray-400 dark:text-zinc-500">· ' + group.member_count + '</span>' + crit +
          '</span>' +
          '<span class="shrink-0 font-semibold tabular-nums" data-pct-color="' + pct + '">' + pct + '%</span>' +
        '</div>' +
        '<div class="h-1.5 w-full overflow-hidden rounded-full bg-gray-100 dark:bg-zinc-800">' +
          '<div id="' + barId + '" class="h-full rounded-full ' + t.bar + '" style="width:0%;transition:width .8s cubic-bezier(.22,1,.36,1)"></div>' +
        '</div>' +
      '</div>';
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

    var groupsHtml = (dept.groups || []).map(function (g, gi) { return groupRow(g, gi, deptKey); }).join('');
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
      (dept.groups || []).forEach(function (g, gi) {
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
    if (isInitial) renderSkeleton(); // only the first paint shows skeletons
    fetch_(token).then(function (data) {
      renderRibbon(data.insights);
      renderDepartments(data.departments);
      applyPctColors();
      adaptGrid(data.departments);
      bindCriticalCard();
    }).catch(function (err) {
      console.error('DeptDashboard load failed:', err);
      if (isInitial) renderError('მონაცემების ჩატვირთვა ვერ მოხერხდა.');
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

  // Adaptive grid: if empty columns exist, remaining cards span evenly.
  function adaptGrid(departments) {
    var host = document.getElementById('dept-dashboard-body');
    if (!host) return;
    var nonEmpty = (departments || []).filter(function (d) { return !d.is_empty; });
    host.classList.remove('xl:grid-cols-3', 'lg:grid-cols-2');
    if (nonEmpty.length <= 2) {
      host.classList.add('lg:grid-cols-' + Math.max(1, nonEmpty.length));
    } else {
      host.classList.add('lg:grid-cols-2', 'xl:grid-cols-3');
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
      if (dlg && dlg.showModal) dlg.showModal();
    });
  }

  window.DeptDashboard = {
    start: start, stop: stop, load: load,
    renderRibbon: renderRibbon, renderDepartments: renderDepartments,
    renderSkeleton: renderSkeleton, countUp: countUp,
    applyPctColors: applyPctColors, adaptGrid: adaptGrid, bindCriticalCard: bindCriticalCard
  };
})();
