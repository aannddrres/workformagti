/* ════════════════════════════════════════════════════
   Magti Portal - SPA Routing Engine
   Handles history, navigation, switchMainPage, and deep-linking
   ════════════════════════════════════════════════════ */

function navTo(pageId) {
        const link = document.querySelector(`a[onclick*="${pageId}"]`);
        switchMainPage(pageId, link);
      }

async function navToArticleView(articleId) {
        let article = (window.Store && Store.articles && Store.articles[articleId])
          || (window.adminArticles || {})[articleId];
        if (!article || article.content === undefined) {
          try {
            article = (typeof Store !== 'undefined' && Store.getArticle)
              ? await Store.getArticle(articleId) : article;
          } catch (e) {
            if (typeof showToast === 'function') showToast('შეცდომა', 'სტატია ვერ ჩაიტვირთა', { variant: 'error' });
            return;
          }
        }
        if (!article) return;

        // Remember where to return (don't stack article-view onto itself).
        if (window.currentPageId && window.currentPageId !== 'page-article-view') {
          window._articleViewReturnPage = window.currentPageId;
        }
        if (typeof closeArticleModal === 'function') closeArticleModal();

        document.querySelectorAll('.page-section').forEach(function (el) { el.classList.add('hidden'); });
        const view = document.getElementById('page-article-view');
        if (view) view.classList.remove('hidden');
        window.currentPageId = 'page-article-view';

        const titleEl = document.getElementById('article-view-title');
        if (titleEl) titleEl.textContent = article.title || '';
        const metaEl = document.getElementById('article-view-meta');
        if (metaEl) {
          const date = article.created_at ? new Date(article.created_at).toLocaleDateString('ka-GE') : '';
          metaEl.textContent = `${article.target_department || ''} · ვერსია ${article.version || 1} · ${date}${article.tags ? ' · ' + article.tags : ''}`;
        }
        renderArticleBody(document.getElementById('article-view-content'), article.content || '');

        const att = document.getElementById('article-view-attachment');
        if (att) {
          if (article.attachment_url) {
            att.href = article.attachment_url;
            att.classList.remove('hidden');
            att.classList.add('inline-flex');
          } else {
            att.classList.add('hidden');
            att.classList.remove('inline-flex');
          }
        }

        try { history.replaceState(null, '', `#/article/${articleId}`); } catch (_) { /* private-mode */ }
        window.scrollTo(0, 0);
      }

function backFromArticleView() {
        const target = window._articleViewReturnPage || 'page-info';
        const link = document.querySelector(`a[onclick*="${target}"]`);
        switchMainPage(target, link);
      }

async function navToCategoryView(slug) {
        await loadTaxonomyData();
        const cat = (window.taxonomyCategories || []).find(c => c.slug === slug || String(c.id) === slug);
        if (!cat) { if (typeof showToast === 'function') showToast('შეცდომა', 'კატეგორია ვერ მოიძებნა', { variant: 'error' }); return; }

        if (window.currentPageId && window.currentPageId !== 'page-category-view') {
          window._categoryViewReturnPage = window.currentPageId;
        }
        window._activeCategory = cat;
        window._categoryProfile = defaultProfileForDept(window.currentUser ? window.currentUser.department : '');

        document.querySelectorAll('.page-section').forEach(el => el.classList.add('hidden'));
        const view = document.getElementById('page-category-view');
        if (view) view.classList.remove('hidden');
        window.currentPageId = 'page-category-view';

        document.getElementById('category-view-title').textContent = cat.name;
        document.getElementById('category-view-icon').innerHTML = `<i aria-hidden="true" class="fa-solid ${cat.icon || 'fa-layer-group'}"></i>`;
        _applyProfilePillState();
        renderCategoryArticles();

        try { history.replaceState(null, '', `#/category/${slug}`); } catch (_) { }
        window.scrollTo(0, 0);
      }

function backFromCategoryView() {
        const target = window._categoryViewReturnPage || 'page-dashboard';
        const link = document.querySelector(`a[onclick*="${target}"]`);
        switchMainPage(target, link);
      }

function switchMainPage(pageId, element) {
        if (pageId === 'page-admin') {
          const role = window.currentUser ? window.currentUser.role : '';
          if (role !== 'admin' && role !== 'content_admin') {
            if (typeof showToast === 'function') {
              showToast('წვდომა უარყოფილია', 'თქვენ არ გაქვთ ამ გვერდის ნახვის უფლება.', { variant: 'error' });
            }
            return;
          }
        }
        if (pageId === 'page-manager') {
          const role = window.currentUser ? window.currentUser.role : '';
          if (role !== 'admin' && role !== 'manager') {
            if (typeof showToast === 'function') {
              showToast('წვდომა უარყოფილია', 'თქვენ არ გაქვთ ამ გვერდის ნახვის უფლება.', { variant: 'error' });
            }
            return;
          }
        }

        const dot = document.getElementById('sidebar-reading-dot');
        if (dot) {
          if (pageId === 'page-reading') {
            dot.classList.remove('bg-[#E30613]');
            dot.classList.add('border-2', 'border-white', 'bg-transparent');
          } else {
            dot.classList.add('bg-[#E30613]');
            dot.classList.remove('border-2', 'border-white', 'bg-transparent');
          }
        }

        document.querySelectorAll('.page-section').forEach(function (el) { el.classList.add('hidden'); });
        var target = document.getElementById(pageId);
        if (target) target.classList.remove('hidden');

        document.querySelectorAll('.sidebar-link').forEach(function (link) {
          link.className = 'sidebar-link relative flex items-center gap-3 rounded-xl px-4 py-3 text-[13px] font-medium text-gray-600 transition-all duration-200 ease-out hover:bg-gray-50/80 hover:text-gray-900 active:scale-[0.98]';
          // Clear any inline active styling from a previous active state.
          link.style.removeProperty('border-left'); link.style.removeProperty('background-color'); link.style.removeProperty('color');
          var icon = link.querySelector('i');
          if (icon) { icon.classList.remove('text-white', 'text-[#E30613]'); icon.classList.add('text-gray-400'); }
          link.querySelector('.active-bar')?.remove();
          
          var badge = link.querySelector('[data-inverted="true"]');
          if (badge) {
              badge.classList.remove('bg-white');
              badge.classList.add('bg-[#E30613]');
              if (badge.dataset.hadText === "true") {
                  badge.classList.remove('text-[#E30613]');
                  badge.classList.add('text-white');
                  delete badge.dataset.hadText;
              }
              delete badge.dataset.inverted;
          }
        });

        if (element) {
          // Refined enterprise active state: brand-red left border + faint red
          // tint + slate text (replaces the old solid bg-[#E30613] block).
          element.className = 'sidebar-link nav-active relative flex items-center gap-3 rounded-xl px-4 py-3 text-[13px] font-semibold transition-all duration-200 ease-out active:scale-[0.98]';
          // Inline active styling — guaranteed to win the cascade (the Tailwind
          // CDN + sidebar rules otherwise suppress the class-based version).
          // setProperty(...,'important') — an existing theme/reset !important rule
          // otherwise overrides even inline styles for links inside #sidebar.
          var _dk = document.body.classList.contains('dark');
          element.style.setProperty('border-left', '4px solid #E30613', 'important');
          element.style.setProperty('background-color', _dk ? 'rgba(69,10,10,0.3)' : 'rgba(254,242,242,0.6)', 'important');
          element.style.setProperty('color', _dk ? '#f4f4f5' : '#0f172a', 'important');
          var icon = element.querySelector('i');
          if (icon) { icon.classList.remove('text-gray-400', 'text-white'); icon.classList.add('text-[#E30613]'); }
        }

        // [Fix] Lazy-load page data when entering certain pages so deep links + sidebar clicks behave the same.
        window.currentPageId = pageId;
        const token = Auth.getToken();
        if (token) {
          if (pageId === 'page-manager' && typeof fetchAndRenderManagerStats === 'function') {
            fetchAndRenderManagerStats(token);
          }
          if (pageId === 'page-dashboard' && typeof renderDashboardCategoryGrid === 'function') {
            renderDashboardCategoryGrid();
          }
        }

        // Item 9: persist active page across browser refreshes via URL hash.
        // We use replaceState (not assignment to location.hash) when the user
        // is the source of the change to avoid flooding browser history with
        // entries from sidebar clicks; the hashchange listener handles back/forward.
        try {
          const desired = '#' + pageId;
          if (window.location.hash !== desired) {
            history.replaceState(null, '', desired);
          }
        } catch (_) { /* private-mode quotas etc. — non-fatal */ }

        // Close sidebar on mobile after clicking a link
        if (window.innerWidth < 768) {
          document.body.classList.remove('sidebar-open');
        }
      }

function switchProfileTab(tabId, element) {
        document.querySelectorAll('.profile-tab-content').forEach(function (el) { el.classList.add('hidden'); });
        var target = document.getElementById(tabId);
        if (target) target.classList.remove('hidden');
        document.querySelectorAll('.profile-tab-btn').forEach(function (btn) { btn.className = 'profile-tab-btn whitespace-nowrap border-b-2 border-transparent pb-3 text-gray-500 transition-colors hover:text-gray-800'; });
        if (element) element.className = 'profile-tab-btn whitespace-nowrap border-b-2 border-[#E30613] pb-3 text-[#E30613]';

        if (tabId === 'profile-tab-5') {
          const token = Auth.getToken();
          if (token) fetchAndRenderSearchHistory(token);
        }
      }

function switchAdmin(page) {
        if (page === 'users' || page === 'audit') {
          const role = window.currentUser ? window.currentUser.role : '';
          if (role !== 'admin') {
            if (typeof showToast === 'function') {
              showToast('წვდომა უარყოფილია', 'ეს სექცია ხელმისაწვდომია მხოლოდ სისტემური ადმინისტრატორისთვის.', { variant: 'error' });
            }
            return;
          }
        }

        document.querySelectorAll('.admin-panel').forEach(function (p) { p.classList.add('hidden'); });
        var target = document.getElementById('admin-' + page);
        if (target) target.classList.remove('hidden');

        var activeClass = 'admin-btn rounded-xl bg-[#E30613] px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-colors';
        var inactiveClass = 'admin-btn rounded-xl border border-gray-300 bg-white px-5 py-2.5 text-sm font-medium text-gray-600 shadow-sm transition-colors hover:bg-gray-50';

        document.getElementById('adminBtn-main').className = page === 'main'
          ? activeClass : inactiveClass;
        document.getElementById('adminBtn-content').className = page === 'content'
          ? activeClass : inactiveClass;
        document.getElementById('adminBtn-users').className = page === 'users'
          ? activeClass : inactiveClass;

        var catBtn = document.getElementById('adminBtn-categories');
        if (catBtn) catBtn.className = page === 'categories' ? activeClass : inactiveClass;

        var auditBtn = document.getElementById('adminBtn-audit');
        if (auditBtn) auditBtn.className = page === 'audit' ? activeClass : inactiveClass;

        var migratedBtn = document.getElementById('adminBtn-migrated');
        if (migratedBtn) migratedBtn.className = page === 'migrated' ? activeClass : inactiveClass;

        if (page === 'main' && !window._chartsInit) { initCharts(); window._chartsInit = true; }

        // [Fix] Lazy-load data for the newly-visible panel.
        const token = Auth.getToken();
        if (!token) return;
        if (page === 'categories' && typeof fetchAndRenderCategoriesAdmin === 'function') fetchAndRenderCategoriesAdmin(token);
        if (page === 'audit' && typeof fetchAndRenderAuditLog === 'function') fetchAndRenderAuditLog(token);
        if (page === 'migrated' && typeof fetchAndRenderMigratedArticles === 'function') fetchAndRenderMigratedArticles(token);
        if (page === 'content' && typeof populateArticleCategorySelect === 'function') populateArticleCategorySelect();
      }

window.addEventListener('hashchange', function () {
        const raw = window.location.hash.substring(1);
        // Full-page article deep-link / back-forward: #/article/{id}
        const artMatch = raw.match(/^\/article\/(\d+)$/);
        if (artMatch) {
          const id = parseInt(artMatch[1], 10);
          if (window.currentPageId === 'page-article-view' && window.activeArticleId === id) return;
          navToArticleView(id);
          return;
        }
        // Category view deep-link / back-forward: #/category/{slug}
        const catMatch = raw.match(/^\/category\/([\w-]+)$/);
        if (catMatch) {
          const slug = catMatch[1];
          if (window.currentPageId === 'page-category-view' && window._activeCategory && window._activeCategory.slug === slug) return;
          navToCategoryView(slug);
          return;
        }
        const pageId = raw;
        if (!pageId || pageId === window.currentPageId) return;
        if (!document.getElementById(pageId)) return;
        const link = document.querySelector(`a[onclick*="${pageId}"]`);
        switchMainPage(pageId, link);
      });


// Export to global window scope for backwards compatibility
window.backFromArticleView = backFromArticleView;
window.backFromCategoryView = backFromCategoryView;
window.navTo = navTo;
window.navToArticleView = navToArticleView;
window.navToCategoryView = navToCategoryView;
window.switchAdmin = switchAdmin;
window.switchMainPage = switchMainPage;
window.switchProfileTab = switchProfileTab;
