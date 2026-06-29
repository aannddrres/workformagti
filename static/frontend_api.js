async function fetchCurrentUser(token) {
        const response = await fetch('/api/users/me', {
          headers: {
            'Authorization': `Bearer ${token}`
          }
        });
        if (!response.ok) {
          throw new Error(`API request failed with status ${response.status}`);
        }
        return await response.json();
      }

async function fetchNews(token) {
        const response = await fetch('/api/news', {
          headers: {
            'Authorization': `Bearer ${token}`
          }
        });

        if (!response.ok) {
          throw new Error(`API request failed with status ${response.status}`);
        }
        const news = await response.json();
        news.forEach(item => {
          window.cachedNewsItems[item.id] = item;
        });
        return news;
      }

async function fetchStatistics(token) {
        try {
          const response = await fetch('/api/statistics/compliance', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error(`API error: ${response.status}`);
          return await response.json();
        } catch (error) {
          console.error('Failed to fetch statistics:', error);
          return null; // Gracefully handle fetch error without breaking the UI
        }
      }

async function fetchAndRenderMyReadings(token) {
        try {
          const response = await fetch('/api/compliance/my-readings', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (handleSessionExpiry(response)) return;
          if (!response.ok) throw new Error('Failed to fetch readings');
          window.myReadings = await response.json();
          renderFilteredReadings('all'); // Render 'all' tab by default
        } catch (error) {
          console.error(error);
          const container = document.getElementById('readings-list');
          if (container) container.innerHTML = '<p class="text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
        }
      }

// Executive Department Dashboard entry point. The render/animation/auto-refresh
// logic lives in the decoupled DeptDashboard module (static/js/dept-dashboard.js);
// this thin wrapper preserves the legacy call sites (app-router nav + app-core
// bindings) that still invoke fetchAndRenderManagerStats(token).
function fetchAndRenderManagerStats(token) {
        if (window.DeptDashboard && typeof DeptDashboard.start === 'function') {
          DeptDashboard.start(token);
        } else {
          console.warn('DeptDashboard module not loaded yet.');
        }
      }

async function fetchNotificationsCount(token) {
        const mustRead = document.getElementById('must-read-container');
        if (mustRead) {
          mustRead.innerHTML = `
            <li class="dashboard-list-card animate-pulse flex items-center justify-between p-4 min-h-[72px]">
              <div class="flex items-center gap-3 w-full">
                <span class="h-2.5 w-2.5 shrink-0 rounded-full bg-slate-200 dark:bg-zinc-700"></span>
                <div class="flex-1 space-y-2">
                  <div class="h-4 bg-slate-200 dark:bg-zinc-700 rounded w-3/4"></div>
                  <div class="h-3 bg-slate-200 dark:bg-zinc-700 rounded w-1/2"></div>
                </div>
              </div>
            </li>
            <li class="dashboard-list-card animate-pulse flex items-center justify-between p-4 min-h-[72px]">
              <div class="flex items-center gap-3 w-full">
                <span class="h-2.5 w-2.5 shrink-0 rounded-full bg-slate-200 dark:bg-zinc-700"></span>
                <div class="flex-1 space-y-2">
                  <div class="h-4 bg-slate-200 dark:bg-zinc-700 rounded w-3/4"></div>
                  <div class="h-3 bg-slate-200 dark:bg-zinc-700 rounded w-1/2"></div>
                </div>
              </div>
            </li>
          `;
        }

        try {
          const response = await fetch('/api/compliance/my-readings', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (response.ok) {
            const readings = await response.json();
            window.myReadings = readings;
            const unread = readings.filter(r => r.status === 'unread' || r.status === 'overdue' || r.is_overdue);
            const readCount = readings.filter(r => r.status === 'read').length;
            const bellBadge = document.getElementById('bell-badge');
            if (bellBadge) {
              if (unread.length > 0) {
                bellBadge.textContent = unread.length;
                bellBadge.classList.remove('hidden');
                bellBadge.classList.add('flex');
              } else {
                bellBadge.classList.add('hidden');
                bellBadge.classList.remove('flex');
              }
            }

            // Sidebar red dot mirrors the unread state
            const dot = document.getElementById('sidebar-reading-dot');
            if (dot) {
              dot.classList.toggle('hidden', unread.length === 0);
              if (window.currentPageId === 'page-reading') {
                dot.classList.remove('bg-[#E30613]');
                dot.classList.add('border-2', 'border-white', 'bg-transparent');
              } else {
                dot.classList.add('bg-[#E30613]');
                dot.classList.remove('border-2', 'border-white', 'bg-transparent');
              }
            }

            // Dashboard stat cards
            const statUnread = document.getElementById('stat-unread-readings');
            if (statUnread) {
              statUnread.textContent = unread.length;
              // Stat card is now a semantic <a> (was <button>); match either.
              // The card ships `hidden` in the raw HTML to avoid a FOUC flash
              // on load — only ever reveal it, never re-hide it here.
              const statCard = statUnread.closest('a, button');
              if (statCard && unread.length > 0) statCard.classList.remove('hidden');
            }
            const statRead = document.getElementById('stat-read-count');
            if (statRead) statRead.textContent = readCount;

            // Dashboard "must read" list: most urgent unread items first
            const mustRead = document.getElementById('must-read-container');
            if (mustRead) {
              mustRead.innerHTML = '';
              if (unread.length === 0) {
                mustRead.innerHTML = `
                  <li class="dashboard-list-card flex flex-col items-center justify-center p-8 text-center border-emerald-100/50 dark:border-emerald-500/20 md:min-h-[280px]">
                    <div class="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 dark:bg-emerald-950/40 text-emerald-500 dark:text-emerald-400">
                      <i class="fa-solid fa-check text-xl"></i>
                    </div>
                    <h4 class="text-[14px] font-bold text-gray-800 dark:text-zinc-200">სავალდებულო მასალები წაკითხულია</h4>
                    <p class="mt-1 text-[12px] text-gray-500 dark:text-zinc-400">ამ დროისთვის თქვენ არ გაქვთ ახალი წასაკითხი მასალები.</p>
                  </li>
                `;
              } else {
                unread
                  .slice()
                  .sort((a, b) => new Date(a.reading.due_date) - new Date(b.reading.due_date))
                  .slice(0, 3)
                  .forEach(item => {
                    const due = (window.formatDate ? window.formatDate(item.reading.due_date) : new Date(item.reading.due_date).toLocaleDateString('ka-GE'));
                    const overdue = item.status === 'overdue' || item.is_overdue;
                    const displayTitle = item.item_title || `სავალდებულო მასალა #${item.reading.item_id}`;
                    mustRead.insertAdjacentHTML('beforeend', `
                      <li onclick="navTo('page-reading')" 
                          onkeydown="if(event.key==='Enter'||event.key===' '){navTo('page-reading');event.preventDefault();}" 
                          tabindex="0" 
                          role="button" 
                          class="dashboard-list-card flex cursor-pointer items-center justify-between p-4 focus:outline-none focus:ring-2 focus:ring-[#E30613]">
                        <div class="flex items-center gap-3 w-full">
                          <span class="h-2.5 w-2.5 shrink-0 rounded-full ${overdue ? 'bg-[#E30613]' : 'bg-orange-400'}"></span>
                          <div class="flex-1 min-w-0">
                            <h4 class="text-[14px] font-bold text-gray-800 dark:text-zinc-200 truncate">${displayTitle}</h4>
                            <div class="mt-1.5 flex items-center gap-2">
                              <span class="rounded ${overdue ? 'bg-red-100/80 text-[#E30613] dark:bg-red-950/40 dark:text-red-400' : 'bg-gray-100/80 text-gray-500 dark:bg-zinc-800/80 dark:text-zinc-400'} px-1.5 py-0.5 text-[10px] font-bold">${overdue ? 'ვადაგადაცილებული' : 'წასაკითხი'}</span>
                              <span class="text-[11px] text-gray-400 dark:text-zinc-500">ვადა: ${due}</span>
                            </div>
                          </div>
                        </div>
                      </li>`);
                  });
              }
            }
          }
        } catch (error) {
          console.error('Error fetching notifications count:', error);
        }
      }

async function fetchAndRenderFavorites(token) {
        const container = document.getElementById('favorites-list');
        if (!container) return;

        try {
          const response = await api('/api/favorites');
          if (!response.ok) throw new Error('Failed to fetch favorites');
          const favorites = await response.json();

          // 1) Update shared state FIRST, then signal readiness — any renderer
          //    awaiting Store.whenFavoritesReady() now sees the full set.
          clearObj(Store.favorites);
          favorites.forEach(fav => {
            Store.favorites[`${fav.item_type}_${fav.item_id}`] = fav.id;
          });
          Store.markReady('favorites');

          // 2) Render the favourites page + the profile-cabinet mirror.
          container.innerHTML = '';
          const profileList = document.getElementById('profile-favorites-list');

          if (favorites.length === 0) {
            container.innerHTML = '<p class="text-sm text-gray-500">რჩეულები არ მოიძებნა.</p>';
            if (profileList) profileList.innerHTML = container.innerHTML;
            updateStarIcons();
            return;
          }

          favorites.forEach(fav => {
            let title = fav.item_title || `მასალა #${fav.item_id}`;
            let icon = 'fa-star';
            let clickAction = '';
            
            if (fav.item_type === 'news') {
              icon = 'fa-bullhorn';
              clickAction = `onclick="openNewsDetailModal(${fav.item_id})"`
            } else if (fav.item_type === 'article') {
              icon = 'fa-file-lines';
              clickAction = `onclick="openArticleModalById(${fav.item_id})"`
            } else if (fav.item_type === 'video') {
              icon = 'fa-video';
              clickAction = `onclick="viewVideo(${fav.item_id})"`
            } else if (fav.item_type === 'category') {
              icon = 'fa-database';
            }

            const html = `
              <div class="group flex items-center justify-between rounded-xl border border-gray-100 bg-white p-4 shadow-sm transition-all hover:border-red-200 hover:shadow-md cursor-pointer" ${clickAction}>
                <div class="flex items-center gap-4 min-w-0 flex-1">
                  <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-50 text-[#E30613]">
                    <i class="fa-solid ${icon}"></i>
                  </div>
                  <h4 class="font-bold text-gray-800 truncate pr-4">${escapeHtml(title)}</h4>
                </div>
                <button onclick="event.stopPropagation(); removeFavorite(${fav.id})" class="text-xl font-bold text-gray-400 transition-colors hover:text-[#E30613]" aria-label="წაშლა">
                  <i class="fa-solid fa-xmark"></i>
                </button>
              </div>
            `;
            container.insertAdjacentHTML('beforeend', html);
          });

          // Personal cabinet's favorites tab mirrors the favorites page
          if (profileList) profileList.innerHTML = container.innerHTML;

          updateStarIcons();
        } catch (error) {
          console.error(error);
          container.innerHTML = '<p class="text-red-500">რჩეულების ჩატვირთვა ვერ მოხერხდა.</p>';
          Store.markReady('favorites');   // never deadlock renderers awaiting this
        }
      }

async function fetchAndRenderUserProgress(token) {
        const tbody = document.getElementById('user-progress-tbody');
        if (!tbody) return;

        // Default sort: lowest performers first (most actionable for admin)
        if (!window._progressSortMode) window._progressSortMode = 'perf_asc';
        if (!window._progressIncompleteOnly) window._progressIncompleteOnly = false;

        try {
          const response = await fetch('/api/statistics/user-progress', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch user progress');
          const data = await response.json();

          // Store for re-rendering when the department filter changes
          window.userProgressData = data;
          const deptFilter = document.getElementById('progress-dept-filter');
          if (deptFilter) {
            const depts = [...new Set(data.map(u => u.department).filter(Boolean))].sort();
            deptFilter.innerHTML = '<option value="">ყველა დეპარტამენტი</option>' +
              depts.map(d => `<option value="${d}">${d}</option>`).join('');
          }

          // Sync sort dropdown to current mode
          const sortSel = document.getElementById('progress-sort-select');
          if (sortSel) sortSel.value = window._progressSortMode;

          renderProgressRows();
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="6" class="px-5 py-6 text-center text-rose-500 text-sm">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }


async function fetchAndRenderPopularSearches(token) {
        const tbody = document.getElementById('popular-searches-tbody');
        if (!tbody) return;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="py-3 pr-4"><div class="h-4 bg-gray-200 rounded w-2/3"></div></td>
            <td class="py-3 text-center"><div class="h-4 bg-gray-200 rounded w-8 mx-auto"></div></td>
          </tr>
          <tr class="animate-pulse">
            <td class="py-3 pr-4"><div class="h-4 bg-gray-200 rounded w-1/2"></div></td>
            <td class="py-3 text-center"><div class="h-4 bg-gray-200 rounded w-8 mx-auto"></div></td>
          </tr>
        `;

        try {
          const response = await fetch('/api/statistics/popular-searches', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch popular searches');
          const searches = await response.json();

          tbody.innerHTML = '';
          if (searches.length === 0) {
            tbody.innerHTML = '<tr><td colspan="2" class="py-4 text-center text-gray-500">მონაცემები არ არის</td></tr>';
            return;
          }

          searches.forEach((item, index) => {
            const tr = `
              <tr class="transition-colors hover:bg-gray-50">
                <td class="py-2.5 pr-4 font-medium text-gray-800">
                  <span class="mr-2 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-gray-100 text-[10px] font-bold text-gray-500">${index + 1}</span>
                  <span class="break-words">${escapeHtml(item.search_term)}</span>
                </td>
                <td class="py-2.5 text-center text-[#E30613] font-bold">${escapeHtml(item.count)}</td>
              </tr>
            `;
            tbody.insertAdjacentHTML('beforeend', tr);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="2" class="py-4 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

async function fetchStaleArticles(token) {
        const t = token || Auth.getToken();
        const panel = document.getElementById('stale-content-panel');
        const tbody = document.getElementById('stale-articles-tbody');
        const badge = document.getElementById('stale-count-badge');
        if (!panel || !tbody) return;

        try {
          const response = await fetch('/api/admin/articles/stale', {
            headers: { 'Authorization': `Bearer ${t}` }
          });
          if (!response.ok) throw new Error('Failed to fetch stale articles');
          const articles = await response.json();

          if (articles.length === 0) {
            panel.classList.add('hidden');
            return;
          }

          panel.classList.remove('hidden');
          if (badge) badge.textContent = articles.length;

          tbody.innerHTML = '';
          articles.forEach(article => {
            const tr = document.createElement('tr');
            tr.className = 'transition-colors hover:bg-amber-50/60';
            tr.innerHTML = `
              <td class="py-2.5 pr-4 font-medium text-gray-800">
                <button onclick="openArticleModalById(${article.id})" class="text-left hover:text-[#E30613] hover:underline transition-colors">${escapeHtml(article.title)}</button>
              </td>
              <td class="py-2.5 pr-4 text-center text-xs text-gray-500">${escapeHtml((article.target_departments || []).join(', '))}</td>
              <td class="py-2.5 pr-4 text-center">
                <span class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-bold ${article.days_stale > 365 ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-700'}">
                  ${article.days_stale} დღე
                </span>
              </td>
              <td class="py-2.5 text-center">
                <button onclick="verifyStaleArticle(${article.id}, this)" class="rounded-lg bg-amber-600 px-3 py-1 text-xs font-bold text-white shadow-sm transition-all hover:bg-amber-700 active:scale-95">
                  ✓ აქტუალურია
                </button>
              </td>`;
            tbody.appendChild(tr);
          });
        } catch (error) {
          console.error('Stale articles fetch error:', error);
          panel.classList.add('hidden');
        }
      }

window._articlePageSize = 20;
window._articleCurrentPage = 1;
window._articlesCached = [];

function _renderArticleRow(article) {
  const date = new Date(article.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
  const categoryText = article.category_name || (typeof Store !== 'undefined' && Store.categories && Store.categories[article.category_id]) || 'ID: ' + article.category_id;
  const archivedBadge = article.status === 'archived'
    ? '<span class="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-bold text-gray-500">არქივი</span>' : '';
  const archiveAction = article.status === 'archived'
    ? `<button onclick="window.toggleArticleArchive(${article.id}, false)" class="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 transition-colors"><i class="fa-solid fa-box-open text-gray-400 w-4"></i> ამოღება არქივიდან</button>`
    : `<button onclick="window.toggleArticleArchive(${article.id}, true)" class="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 transition-colors"><i class="fa-solid fa-box-archive text-gray-400 w-4"></i> დაარქივება</button>`;
  return `
    <tr class="transition-colors hover:bg-gray-50 ${article.status === 'archived' ? 'opacity-60' : ''}">
      <td class="px-5 py-3"><span class="inline-flex items-center gap-2"><i class="fa-solid fa-file-lines text-gray-400"></i> ${escapeHtml(article.title)}${archivedBadge}</span></td>
      <td class="px-5 py-3 text-gray-500">${escapeHtml(categoryText)}</td>
      <td class="px-5 py-3 text-gray-500">${date}</td>
      <td class="px-5 py-3 text-center">
        <div class="flex items-center justify-center gap-3">
          <input type="checkbox" class="archive-check h-4 w-4 accent-[#E30613]" data-article-id="${article.id}" />
          <div class="relative inline-block text-left article-actions-dropdown">
            <button onclick="toggleArticleActionsMenu(event, ${article.id})" class="text-gray-400 hover:text-gray-600 focus:outline-none p-1" aria-label="მოქმედებები">
              <i class="fa-solid fa-ellipsis-vertical text-lg"></i>
            </button>
            <div id="article-actions-menu-${article.id}" class="absolute right-0 top-full mt-1 origin-top-right w-48 rounded-xl border border-gray-100 bg-white shadow-lg hidden z-20 py-1">
              <button onclick="window.viewArticleHistory(${article.id})" class="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 transition-colors">
                <i class="fa-solid fa-clock-rotate-left text-gray-400 w-4"></i> ისტორია
              </button>
              ${archiveAction}
              <button onclick="window.editArticle(${article.id})" class="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 transition-colors">
                <i class="fa-solid fa-pen-to-square text-gray-400 w-4"></i> რედაქტირება
              </button>
              <button onclick="window.deleteArticle(${article.id})" class="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 transition-colors">
                <i class="fa-solid fa-trash-can text-red-400 w-4"></i> წაშლა
              </button>
            </div>
          </div>
        </div>
      </td>
    </tr>`;
}

function renderArticlePage(page) {
  const tbody = document.getElementById('admin-content-tbody');
  const pagDiv = document.getElementById('admin-content-pagination');
  if (!tbody) return;

  const total = window._articlesCached.length;
  const pageSize = window._articlePageSize;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  page = Math.max(1, Math.min(page, totalPages));
  window._articleCurrentPage = page;

  const start = (page - 1) * pageSize;
  const slice = window._articlesCached.slice(start, start + pageSize);

  tbody.innerHTML = '';
  if (total === 0) {
    tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-gray-500">სტატიები არ მოიძებნა</td></tr>';
    if (pagDiv) pagDiv.innerHTML = '';
    return;
  }

  slice.forEach(a => tbody.insertAdjacentHTML('beforeend', _renderArticleRow(a)));
  syncSelectAllState();

  if (pagDiv) {
    const end = Math.min(start + pageSize, total);
    let html = '<span class="text-sm text-gray-500">' + (start + 1) + '–' + end + ' / ' + total + '</span>';
    html += '<div class="flex items-center gap-1">';

    html += '<button onclick="renderArticlePage(1)" ' + (page <= 1 ? 'disabled' : '') +
      ' class="rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors ' +
      (page <= 1 ? 'text-gray-300 cursor-default' : 'text-gray-600 hover:bg-gray-100') +
      '"><i class="fa-solid fa-angles-left"></i></button>';

    html += '<button onclick="renderArticlePage(' + (page - 1) + ')" ' + (page <= 1 ? 'disabled' : '') +
      ' class="rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors ' +
      (page <= 1 ? 'text-gray-300 cursor-default' : 'text-gray-600 hover:bg-gray-100') +
      '"><i class="fa-solid fa-chevron-left"></i></button>';

    var startP = Math.max(1, page - 2);
    var endP = Math.min(totalPages, startP + 4);
    startP = Math.max(1, endP - 4);
    for (var p = startP; p <= endP; p++) {
      html += '<button onclick="renderArticlePage(' + p + ')" class="rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ' +
        (p === page ? 'bg-[#B91C1C] text-white shadow-sm' : 'text-gray-600 hover:bg-gray-100') + '">' + p + '</button>';
    }

    html += '<button onclick="renderArticlePage(' + (page + 1) + ')" ' + (page >= totalPages ? 'disabled' : '') +
      ' class="rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors ' +
      (page >= totalPages ? 'text-gray-300 cursor-default' : 'text-gray-600 hover:bg-gray-100') +
      '"><i class="fa-solid fa-chevron-right"></i></button>';

    html += '<button onclick="renderArticlePage(' + totalPages + ')" ' + (page >= totalPages ? 'disabled' : '') +
      ' class="rounded-lg px-2.5 py-1.5 text-sm font-medium transition-colors ' +
      (page >= totalPages ? 'text-gray-300 cursor-default' : 'text-gray-600 hover:bg-gray-100') +
      '"><i class="fa-solid fa-angles-right"></i></button>';

    html += '</div>';
    pagDiv.innerHTML = html;
  }
}

async function fetchAndRenderAdminContent(token) {
        const tbody = document.getElementById('admin-content-tbody');
        if (!tbody) return;

        const catFilter = document.getElementById('article-filter-category');
        if (catFilter && catFilter.options.length <= 1) {
          const cats = (typeof Store !== 'undefined' && Store.categories) ? Store.categories : {};
          const catList = Object.keys(cats).map(id => ({ id: parseInt(id), name: cats[id] })).sort((a, b) => a.name.localeCompare(b.name, 'ka'));
          catFilter.innerHTML = '<option value="">ყველა კატეგორია</option>' + catList.map(c => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
        }

        const q = document.getElementById('article-filter-search')?.value.trim() || '';
        const categoryId = document.getElementById('article-filter-category')?.value || '';
        const status = document.getElementById('article-filter-status')?.value || '';

        let url = '/api/articles?limit=1000';
        if (q) url += `&q=${encodeURIComponent(q)}`;
        if (categoryId) url += `&category_id=${encodeURIComponent(categoryId)}`;
        if (status) url += `&status=${encodeURIComponent(status)}`;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-2/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-8 bg-gray-200 rounded w-24 mx-auto"></div></td>
          </tr>
        `;

        try {
          const response = await fetch(url, {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch articles');
          const articles = await response.json();

          window.adminArticles = {};
          articles.forEach(a => { window.adminArticles[a.id] = a; });
          window._articlesCached = articles;
          window._articleCurrentPage = 1;
          renderArticlePage(1);
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

let _articleFilterDebounce = null;
function onArticleFilterChange() {
  if (_articleFilterDebounce) window.clearTimeout(_articleFilterDebounce);
  _articleFilterDebounce = window.setTimeout(() => {
    const token = localStorage.getItem('magti_token');
    if (token) fetchAndRenderAdminContent(token);
  }, 300);
}

// Header "select all" — toggles every row checkbox in the content table.
function toggleSelectAllContent(master) {
  document.querySelectorAll('#admin-content-tbody .archive-check')
    .forEach(box => { box.checked = master.checked; });
  master.indeterminate = false;
}

// Reflect the per-row checkbox set onto the master (checked / indeterminate).
function syncSelectAllState() {
  const master = document.getElementById('select-all-content');
  if (!master) return;
  const boxes = [...document.querySelectorAll('#admin-content-tbody .archive-check')];
  const checked = boxes.filter(b => b.checked).length;
  master.checked = boxes.length > 0 && checked === boxes.length;
  master.indeterminate = checked > 0 && checked < boxes.length;
}

function toggleArticleActionsMenu(event, id) {
  event.stopPropagation();
  document.querySelectorAll('[id^="article-actions-menu-"]').forEach(el => {
    if (el.id !== `article-actions-menu-${id}`) el.classList.add('hidden');
  });
  const menu = document.getElementById(`article-actions-menu-${id}`);
  if (!menu) return;

  const opening = menu.classList.contains('hidden');
  menu.classList.toggle('hidden');
  if (!opening) return;

  const btn = event.currentTarget;
  const scrollParent = btn.closest('.overflow-x-auto') || document.body;
  const spaceBelow = scrollParent.getBoundingClientRect().bottom - btn.getBoundingClientRect().bottom;
  const menuHeight = menu.offsetHeight || 160;

  menu.classList.remove('top-full', 'mt-1', 'origin-top-right', 'bottom-full', 'mb-1', 'origin-bottom-right');
  if (spaceBelow < menuHeight) {
    menu.classList.add('bottom-full', 'mb-1', 'origin-bottom-right');
  } else {
    menu.classList.add('top-full', 'mt-1', 'origin-top-right');
  }
}

// Close dropdowns on document click
document.addEventListener('click', () => {
  document.querySelectorAll('[id^="article-actions-menu-"]').forEach(el => el.classList.add('hidden'));
});

// Keep the master checkbox in sync when individual rows are toggled.
document.addEventListener('change', (e) => {
  if (e.target && e.target.classList && e.target.classList.contains('archive-check')) {
    syncSelectAllState();
  }
});

// Audit log date-range picker. Deferred to DOMContentLoaded — this script
// executes before the Flatpickr CDN <script> (it's declared earlier in
// <head>, and deferred scripts run in document order), so the `flatpickr`
// global isn't defined yet at this point in the file.
document.addEventListener('DOMContentLoaded', () => {
  if (document.getElementById('log-date-range')) {
    flatpickr("#log-date-range", { mode: "range", dateFormat: "Y-m-d" });
  }

  const exportBtn = document.getElementById('btn-export-audit-csv');
  if (exportBtn) {
    exportBtn.addEventListener('click', () => {
      const params = new URLSearchParams();
      const userName = document.getElementById('log-search-user').value;
      if (userName) params.append('user_name', userName);

      const fp = document.querySelector("#log-date-range")._flatpickr;
      if (fp && fp.selectedDates.length === 2) {
        params.append('start_date', fp.formatDate(fp.selectedDates[0], "Y-m-d"));
        params.append('end_date', fp.formatDate(fp.selectedDates[1], "Y-m-d"));
      }

      const exportUrl = '/api/audit-logs/export?' + params.toString();
      window.location.href = exportUrl;
    });
  }
});

window.onArticleFilterChange = onArticleFilterChange;
window.toggleArticleActionsMenu = toggleArticleActionsMenu;
window.toggleSelectAllContent = toggleSelectAllContent;
window.syncSelectAllState = syncSelectAllState;


// ── Migrated legacy articles (Admin "მიგრირებული ბაზა" verification view) ──
// Pulls the full article list (high limit — the summary endpoint defaults to 20)
// and filters to the importer's sentinel tag. Cached client-side so the search
// box filters instantly without re-hitting the API.
const MIGRATED_SENTINEL_TAG = 'მიგრირებული';

async function fetchAndRenderMigratedArticles(token) {
        const tbody = document.getElementById('admin-migrated-tbody');
        if (!tbody) return;
        const t = token || (typeof Auth !== 'undefined' ? Auth.getToken() : null);
        tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-6 text-center text-gray-400">იტვირთება...</td></tr>';
        try {
          const response = await fetch('/api/articles?limit=1000', {
            headers: { 'Authorization': `Bearer ${t}` }
          });
          if (typeof handleSessionExpiry === 'function' && handleSessionExpiry(response)) return;
          if (!response.ok) throw new Error('Failed to fetch articles');
          const all = await response.json();

          const migrated = (all || [])
            .filter(a => (a.tags || '').split(',').map(s => s.trim()).includes(MIGRATED_SENTINEL_TAG))
            .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));

          window.migratedArticlesCache = migrated;
          const badge = document.getElementById('migrated-count-badge');
          if (badge) badge.textContent = migrated.length;
          renderMigratedRows(migrated);
        } catch (error) {
          console.error('Migrated articles error:', error);
          tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-6 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

function renderMigratedRows(list) {
        const tbody = document.getElementById('admin-migrated-tbody');
        if (!tbody) return;
        const esc = (s) => (typeof escapeHtml === 'function' ? escapeHtml(String(s)) : String(s));
        if (!list || list.length === 0) {
          tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-6 text-center text-gray-500">მიგრირებული სტატია ვერ მოიძებნა</td></tr>';
          return;
        }
        tbody.innerHTML = '';
        list.forEach((a, i) => {
          const dt = a.created_at ? new Date(a.created_at).toLocaleString('ka-GE') : '—';
          const cat = (typeof Store !== 'undefined' && Store.categories && Store.categories[a.category_id])
            ? Store.categories[a.category_id] : ('ID: ' + a.category_id);
          tbody.insertAdjacentHTML('beforeend', `
            <tr class="transition-colors hover:bg-gray-50">
              <td class="px-5 py-3 text-xs text-gray-400">${i + 1}</td>
              <td class="px-5 py-3 font-medium text-gray-800">
                <button onclick="openArticleModalById(${a.id})"
                  class="inline-flex items-center gap-2 text-left transition-colors hover:text-[#E30613] hover:underline">
                  <i class="fa-solid fa-file-lines text-gray-400"></i> ${esc(a.title)}
                </button>
                <span class="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-500">${esc(cat)}</span>
              </td>
              <td class="px-5 py-3 text-xs text-gray-500">${esc(dt)}</td>
              <td class="px-5 py-3 text-center">
                <button onclick="openArticleModalById(${a.id})"
                  class="rounded-lg bg-[#E30613] px-3 py-1 text-xs font-bold text-white shadow-sm transition-all hover:bg-red-700 active:scale-95">
                  <i class="fa-solid fa-eye mr-1"></i> გახსნა
                </button>
              </td>
            </tr>`);
        });
      }

function filterMigratedArticles(term) {
        const q = (term || '').toLowerCase().trim();
        const list = window.migratedArticlesCache || [];
        renderMigratedRows(q ? list.filter(a => (a.title || '').toLowerCase().includes(q)) : list);
      }

async function fetchAndRenderAdminNews(token) {
        const tbody = document.getElementById('admin-news-tbody');
        if (!tbody) return;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-2/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-8 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/2"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-8 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
        `;

        try {
          const response = await fetch('/api/news?limit=1000', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch news');
          const newsItems = await response.json();

          // Update global cache
          newsItems.forEach(item => {
            window.cachedNewsItems = window.cachedNewsItems || {};
            window.cachedNewsItems[item.id] = item;
          });

          tbody.innerHTML = '';
          if (newsItems.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-gray-500">სიახლეები არ მოიძებნა</td></tr>';
            return;
          }

          newsItems.forEach(item => {
            const date = new Date(item.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
            const deptText = item.target_department === 'All' ? 'ყველა დეპარტამენტი' : item.target_department;
            const safeTitle = item.title.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");

            const tr = `
              <tr class="transition-colors hover:bg-gray-50">
                <td class="px-5 py-3"><span class="inline-flex items-center gap-2"><i class="fa-solid fa-bullhorn text-gray-400"></i> ${safeTitle}</span></td>
                <td class="px-5 py-3 text-gray-500">${deptText}</td>
                <td class="px-5 py-3 text-gray-500">${date}</td>
                <td class="px-5 py-3">
                  <div class="flex items-center justify-center gap-3">
                    <button onclick="editNews(${item.id})" class="text-gray-400 hover:text-blue-500 transition-colors" aria-label="რედაქტირება" title="რედაქტირება"><i class="fa-solid fa-pen-to-square"></i></button>
                    <button onclick="viewNewsHistory(${item.id})" class="text-gray-400 hover:text-purple-500 transition-colors" aria-label="ისტორია" title="ვერსიების ისტორია"><i class="fa-solid fa-clock-rotate-left"></i></button>
                    <button onclick="deleteNews(${item.id})" class="text-gray-400 hover:text-[#E30613] transition-colors" aria-label="წაშლა" title="წაშლა"><i class="fa-solid fa-trash-can"></i></button>
                  </div>
                </td>
              </tr>
            `;
            tbody.insertAdjacentHTML('beforeend', tr);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

async function fetchAndRenderAdminVideos(token) {
        const tbody = document.getElementById('admin-videos-tbody');
        if (!tbody) return;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-2/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-8 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
        `;

        try {
          const response = await fetch('/api/videos', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch videos');
          const videos = await response.json();

          // Cache videos
          window.cachedVideos = window.cachedVideos || {};
          videos.forEach(v => {
            window.cachedVideos[v.id] = v;
          });

          tbody.innerHTML = '';
          if (videos.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-gray-500">ვიდეოები არ მოიძებნა</td></tr>';
            return;
          }

          videos.forEach(item => {
            const safeTitle = item.title.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
            const categoryText = item.category || '—';
            const deptText = item.target_department === 'All' ? 'ყველა დეპარტამენტი' : item.target_department;

            const tr = `
              <tr class="transition-colors hover:bg-gray-50">
                <td class="px-5 py-3"><span class="inline-flex items-center gap-2"><i class="fa-solid fa-circle-play text-gray-400"></i> ${safeTitle}</span></td>
                <td class="px-5 py-3 text-gray-500">${categoryText}</td>
                <td class="px-5 py-3 text-gray-500">${deptText}</td>
                <td class="px-5 py-3">
                  <div class="flex items-center justify-center gap-3">
                    <button onclick="editVideo(${item.id})" class="text-gray-400 hover:text-blue-500 transition-colors" aria-label="რედაქტირება" title="რედაქტირება"><i class="fa-solid fa-pen-to-square"></i></button>
                    <button onclick="deleteVideo(${item.id})" class="text-gray-400 hover:text-[#E30613] transition-colors" aria-label="წაშლა" title="წაშლა"><i class="fa-solid fa-trash-can"></i></button>
                  </div>
                </td>
              </tr>
            `;
            tbody.insertAdjacentHTML('beforeend', tr);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-3 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

async function editVideo(videoId) {
        let item = (window.cachedVideos || {})[videoId];
        if (!item && window.Store && window.Store.videos && window.Store.videos[videoId]) {
          item = window.Store.videos[videoId];
        }
        if (!item) {
          alert('ვიდეოს მონაცემები ვერ მოიძებნა. სცადეთ გვერდის განახლება.');
          return;
        }

        if (typeof focusVideoForm === 'function') {
          focusVideoForm();
        }
        window.editingVideoId = videoId;

        // Prefill fields
        document.getElementById('video-title').value = item.title || '';
        document.getElementById('video-url').value = item.video_url || '';
        document.getElementById('video-category').value = item.category || '';
        document.getElementById('video-department').value = item.target_department || 'All';

        // Set form title to Editing
        const titleEl = document.querySelector('#admin-video-panel h3');
        if (titleEl) {
          titleEl.textContent = 'ვიდეოს რედაქტირება';
        }

        // Fetch compliance info
        const token = Auth.getToken();
        const checkbox = document.getElementById('video-mandatory');
        const dateInput = document.getElementById('video-due-date');

        checkbox.checked = false;
        dateInput.value = '';
        if (typeof toggleVideoDueDate === 'function') {
          toggleVideoDueDate();
        }

        try {
          const r = await fetch(`/api/compliance/required-readings/by-item/video/${videoId}`, {
            headers: { Authorization: 'Bearer ' + token },
          });
          if (r.ok) {
            const rr = await r.json();
            if (rr && rr.due_date) {
              checkbox.checked = true;
              dateInput.value = rr.due_date.substring(0, 10);
              if (typeof toggleVideoDueDate === 'function') {
                toggleVideoDueDate();
              }
            }
          }
        } catch (err) {
          console.error('Failed to load video compliance data', err);
        }
      }

async function deleteVideo(videoId) {
        if (!confirm('ნამდვილად გსურთ ამ ვიდეოს წაშლა?')) return;
        const token = Auth.getToken();
        if (!token) return;

        try {
          // Remove any compliance requirement
          if (typeof syncMandatoryFor === 'function') {
            await syncMandatoryFor('video', videoId, 'All', false, null, token);
          }

          const response = await fetch(`/api/videos/${videoId}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok && response.status !== 204) throw new Error('ვიდეოს წაშლა ვერ მოხერხდა');

          if (typeof showToast === 'function') {
            showToast('ვიდეო წაიშალა', '', { variant: 'success' });
          }
          if (typeof fetchAndRenderVideos === 'function') {
            fetchAndRenderVideos(token);
          }
          fetchAndRenderAdminVideos(token);
        } catch (error) {
          console.error(error);
          alert('შეცდომა წაშლისას: ' + error.message);
        }
      }

async function editVideoDirectly(videoId) {
        const user = window.currentUser || (typeof Auth !== 'undefined' && Auth._payload ? Auth._payload() : null);
        const isAdmin = user && (user.role === 'admin' || user.role === 'content_admin');
        if (!isAdmin) {
          if (typeof showToast === 'function') {
            showToast('წვდომა უარყოფილია', 'ვიდეოს რედაქტირება შეუძლიათ მხოლოდ ადმინისტრატორებს.', { variant: 'error' });
          }
          return;
        }

        // 1. Navigate to the admin page
        if (typeof navTo === 'function') {
          navTo('page-admin');
        } else if (typeof switchMainPage === 'function') {
          const adminBtn = document.getElementById('sidebar-admin-link');
          if (adminBtn) switchMainPage('page-admin', adminBtn);
        }

        // 2. Open the Content tab
        if (typeof switchAdmin === 'function') {
          switchAdmin('content');
        }
        
        const token = Auth.getToken();
        // Ensure the admin video grid is rendered, then trigger editVideo
        if (typeof fetchAndRenderAdminVideos === 'function') {
          await fetchAndRenderAdminVideos(token);
        }
        
        const contentTabBtn = [...document.querySelectorAll('.content-tab-btn')].find(btn => btn.textContent.includes('ვიდეოები'));
        if (typeof switchContentTab === 'function') {
          switchContentTab('videos', contentTabBtn);
        }

        if (typeof editVideo === 'function') {
          await editVideo(videoId);
        }
      }

window.fetchAndRenderAdminVideos = fetchAndRenderAdminVideos;
window.editVideo = editVideo;
window.deleteVideo = deleteVideo;
window.editVideoDirectly = editVideoDirectly;

async function fetchAndRenderKnowledgeBase(token, append = false) {
        const container = document.getElementById('kb-articles-container');
        if (!container) return;

        if (window.kbIsLoading) return;
        window.kbIsLoading = true;

        if (!append) {
          window.kbSkip = 0;
          window.kbHasMore = true;
          container.innerHTML = `
            <div class="animate-pulse flex flex-col items-center justify-center rounded-2xl border border-gray-100 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 shadow-sm min-h-[140px]">
              <div class="mb-4 h-12 w-12 rounded-2xl bg-gray-200 dark:bg-zinc-800"></div>
              <div class="h-4 w-3/4 rounded bg-gray-200 dark:bg-zinc-800"></div>
            </div>
            <div class="animate-pulse flex flex-col items-center justify-center rounded-2xl border border-gray-100 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 shadow-sm min-h-[140px]">
              <div class="mb-4 h-12 w-12 rounded-2xl bg-gray-200 dark:bg-zinc-800"></div>
              <div class="h-4 w-3/4 rounded bg-gray-200 dark:bg-zinc-800"></div>
            </div>
            <div class="animate-pulse flex flex-col items-center justify-center rounded-2xl border border-gray-100 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-6 shadow-sm min-h-[140px]">
              <div class="mb-4 h-12 w-12 rounded-2xl bg-gray-200 dark:bg-zinc-800"></div>
              <div class="h-4 w-3/4 rounded bg-gray-200 dark:bg-zinc-800"></div>
            </div>
          `;
        }

        await Store.whenFavoritesReady();   // star icons need favourites loaded

        try {
          const response = await api(`/api/articles?skip=${window.kbSkip}&limit=${window.kbLimit}`);
          if (!response.ok) throw new Error('Failed to fetch articles');
          const articles = await response.json();

          if (!append) {
            clearObj(Store.articles);
            container.innerHTML = '';
          }

          if (articles.length < window.kbLimit) {
            window.kbHasMore = false;
          }

          articles.forEach(a => { Store.articles[a.id] = a; });

          if (articles.length === 0 && !append) {
            container.innerHTML = '<p class="col-span-full text-sm text-gray-500">სტატიები არ მოიძებნა.</p>';
            window.kbIsLoading = false;
            return;
          }

          articles.forEach(article => {
            // Mark as NEW if created in the last 4 days
            const isNew = (new Date() - new Date(article.created_at)) < (4 * 24 * 60 * 60 * 1000);
            const newBadge = isNew ? `<span class="inline-flex items-center gap-1 rounded-full bg-red-50 dark:bg-red-950/40 px-1.5 py-0.5 text-[9px] font-semibold text-[#E30613] dark:text-red-400 border border-red-100/50 dark:border-red-900/30"><span class="h-1 w-1 rounded-full bg-[#E30613] dark:bg-red-500"></span>ახალი</span>` : '';
            const date = new Date(article.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
            const catName = Store.categories[article.category_id] || 'ზოგადი';
            const iconClass = getCategoryIcon(article.category_id, article.title);
            const readTime = article.read_time || Math.max(2, Math.round(article.title.length / 12));
            const styles = getCategoryCardStyles(catName);

            const html = `
              <div onclick="openArticleModalById(${article.id})" 
                   onkeydown="if(event.key==='Enter'||event.key===' '){openArticleModalById(${article.id});event.preventDefault();}" 
                   tabindex="0" 
                   role="button" 
                   class="kb-card group relative flex cursor-pointer flex-col justify-between overflow-hidden rounded-2xl border border-gray-150 dark:border-zinc-800 bg-white dark:bg-zinc-900 p-5 shadow-sm transition-all duration-300 hover:-translate-y-1 ${styles.borderHover} hover:shadow-md focus:outline-none focus:ring-2 focus:ring-[#E30613] min-h-[160px] ${styles.borderAccent}">
                
                <!-- Top Row: Category & Star/New -->
                <div class="flex items-center justify-between w-full mb-3 shrink-0">
                  <div class="flex items-center gap-2" onclick="event.stopPropagation();">
                    <div class="flex h-7 w-7 items-center justify-center rounded-lg ${styles.iconBg} text-sm transition-transform group-hover:scale-105">
                      <i class="fa-solid ${iconClass}"></i>
                    </div>
                    <span class="text-[10px] font-bold uppercase tracking-wider text-gray-400 dark:text-zinc-500">${escapeHtml(catName)}</span>
                  </div>
                  <div class="flex items-center gap-2">
                    ${newBadge}
                    <!-- [P1-8] Star with visible iconic contrast (border + gray-500 vs. invisible gray-300) -->
                    <button onclick="toggleFavorite('article', ${article.id}, this); event.stopPropagation();" data-fav-type="article" data-fav-id="${article.id}"
                      class="flex h-7 w-7 items-center justify-center rounded-full border border-gray-200 text-gray-500 transition-all hover:border-yellow-400 hover:bg-yellow-50 hover:text-yellow-500 focus:outline-none focus:ring-2 focus:ring-yellow-400 dark:border-zinc-700 dark:text-zinc-400"
                      aria-label="რჩეულებში დამატება">
                      <i class="fa-regular fa-star text-xs"></i>
                    </button>
                  </div>
                </div>

                <!-- Title — [P1-7] fixed 2-line min-height stops grid jitter -->
                <h4 class="w-full text-left text-[13.5px] font-semibold text-gray-800 dark:text-zinc-200 line-clamp-2 min-h-[2.6rem] leading-tight ${styles.textAccent} transition-colors mb-4" title="${escapeHtml(article.title)}">
                  ${escapeHtml(article.title)}
                </h4>

                <!-- Footer: Date / Read Time -->
                <div class="flex items-center justify-between border-t border-gray-50 dark:border-zinc-800/50 pt-3 text-[10.5px] text-gray-400 dark:text-zinc-500 w-full mt-auto shrink-0">
                  <span class="flex items-center gap-1.5">
                    <i class="fa-regular fa-calendar text-xs"></i>
                    ${date}
                  </span>
                  <span class="flex items-center gap-1.5">
                    <i class="fa-regular fa-clock text-xs"></i>
                    ${readTime} წთ. საკითხავი
                  </span>
                </div>
              </div>
            `;
            container.insertAdjacentHTML('beforeend', html);
          });
          updateStarIcons();

          // Setup Intersection Observer for Infinite Scroll
          if (window.kbObserver) window.kbObserver.disconnect();
          if (window.kbHasMore) {
            const cards = container.querySelectorAll('.kb-card');
            if (cards.length > 0) {
              const lastCard = cards[cards.length - 1];
              window.kbObserver = new IntersectionObserver((entries) => {
                if (entries[0].isIntersecting) {
                  window.kbSkip += window.kbLimit;
                  fetchAndRenderKnowledgeBase(token, true);
                }
              }, { rootMargin: '100px' });
              window.kbObserver.observe(lastCard);
            }
          }
        } catch (error) {
          console.error(error);
          if (!append) container.innerHTML = '<p class="col-span-full text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
        } finally {
          window.kbIsLoading = false;
        }
      }

async function fetchAndRenderVideos(token) {
        const container = document.getElementById('videos-container');
        if (!container) return;
        await Store.whenFavoritesReady();   // star icons need favourites loaded

        try {
          const response = await api('/api/videos');
          if (!response.ok) throw new Error('Failed to fetch videos');
          const videos = await response.json();

          // Cache so the click handler can resolve the video URL by id
          clearObj(Store.videos);
          videos.forEach(v => { Store.videos[v.id] = v; });

          container.innerHTML = '';
          if (videos.length === 0) {
            container.innerHTML = '<p class="col-span-full text-sm text-gray-500">ვიდეოები არ მოიძებნა.</p>';
            return;
          }

          videos.forEach(video => {
            let ytId = null;
            if (video.video_url) {
              const urlStr = video.video_url.trim();
              if (/^[a-zA-Z0-9_-]{11}$/.test(urlStr)) {
                ytId = urlStr;
              } else {
                try {
                  let checkUrl = urlStr;
                  if (!/^https?:\/\//i.test(checkUrl)) {
                    checkUrl = 'https://' + checkUrl;
                  }
                  const u = new URL(checkUrl);
                  if (u.pathname.startsWith('/embed/')) {
                    const parts = u.pathname.split('/');
                    const id = parts[2];
                    if (id && id.length === 11) ytId = id;
                  } else if (u.hostname.endsWith('youtu.be')) {
                    const id = u.pathname.replace(/^\//, '').split('/')[0];
                    if (id && id.length === 11) ytId = id;
                  } else if (u.hostname.includes('youtube.com')) {
                    const id = u.searchParams.get('v') || (u.pathname.match(/^\/shorts\/([^/?]+)/) || [])[1];
                    if (id && id.length === 11) ytId = id;
                  }
                } catch (_) {}
              }
            }
            const thumbnailSrc = ytId ? `https://img.youtube.com/vi/${ytId}/mqdefault.jpg` : '/static/placeholder.png';

            const user = window.currentUser || (typeof Auth !== 'undefined' && Auth._payload ? Auth._payload() : null);
            const isAdmin = user && (user.role === 'admin' || user.role === 'content_admin');

            const editControls = isAdmin ? `
              <button onclick="editVideoDirectly(${video.id}); event.stopPropagation();" 
                      class="flex h-7 w-7 items-center justify-center rounded-full bg-black/40 text-xs text-white backdrop-blur-sm hover:bg-black/60 hover:text-blue-400 transition-colors focus:outline-none" 
                      title="ვიდეოს რედაქტირება">
                <i class="fa-solid fa-ellipsis-vertical"></i>
              </button>
              <button onclick="editVideoDirectly(${video.id}); event.stopPropagation();" 
                      class="flex h-7 w-7 items-center justify-center rounded-full bg-black/40 text-xs text-white backdrop-blur-sm hover:bg-black/60 hover:text-blue-400 transition-colors focus:outline-none" 
                      title="ვიდეოს რედაქტირება">
                <i class="fa-solid fa-pen-to-square"></i>
              </button>
            ` : `
              <div class="flex h-7 w-7 items-center justify-center rounded-full bg-black/40 text-xs text-white backdrop-blur-sm hover:bg-black/60">
                <i class="fa-solid fa-ellipsis-vertical"></i>
              </div>
            `;

            const html = `
              <div onclick="viewVideo(${video.id})" 
                   onkeydown="if(event.key==='Enter'||event.key===' '){viewVideo(${video.id});event.preventDefault();}" 
                   tabindex="0" 
                   role="button" 
                   data-video-title="${video.title.toLowerCase()}" 
                   class="video-card group cursor-pointer overflow-hidden rounded-2xl border border-gray-100 bg-white shadow-sm transition-all hover:border-gray-200 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-[#E30613]">
                <div class="relative flex aspect-video w-full items-center justify-center bg-[#1a1a2e]">
                  <img src="${thumbnailSrc}" alt="${escapeHtml(video.title)}" class="absolute inset-0 h-full w-full object-cover opacity-60 group-hover:opacity-85 transition-opacity">
                  <div class="absolute left-4 top-4 text-[11px] font-bold tracking-widest text-white/80 z-10">MAGTI</div>
                  <div class="absolute right-4 top-4 z-10">
                    <button onclick="toggleFavorite('video', ${video.id}, this); event.stopPropagation();" data-fav-type="video" data-fav-id="${video.id}" class="text-lg text-white/80 transition-all hover:scale-110 hover:text-yellow-400 focus:outline-none focus:ring-2 focus:ring-yellow-400 rounded-full"><i class="fa-regular fa-star"></i></button>
                  </div>
                  <div class="relative z-10 flex h-14 w-14 items-center justify-center rounded-full bg-white/20 text-white backdrop-blur-sm transition-transform group-hover:scale-110 group-hover:bg-white/30">
                    <i class="fa-solid fa-play ml-1 text-xl"></i>
                  </div>
                  <div class="absolute bottom-4 left-4 flex gap-2 z-10">
                    <div class="flex h-7 w-7 items-center justify-center rounded-full bg-black/40 text-xs text-white backdrop-blur-sm hover:bg-black/60">
                      <i class="fa-solid fa-share"></i>
                    </div>
                    ${editControls}
                  </div>
                  <div class="absolute bottom-4 right-4 flex items-center gap-2 rounded bg-black/40 px-2.5 py-1 backdrop-blur-sm hover:bg-black/60 z-10">
                    <span class="text-[10px] font-medium text-white/90">ნახვები: ${video.views_count}</span>
                    <i class="fa-solid fa-eye text-[15px] text-gray-300"></i>
                  </div>
                </div>
                <div class="p-4">
                  <h4 class="font-bold text-gray-800">${escapeHtml(video.title)}</h4>
                </div>
              </div>
            `;
            container.insertAdjacentHTML('beforeend', html);
          });
          updateStarIcons();
        } catch (error) {
          console.error(error);
          container.innerHTML = '<p class="col-span-full text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
        }
      }

async function fetchAndRenderNewsPage(token, loadMore = false) {
        const container = document.getElementById('news-page-container');
        if (!container) return;
        await Store.whenFavoritesReady();   // star icons need favourites loaded
        
        const limit = 10;
        if (!loadMore) {
           window.newsCurrentSkip = 0;
           window.allNewsItems = [];
           container.innerHTML = '<p class="text-gray-400 py-8 text-center col-span-full">იტვირთება...</p>';
        } else {
           window.newsCurrentSkip = (window.newsCurrentSkip || 0) + limit;
           const btn = document.getElementById('news-load-more-btn');
           if (btn) btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> იტვირთება...';
        }

        try {
          const response = await api(`/api/news?skip=${window.newsCurrentSkip || 0}&limit=${limit}`);
          if (!response.ok) throw new Error('Failed to fetch news');
          const newsItems = await response.json();

          window.newsHasMore = newsItems.length === limit;

          // Cache news items
          newsItems.forEach(item => {
            window.cachedNewsItems = window.cachedNewsItems || {};
            window.cachedNewsItems[item.id] = item;
          });

          // Store in a global variable for client-side search, filtering, and sorting
          window.allNewsItems = (window.allNewsItems || []).concat(newsItems);

          // Perform initial filter and render
          runNewsFilter(loadMore);
        } catch (error) {
          console.error(error);
          if (!loadMore) {
            container.innerHTML = '<p class="text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</p>';
          } else {
             const btn = document.getElementById('news-load-more-btn');
             if (btn) btn.innerHTML = 'შეცდომა ჩატვირთვისას';
          }
        }
      }
      
      window.loadMoreNews = () => fetchAndRenderNewsPage(Auth.getToken(), true);

async function fetchKbCategories(token) {
        const select = document.getElementById('kb-category-filter');
        if (!select) return;
        try {
          const response = await fetch('/api/categories', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) return;
          const categories = await response.json();
          categories.forEach(c => { Store.categories[c.id] = c.name; });
          Store.markReady('categories');

          // [Fix G12] Filter dropdown shows tree: top-levels first, then indented subcategories.
          const tops = categories.filter(c => !c.parent_id);
          const subsByParent = {};
          categories.filter(c => c.parent_id).forEach(c => {
            (subsByParent[c.parent_id] = subsByParent[c.parent_id] || []).push(c);
          });
          let optHtml = '<option value="">ყველა კატეგორია</option>';
          tops.forEach(t => {
            optHtml += `<option value="${t.id}">${escapeHtml(t.name)}</option>`;
            (subsByParent[t.id] || []).forEach(sub => {
              optHtml += `<option value="${sub.id}">  └ ${escapeHtml(sub.name)}</option>`;
            });
          });
          // Orphans (parent removed)
          const topIds = new Set(tops.map(t => t.id));
          categories.filter(c => c.parent_id && !topIds.has(c.parent_id))
            .forEach(o => { optHtml += `<option value="${o.id}">${escapeHtml(o.name)}</option>`; });
          select.innerHTML = optHtml;

          // [Fix G12] Render the bento dynamically from top-level categories.
          renderKbBento(tops);

          // Trigger initial Bento active state update
          updateBentoActiveState();
        } catch (error) {
          console.error('Error fetching categories:', error);
        }
      }

async function fetchAndRenderMessages(token) {
        try {
          const response = await api('/api/messages');
          if (!response.ok) throw new Error('Failed to fetch messages');
          Store.messages = await response.json();
          window.userMessages = Store.messages;

          const unreadCount = window.userMessages.filter(m => !m.is_read).length;
          const badge = document.getElementById('envelope-badge');
          if (badge) {
            badge.textContent = unreadCount;
            badge.classList.toggle('hidden', unreadCount === 0);
            badge.classList.toggle('flex', unreadCount > 0);
          }

          // Fetch sent messages if user is a manager or admin
          const role = window.currentUser ? window.currentUser.role : '';
          if (role === 'manager' || role === 'admin') {
            try {
              const sentResponse = await api('/api/messages/sent');
              if (sentResponse.ok) {
                window.sentMessages = await sentResponse.json();
              } else {
                window.sentMessages = [];
              }
            } catch (sentError) {
              console.error('Error fetching sent messages:', sentError);
              window.sentMessages = [];
            }
          } else {
            window.sentMessages = [];
          }

          renderMessages();
        } catch (error) {
          console.error('Error fetching messages:', error);
        }
      }

async function fetchAndRenderAdminFeedback(token) {
        const tbody = document.getElementById('admin-feedback-tbody');
        if (!tbody) return;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/2"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-2/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-6 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/2"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-6 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
        `;

        try {
          const response = await fetch('/api/admin/feedback', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch feedback');
          const feedbacks = await response.json();

          tbody.innerHTML = '';
          if (feedbacks.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-3 text-center text-gray-500">ხარვეზები არ მოიძებნა</td></tr>';
            return;
          }

          feedbacks.forEach(fb => {
            const date = new Date(fb.created_at).toLocaleString('ka-GE');
            const tr = document.createElement('tr');
            tr.className = 'transition-colors hover:bg-gray-50';
            // [Fix] Status badge now reflects resolved/rejected with color, plus
            //       action buttons so admins can transition state without leaving the page.
            const statusStyles = {
              open: 'bg-yellow-100 text-yellow-800',
              resolved: 'bg-green-100 text-green-800',
              rejected: 'bg-gray-200 text-gray-700',
            };
            const statusLabels = { open: 'ღია', resolved: 'მოგვარდა', rejected: 'უარყოფილია' };
            const cls = statusStyles[fb.status] || statusStyles.open;
            const lab = statusLabels[fb.status] || fb.status;
            const actions = fb.status === 'open' ? `
              <div class="mt-1 flex justify-center gap-1.5">
                <button onclick="setFeedbackStatus(${fb.id}, 'resolved')"
                  class="rounded-md bg-green-600 px-2 py-0.5 text-[10px] font-bold text-white hover:bg-green-700">მოგვარდა</button>
                <button onclick="setFeedbackStatus(${fb.id}, 'rejected')"
                  class="rounded-md bg-gray-500 px-2 py-0.5 text-[10px] font-bold text-white hover:bg-gray-600">უარყოფა</button>
              </div>` : `
              <div class="mt-1 flex justify-center">
                <button onclick="setFeedbackStatus(${fb.id}, 'open')"
                  class="rounded-md border border-gray-300 px-2 py-0.5 text-[10px] font-semibold text-gray-600 hover:bg-gray-50">გახსნა</button>
              </div>`;
            tr.innerHTML = `
              <td class="px-5 py-3 font-medium text-gray-800">${escapeHtml(fb.user_name || 'უცნობი')}</td>
              <td class="px-5 py-3 text-gray-600">${escapeHtml(fb.article_title || 'უცნობი სტატია')} (ID: ${fb.article_id})</td>
              <td class="px-5 py-3 text-gray-600 max-w-xs truncate" title="${escapeHtml(fb.message)}">${escapeHtml(fb.message)}</td>
              <td class="px-5 py-3 text-gray-500">${date}</td>
              <td class="px-5 py-3 text-center">
                <span class="inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold leading-5 ${cls}">${lab}</span>
                ${actions}
              </td>
            `;
            tbody.appendChild(tr);
          });
        } catch (error) {
          console.error('Error rendering feedback:', error);
        }
      }

async function fetchAndRenderSearchHistory(token) {
        const tbody = document.getElementById('search-history-table-body');
        if (!tbody) return;
        try {
          const response = await fetch('/api/search/history', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch search history');
          const history = await response.json();
          tbody.innerHTML = '';
          if (history.length === 0) {
            tbody.innerHTML = `
              <tr>
                <td colspan="3" class="px-6 py-4 text-center text-gray-400">ისტორია ცარიელია</td>
              </tr>
            `;
            return;
          }
          history.forEach(item => {
            const date = new Date(item.timestamp).toLocaleString('ka-GE');
            const escTerm = (item.search_term || '').replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
            tbody.insertAdjacentHTML('beforeend', `
              <tr class="border-b border-gray-100 hover:bg-gray-50">
                <td class="px-6 py-4 font-medium text-gray-800">${escTerm}</td>
                <td class="px-6 py-4 text-xs text-gray-500">${date}</td>
                <td class="px-6 py-4 text-right">
                  <button onclick="searchHistoryItemClick('${escTerm.replace(/'/g, "\\'")}')" 
                          class="rounded-lg bg-gray-100 px-3 py-1.5 text-xs font-semibold text-gray-600 transition-colors hover:bg-[#E30613] hover:text-white"
                          role="button" tabindex="0">
                    ძებნა
                  </button>
                </td>
              </tr>
            `);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = `
            <tr>
              <td colspan="3" class="px-6 py-4 text-center text-red-500">ისტორიის ჩატვირთვა ვერ მოხერხდა.</td>
            </tr>
          `;
        }
      }

async function fetchKPIs(token) {
        try {
          const response = await fetch('/api/statistics/kpi', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch KPIs');
          const kpi = await response.json();

          // ── Core count updates ───────────────────────────────────────────────
          const usersEl   = document.getElementById('kpi-users');
          const articlesEl= document.getElementById('kpi-articles');
          const readingsEl= document.getElementById('kpi-readings');
          const videosEl  = document.getElementById('kpi-videos');

          if (usersEl)    usersEl.textContent    = kpi.users;
          if (articlesEl) articlesEl.textContent  = kpi.articles;
          if (readingsEl) readingsEl.textContent  = kpi.required_readings;
          if (videosEl)   videosEl.textContent    = kpi.videos;

          // ── Trend arrows (static visual rhythm — real delta unavailable) ─────
          // Use gentle arrows to signal that each metric is "live"
          const setTrend = (id, arrow, label) => {
            const el = document.getElementById(id);
            if (el) el.textContent = arrow;
          };
          setTrend('kpi-users-trend',    '▲');
          setTrend('kpi-articles-trend', '▲');
          setTrend('kpi-readings-trend', '▼');  // readings pending = actionable alert
          setTrend('kpi-videos-trend',   '▲');

          // ── Bottom micro-progress bars (show relative fill vs assumed max) ───
          // Normalise against known totals to give a sense of scale
          const maxRef = Math.max(kpi.users || 1, 1);
          const setBar = (id, value, max) => {
            const el = document.getElementById(id);
            if (el) el.style.width = Math.min(100, Math.round((value / max) * 100)) + '%';
          };
          setBar('kpi-users-bar',    kpi.users,              maxRef);
          setBar('kpi-articles-bar', kpi.articles,           Math.max(kpi.articles, 50));
          setBar('kpi-readings-bar', kpi.required_readings,  Math.max(kpi.required_readings, 20));
          setBar('kpi-videos-bar',   kpi.videos,             Math.max(kpi.videos, 20));

        } catch (error) {
          console.error('Error fetching KPIs:', error);
        }
      }


async function fetchAndRenderUsers(token, managerId = null) {
        const tbody = document.getElementById('admin-users-tbody');
        if (!tbody) return;

        tbody.innerHTML = `
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/2"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-16"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-6 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
          <tr class="animate-pulse">
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/3"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-1/4"></div></td>
            <td class="px-5 py-3"><div class="h-4 bg-gray-200 rounded w-16"></div></td>
            <td class="px-5 py-3 text-center"><div class="h-6 bg-gray-200 rounded w-16 mx-auto"></div></td>
          </tr>
        `;

        try {
          // Block 5: optional group/team-lead filter (manager_id).
          const url = managerId ? `/api/users?manager_id=${encodeURIComponent(managerId)}` : '/api/users';
          const response = await fetch(url, {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch users');
          const users = await response.json();

          window.adminUsersData = users; // Cache for edit modal

          const roleLabels = { admin: 'ადმინისტრატორი', content_admin: 'კონტენტ ადმინი', manager: 'მენეჯერი', operator: 'ოპერატორი' };
          tbody.innerHTML = '';
          users.forEach(user => {
            const isSelf = window.currentUser && user.id === window.currentUser.id;
            const statusBtn = isSelf
              ? '<span class="text-xs text-gray-400">თქვენ</span>'
              : user.is_active
                ? `<button onclick="toggleUserStatus(${user.id}, false)" class="rounded-full bg-green-100 px-3 py-1 text-xs font-bold text-green-700 transition-colors hover:bg-red-100 hover:text-[#E30613]">აქტიური</button>`
                : `<button onclick="toggleUserStatus(${user.id}, true)" class="rounded-full bg-gray-200 px-3 py-1 text-xs font-bold text-gray-500 transition-colors hover:bg-green-100 hover:text-green-700">გათიშული</button>`;

            const editBtn = isSelf ? '' : `<button onclick="openUserEditModal(${user.id})" class="ml-3 text-gray-400 hover:text-blue-500 transition-colors" aria-label="რედაქტირება"><i class="fa-solid fa-pen-to-square"></i></button>`;

            let progressHtml = '<span class="text-gray-400">—</span>';
            if (user.role === 'operator') {
              const readCount = user.read_count || 0;
              const reqCount = user.required_count || 0;
              const pct = user.progress_percentage !== undefined ? user.progress_percentage : (reqCount > 0 ? Math.round((readCount / reqCount) * 100) : 0);
              progressHtml = `
                <div class="flex items-center gap-2" title="${readCount}/${reqCount} წაკითხული">
                  <div class="w-16 bg-gray-200 rounded-full h-1.5 overflow-hidden">
                    <div class="bg-emerald-500 h-1.5 rounded-full" style="width: ${pct}%"></div>
                  </div>
                  <span class="text-xs font-bold text-gray-600">${pct}%</span>
                </div>
              `;
            }

            tbody.insertAdjacentHTML('beforeend', `
              <tr class="transition-colors hover:bg-gray-50 ${user.is_active ? '' : 'opacity-60'}">
                <td class="px-5 py-3 font-medium text-gray-800">${escapeHtml(user.name)}</td>
                <td class="px-5 py-3 text-gray-500">${escapeHtml(user.email)}</td>
                <td class="px-5 py-3 text-gray-500">${escapeHtml(user.department || '—')}</td>
                <td class="px-5 py-3 text-gray-500">${progressHtml}</td>
                <td class="px-5 py-3"><span class="rounded bg-gray-100 px-2 py-0.5 text-xs font-semibold text-gray-600">${escapeHtml(roleLabels[user.role] || user.role)}</span></td>
                <td class="px-5 py-3 text-center">
                  <div class="flex items-center justify-center">
                    ${statusBtn}
                    ${editBtn}
                  </div>
                </td>
              </tr>`);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-3 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

// Block 5: populates #admin-group-filter with team leads on page load.
async function loadGroupLeaders(token) {
        const select = document.getElementById('admin-group-filter');
        if (!select) return;
        try {
          const response = await fetch('/api/admin/group-leaders', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch group leaders');
          const leaders = await response.json();
          const optionsHtml = leaders.map(l => `<option value="${l.id}">${escapeHtml(l.name)}</option>`).join('');
          select.insertAdjacentHTML('beforeend', optionsHtml);
        } catch (error) {
          console.error(error);
        }
      }

// Block 5: re-fetches the user table filtered by the selected group leader.
function onAdminGroupFilterChange(managerId) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        fetchAndRenderUsers(token, managerId || null);
      }

/* ── Role console (system-admin role management) ───────────────────────────
   Pick a role card → see its members → bulk-move several to another role, or
   edit one person via the existing user-edit modal. Reuses GET /api/users and
   POST /api/admin/roles/bulk-reassign. */
const ROLE_CONSOLE_ORDER = ['admin', 'content_admin', 'manager', 'operator'];
const ROLE_CONSOLE_LABELS = { admin: 'სისტემის ადმინი', content_admin: 'კონტენტის ადმინი', manager: 'მენეჯერი', operator: 'ოპერატორი' };
const ROLE_CONSOLE_ICONS = { admin: 'fa-shield-halved', content_admin: 'fa-pen-nib', manager: 'fa-users-gear', operator: 'fa-headset' };

async function renderRoleConsole(token) {
        token = token || localStorage.getItem('magti_token');
        if (!token) return;
        const tbody = document.getElementById('role-console-members');
        try {
          const res = await fetch('/api/users', { headers: { 'Authorization': `Bearer ${token}` } });
          if (!res.ok) throw new Error('Failed to fetch users');
          window.adminUsersData = await res.json(); // shared cache with the RBAC table + edit modal
        } catch (e) {
          console.error(e);
          if (tbody) tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-6 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
          return;
        }
        if (!window._roleConsoleActiveRole) window._roleConsoleActiveRole = 'operator';
        window._roleConsoleSelection = new Set();
        const sa = document.getElementById('role-console-select-all');
        if (sa) sa.checked = false;
        renderRoleConsoleCards();
        renderRoleConsoleMembers();
      }

function roleConsoleCounts() {
        const counts = { admin: 0, content_admin: 0, manager: 0, operator: 0 };
        (window.adminUsersData || []).forEach(u => { if (counts[u.role] !== undefined) counts[u.role]++; });
        return counts;
      }

function renderRoleConsoleCards() {
        const wrap = document.getElementById('role-console-cards');
        if (!wrap) return;
        const counts = roleConsoleCounts();
        wrap.innerHTML = ROLE_CONSOLE_ORDER.map(role => {
          const active = role === window._roleConsoleActiveRole;
          return `
            <button type="button" onclick="selectConsoleRole('${role}')"
              class="flex items-center gap-3 rounded-2xl border p-4 text-left shadow-sm transition-colors ${active ? 'border-[#B91C1C] bg-red-50 ring-1 ring-[#B91C1C]' : 'border-gray-200 bg-white hover:bg-gray-50'}">
              <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${active ? 'bg-[#B91C1C] text-white' : 'bg-gray-100 text-gray-500'}">
                <i class="fa-solid ${ROLE_CONSOLE_ICONS[role]}"></i>
              </span>
              <span class="min-w-0">
                <span class="block truncate text-sm font-semibold text-gray-800">${ROLE_CONSOLE_LABELS[role]}</span>
                <span class="block text-xs text-gray-500">${counts[role]} მომხმარებელი</span>
              </span>
            </button>`;
        }).join('');
      }

function selectConsoleRole(role) {
        window._roleConsoleActiveRole = role;
        window._roleConsoleSelection = new Set();
        const sa = document.getElementById('role-console-select-all');
        if (sa) sa.checked = false;
        renderRoleConsoleCards();
        renderRoleConsoleMembers();
      }

function renderRoleConsoleMembers() {
        const tbody = document.getElementById('role-console-members');
        if (!tbody) return;
        const role = window._roleConsoleActiveRole;

        // Default the move-target away from the role we're viewing, preferring
        // the least-privileged option so a bulk move never silently aims at admin.
        const targetSel = document.getElementById('role-console-target');
        if (targetSel && targetSel.value === role) {
          const alt = ['operator', 'manager', 'content_admin', 'admin'].find(r => r !== role);
          if (alt) targetSel.value = alt;
        }

        const members = (window.adminUsersData || []).filter(u => u.role === role);
        if (!members.length) {
          tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-6 text-center text-gray-400">ამ როლში მომხმარებლები არ არიან.</td></tr>';
          updateRoleConsoleSelectedCount();
          return;
        }
        const meId = window.currentUser ? window.currentUser.id : null;
        tbody.innerHTML = members.map(u => {
          const isSelf = u.id === meId;
          const checkbox = isSelf
            ? '<span class="text-[10px] text-gray-400">თქვენ</span>'
            : `<input type="checkbox" class="role-member-checkbox h-4 w-4 rounded border-gray-300 accent-[#B91C1C]" value="${u.id}" onchange="toggleRoleMember(${u.id}, this.checked)" ${window._roleConsoleSelection.has(u.id) ? 'checked' : ''} />`;
          const editBtn = isSelf ? '' : `<button onclick="openUserEditModal(${u.id})" class="text-gray-400 hover:text-blue-500 transition-colors" aria-label="რედაქტირება"><i class="fa-solid fa-pen-to-square"></i></button>`;
          return `
            <tr class="transition-colors hover:bg-gray-50 ${u.is_active ? '' : 'opacity-60'}">
              <td class="px-4 py-3">${checkbox}</td>
              <td class="px-5 py-3 font-medium text-gray-800">${escapeHtml(u.name)}</td>
              <td class="px-5 py-3 text-gray-500">${escapeHtml(u.email)}</td>
              <td class="px-5 py-3 text-gray-500">${escapeHtml(u.department || '—')}</td>
              <td class="px-5 py-3 text-center">${editBtn}</td>
            </tr>`;
        }).join('');
        updateRoleConsoleSelectedCount();
      }

function toggleRoleMember(id, checked) {
        if (!window._roleConsoleSelection) window._roleConsoleSelection = new Set();
        if (checked) window._roleConsoleSelection.add(id); else window._roleConsoleSelection.delete(id);
        updateRoleConsoleSelectedCount();
      }

function toggleAllRoleMembers(checked) {
        if (!window._roleConsoleSelection) window._roleConsoleSelection = new Set();
        document.querySelectorAll('.role-member-checkbox').forEach(cb => {
          cb.checked = checked;
          const id = parseInt(cb.value, 10);
          if (checked) window._roleConsoleSelection.add(id); else window._roleConsoleSelection.delete(id);
        });
        updateRoleConsoleSelectedCount();
      }

function updateRoleConsoleSelectedCount() {
        const el = document.getElementById('role-console-selected-count');
        const n = window._roleConsoleSelection ? window._roleConsoleSelection.size : 0;
        if (el) el.textContent = `${n} მონიშნული`;
      }

async function bulkReassignRole() {
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        const ids = window._roleConsoleSelection ? [...window._roleConsoleSelection] : [];
        if (!ids.length) {
          showToast('ვერცერთი მომხმარებელი არ არის მონიშნული', '', { variant: 'error' });
          return;
        }
        const targetSel = document.getElementById('role-console-target');
        const newRole = targetSel ? targetSel.value : null;
        if (!newRole) return;
        const label = ROLE_CONSOLE_LABELS[newRole] || newRole;
        if (!confirm(`${ids.length} მომხმარებლის როლი შეიცვლება: „${label}“. უფლებები განულდება ახალი როლის ნაგულისხმევ ნაკრებზე. გავაგრძელო?`)) return;
        try {
          const res = await fetch('/api/admin/roles/bulk-reassign', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ user_ids: ids, new_role: newRole })
          });
          if (!res.ok) {
            let detail = 'ოპერაცია ვერ შესრულდა';
            try { const j = await res.json(); detail = j.detail || detail; } catch (_) {}
            throw new Error(detail);
          }
          const data = await res.json();
          showToast('როლები განახლდა', `შეიცვალა: ${data.changed} · გამოტოვებული: ${data.skipped}`, { variant: 'success' });
          await renderRoleConsole(token);
          if (typeof fetchKPIs === 'function') fetchKPIs(token);
        } catch (e) {
          console.error(e);
          showToast('როლების შეცვლა ვერ მოხერხდა', e.message, { variant: 'error' });
        }
      }

async function fetchAndRenderAuditLogs(token) {
        const tbody = document.getElementById('admin-audit-tbody');
        if (!tbody) return;
        try {
          const response = await fetch('/api/audit-logs', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch audit logs');
          const logs = await response.json();

          tbody.innerHTML = '';
          if (logs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="px-5 py-3 text-center text-gray-500">ლოგები არ მოიძებნა</td></tr>';
            return;
          }

          logs.forEach(log => {
            const date = new Date(log.timestamp).toLocaleString('ka-GE');
            const tr = `
              <tr class="transition-colors hover:bg-gray-50">
                <td class="px-5 py-3 text-gray-500">#${log.id}</td>
                <td class="px-5 py-3 font-medium text-gray-800">${log.admin_id}</td>
                <td class="px-5 py-3"><span class="rounded bg-gray-100 px-2 py-0.5 text-[11px] font-bold text-gray-600">${escapeHtml(log.action)}</span></td>
                <td class="px-5 py-3 text-gray-500">${escapeHtml(log.item_type)}</td>
                <td class="px-5 py-3 text-gray-500">#${log.item_id}</td>
                <td class="px-5 py-3 text-gray-500">${date}</td>
              </tr>
            `;
            tbody.insertAdjacentHTML('beforeend', tr);
          });
        } catch (error) {
          console.error(error);
          tbody.innerHTML = '<tr><td colspan="6" class="px-5 py-3 text-center text-red-500">მონაცემების ჩატვირთვა ვერ მოხერხდა.</td></tr>';
        }
      }

async function fetchAndRenderCategories(token) {
        try {
          const response = await fetch('/api/categories', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to fetch categories');
          const categories = await response.json();

          // Options mapping
          const optionsHtmlId = '<option value="" disabled selected>აირჩიეთ კატეგორია</option>' + categories.map(c => `<option value="${c.id}">${c.name}</option>`).join('');
          const optionsHtmlName = '<option value="" disabled selected>აირჩიეთ კატეგორია</option>' + categories.map(c => `<option value="${c.name}">${c.name}</option>`).join('');

          const articleSelect = document.getElementById('article-category');
          if (articleSelect && articleSelect.tagName === 'SELECT') {
            articleSelect.innerHTML = optionsHtmlId;
          }

          const videoSelect = document.getElementById('video-category');
          if (videoSelect && videoSelect.tagName === 'SELECT') {
            // Video DB schema uses string for category
            videoSelect.innerHTML = optionsHtmlName;
          }

        } catch (error) {
          console.error(error);
        }
      }

// Item 23: Track which parent categories are currently expanded so a re-render
// (after edit/delete) preserves the user's open/closed state. Scoped to the
// admin categories panel only.
window._adminCategoryExpanded = window._adminCategoryExpanded || new Set();

function toggleAdminCategoryExpand(parentId) {
  const set = window._adminCategoryExpanded;
  if (set.has(parentId)) set.delete(parentId); else set.add(parentId);
  // In-place DOM toggle — no fetch, no full repaint.
  document.querySelectorAll(`tr[data-parent-of="${parentId}"]`).forEach(tr => {
    tr.classList.toggle('hidden');
  });
  const chev = document.getElementById(`cat-chev-${parentId}`);
  if (chev) chev.classList.toggle('rotate-90');
}

async function fetchAndRenderCategoriesAdmin(token) {
        const tbody = document.getElementById('admin-categories-tbody');
        if (!tbody) return;
        tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-4 text-center text-gray-400">იტვირთება...</td></tr>';
        try {
          const res = await fetch('/api/categories', { headers: { Authorization: 'Bearer ' + token } });
          if (!res.ok) throw new Error('კატეგორიების ჩამოტვირთვა ვერ მოხერხდა');
          const cats = await res.json();
          // Build name map for parent display
          const byId = Object.fromEntries(cats.map(c => [c.id, c]));
          // Refresh the parent dropdown in the create form
          const parentSel = document.getElementById('cat-parent');
          if (parentSel) {
            parentSel.innerHTML = '<option value="">— ძირითადი კატეგორია —</option>' +
              cats.filter(c => !c.parent_id).map(c => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
          }
          if (cats.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4" class="px-5 py-6 text-center text-gray-400">კატეგორიები არ არის</td></tr>';
            return;
          }
          // Item 23: Render parent → children as a single in-place tree.
          // Parent rows expose a chevron that toggles ALL their children inline,
          // without re-fetching or switching pages. Subcategory rows start
          // hidden unless the parent is in the expanded-set.
          const tops = cats.filter(c => !c.parent_id);
          const subsByParent = {};
          cats.filter(c => c.parent_id).forEach(c => {
            (subsByParent[c.parent_id] = subsByParent[c.parent_id] || []).push(c);
          });

          const escSingleQuote = (s) => escapeHtml(s).replace(/'/g, "&#39;");

          const html = tops.map(parent => {
            const kids = subsByParent[parent.id] || [];
            const isOpen = window._adminCategoryExpanded.has(parent.id);
            const chevClass = isOpen ? 'fa-solid fa-chevron-right rotate-90 transition-transform' : 'fa-solid fa-chevron-right transition-transform';
            const childCountBadge = kids.length
              ? `<span class="ml-2 inline-flex items-center rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-semibold text-blue-600">${kids.length} ქვე-კატ.</span>`
              : '';

            const parentRow = `
              <tr class="hover:bg-gray-50">
                <td class="px-5 py-3">
                  <button onclick="toggleAdminCategoryExpand(${parent.id})"
                          class="mr-2 inline-flex h-6 w-6 items-center justify-center rounded-md text-gray-400 hover:bg-gray-100 hover:text-gray-700 ${kids.length ? '' : 'opacity-30 cursor-default'}"
                          ${kids.length ? '' : 'disabled'}
                          aria-label="ქვე-კატეგორიების ნახვა">
                    <i id="cat-chev-${parent.id}" class="${chevClass} text-[11px]"></i>
                  </button>
                  <span class="font-semibold text-gray-800">${escapeHtml(parent.name)}</span>
                  ${childCountBadge}
                </td>
                <td class="px-5 py-3 text-gray-500">#${parent.id}</td>
                <td class="px-5 py-3 text-gray-400">—</td>
                <td class="px-5 py-3 text-center">
                  <button onclick="editCategory(${parent.id}, '${escSingleQuote(parent.name)}', null, '${escSingleQuote(parent.slug || '')}', '${escSingleQuote(parent.icon || '')}')"
                    class="rounded-lg border border-gray-300 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50">რედაქტ.</button>
                  <button onclick="deleteCategory(${parent.id})"
                    class="ml-1 rounded-lg border border-red-200 px-3 py-1 text-xs font-semibold text-red-600 hover:bg-red-50">წაშლა</button>
                </td>
              </tr>`;

            const childRows = kids.map(sub => `
              <tr class="bg-gray-50/40 hover:bg-gray-50 ${isOpen ? '' : 'hidden'}" data-parent-of="${parent.id}">
                <td class="px-5 py-2 pl-12">
                  <span class="mr-2 text-gray-300">└─</span>
                  <span class="text-gray-800">${escapeHtml(sub.name)}</span>
                  <span class="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600">ქვე-კატეგორია</span>
                </td>
                <td class="px-5 py-2 text-gray-500">#${sub.id}</td>
                <td class="px-5 py-2 text-gray-500">${escapeHtml(parent.name)}</td>
                <td class="px-5 py-2 text-center">
                  <button onclick="editCategory(${sub.id}, '${escSingleQuote(sub.name)}', ${parent.id}, '${escSingleQuote(sub.slug || '')}', '${escSingleQuote(sub.icon || '')}')"
                    class="rounded-lg border border-gray-300 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50">რედაქტ.</button>
                  <button onclick="deleteCategory(${sub.id})"
                    class="ml-1 rounded-lg border border-red-200 px-3 py-1 text-xs font-semibold text-red-600 hover:bg-red-50">წაშლა</button>
                </td>
              </tr>`).join('');

            return parentRow + childRows;
          }).join('');

          // Orphan rows (parent missing) — always shown at the end.
          const orphans = cats.filter(c => c.parent_id && !byId[c.parent_id]);
          const orphanHtml = orphans.map(o => `
            <tr class="hover:bg-gray-50">
              <td class="px-5 py-3"><span class="mr-2 text-amber-500">⚠</span><span class="font-medium text-gray-800">${escapeHtml(o.name)}</span><span class="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-700">ობოლი</span></td>
              <td class="px-5 py-3 text-gray-500">#${o.id}</td>
              <td class="px-5 py-3 text-gray-500">#${o.parent_id} (აღარ არსებობს)</td>
              <td class="px-5 py-3 text-center">
                <button onclick="editCategory(${o.id}, '${escSingleQuote(o.name)}', ${o.parent_id}, '${escSingleQuote(o.slug || '')}', '${escSingleQuote(o.icon || '')}')" class="rounded-lg border border-gray-300 px-3 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-50">რედაქტ.</button>
                <button onclick="deleteCategory(${o.id})" class="ml-1 rounded-lg border border-red-200 px-3 py-1 text-xs font-semibold text-red-600 hover:bg-red-50">წაშლა</button>
              </td>
            </tr>`).join('');

          tbody.innerHTML = html + orphanHtml;
        } catch (e) {
          tbody.innerHTML = `<tr><td colspan="4" class="px-5 py-4 text-center text-red-500">${escapeHtml(e.message)}</td></tr>`;
        }
      }

const actionMap = {
  "read_article": "სტატიის წაკითხვა",
  "view": "ნახვა",
  "login": "სისტემაში შესვლა",
  "update": "რედაქტირება",
  "create": "შექმნა",
  "delete": "წაშლა",
  "send_message": "შეტყობინების გაგზავნა",
  "broadcast": "გლობალური შეტყობინება",
  "create_user": "მომხმარებლის შექმნა"
};

const typeMap = {
  "article": "სტატია",
  "user": "მომხმარებელი",
  "news": "სიახლე",
  "system": "სისტემა",
  "category": "კატეგორია"
};

async function fetchAndRenderAuditLog(token) {
        const tbody = document.getElementById('admin-audit-tbody');
        if (!tbody) return;
        tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-4 text-center text-gray-400">იტვირთება...</td></tr>';
        try {
          const userId = document.getElementById('audit-filter-user')?.value || '';
          const action = document.getElementById('audit-filter-action')?.value || '';
          const category = document.getElementById('audit-filter-category')?.value || '';

          const params = new URLSearchParams();
          const userName = document.getElementById('log-search-user').value;
          if (userName) params.append('user_name', userName);

          const fp = document.querySelector("#log-date-range")._flatpickr;
          if (fp && fp.selectedDates.length === 2) {
            params.append('start_date', fp.formatDate(fp.selectedDates[0], "Y-m-d"));
            params.append('end_date', fp.formatDate(fp.selectedDates[1], "Y-m-d"));
          }
          if (userId) params.append('user_id', userId);
          if (action) params.append('action', action);
          if (category) params.append('category', category);

          const res = await fetch(`/api/audit-logs?${params.toString()}`, { headers: { Authorization: 'Bearer ' + token } });
          if (!res.ok) throw new Error('ლოგი ვერ ჩაიტვირთა');
          const logs = await res.json();
          if (logs.length === 0) { tbody.innerHTML = '<tr><td colspan="5" class="px-5 py-4 text-center text-gray-400">ლოგი ცარიელია</td></tr>'; return; }
          // Resolve admin names if /api/users is available (admin only).
          let namesById = {};
          try {
            const u = await fetch('/api/users', { headers: { Authorization: 'Bearer ' + token } });
            if (u.ok) {
              const users = await u.json();
              namesById = Object.fromEntries(users.map(x => [x.id, x.name]));
              const userSelect = document.getElementById('audit-filter-user');
              if (userSelect && userSelect.options.length <= 1) {
                users.forEach(x => {
                  const opt = document.createElement('option');
                  opt.value = x.id;
                  opt.textContent = `${x.name} (${x.email})`;
                  userSelect.appendChild(opt);
                });
              }
            }
          } catch { }
          tbody.innerHTML = logs.map(l => {
            const actionLabel = actionMap[(l.action || '').toLowerCase()] || l.action;
            const typeLabel = typeMap[(l.item_type || '').toLowerCase()] || l.item_type;
            const objectLabel = l.item_name ? `${typeLabel}: ${l.item_name}` : typeLabel;
            return `
            <tr class="hover:bg-gray-50">
              <td class="px-5 py-2 whitespace-nowrap text-xs text-gray-500">${new Date(l.timestamp).toLocaleString('ka-GE')}</td>
              <td class="px-5 py-2 text-gray-800">${escapeHtml(l.admin_name || 'უცნობი')}</td>
              <td class="px-5 py-2 text-gray-700">${escapeHtml(actionLabel)}</td>
              <td class="px-5 py-2 text-gray-700">${escapeHtml(objectLabel)}</td>
              <td class="px-5 py-2 text-gray-500">#${l.item_id}</td>
            </tr>`;
          }).join('');
        } catch (e) {
          tbody.innerHTML = `<tr><td colspan="5" class="px-5 py-4 text-center text-red-500">${escapeHtml(e.message)}</td></tr>`;
        }
      }