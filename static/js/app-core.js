/* ════════════════════════════════════════════════════
   Magti Portal - Core Infrastructure & Event Handling
   Handles auth, state client Store, websocket/SSE connections,
   accessibility tracking, page binding, and bootstrapping.
   ════════════════════════════════════════════════════ */

      /* ════════════════════════════════════════════════════════════════════
         App infrastructure — added during the production-readiness refactor.
         Centralises (1) JWT/auth handling, (2) the API client, and (3) client
         state, so data fetching is robust against race conditions / partial
         loads instead of relying on ad-hoc window.* globals.
         ════════════════════════════════════════════════════════════════════ */

      /** Single source of truth for the JWT. Swapping localStorage for an
       *  httpOnly cookie later is a change in THIS object only — the backend
       *  already issues such a cookie on login. */
      const Auth = {
        KEY: 'magti_token',
        getToken() { return localStorage.getItem(this.KEY); },
        setToken(t) { localStorage.setItem(this.KEY, t); },
        clear() { localStorage.removeItem(this.KEY); },
        _payload() {
          const t = this.getToken();
          if (!t) return null;
          try { return JSON.parse(atob(t.split('.')[1])); } catch (e) { return null; }
        },
        /** Proactive session-timeout check (the server stays authoritative). */
        isExpired() {
          const p = this._payload();
          if (!p) return true;        // missing / malformed token
          if (!p.exp) return false;   // no exp claim — let the server decide
          return Date.now() >= p.exp * 1000;
        },
        redirectToLogin() { this.clear(); window.location.href = 'login.html'; }
      };

      /** One API client for the whole app: injects the bearer token, sends
       *  cookies (so a future cookie-auth switch needs no call-site changes),
       *  and funnels every 401 through a single session-expiry handler. */
      /**
       * Core API client helper function.
       * Sends HTTP requests with authorization headers and parses JSON responses.
       * Handles token extraction and error bubbling.
       * 
       * @param {string} path - The relative API URL.
       * @param {Object} options - Standard fetch configuration options.
       * @returns {Promise<any>} Parsed response data.
       */
      

      /** Central client-side store. Replaces scattered window.* state with one
       *  namespaced object and exposes readiness promises so dependent renders
       *  can AWAIT their data instead of racing it. */
      const Store = {
        favorites: {},   // `${type}_${id}` -> favoriteId
        articles: {},    // id -> article
        videos: {},      // id -> video
        categories: {},  // id -> categoryName
        messages: [],
        _ready: {},
        _resolve: {},
        whenReady(key) {
          if (!this._ready[key]) {
            this._ready[key] = new Promise(res => { this._resolve[key] = res; });
          }
          return this._ready[key];
        },
        markReady(key) {
          this.whenReady(key);
          if (this._resolve[key]) { this._resolve[key](); this._resolve[key] = null; }
        },
        whenFavoritesReady() { return this.whenReady('favorites'); },
        /** Lazy fallbacks: resolve an item even if the user clicked before the
         *  relevant list loaded (there is no single-item API endpoint). */
        async ensureArticles() {
          if (Object.keys(this.articles).length) return this.articles;
          const res = await api('/api/articles');
          if (res.ok) (await res.json()).forEach(a => { this.articles[a.id] = a; });
          return this.articles;
        },
        async getArticle(id) {
          if (this.articles[id] && this.articles[id].content !== undefined) return this.articles[id];
          try {
            const res = await api('/api/articles/' + id);
            if (res.ok) {
              const a = await res.json();
              this.articles[id] = a;
              if (window.adminArticles && window.adminArticles[id]) window.adminArticles[id] = a;
              return a;
            }
          } catch (e) { console.error(e); }
          return null;
        },
        async ensureVideos() {
          if (Object.keys(this.videos).length) return this.videos;
          const res = await api('/api/videos');
          if (res.ok) (await res.json()).forEach(v => { this.videos[v.id] = v; });
          return this.videos;
        },
        async getVideo(id) {
          return this.videos[id] || (await this.ensureVideos())[id] || null;
        }
      };

      /** In-place object clear — keeps the window.* aliases below valid. */
      

      // Backwards-compatible aliases so existing readers keep working while the
      // state lives in one place. These are NEVER reassigned after this point;
      // fetchers mutate the same objects in place via clearObj().
      window.userFavorites = Store.favorites;
      window.kbArticles = Store.articles;
      window.kbVideos = Store.videos;

      /* ── Real-time notifications via Server-Sent Events ──────────────────
         The server pushes a notification the instant new content is published
         for the user's department — no page reload needed. */

      let sseRetryDelay = 1000;
      let sseDisconnectToastShown = false;

      /**
       * Establishes a persistent Server-Sent Events (SSE) stream.
       * Connects to `/api/stream` and listens for live broadcast announcements, news, or articles.
       * Handles automatic reconnection on stream drop.
       */
      

      /** React to a live "new content" push: toast + refresh the affected list. */
      /**
       * Routes incoming SSE live event notifications to appropriate handlers.
       * Displays emergency broadcast banners or standard top-right toast alerts.
       * 
       * @param {string} type - Event type (e.g., 'broadcast', 'news', 'article').
       * @param {Object} data - Event payload.
       */
      

      /** Lightweight toast popup (bottom-right); auto-dismisses after 6s. */
      /**
       * Displays a dynamic toast alert notification in the top-right corner.
       * Auto-dismisses after 8 seconds.
       * 
       * @param {string} title - Main header message.
       * @param {string} subtitle - Secondary body message.
       * @param {Function} onClick - Optional callback when toast is clicked.
       */
      

      document.addEventListener('DOMContentLoaded', async () => {
        // Quill WYSIWYG editor for the article content field; mirrors into the
        // hidden #article-content textarea on submit (submitArticleForm).
        const articleContentEditorEl = document.getElementById('article-content-editor');
        window.articleQuill = articleContentEditorEl ? new Quill('#article-content-editor', {
          theme: 'snow',
          modules: {
            toolbar: {
              container: [
                [{ 'header': [1, 2, 3, false] }],
                ['bold', 'italic', 'underline', 'strike'],
                [{ 'list': 'ordered'}, { 'list': 'bullet' }],
                ['link', 'image'],
                ['clean']
              ],
              handlers: {
                image: imageHandler
              }
            }
          }
        }) : null;

        if (window.articleQuill) {
          window.articleQuill.root.addEventListener('drop', (e) => {
            const files = e.dataTransfer?.files;
            if (files && files.length > 0) {
              e.preventDefault();
              for (const file of files) {
                if (file.type.startsWith('image/')) {
                  uploadInlineImage(file);
                }
              }
            }
          });

          window.articleQuill.root.addEventListener('paste', (e) => {
            const items = e.clipboardData?.items;
            if (items) {
              for (const item of items) {
                if (item.type.startsWith('image/')) {
                  const file = item.getAsFile();
                  if (file) {
                    e.preventDefault();
                    uploadInlineImage(file);
                  }
                }
              }
            }
          });

          window.articleQuill.on('text-change', () => {
            if (typeof window.updateArticlePreview === 'function') {
              window.updateArticlePreview();
            }
          });
        }

        const titleEl = document.getElementById('article-title');
        if (titleEl) {
          titleEl.addEventListener('input', () => {
            if (typeof window.updateArticlePreview === 'function') {
              window.updateArticlePreview();
            }
          });
        }

        // Show the selected attachment's filename next to the upload button.
        const articleUploadFileEl = document.getElementById('article-upload-file');
        if (articleUploadFileEl) {
          articleUploadFileEl.addEventListener('change', () => {
            const nameEl = document.getElementById('file-upload-name');
            if (nameEl) nameEl.textContent = articleUploadFileEl.files[0]?.name || '';
          });
        }

        // 1. Auth Guard: redirect to login if there's no valid (unexpired) token.
        const token = Auth.getToken();
        if (!token || Auth.isExpired()) {
          Auth.redirectToLogin();
          return;
        }

        // Admin category select population
        api('/api/categories').then(res => res.json()).then(cats => {
          const select = document.getElementById('article-category');
          if (select) {
            select.innerHTML = cats.map(c => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
          }
        }).catch(console.error);

        // 2. Validate token server-side by fetching current user
        let currentUser;
        try {
          currentUser = await fetchCurrentUser(token);
          window.currentUser = currentUser;
          updateUserInfo(currentUser);
          applyRBAC(currentUser.role);
        } catch (error) {
          console.error('Failed to fetch user:', error);
          Auth.redirectToLogin();
          return;
        }

        // 3. Admin-area data, gated on the server-verified role.
        //    content_admin manages content + sees aggregate stats; ONLY the
        //    system admin gets user management + per-user progress (those
        //    endpoints 403 for content_admin), so we don't fetch them at all.
        if (currentUser.role === 'admin' || currentUser.role === 'content_admin') {
          const adminPanel = document.getElementById('admin-panel');
          if (adminPanel) adminPanel.classList.remove('hidden');
          fetchAndRenderAdminContent(token);
          fetchAndRenderAdminNews(token);
          fetchKPIs(token);
          fetchAndRenderCategories(token);
          fetchAndRenderPopularSearches(token);
          fetchStaleArticles(token);
        }
        if (currentUser.role === 'admin') {
          fetchAndRenderUserProgress(token);
          fetchAndRenderUsers(token);
          loadGroupLeaders(token); // Block 5: populate the group-filter dropdown
        }

        // Fetch statistics + draw charts ONLY for roles that use them.
        // (Operators would otherwise get a 403 from the admin-only stats endpoint.)
        if (currentUser.role === 'admin' || currentUser.role === 'content_admin') {
          window.dashboardStats = await fetchStatistics(token);
          window.activityStats = await api('/api/statistics/activity').then(res => res.json()).catch(() => []);
          initCharts();
        }

        // Setup Global Search
        setupGlobalSearch(token);

        // Initial render of recently viewed articles
        renderRecentlyViewed();

        // Set default page from hash if valid, or default to Dashboard.
        const initialHash = window.location.hash.substring(1);
        const initialArt = initialHash.match(/^\/article\/(\d+)$/);
        const initialCat = initialHash.match(/^\/category\/([\w-]+)$/);
        if (initialArt) {
          // Deep-link straight to a full-page article; Back falls back to the KB.
          navToArticleView(parseInt(initialArt[1], 10));
        } else if (initialCat) {
          // Deep-link straight to a category view; Back falls back to dashboard.
          switchMainPage('page-dashboard', document.querySelector('a[onclick*="page-dashboard"]'));
          navToCategoryView(initialCat[1]);
        } else {
          let defaultPage = 'page-dashboard';
          if (initialHash && document.getElementById(initialHash)) {
            defaultPage = initialHash;
          }
          const defaultLink = document.querySelector(`a[onclick*="${defaultPage}"]`);
          switchMainPage(defaultPage, defaultLink);
        }

        // Fetch and Render News
        try {
          const newsContainer = document.getElementById('latest-news-container');
          if (newsContainer) {
            const skeletonRow = `
              <li class="animate-pulse flex items-center justify-between rounded-xl bg-white p-4 shadow-sm min-h-[72px] border border-transparent">
                <div class="flex items-center gap-3 w-full">
                  <div class="h-10 w-10 shrink-0 rounded-lg bg-gray-200"></div>
                  <div class="flex-1 space-y-2">
                    <div class="h-4 bg-gray-200 rounded w-3/4"></div>
                    <div class="h-3 bg-gray-200 rounded w-1/4"></div>
                  </div>
                </div>
              </li>
            `;
            newsContainer.innerHTML = skeletonRow.repeat(3);
          }
          const newsItems = await fetchNews(token);
          renderNews(newsItems);
        } catch (error) {
          console.error('Failed to fetch or render news:', error);
          if (error.message.includes('401')) {
            Auth.redirectToLogin();
          }
        }

        // Load favourites FIRST and await it, so the knowledge-base / video /
        // news grids paint their star icons correctly on first render. This
        // removes the race where a list rendered before favourites had loaded.
        fetchAndRenderFavorites(token);

        // Fetch and render remaining dynamic sections
        fetchAndRenderMyReadings(token);
        fetchAndRenderKnowledgeBase(token);
        fetchAndRenderVideos(token);
        fetchAndRenderNewsPage(token);
        fetchNotificationsCount(token);
        fetchAndRenderMessages(token);
        fetchKbCategories(token);
        setupKbSearch(token);
        wireQuickLinks();
        applyStoredSettings();

        // Global Keyboard Shortcuts
        window.addEventListener('keydown', function (event) {
          if ((event.ctrlKey || event.metaKey) && (event.key === 'f' || event.key === 'F')) {
            event.preventDefault();
            const searchInput = document.getElementById('global-search-input');
            if (searchInput) {
              searchInput.focus();
              searchInput.select();
            }
            return;
          }
          const activeEl = document.activeElement;
          const isInput = activeEl && (
            activeEl.tagName === 'INPUT' ||
            activeEl.tagName === 'TEXTAREA' ||
            activeEl.tagName === 'SELECT' ||
            activeEl.isContentEditable
          );

          // [Fix] ESC force-closes the top-most transient layer in priority order:
          // open modal -> open popover -> mobile sidebar drawer. One Escape peels
          // exactly one layer so an operator always knows what it dismisses.
          if (event.key === 'Escape') {
            const openModal = document.querySelector('[id$="-modal"]:not(.hidden).flex');
            if (openModal) {
              event.preventDefault();
              if (openModal.id === 'article-modal' && typeof closeArticleModal === 'function') { closeArticleModal(); return; }
              if (openModal.id === 'reading-content-modal' && typeof closeReadingModal === 'function') { closeReadingModal(); return; }
              if (openModal.id === 'news-detail-modal' && typeof closeNewsDetailModal === 'function') { closeNewsDetailModal(); return; }
              if (openModal.id === 'history-modal' && typeof closeHistoryModal === 'function') { closeHistoryModal(); return; }
              if (openModal.id === 'user-edit-modal' && typeof closeUserEditModal === 'function') { closeUserEditModal(); return; }
              // Generic fallback for any other *-modal that lacks a named closer.
              openModal.classList.add('hidden');
              openModal.classList.remove('flex');
              return;
            }
            // No modal open -> dismiss any open notifications/messages popover.
            const openPopover = document.querySelector('#notifications-popover:not(.hidden), #messages-popover:not(.hidden)');
            if (openPopover) {
              event.preventDefault();
              if (typeof closeNotificationsPopover === 'function') closeNotificationsPopover();
              if (typeof closeMessagesPopover === 'function') closeMessagesPopover();
              return;
            }
            // Still nothing? Collapse the mobile sidebar drawer if it is open.
            if (document.body.classList.contains('sidebar-open')) {
              event.preventDefault();
              document.body.classList.remove('sidebar-open');
              return;
            }
          }

          // [Fix] Trap focus inside open modals on Tab
          if (event.key === 'Tab') {
            const openModal = document.querySelector('[id$="-modal"]:not(.hidden).flex');
            if (openModal) {
              const focusableElements = openModal.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
              if (focusableElements.length > 0) {
                const firstElement = focusableElements[0];
                const lastElement = focusableElements[focusableElements.length - 1];
                if (event.shiftKey) {
                  if (document.activeElement === firstElement || document.activeElement === document.body) { event.preventDefault(); lastElement.focus(); }
                } else {
                  if (document.activeElement === lastElement || document.activeElement === document.body) { event.preventDefault(); firstElement.focus(); }
                }
              } else {
                event.preventDefault();
              }
            }
          }

          if (isInput && !event.altKey) {
            if (event.key === 'Escape') {
              activeEl.blur();
            }
            return;
          }

          if (event.key === '/' || ((event.ctrlKey || event.metaKey) && (event.key === 'k' || event.key === 'K'))) {
            event.preventDefault();
            // '/' prefers the LOCAL Knowledge Base filter when the KB page is open
            // (the operator narrows the list in place); everywhere else, and for
            // Ctrl/Cmd+K, it focuses global search.
            const activePage = document.querySelector('.page-section:not(.hidden)');
            const kbSearch = document.getElementById('kb-search-input');
            const useLocal = event.key === '/' && activePage && activePage.id === 'page-info' && kbSearch && kbSearch.offsetParent !== null;
            const target = useLocal ? kbSearch : document.getElementById('global-search-input');
            if (target) {
              target.focus();
              if (typeof target.select === 'function') target.select();
            }
          } else if (event.altKey && (event.key === 'h' || event.key === 'H')) {
            event.preventDefault();
            navTo('page-dashboard');          // Alt+H -> Dashboard
          } else if (event.altKey && (event.key === 'k' || event.key === 'K')) {
            event.preventDefault();
            navTo('page-info');               // Alt+K -> Knowledge Base
          } else if (event.altKey && (event.key === 't' || event.key === 'T')) {
            event.preventDefault();
            navTo('page-manager');            // Alt+T -> Team Stats
          } else if (event.altKey && (event.key === 'c' || event.key === 'C')) {
            event.preventDefault();
            navTo('page-profile');            // Alt+C -> Profile
          }
        });

        startEventStream();   // open the live (SSE) notification channel
        renderPinnedDock();
      });

      async function uploadInlineImage(file) {
        const formData = new FormData();
        formData.append('file', file);
        
        const token = Auth.getToken();
        try {
          const res = await fetch('/api/upload', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${token}`
            },
            body: formData
          });
          if (!res.ok) throw new Error('ფაილის ატვირთვა ჩავარდა');
          const data = await res.json();
          const url = data.url;
          
          if (window.articleQuill) {
            const range = window.articleQuill.getSelection() || { index: window.articleQuill.getLength() };
            window.articleQuill.insertEmbed(range.index, 'image', url);
            window.articleQuill.setSelection(range.index + 1);
          }
        } catch (err) {
          console.error('Inline image upload failed:', err);
          alert('სურათის ატვირთვა ვერ მოხერხდა: ' + err.message);
        }
      }

      function imageHandler() {
        const input = document.createElement('input');
        input.setAttribute('type', 'file');
        input.setAttribute('accept', 'image/*');
        input.click();
        input.onchange = () => {
          const file = input.files[0];
          if (file) {
            uploadInlineImage(file);
          }
        };
      }

      // Favourite IDs now live in Store.favorites; window.userFavorites is a
      // backwards-compatible alias set up in the infrastructure block above.

      function getArticleFormData() {
        const title = document.getElementById('article-title')?.value.trim() || '';
        const content = window.articleQuill ? window.articleQuill.root.innerHTML : '';
        const category_id = parseInt(document.getElementById('article-category')?.value) || 1;
        const audienceEl = document.getElementById('article-audience-profile');
        const audience_profile = audienceEl ? audienceEl.value : 'all';
        
        const target_departments = [];
        if (document.getElementById('dept-info')?.checked) target_departments.push('Informational');
        if (document.getElementById('dept-tech')?.checked) target_departments.push('Support');
        if (document.getElementById('dept-service')?.checked) target_departments.push('Service Centers');
        
        const is_mandatory = document.getElementById('article-mandatory')?.checked || false;
        const due_date = document.getElementById('article-due-date')?.value || '';
        const status = document.getElementById('article-status')?.value || 'draft';
        const published_at_val = document.getElementById('article-published-at')?.value || '';
        const tags = document.getElementById('article-tags')?.value.trim() || '';
        const visible_to_tech_info = document.getElementById('article-visible-tech-info')?.checked || false;
        const visible_to_service_center = document.getElementById('article-visible-service-center')?.checked || false;
        const attachment_url = document.getElementById('article-attachment-url')?.value || '';

        return {
          title,
          content,
          category_id,
          audience_profile,
          target_departments,
          is_mandatory,
          due_date,
          status,
          published_at_val,
          tags,
          visible_to_tech_info,
          visible_to_service_center,
          attachment_url
        };
      }

      async function performAutosave(currentData, currentStr) {
        const statusEl = document.getElementById('autosave-status');
        if (statusEl) {
          statusEl.textContent = 'Saving...';
          statusEl.style.opacity = '1';
        }

        const token = Auth.getToken();
        if (!token) return;

        const editingId = window.editingArticleId;
        const isNew = !editingId;
        
        let url = isNew ? '/api/articles' : `/api/articles/${editingId}/autosave`;
        let method = isNew ? 'POST' : 'PATCH';

        let payload = {};
        if (isNew) {
          payload = {
            title: currentData.title || 'Untitled Draft',
            content: currentData.content,
            category_id: currentData.category_id,
            audience_profile: currentData.audience_profile,
            target_departments: currentData.target_departments.length ? currentData.target_departments : ['Informational'],
            status: 'draft',
            is_draft: true,
            attachment_url: currentData.attachment_url || null,
            tags: currentData.tags || null,
            visible_to_tech_info: currentData.visible_to_tech_info,
            visible_to_service_center: currentData.visible_to_service_center
          };
          if (currentData.status === 'scheduled' && currentData.published_at_val) {
            payload.published_at = new Date(currentData.published_at_val).toISOString();
          }
        } else {
          payload = {
            title: currentData.title,
            content: currentData.content,
            category_id: currentData.category_id,
            audience_profile: currentData.audience_profile,
            target_departments: currentData.target_departments,
            status: currentData.status,
            is_draft: true,
            attachment_url: currentData.attachment_url || null,
            tags: currentData.tags || null,
            visible_to_tech_info: currentData.visible_to_tech_info,
            visible_to_service_center: currentData.visible_to_service_center
          };
          if (currentData.published_at_val) {
            payload.published_at = new Date(currentData.published_at_val).toISOString();
          }
        }

        try {
          const res = await fetch(url, {
            method: method,
            headers: {
              'Content-Type': 'application/json',
              'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify(payload)
          });
          if (!res.ok) throw new Error('Auto-save request failed');
          const data = await res.json();

          if (isNew) {
            window.editingArticleId = data.id;
            document.getElementById('article-form-title').textContent = 'სტატიის რედაქტირება';
            document.getElementById('cancel-edit-btn').classList.remove('hidden');
          }

          window.lastSavedArticleFormStr = currentStr;

          const now = new Date();
          const timeStr = now.toLocaleTimeString('ka-GE', { hour: '2-digit', minute: '2-digit' });
          if (statusEl) {
            statusEl.textContent = `Draft saved at ${timeStr}`;
          }
        } catch (err) {
          console.error('Autosave error:', err);
          if (statusEl) {
            statusEl.textContent = 'Auto-save failed';
          }
        }
      }

      function initAutosave() {
        if (window.autosaveIntervalId) {
          clearInterval(window.autosaveIntervalId);
        }
        setTimeout(() => {
          window.lastSavedArticleFormStr = JSON.stringify(getArticleFormData());
          window.autosaveIntervalId = setInterval(async () => {
            const panel = document.getElementById('admin-panel');
            if (!panel || panel.classList.contains('hidden')) return;

            const currentData = getArticleFormData();
            const currentStr = JSON.stringify(currentData);
            if (currentStr === window.lastSavedArticleFormStr) return;

            await performAutosave(currentData, currentStr);
          }, 30000);
        }, 100);
      }

      function stopAutosave() {
        if (window.autosaveIntervalId) {
          clearInterval(window.autosaveIntervalId);
          window.autosaveIntervalId = null;
        }
        const statusEl = document.getElementById('autosave-status');
        if (statusEl) statusEl.textContent = '';
      }

      /**
       * Fetches current user info from the API.
       */
      /**
       * Profile retriever.
       * Loads current user information and populates session contexts.
       * 
       * @param {string} token - The OAuth2 Bearer token.
       */
      

      /**
       * Updates UI elements with the user's data.
       */
      

      /**
       * Hides elements based on required roles.
       */
      /**
       * Role-Based Access Control (RBAC) UI filter.
       * Dynamically shows or hides elements (e.g., Admin Panel, Manager Tab) 
       * based on the current user's authenticated role.
       * 
       * @param {string} userRole - The role of the user (admin, content_admin, manager, operator).
       */
      

      // Global cache for news items so they can be read in a modal by ID
      window.cachedNewsItems = {};

      /**
       * Fetches news from the API using the provided JWT token.
       */
      /**
       * Fetches general announcements for the news feed container.
       * Filters by department on the backend automatically.
       * 
       * @param {string} token - OAuth2 token.
       */
      

      /**
       * Renders news items into the dashboard's "Latest News" section.
       */
      // Unified date formatter — DD/MM/YYYY (ka-GE) used across every rendering
      // template (Mandatory Readings, Recently Added, …). Exposed on window so
      // the external static/frontend_api.js sheet can share the exact format.
      const formatDate = (d) => new Date(d).toLocaleDateString('ka-GE', { year: 'numeric', month: '2-digit', day: '2-digit' });
      window.formatDate = formatDate;

      

      /**
       * Fetches compliance statistics from the API for the admin dashboard.
       */
      

      /**
       * Setup Global Search with Debounce
       */
      /**
       * Initializes keyup listener for global search query input.
       * Employs search cache to limit database query load.
       * 
       * @param {string} token - OAuth2 token.
       */
      

      /**
       * Session guard: a 401 mid-session means the JWT expired —
       * send the user back to login instead of failing silently.
       */
      

      /**
       * Render grouped search results (articles / news / videos) in the dropdown.
       * [P0-4] Wider, word-boundary-clamped snippets + per-row TYPE badge so operators can
       *         distinguish procedure vs. news vs. video at a glance.
       * [P0-2] Roving-tabindex keyboard nav (Arrow / Enter / Escape) without breaking typing —
       *         we only intercept those specific keys on the input.
       */
      

      // Store readings to filter without re-fetching
      window.myReadings = [];

      /**
       * Fetch and render mandatory readings for the reading page.
       */
      /**
       * Compliance Reading list engine.
       * Fetches user's assigned readings, parses status fields ('read', 'unread', 'overdue'),
       * and renders the checklist cards with due dates and quick-read modal hooks.
       * 
       * @param {string} token - OAuth2 token.
       */
      

      /**
       * Switch active tab and re-render the list
       */
      

      /**
       * Render the list of readings based on the active filter.
       */
      

      /**
       * Open the reading modal and populate it with content.
       * [P0-3] Renders HTML when content contains tags (parity with the article modal) instead of
       *        dumping policy text as a single plain string. Compliance content needs structure.
       */
      

      

      /**
       * [Fix] Type-aware dispatcher for mandatory-reading items.
       *
       *   /api/compliance/my-readings returns only a short description in `item_content` —
       *   that's why opening some articles previously showed a single sentence instead of
       *   the full procedure. We now route each reading item to the matching reader so
       *   articles open in the full article modal (TOC, scripts, related, attachments,
       *   staleness banner, notes, print) and news items open in the news modal.
       *
       *   item_type values handled: 'article', 'news', 'video', and a textual fallback.
       *   Returns the DOM id of the modal that was opened (used by openAndMarkRead to
       *   attach a "I read and understood" confirm button in the right place).
       */
      

      /**
       * [P0-3] Append the compliance "I read and understood" confirm button to whichever
       *        modal was opened. Centralised so all reader types get identical behaviour.
       */
      

      /**
       * [P0-3] Open the matching modal and surface an explicit "I read and understood"
       *        confirm button instead of marking the item read on mere open (which
       *        counted misclicks as compliance).
       */
      

      /**
       * Fetch and render manager team stats
       */
      /**
       * Departmental Compliance Monitor (Manager View).
       * Retrieves compliance completion rates for all operators under the manager's department
       * and lists them in a high-performing leaderboard UI.
       * 
       * @param {string} token - OAuth2 token.
       */
      

      /**
       * Fetch notifications count (Unread mandatory readings)
       */
      

      /**
       * Mark a mandatory reading as read.
       */
      

      /**
       * Fetch and render user's favorites
       */
      

      /**
       * Toggle a favorite item
       */
      /**
       * Bookmarks management helper.
       * Toggles the favorite/starred status of articles, news, or videos.
       * Updates backend db records and switches star icons in the UI.
       * 
       * @param {string} itemType - Type of item ('article', 'news', 'video').
       * @param {number} itemId - Database ID of the item.
       * @param {HTMLElement} btnElement - Triggering button element.
       */
      

      /**
       * Remove favorite by ID
       */
      

      /**
       * Sync star icons with current user favorites state
       */
      

      /**
       * Admin CMS: Fetch and render user reading progress
       */
      

      

      /**
       * Admin CMS: render the progress table, honouring the department filter.
       */
      

      /**
       * Admin CMS: Fetch and render popular searches
       */
      

      /**
       * Admin Dashboard: Fetch articles not verified in 180+ days and render
       * them in the Stale Content Alert panel with one-click verify actions.
       *
       * @param {string} [token] - Bearer token (if omitted, reads from Auth).
       */
      

      /**
       * Verify a stale article inline from the admin dashboard panel.
       * @param {number} articleId - The article ID to verify.
       * @param {HTMLButtonElement} btn - The clicked button for visual feedback.
       */
      

      /**
       * Admin CMS: Fetch and render content management table
       */
      

      /**
       * Admin CMS: Toggles due date field requirement
       */
      

      

      /**
       * Uploads a file for an article attachment
       */
      

      /**
       * Admin CMS: Submits new article and handles secondary reading assignment
       */
      

      /**
       * Admin CMS: Enter edit mode — populate the article form from the
       * cached /api/articles data and switch submit to PUT.
       */
      /**
       * Populate the admin article-form <select id="article-category"> from
       * /api/categories. Prefers the 10 taxonomy categories (those with a slug).
       * Optionally pre-selects `selectedId`.
       */
      

      

      /**
       * Admin CMS: Leave edit mode and restore the form to "create" state.
       */
      

      

      /**
       * Admin CMS: Cancel button — discard edits and reset the form.
       */
      

      /**
       * Admin CMS: Delete an article
       */
      

      /* ── News Details Modal & Admin Forms Submission Handlers ── */

      

      

      

      

      

      

      /**
       * [Fix G10] Centralised required-reading upsert for content forms.
       *
       * Computes the diff between (currentRR | none) and (wantsMandatory ?) and
       * issues the corresponding POST / PUT / DELETE against the existing
       * /api/compliance/required-readings endpoints. Idempotent — calling twice
       * with the same inputs is a no-op.
       *
       * Used by submitArticleForm, submitNewsForm, and submitVideoForm so that
       * the "სავალდებულოდ გასაცნობი" checkbox works identically on create AND edit.
       */
      

      

      

      

      // [Fix G9] Handle the news attachment upload (mirrors handleArticleFileUpload).
      
      
      

      // [Fix G9] News revision history viewer — mirrors viewArticleHistory.
      
      

      

      

      

      

      

      /**
       * Admin CMS: Handle Export CSV Download
       */
      /**
       * CSV Exporter trigger (System Administrator only).
       * Streams reading status database tables into a CSV attachment download.
       */
      

      /**
       * Fetch and render knowledge base articles
       */
      window.kbSkip = 0;
      window.kbLimit = 500; // გაზრდილი ლიმიტი, რათა დაჯგუფება სრულყოფილად მოხდეს
      window.kbHasMore = true;
      window.kbIsLoading = false;
      window.kbObserver = null;

      

      

      

      /**
       * Fetch and render video instructions
       */
      

      /**
       * Fetch and render full news page
       */
      /**
       * Fetch and render full news page
       */
      

      /**
       * Render news items list to the container
       */
      

      // State for favorites toggle
      let newsOnlyStarred = false;

      /**
       * Toggle the starred-only filter state and update the button styling
       */
      

      /**
       * Core filter and sort function triggered by search input, department filter, sorting, or favorites toggle
       */
      

      /* ════════════════════════════════════════════════════
         Navigation, notifications, modal, messages, settings,
         admin RBAC & archive — interactive-element handlers
         ════════════════════════════════════════════════════ */

      /** Navigate to a main page, keeping the sidebar highlight in sync. */
      /**
       * Single Page Application (SPA) navigation handler.
       * Hides all other pages, shows the selected main section, and fires smooth
       * tab transitions. Resets scroll position.
       * 
       * @param {string} pageId - Target page container ID.
       */
      

      /** Item 24: expand/collapse the Admin sub-menu in the sidebar.
       *  Sub-links inside obey the same data-required-role gates as everything
       *  else (handled centrally by applyRBAC), so a content_admin sees the
       *  Content + Categories links but never Users or Audit Logs. */
      

      /** Hamburger: collapse/expand the sidebar. */
      

      /** Logout: clear the JWT and return to the login page. */
      

      /** Quick links run a global search for their topic. */
      

      /** [P1-6] Single source of truth for the bottom "quick links" / "recommended" grids.
       *  Mounts the <template id="quick-links-tpl"> into every [data-quick-links] host on the
       *  page exactly once (idempotent via data-mounted), and wires each button to quickSearch().
       *  Also re-wires the sidebar dock buttons (unchanged behaviour). */
      const QUICK_LINKS = [
        { label: 'როუმინგი', icon: 'fa-globe' },
        { label: 'პორტირება', icon: 'fa-arrow-right-arrow-left' },
        { label: 'ინტერნეტი', icon: 'fa-wifi' },
        { label: 'IPTV', icon: 'fa-tv' },
        { label: 'MyMagti', icon: 'fa-mobile-screen' },
      ];

      

      /** Back-compat alias — callers (1118) still invoke wireQuickLinks(). */
      

      /** Build the article reader modal once, on demand.
       *  [P0-1] Scripts panel is now at the TOP (sticky) — operators get to the call script in 0 scrolls.
       *  [P1-10] Modal widened to 920px max with prose constrained to 72ch for comfortable reading.
       *  [P1-9] Staleness banner is severity-tiered (medium/high/critical) — set by openArticleModal().
       *  Existing element IDs (article-modal, -title, -meta, -content, -staleness-banner, -toc,
       *  -attachment, -note, -scripts, -scripts-list, -related, -related-list, modal-pin-btn,
       *  modal-report-btn, modal-verify-btn) are PRESERVED so every existing fetcher keeps working. */
      

      /** [P0-1] Copy the first script block. Triggered by button OR Ctrl+Shift+C. */
      

      // [P0-1] Global keyboard shortcut: Ctrl+Shift+C copies the first script when the modal is open.
      document.addEventListener('keydown', (e) => {
        if (e.ctrlKey && e.shiftKey && (e.key === 'C' || e.key === 'c')) {
          const modal = document.getElementById('article-modal');
          if (modal && !modal.classList.contains('hidden')) {
            e.preventDefault();
            copyFirstScript();
          }
        }
      });

      /** Open an article (from search results, KB cards, etc.) in the reader modal. */
      /**
       * Shared article-body renderer used by BOTH the reader modal and the
       * full-page standalone view. Parses markdown code blocks + inline code,
       * then sanitizes rich HTML via DOMPurify before injecting it. Single
       * source of truth so the .article-content-optimized layout stays
       * identical in popup and full-screen contexts.
       */
      

      

      

      

      

      

      /** [P1-12] Pinned-dock collision avoidance.
       *  When ANY *-modal is open we dim the dock and disable pointer events so it stops
       *  overlapping the modal's bottom-right action area. Called from every open/close hook. */
      

      

      

      

      

      /** Populate the knowledge-base category filter from /api/categories. */
      

      /** [Fix G12] Render the KB bento cards from the live category list.
       *  Falls back gracefully if no categories exist yet. */
      

      /**
       * Handle bento card category clicks
       */
      

      /**
       * Update Bento category cards borders and backgrounds based on the selected filter.
       * [Fix G12] Works on the dynamic data-bento-cat-id markers so the highlight
       *           tracks whichever categories the admin has actually created.
       */
      

      /** Debounced knowledge-base search wired to /api/search. */
      

      

      /** Client-side filter of the loaded video grid by title. */
      

      /** Item 20: convert a raw YouTube URL into an embeddable /embed/ form.
       *  Returns null if the URL is not recognisably YouTube — caller falls back
       *  to a native <video> element. */
      

      /** Item 20 (hardened): register a view and open the video in the inline
       *  modal — never in a new window. Routes YouTube URLs to /embed/, falls
       *  back to a native <video controls> player for self-hosted /uploads files.
       *  All property access goes through optional-chaining / fallbacks so a
       *  partially-loaded Store can't throw "Cannot read properties of undefined". */
      

      

      /** Envelope: jump straight to the personal messages tab. */
      

      /** Fetch personal messages, render the tab, update the envelope badge. */
      

      /** Render the messages list honouring the read/unread radio filter. */
      

      

      

      /** Settings: apply and persist the chosen font size, background color, and theme style. */
      /**
       * User settings visual applier.
       * Reads theme properties (e.g., custom colors, font sizes) and mutates the DOM
       * or CSS variables accordingly. Persists selection in localStorage.
       */
      

      

      

      

      

      /** Validate that a URL is safe to use as an href/src — blocks javascript:,
       *  data:text/html, and other active-content schemes. Returns the URL on
       *  pass, '#' on fail. */
      

      /**
       * Copies a Quick-Copy script block to the clipboard and shows visual feedback.
       * @param {HTMLButtonElement} btn - The clicked copy button element.
       * @param {number} idx - Index of the script card (for DOM lookup).
       */
      

      

      

      

      

      

      

      

      

      /**
       * Private scratchpad note manager.
       * Saves personal operator annotations and reminders associated with individual
       * Knowledge Base articles back to the SQLite backend.
       */
      

      /**
       * Crowdsourced Knowledge feedback submission.
       * Prompts the operator to describe errors/outdated content on an article and
       * files an issue report to the admin review queue.
       */
      

      

      

      

      

      

      

      

      /** Inline profile editing backed by PUT /api/users/me. */
      

      

      /** Admin: live KPI counters from /api/statistics/kpi. */
      

      /** Admin RBAC: user list with activate/deactivate toggles. */
      

      

      /** Admin: Open User Edit Modal */
      

      /** Admin: Close User Edit Modal */
      

      /** Admin: Submit User Edit Form */
      

      /** Admin: Fetch and render Audit Logs */
      

      /** Admin: Fetch Categories and populate dropdowns & table */
      

      /** Admin: Submit new category */
      

      /** Admin: Delete category */
      

      /** Admin: scroll to and focus the create-article form. */
      

      /** Admin: scroll to and focus the create-news form. */
      

      /** Admin: scroll to and focus the create-video form. */
      

      /** Admin: archive every checked article via PUT (status -> archived). */
      /** Admin: archive every checked article via POST /archive */
      

      /** Admin: unarchive every checked article via POST /unarchive */
      
    

async function api(path, options = {}) {
        const headers = Object.assign({}, options.headers || {});
        const token = Auth.getToken();
        if (token) headers['Authorization'] = `Bearer ${token}`;
        const response = await fetch(path, { ...options, headers, credentials: 'include' });
        if (response.status === 401) {
          handleSessionExpiry(response);
          throw new Error('401 Unauthorized');
        }
        if (!response.ok) {
          let errMsg = `მოთხოვნა ჩავარდა სტატუსით ${response.status}`;
          try {
            const errData = await response.json();
            if (errData && errData.detail) errMsg = errData.detail;
          } catch (e) { }
          showToast('შეცდომა', errMsg);
          throw new Error(errMsg);
        }
        return response;
      }

function clearObj(o) { for (const k in o) if (Object.prototype.hasOwnProperty.call(o, k)) delete o[k]; }

function startEventStream() {
        if (!window.EventSource) return;
        if (window._magtiES) {
          window._magtiES.close();
          window._magtiES = null;
        }

        const statusDot = document.getElementById('sse-status-dot');
        if (statusDot) {
          statusDot.className = 'inline-block h-2 w-2 rounded-full bg-yellow-400 animate-pulse';
          statusDot.title = 'SSE კავშირი: მიერთება...';
        }

        try {
          const es = new EventSource('/api/stream', { withCredentials: true });

          es.onopen = () => {
            sseRetryDelay = 1000; // Reset delay on successful connection
            if (sseDisconnectToastShown) {
              if (typeof showToast === 'function') {
                showToast('Magti AI', 'სერვერთან კავშირი აღდგა 🟢');
              }
              sseDisconnectToastShown = false;
            }
            if (statusDot) {
              statusDot.className = 'inline-block h-2 w-2 rounded-full bg-green-500';
              statusDot.title = 'SSE კავშირი: აქტიური';
            }
          };

          ['news', 'article', 'video', 'broadcast', 'nudge'].forEach(type => {
            es.addEventListener(type, ev => {
              try {
                const parsedData = JSON.parse(ev.data);
                if (type === 'nudge') {
                  // Handle real-time compliance nudge notification
                  if (window.currentUser && parsedData.user_id === window.currentUser.id) {
                    if (typeof showToast === 'function') {
                      showToast('შეტყობინება მენეჯერისგან 🔔', parsedData.message, () => navTo('page-reading'));
                    }
                  }
                } else {
                  handleLiveEvent(type, parsedData);
                }
              }
              catch (e) { console.error('SSE parse error:', e); }
            });
          });

          es.onerror = () => {
            es.close();
            window._magtiES = null;
            if (statusDot) {
              statusDot.className = 'inline-block h-2 w-2 rounded-full bg-red-500';
              statusDot.title = `SSE კავშირი: გათიშული — მცდელობა ${Math.round(sseRetryDelay / 1000)} წამში...`;
            }
            if (!sseDisconnectToastShown) {
              if (typeof showToast === 'function') {
                showToast('კავშირი გაწყდა', 'სერვერთან კავშირი გაწყდა. ხელახალი მცდელობა... 🔴');
              }
              sseDisconnectToastShown = true;
            }
            // Trigger reconnection with exponential backoff
            setTimeout(startEventStream, sseRetryDelay);
            sseRetryDelay = Math.min(sseRetryDelay * 2, 30000); // Limit backoff to 30s
          };

          window._magtiES = es;
        } catch (e) {
          console.error('SSE init failed:', e);
          if (statusDot) {
            statusDot.className = 'inline-block h-2 w-2 rounded-full bg-red-500';
            statusDot.title = 'SSE კავშირი: ჩავარდა';
          }
          setTimeout(startEventStream, sseRetryDelay);
          sseRetryDelay = Math.min(sseRetryDelay * 2, 30000);
        }
      }

function handleLiveEvent(type, data) {
        const token = Auth.getToken();
        if (type === 'news') {
          showToast('ახალი სიახლე', data.title, () => navTo('page-news'));
          fetchNews(token).then(renderNews).catch(() => { });  // dashboard cards + badge
          fetchAndRenderNewsPage(token);                       // the news page list
        } else if (type === 'article') {
          showToast('ახალი სტატია', data.title, () => navTo('page-info'));
          fetchAndRenderKnowledgeBase(token);
        } else if (type === 'video') {
          showToast('ახალი ვიდეო', data.title, () => navTo('page-video'));
          fetchAndRenderVideos(token);
        } else if (type === 'broadcast') {
          showBroadcastBanner(data.message);
        }
      }

function showToast(title, subtitle, optsOrOnClick) {
        // Backwards-compat: third arg may be a function (legacy onClick) OR an
        // options object { variant: 'success'|'error'|'info', onClick }.
        let onClick = null;
        let variant = 'info';
        if (typeof optsOrOnClick === 'function') {
          onClick = optsOrOnClick;
        } else if (optsOrOnClick && typeof optsOrOnClick === 'object') {
          variant = optsOrOnClick.variant || 'info';
          onClick = optsOrOnClick.onClick || null;
        }

        let host = document.getElementById('toast-host');
        if (!host) {
          host = document.createElement('div');
          host.id = 'toast-host';
          host.className = 'fixed bottom-5 right-5 z-[100] flex flex-col gap-2';
          document.body.appendChild(host);
        }
        const variantStyles = {
          success: { box: 'bg-emerald-50 text-emerald-700', icon: 'fa-circle-check' },
          error:   { box: 'bg-red-50 text-[#E30613]',       icon: 'fa-triangle-exclamation' },
          info:    { box: 'bg-red-50 text-[#E30613]',       icon: 'fa-bell' },
        };
        const v = variantStyles[variant] || variantStyles.info;
        const card = document.createElement('div');
        card.className = 'flex w-80 cursor-pointer items-start gap-3 rounded-xl border border-gray-100 bg-white p-4 shadow-lg ring-1 ring-black/5';
        card.innerHTML =
          `<div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${v.box}"><i aria-hidden="true" class="fa-solid ${v.icon}"></i></div>` +
          '<div class="flex-1 overflow-hidden"><p class="toast-title text-[13px] font-bold text-gray-800"></p><p class="toast-sub truncate text-xs text-gray-500"></p></div>';
        // textContent (not innerHTML) so a content title can never inject markup.
        card.querySelector('.toast-title').textContent = title;
        card.querySelector('.toast-sub').textContent = subtitle || '';
        card.onclick = () => { if (onClick) onClick(); card.remove(); };
        host.appendChild(card);
        setTimeout(() => card.remove(), 6000);
      }

function updateUserInfo(user) {
        // Header updates
        document.getElementById('header-user-name').textContent = user.name;
        document.getElementById('header-user-dept').textContent = user.department || user.role;

        // Sidebar updates
        const sidebarUserName = document.getElementById('sidebar-user-name');
        if (sidebarUserName) sidebarUserName.textContent = user.name;

        const sidebarUserDept = document.getElementById('sidebar-user-dept');
        if (sidebarUserDept) {
          sidebarUserDept.textContent = user.department || (user.role === 'admin' || user.role === 'content_admin' ? 'ადმინისტრატორი' : user.role === 'manager' ? 'მენეჯერი' : 'ოპერატორი');
        }

        // Dynamic initials
        const initial = (user.name || '').trim().charAt(0) || 'ნ';
        const headerInit = document.getElementById('header-user-avatar-initial');
        if (headerInit) headerInit.textContent = initial;
        const sidebarInit = document.getElementById('sidebar-user-avatar-initial');
        if (sidebarInit) sidebarInit.textContent = initial;
        // Greeting — avoid the awkward "გამარჯობა, სისტემური!" split when the
        // account is a role/system label rather than a real person's name.
        // If the name looks like a role keyword, fall back to a clean greeting.
        (function setGreeting() {
          const name = (user.name || '').trim();
          const roleKeywords = ['სისტემური', 'ადმინისტრატორი', 'ადმინი', 'ოპერატორი', 'მენეჯერი', 'სისტემა', 'system', 'admin'];
          const looksLikeRole = !name || roleKeywords.some(k => name.toLowerCase().includes(k.toLowerCase()));
          const greetingEl = document.getElementById('dashboard-greeting');
          if (greetingEl) greetingEl.textContent = looksLikeRole ? 'გამარჯობა!' : `გამარჯობა, ${name.split(' ')[0]}!`;
        })();

        // Profile Tab updates
        const profileName = document.getElementById('profile-name');
        if (profileName) profileName.textContent = user.name;

        const profileRole = document.getElementById('profile-role');
        if (profileRole) profileRole.textContent = user.role === 'admin' || user.role === 'content_admin' ? 'ადმინისტრატორი' : user.role === 'manager' ? 'მენეჯერი' : 'ოპერატორი';

        const profileDept = document.getElementById('profile-department');
        if (profileDept) profileDept.textContent = user.department || '—';

        const profilePos = document.getElementById('profile-position');
        if (profilePos) profilePos.textContent = user.position || '—';

        const profileEmail = document.getElementById('profile-email');
        if (profileEmail) profileEmail.textContent = user.email || '—';

        // Dynamic Card Pack Style Applier
        const cardStyle = user.card_style || localStorage.getItem('magti_card_style') || 'corporate';
        document.body.classList.remove('card-theme-corporate', 'card-theme-glass', 'card-theme-bold', 'card-theme-minimal', 'card-theme-colorful');
        document.body.classList.add('card-theme-' + cardStyle);
        const cardStyleSelect = document.getElementById('settings-card-style');
        if (cardStyleSelect) cardStyleSelect.value = cardStyle;
        localStorage.setItem('magti_card_style', cardStyle);
      }

function applyRBAC(userRole) {
        document.querySelectorAll('[data-required-role]').forEach(el => {
          const allowedRoles = el.getAttribute('data-required-role').split(',');
          if (!allowedRoles.includes(userRole)) {
            el.style.display = 'none';
          } else {
            el.style.display = '';
            el.classList.remove('hidden');
          }
        });
      }

function setupGlobalSearch(token) {
        const searchInput = document.getElementById('global-search-input');
        const searchResults = document.getElementById('global-search-results');

        if (!searchInput || !searchResults) return;

        let debounceTimeout;

        async function showZeroStateSearch() {
          let recentViews = [];
          try { recentViews = JSON.parse(localStorage.getItem('magti_recently_viewed')) || []; } catch(e){}
          
          let history = [];
          try {
            const res = await fetch('/api/search/history', { headers: { 'Authorization': `Bearer ${token}` } });
            if (res.ok) history = await res.json();
          } catch(e) {}

          if (recentViews.length === 0 && history.length === 0) {
            searchResults.innerHTML = '<div class="p-3 text-sm text-gray-500 text-center">ძიების ისტორია ცარიელია</div>';
            searchResults.classList.remove('hidden');
            return;
          }

          let html = '';
          if (recentViews.length > 0) {
            html += '<p class="px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-widest text-gray-400">ბოლოს ნანახი სტატიები</p><ul class="flex flex-col gap-1 mb-2">';
            recentViews.slice(0, 5).forEach(item => {
              html += `
                <li class="cursor-pointer rounded-lg p-2 transition-colors hover:bg-gray-50 flex items-center gap-2" onclick="openArticleModalById(${item.id}); document.getElementById('global-search-results').classList.add('hidden'); document.getElementById('global-search-input').blur();">
                  <i class="fa-solid fa-clock-rotate-left text-gray-300 text-xs"></i>
                  <span class="text-sm text-gray-700 truncate">${escapeHtml(item.title)}</span>
                </li>`;
            });
            html += '</ul>';
          }
          if (history.length > 0) {
            html += '<p class="px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-widest text-gray-400">ბოლო ძიებები</p><ul class="flex flex-col gap-1">';
            const uniqueTerms = [...new Set(history.map(h => h.search_term))].slice(0, 5);
            uniqueTerms.forEach(term => {
              html += `
                <li class="cursor-pointer rounded-lg p-2 transition-colors hover:bg-gray-50 flex items-center gap-2" onclick="quickSearch('${escapeHtml(term).replace(/'/g, "\\'")}');">
                  <i class="fa-solid fa-magnifying-glass text-gray-300 text-xs"></i>
                  <span class="text-sm text-gray-700 truncate">${escapeHtml(term)}</span>
                </li>`;
            });
            html += '</ul>';
          }
          searchResults.innerHTML = html;
          searchResults.classList.remove('hidden');
        }

        searchInput.addEventListener('input', (e) => {
          const query = e.target.value.trim();

          clearTimeout(debounceTimeout);

          if (query.length === 0) {
            showZeroStateSearch();
            return;
          }

          debounceTimeout = setTimeout(async () => {
            try {
              // Portal-wide search: articles + news + videos in one call
              const response = await fetch(`/api/search/global?q=${encodeURIComponent(query)}`, {
                headers: { 'Authorization': `Bearer ${localStorage.getItem('magti_token')}` }
              });

              if (handleSessionExpiry(response)) return;
              if (!response.ok) throw new Error('Search failed');

              const results = await response.json();
              renderSearchResults(results, searchResults, query);
            } catch (error) {
              console.error('Error fetching search results:', error);
              searchResults.innerHTML = '<div class="p-3 text-sm text-center text-red-500">ძიებისას დაფიქსირდა შეცდომა.</div>';
              searchResults.classList.remove('hidden');
            }
          }, 300);
        });

        // Close dropdown when clicking outside
        document.addEventListener('click', (e) => {
          if (!searchInput.contains(e.target) && !searchResults.contains(e.target)) {
            searchResults.classList.add('hidden');
          }
        });

        // Re-open if clicking input with text
        searchInput.addEventListener('focus', () => {
          if (searchInput.value.trim().length === 0) {
            showZeroStateSearch();
          } else if (searchResults.innerHTML.trim() !== '') {
            searchResults.classList.remove('hidden');
          }
        });
      }

function handleSessionExpiry(response) {
        if (response.status === 401) {
          localStorage.removeItem('magti_token');
          alert('სესია ამოიწურა — გთხოვთ ხელახლა შეხვიდეთ სისტემაში.');
          window.location.href = 'login.html';
          return true;
        }
        return false;
      }

function filterReadings(filter, element) {
        document.querySelectorAll('.reading-filter-btn').forEach(btn => {
          btn.className = 'reading-filter-btn border-b-2 border-transparent px-4 pb-3 text-sm font-medium text-gray-500 hover:text-gray-800';
        });
        element.className = 'reading-filter-btn border-b-2 border-[#E30613] px-4 pb-3 text-sm font-semibold text-[#E30613]';
        renderFilteredReadings(filter);
      }

function openReadingModal(readingId) {
        const item = window.myReadings.find(r => r.reading.id === readingId);
        if (!item) return;

        const modal = document.getElementById('reading-content-modal');
        document.getElementById('reading-modal-title').textContent = item.item_title;
        const date = new Date(item.reading.due_date).toLocaleDateString('ka-GE');
        document.getElementById('reading-modal-meta').textContent = `ვადა: ${date} | ტიპი: ${item.reading.item_type}`;

        const contentEl = document.getElementById('reading-modal-content');
        if (item.reading.item_type === 'video') {
          // X-8: validate URL scheme (blocks javascript:) AND escape both the
          // href attribute and the visible text — a video URL is content-admin
          // input but still untrusted from this widget's perspective.
          const url = safeUrl(item.item_content);
          contentEl.innerHTML = `<p>ვიდეოს სანახავად გადადით ბმულზე:</p><a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer" class="font-medium text-blue-600 hover:underline">${escapeHtml(item.item_content)}</a>`;
        } else {
          // [P0-3] Mirror the article-modal rendering: if backend served sanitized HTML, render it.
          const body = item.item_content || '';
          if (/<(p|div|h[1-6]|ul|ol|li|table|br|a|strong|b|em)\b/i.test(body)) {
            contentEl.innerHTML = body;
          } else {
            contentEl.textContent = body;
          }
        }

        // [P0-3] Always remove any stale confirm-read button before deciding whether to render it.
        const existingConfirm = modal.querySelector('[data-action="confirm-read"]');
        if (existingConfirm) existingConfirm.remove();

        modal.classList.remove('hidden');
        modal.classList.add('flex');
        if (typeof setDockMode === 'function') setDockMode(); // [P1-12]
      }

function closeReadingModal() {
        const modal = document.getElementById('reading-content-modal');
        if (modal) { modal.classList.add('hidden'); modal.classList.remove('flex'); }
        if (typeof setDockMode === 'function') setDockMode(); // [P1-12]
      }

async function openReadingItem(readingId) {
        const item = window.myReadings.find(r => r.reading.id === readingId);
        if (!item) return null;
        const type = item.reading.item_type;
        const itemId = item.reading.item_id;

        if (type === 'article') {
          await openArticleModalById(itemId);   // already fetches /api/articles/:id and logs the view
          return 'article-modal';
        }

        if (type === 'news') {
          // Use cached news item from /api/news if the news list already loaded; otherwise fetch the single item.
          window.cachedNewsItems = window.cachedNewsItems || {};
          let cached = window.cachedNewsItems[itemId];
          if (!cached || !cached.content) {
            try {
              const res = await api('/api/news/' + itemId);
              if (res.ok) { cached = await res.json(); window.cachedNewsItems[itemId] = cached; }
            } catch (e) { console.error('Failed to fetch news for reading:', e); }
          }
          if (cached) { openNewsDetailModal(itemId); return 'news-detail-modal'; }
          // Fall through to the simple modal if the fetch failed.
        }

        // Video links and plain-text readings keep the simple reading modal.
        openReadingModal(readingId);
        return 'reading-content-modal';
      }

function appendComplianceConfirmButton(modalId, readingId) {
        const modal = document.getElementById(modalId);
        if (!modal) return;
        // For the article modal the body lives inside #article-modal-scroll so the button
        // appears at the natural end of the procedure. The other two modals use the
        // .relative panel directly.
        const target = modalId === 'article-modal'
          ? modal.querySelector('#article-modal-scroll')
          : modal.querySelector('.relative');
        if (!target) return;
        // Strip any prior confirm button for this modal — handles re-opens.
        const existing = target.querySelector('[data-action="confirm-read"]');
        if (existing) existing.remove();

        const btn = document.createElement('button');
        btn.dataset.action = 'confirm-read';
        btn.className = 'mt-6 w-full rounded-xl bg-[#E30613] py-3 text-sm font-bold text-white shadow-sm transition-all hover:bg-red-700 active:scale-[0.98] focus:outline-none focus:ring-2 focus:ring-[#E30613] focus:ring-offset-2';
        btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-check mr-2"></i>წავიკითხე და გავიგე';
        btn.onclick = () => {
          markAsRead(readingId);   // existing API call kept intact
          if (modalId === 'article-modal') closeArticleModal();
          else if (modalId === 'news-detail-modal') closeNewsDetailModal();
          else closeReadingModal();
        };
        target.appendChild(btn);
      }

async function openAndMarkRead(readingId) {
        const openedModalId = await openReadingItem(readingId);
        if (openedModalId) appendComplianceConfirmButton(openedModalId, readingId);
      }

async function markAsRead(readingId) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        try {
          const response = await fetch(`/api/compliance/mark-read/${readingId}`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to mark as read');

          // Re-fetch to update the UI gracefully
          fetchAndRenderMyReadings(token);
          fetchNotificationsCount(token);
        } catch (error) {
          console.error('Error marking as read:', error);
          alert('სტატუსის განახლება ვერ მოხერხდა.');
        }
      }

async function toggleFavorite(itemType, itemId, btnElement) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const key = `${itemType}_${itemId}`;
        const favoriteId = window.userFavorites[key];

        try {
          if (favoriteId) {
            // Remove from DB
            await fetch(`/api/favorites/${favoriteId}`, {
              method: 'DELETE',
              headers: { 'Authorization': `Bearer ${token}` }
            });
            delete window.userFavorites[key];
          } else {
            // Add to DB
            const response = await fetch('/api/favorites', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
              body: JSON.stringify({ item_type: itemType, item_id: itemId })
            });
            const newFav = await response.json();
            window.userFavorites[key] = newFav.id;
          }

          await fetchAndRenderFavorites(token);
          if (itemType === 'news') {
            runNewsFilter();
          }
        } catch (error) {
          console.error(error);
        }
      }

async function removeFavorite(favoriteId) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        try {
          await fetch(`/api/favorites/${favoriteId}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          await fetchAndRenderFavorites(token);
          if (window.allNewsItems) {
            runNewsFilter();
          }
        } catch (error) {
          console.error(error);
        }
      }

function updateStarIcons() {
        document.querySelectorAll('[data-fav-type]').forEach(btn => {
          const type = btn.getAttribute('data-fav-type');
          const id = btn.getAttribute('data-fav-id');

          if (window.userFavorites[`${type}_${id}`]) {
            btn.classList.remove('text-gray-300');
            btn.classList.add('text-yellow-400');
            btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-star"></i>';
          } else {
            btn.classList.remove('text-yellow-400');
            btn.classList.add('text-gray-300');
            btn.innerHTML = '<i aria-hidden="true" class="fa-regular fa-star"></i>';
          }
        });
      }

async function nudgeUser(userId, btn) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const originalHtml = btn.innerHTML;
        btn.disabled = true;
        btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-spinner animate-spin"></i>...';

        try {
          const response = await fetch(`/api/users/${userId}/nudge`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Nudge failed');

          btn.className = 'rounded-full bg-green-50 border border-green-200 px-3 py-1 text-[11px] font-bold text-green-600 transition-colors flex items-center gap-1 mx-auto shadow-sm';
          btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-check"></i> გაგზავნილი';

          if (typeof showToast === 'function') {
            showToast('ნუჯი', 'შეტყობინება წარმატებით გაეგზავნა ოპერატორს! 🔔');
          }

          setTimeout(() => {
            btn.disabled = false;
            btn.className = 'rounded-full bg-blue-50 hover:bg-blue-100 border border-blue-100 hover:border-blue-200 px-3 py-1 text-[11px] font-bold text-blue-600 transition-colors flex items-center gap-1 mx-auto shadow-sm active:scale-95';
            btn.innerHTML = originalHtml;
          }, 4000);
        } catch (error) {
          console.error(error);
          btn.disabled = false;
          btn.innerHTML = originalHtml;
          alert('ნუჯის გაგზავნა ვერ მოხერხდა.');
        }
      }

async function verifyStaleArticle(articleId, btn) {
        const token = Auth.getToken();
        if (!token) return;

        btn.disabled = true;
        btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-spinner animate-spin"></i>';

        try {
          const response = await fetch(`/api/articles/${articleId}/verify`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Verify failed');

          // Remove the row with a smooth transition
          const row = btn.closest('tr');
          if (row) {
            row.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
            row.style.opacity = '0';
            row.style.transform = 'translateX(20px)';
            setTimeout(() => {
              row.remove();
              // Update badge count
              const badge = document.getElementById('stale-count-badge');
              const tbody = document.getElementById('stale-articles-tbody');
              if (badge && tbody) {
                const count = tbody.querySelectorAll('tr').length;
                badge.textContent = count;
                if (count === 0) {
                  document.getElementById('stale-content-panel')?.classList.add('hidden');
                }
              }
            }, 300);
          }

          showToast('სტატია გადამოწმებულია', '', null);
        } catch (error) {
          console.error('Verify stale article error:', error);
          btn.disabled = false;
          btn.innerHTML = '✓ აქტუალურია';
          showToast('შეცდომა', 'გადამოწმება ვერ მოხერხდა', null);
        }
      }

function toggleDueDate() {
        const isMandatory = document.getElementById('article-mandatory').checked;
        const dueDateContainer = document.getElementById('due-date-container');
        const dueDateInput = document.getElementById('article-due-date');

        if (isMandatory) {
          dueDateContainer.classList.remove('hidden');
          dueDateContainer.classList.add('flex');
          dueDateInput.required = true;
        } else {
          dueDateContainer.classList.add('hidden');
          dueDateContainer.classList.remove('flex');
          dueDateInput.required = false;
        }
      }

function toggleScheduledDate() {
        const status = document.getElementById('article-status').value;
        const schedContainer = document.getElementById('scheduled-date-container');
        const schedInput = document.getElementById('article-published-at');

        if (status === 'scheduled') {
          schedContainer.classList.remove('hidden');
          schedInput.required = true;
        } else {
          schedContainer.classList.add('hidden');
          schedInput.required = false;
          schedInput.value = '';
        }
      }

async function handleArticleFileUpload(input) {
        if (!input.files || input.files.length === 0) return;
        const file = input.files[0];
        const formData = new FormData();
        formData.append('file', file);

        const token = Auth.getToken();
        const progressEl = document.getElementById('article-upload-progress');
        progressEl.classList.remove('hidden');

        try {
          const response = await fetch('/api/upload', {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` },
            body: formData
          });
          if (!response.ok) throw new Error('ატვირთვა ვერ მოხერხდა (შესაძლოა ფაილი ზედმეტად დიდია)');

          const data = await response.json();
          document.getElementById('article-attachment-url').value = data.url;

          const attLink = document.getElementById('current-attachment-link');
          attLink.classList.remove('hidden');
          attLink.classList.add('flex');
          attLink.querySelector('a').href = data.url;
          attLink.querySelector('a').textContent = data.filename || 'მიმაგრებული ფაილი';
          window._removeAttachment = false;

          showToast('წარმატება', 'ფაილი აიტვირთა სისტემაში');
        } catch (err) {
          console.error(err);
          alert(err.message);
        } finally {
          progressEl.classList.add('hidden');
          input.value = '';
        }
      }

async function submitArticleForm(event) {
        event.preventDefault();
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const title = document.getElementById('article-title').value;
        // Sync Quill's HTML output into the hidden textarea right before it's read.
        if (window.articleQuill) {
          document.getElementById('article-content').value = window.articleQuill.root.innerHTML;
        }
        const content = document.getElementById('article-content').value;
        const categoryId = parseInt(document.getElementById('article-category').value) || 1;
        const audienceEl = document.getElementById('article-audience-profile');
        const audienceProfile = audienceEl ? audienceEl.value : 'all';
        // Multi-department targeting via 3 toggles; labels map to the English
        // department values stored in target_departments.
        const targetDepartments = [];
        if (document.getElementById('dept-info').checked) targetDepartments.push('Informational');
        if (document.getElementById('dept-tech').checked) targetDepartments.push('Support');
        if (document.getElementById('dept-service').checked) targetDepartments.push('Service Centers');
        if (targetDepartments.length === 0) {
          alert('აირჩიეთ მინიმუმ ერთი დეპარტამენტი');
          return;
        }
        const targetDepartment = targetDepartments[0];
        const isMandatory = document.getElementById('article-mandatory').checked;
        const dueDate = document.getElementById('article-due-date').value;
        const status = document.getElementById('article-status').value;
        const publishedAtVal = document.getElementById('article-published-at').value;
        const tags = document.getElementById('article-tags').value.trim();
        // Block 5: role-based content visibility toggles.
        const visibleToTechInfo = document.getElementById('article-visible-tech-info').checked;
        const visibleToServiceCenter = document.getElementById('article-visible-service-center').checked;
        let publishedAt = null;
        if (status === 'scheduled' && publishedAtVal) {
          publishedAt = new Date(publishedAtVal).toISOString();
        }

        // Edit mode sends PUT to the existing article; create mode POSTs a new one
        const editingId = window.editingArticleId;
        const url = editingId
          ? `/api/articles/${editingId}`
          : '/api/articles';

        let attachmentUrl = document.getElementById('article-attachment-url').value || null;
        if (window._removeAttachment) attachmentUrl = null;

        const payload = { title, content, category_id: categoryId, audience_profile: audienceProfile, target_departments: targetDepartments, status, published_at: publishedAt, tags: tags || null, visible_to_tech_info: visibleToTechInfo, visible_to_service_center: visibleToServiceCenter, is_draft: false };
        if (editingId) {
          // Preserve fields the form doesn't expose, or PUT would null them out
          const cached = (window.adminArticles || {})[editingId] || {};
          if (window._removeAttachment) {
            payload.attachment_url = null;
          } else if (attachmentUrl) {
            payload.attachment_url = attachmentUrl;
          } else {
            payload.attachment_url = cached.attachment_url;
          }
          payload.author_id = cached.author_id;
        } else {
          payload.attachment_url = attachmentUrl;
        }

        try {
          // 1. Primary API call to create or update the Article
          const articleRes = await fetch(url, {
            method: editingId ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify(payload)
          });
          if (!articleRes.ok) throw new Error(editingId ? 'სტატიის განახლება ვერ მოხერხდა' : 'სტატიის შექმნა ვერ მოხერხდა');
          const articleData = await articleRes.json();

          // [Fix G10] Mandatory flag now syncs on BOTH create and edit (was create-only).
          await syncMandatoryFor('article', articleData.id, targetDepartment, isMandatory, dueDate, token);

          showToast(editingId ? 'სტატია განახლდა' : 'სტატია დაემატა', '', { variant: 'success' });
          event.target.reset();
          if (window.articleQuill) window.articleQuill.setText('');
          toggleDueDate();
          exitEditMode();
          fetchAndRenderAdminContent(token); // Refresh the table automatically
        } catch (error) {
          console.error(error);
          showToast('სტატია ვერ შეინახა', error.message, { variant: 'error' });
        }
      }

async function populateArticleCategorySelect(selectedId) {
        const sel = document.getElementById('article-category');
        if (!sel) return;
        try {
          const token = Auth.getToken();
          const res = await fetch('/api/categories', { headers: { 'Authorization': `Bearer ${token}` } });
          if (!res.ok) return;
          const cats = await res.json();
          const taxo = cats.filter(c => c.slug);     // the 10 taxonomy categories
          const list = (taxo.length ? taxo : cats).slice().sort((a, b) => a.name.localeCompare(b.name, 'ka'));
          sel.innerHTML = list.map(c => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
          if (selectedId != null) sel.value = String(selectedId);
        } catch (e) { console.error('category select load failed', e); }
      }

async function editArticle(articleId) {
        let article = (window.adminArticles || {})[articleId];
        if (!article || article.content === undefined) {
          article = await Store.getArticle(articleId);
        }
        if (!article) {
          alert('სტატიის მონაცემები ვერ მოიძებნა. სცადეთ გვერდის განახლება.');
          return;
        }

        window.editingArticleId = articleId;

        document.getElementById('article-title').value = article.title;
        document.getElementById('article-content').value = article.content;
        if (window.articleQuill) window.articleQuill.root.innerHTML = article.content || '';
        await populateArticleCategorySelect(article.category_id);
        const audSel = document.getElementById('article-audience-profile');
        if (audSel) audSel.value = article.audience_profile || 'all';
        // Reverse-map English department values back onto the 3 toggles.
        const editDepts = article.target_departments || [];
        document.getElementById('dept-info').checked = editDepts.includes('Informational');
        document.getElementById('dept-tech').checked = editDepts.includes('Support');
        document.getElementById('dept-service').checked = editDepts.includes('Service Centers');
        document.getElementById('article-tags').value = article.tags || '';
        // Block 5: prefill role-based visibility toggles from the existing article.
        document.getElementById('article-visible-tech-info').checked = article.visible_to_tech_info !== false;
        document.getElementById('article-visible-service-center').checked = !!article.visible_to_service_center;

        const status = article.status || 'published';
        document.getElementById('article-status').value = status;
        const schedInput = document.getElementById('article-published-at');
        if (status === 'scheduled' && article.published_at) {
          const dateObj = new Date(article.published_at);
          const pad = n => n.toString().padStart(2, '0');
          schedInput.value = `${dateObj.getFullYear()}-${pad(dateObj.getMonth() + 1)}-${pad(dateObj.getDate())}T${pad(dateObj.getHours())}:${pad(dateObj.getMinutes())}`;
        } else {
          schedInput.value = '';
        }
        toggleScheduledDate();

        window._removeAttachment = false;
        document.getElementById('article-attachment-url').value = article.attachment_url || '';
        const attLink = document.getElementById('current-attachment-link');
        if (article.attachment_url) {
          attLink.classList.remove('hidden');
          attLink.classList.add('flex');
          attLink.querySelector('a').href = article.attachment_url;
          attLink.querySelector('a').textContent = article.attachment_url.split('/').pop() || 'მიმაგრებული ფაილი';
        } else {
          attLink.classList.add('hidden');
          attLink.classList.remove('flex');
        }

        // [Fix G10] Mandatory section stays VISIBLE during edit. Prefill from existing RR row.
        document.getElementById('mandatory-section').classList.remove('hidden');
        document.getElementById('article-mandatory').checked = false;
        document.getElementById('article-due-date').value = '';
        toggleDueDate();
        const token = Auth.getToken();
        if (token) {
          try {
            const r = await fetch(`/api/compliance/required-readings/by-item/article/${articleId}`, {
              headers: { Authorization: 'Bearer ' + token },
            });
            if (r.ok) {
              const rr = await r.json();
              if (rr && rr.id) {
                document.getElementById('article-mandatory').checked = true;
                if (rr.due_date) {
                  document.getElementById('article-due-date').value = String(rr.due_date).slice(0, 10);
                }
                toggleDueDate();
              }
            }
          } catch { }
        }

        document.getElementById('article-form-title').textContent = 'სტატიის რედაქტირება';
        document.getElementById('cancel-edit-btn').classList.remove('hidden');
        const panel = document.getElementById('admin-panel');
        if (panel) {
          panel.classList.remove('hidden');
          setTimeout(() => panel.classList.remove('translate-x-full'), 10);
        }
        document.getElementById('admin-panel-backdrop')?.classList.remove('hidden');

        // Reset autosave state for editing article
        initAutosave();

        if (typeof window.updateArticlePreview === 'function') {
          window.updateArticlePreview();
        }
        if (typeof window.setPreviewDevice === 'function') {
          window.setPreviewDevice('desktop');
        }
      }

function closeArticleDrawer() {
        const panel = document.getElementById('admin-panel');
        if (panel) {
          panel.classList.add('translate-x-full');
          setTimeout(() => panel.classList.add('hidden'), 300);
        }
        document.getElementById('admin-panel-backdrop')?.classList.add('hidden');

        // Stop autosave
        stopAutosave();
      }
window.closeArticleDrawer = closeArticleDrawer;

function exitEditMode() {
        window.editingArticleId = null;
        window._removeAttachment = false;
        document.getElementById('article-attachment-url').value = '';
        document.getElementById('article-form-title').textContent = 'ახალი სტატიის დამატება';
        document.getElementById('cancel-edit-btn').classList.add('hidden');
        const attLink = document.getElementById('current-attachment-link');
        attLink.classList.add('hidden');
        attLink.classList.remove('flex');
        document.getElementById('mandatory-section').classList.remove('hidden');
        document.getElementById('article-status').value = 'published';
        document.getElementById('article-published-at').value = '';
        document.getElementById('article-tags').value = '';
        toggleScheduledDate();
        closeArticleDrawer();
      }

function removeCurrentAttachment() {
        window._removeAttachment = true;
        document.getElementById('current-attachment-link').classList.add('hidden');
        document.getElementById('current-attachment-link').classList.remove('flex');
      }

function cancelEdit() {
        document.getElementById('create-article-form').reset();
        if (window.articleQuill) window.articleQuill.setText('');
        toggleDueDate();
        exitEditMode();
      }

async function deleteArticle(articleId) {
        if (!confirm('ნამდვილად გსურთ სტატიის წაშლა?')) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        try {
          const response = await fetch('/api/articles/' + articleId, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Deletion failed');
          fetchAndRenderAdminContent(token); // refresh
        } catch (error) {
          console.error(error);
          alert('წაშლა ვერ მოხერხდა');
        }
      }

async function openNewsDetailModal(newsId) {
        // Cache may have been populated with a NewsSummaryResponse (no `content` field)
        // by the global-search click handler. Self-heal: if content is missing, fetch
        // the full record so the modal body never renders empty.
        window.cachedNewsItems = window.cachedNewsItems || {};
        let item = window.cachedNewsItems[newsId];
        if (!item || item.content === undefined || item.content === null) {
          try {
            const res = await api('/api/news/' + newsId);
            if (res.ok) {
              item = await res.json();
              window.cachedNewsItems[newsId] = item;
            }
          } catch (e) {
            console.error('Failed to fetch news detail:', e);
          }
        }
        if (!item) return;

        const modal = document.getElementById('news-detail-modal');
        if (!modal) return;

        document.getElementById('news-modal-title').textContent = item.title;
        document.getElementById('news-modal-dept').textContent = item.target_department === 'All' ? 'ყველა დეპარტამენტი' : item.target_department;

        const date = new Date(item.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
        document.getElementById('news-modal-meta').textContent = `გამოქვეყნდა: ${date}`;

        const contentDiv = document.getElementById('news-modal-content');
        const body = item.content || '';
        if (/<(p|div|h[1-6]|ul|ol|li|table|br|a|strong|b|em|img)\b/i.test(body)) {
          contentDiv.innerHTML = body;
        } else {
          contentDiv.textContent = body;
        }

        // [Fix G9] Surface the attachment link inside the news modal when set.
        const existingAtt = document.getElementById('news-modal-attachment');
        if (existingAtt) existingAtt.remove();
        if (item.attachment_url) {
          const a = document.createElement('a');
          a.id = 'news-modal-attachment';
          a.href = item.attachment_url;
          a.target = '_blank';
          a.rel = 'noopener noreferrer';
          a.className = 'mt-4 inline-flex items-center gap-2 rounded-xl border border-gray-200 bg-gray-50 px-4 py-2.5 text-sm font-medium text-[#E30613] hover:bg-red-50';
          a.innerHTML = '<i aria-hidden="true" class="fa-solid fa-paperclip"></i> თანდართული ფაილი';
          contentDiv.parentNode.appendChild(a);
        }

        modal.classList.remove('hidden');
        modal.classList.add('flex');
        if (typeof setDockMode === 'function') setDockMode(); // [P1-12]
      }

function closeNewsDetailModal() {
        const modal = document.getElementById('news-detail-modal');
        if (modal) {
          modal.classList.add('hidden');
          modal.classList.remove('flex');
        }
        if (typeof setDockMode === 'function') setDockMode(); // [P1-12]
      }

function toggleNewsDueDate() {
        const isMandatory = document.getElementById('news-mandatory').checked;
        const dueDateContainer = document.getElementById('news-due-date-container');
        const dueDateInput = document.getElementById('news-due-date');
        if (isMandatory) {
          dueDateContainer.classList.remove('hidden');
          dueDateContainer.classList.add('flex');
          dueDateInput.required = true;
        } else {
          dueDateContainer.classList.add('hidden');
          dueDateContainer.classList.remove('flex');
          dueDateInput.required = false;
        }
      }

function exitNewsEditMode() {
        window.editingNewsId = null;
        const formTitle = document.getElementById('news-form-title');
        if (formTitle) formTitle.textContent = 'ახალი სიახლის დამატება';
        document.getElementById('news-mandatory-section')?.classList.remove('hidden');
      }

function cancelNewsEdit() {
        document.getElementById('admin-news-panel')?.classList.add('hidden');
        document.getElementById('create-news-form').reset();
        toggleNewsDueDate();
        exitNewsEditMode();
      }

async function submitNewsForm(event) {
        event.preventDefault();
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const title = document.getElementById('news-title').value;
        const content = document.getElementById('news-content').value;
        const targetDepartment = document.getElementById('news-department').value;
        const isMandatory = document.getElementById('news-mandatory').checked;
        const dueDate = document.getElementById('news-due-date').value;
        // [Fix G9] Optional centralised attachment per spec.
        const attachmentUrl = document.getElementById('news-attachment-url')?.value || null;
        // Block 5: role-based content visibility toggles.
        const visibleToTechInfo = document.getElementById('news-visible-tech-info').checked;
        const visibleToServiceCenter = document.getElementById('news-visible-service-center').checked;

        const editingId = window.editingNewsId;
        const url = editingId ? `/api/news/${editingId}` : '/api/news';

        const payload = {
          title, content, target_department: targetDepartment,
          attachment_url: attachmentUrl || null,
          visible_to_tech_info: visibleToTechInfo,
          visible_to_service_center: visibleToServiceCenter,
        };

        try {
          // 1. Primary API call to create or update the News
          const newsRes = await fetch(url, {
            method: editingId ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify(payload)
          });
          if (!newsRes.ok) throw new Error(editingId ? 'სიახლის განახლება ვერ მოხერხდა' : 'სიახლის შექმნა ვერ მოხერხდა');
          const newsData = await newsRes.json();

          // [Fix G10] Mandatory flag now syncs on BOTH create and edit. Logic:
          //   - existing RR + checkbox ON  → PUT (update date/dept)
          //   - existing RR + checkbox OFF → DELETE
          //   - no RR       + checkbox ON  → POST
          await syncMandatoryFor('news', newsData.id, targetDepartment, isMandatory, dueDate, token);

          showToast(editingId ? 'სიახლე განახლდა' : 'სიახლე დაემატა', '', { variant: 'success' });
          event.target.reset();
          toggleNewsDueDate();
          cancelNewsEdit();
          fetchAndRenderAdminNews(token); // Refresh admin list
          fetchNews(token).then(renderNews).catch(() => { }); // Refresh dashboard list
          fetchAndRenderNewsPage(token); // Refresh dedicated news page
        } catch (error) {
          console.error(error);
          alert('დაფიქსირდა შეცდომა: ' + error.message);
        }
      }

async function syncMandatoryFor(itemType, itemId, targetDepartment, wantsMandatory, dueDateStr, token) {
        // Discover existing RR for this item (admin-only endpoint).
        let existing = null;
        try {
          const r = await fetch(`/api/compliance/required-readings/by-item/${itemType}/${itemId}`, {
            headers: { Authorization: 'Bearer ' + token },
          });
          if (r.ok) existing = await r.json();
        } catch { }

        const dueIso = dueDateStr ? new Date(dueDateStr).toISOString() : null;

        if (wantsMandatory && dueIso) {
          const payload = {
            item_type: itemType, item_id: itemId,
            target_department: targetDepartment, due_date: dueIso,
            priority: 'high',
          };
          if (existing && existing.id) {
            const res = await fetch(`/api/compliance/required-readings/${existing.id}`, {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
              body: JSON.stringify(payload),
            });
            if (!res.ok) throw new Error('სავალდებულოდ გასაცნობის განახლება ვერ მოხერხდა');
          } else {
            const res = await fetch('/api/compliance/required-readings', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
              body: JSON.stringify(payload),
            });
            if (!res.ok) throw new Error('სავალდებულოდ გასაცნობის მინიჭება ვერ მოხერხდა');
          }
        } else if (existing && existing.id) {
          // Checkbox was unticked — drop the assignment.
          const res = await fetch(`/api/compliance/required-readings/${existing.id}`, {
            method: 'DELETE',
            headers: { Authorization: 'Bearer ' + token },
          });
          if (!res.ok && res.status !== 204) throw new Error('სავალდებულოდ გასაცნობის წაშლა ვერ მოხერხდა');
        }
      }

function switchContentTab(tabId, element) {
        document.querySelectorAll('.content-tab-panel').forEach(el => el.classList.add('hidden'));
        const target = document.getElementById(`admin-${tabId}-table-container`);
        if (target) target.classList.remove('hidden');

        document.querySelectorAll('.content-tab-btn').forEach(btn => {
          btn.className = 'content-tab-btn border-b-2 border-transparent px-4 pb-3 text-sm font-medium text-gray-500 hover:text-gray-800';
        });
        if (element) {
          element.className = 'content-tab-btn border-b-2 border-[#E30613] px-4 pb-3 text-sm font-semibold text-[#E30613]';
        }
      }

async function editNews(newsId) {
        const item = (window.cachedNewsItems || {})[newsId];
        if (!item) {
          alert('სიახლის მონაცემები ვერ მოიძებნა. სცადეთ გვერდის განახლება.');
          return;
        }

        window.editingNewsId = newsId;

        document.getElementById('news-title').value = item.title;
        document.getElementById('news-content').value = item.content;
        document.getElementById('news-department').value = item.target_department;

        // [Fix G9] Attachment URL prefilled if present.
        const attUrlInput = document.getElementById('news-attachment-url');
        if (attUrlInput) attUrlInput.value = item.attachment_url || '';
        updateNewsAttachmentChip(item.attachment_url);

        // Block 5: prefill role-based visibility toggles from the existing news item.
        document.getElementById('news-visible-tech-info').checked = item.visible_to_tech_info !== false;
        document.getElementById('news-visible-service-center').checked = !!item.visible_to_service_center;

        // [Fix G10] Mandatory section is no longer hidden on edit. Prefill it
        //           from the existing RequiredReading row (if any).
        const mandSection = document.getElementById('news-mandatory-section');
        if (mandSection) mandSection.classList.remove('hidden');
        document.getElementById('news-mandatory').checked = false;
        document.getElementById('news-due-date').value = '';
        toggleNewsDueDate();
        const token = Auth.getToken();
        if (token) {
          try {
            const r = await fetch(`/api/compliance/required-readings/by-item/news/${newsId}`, {
              headers: { Authorization: 'Bearer ' + token },
            });
            if (r.ok) {
              const rr = await r.json();
              if (rr && rr.id) {
                document.getElementById('news-mandatory').checked = true;
                if (rr.due_date) {
                  document.getElementById('news-due-date').value = String(rr.due_date).slice(0, 10);
                }
                toggleNewsDueDate();
              }
            }
          } catch { }
        }

        const formTitle = document.getElementById('news-form-title');
        if (formTitle) formTitle.textContent = 'სიახლის რედაქტირება';

        // Focus and display the news form
        document.getElementById('admin-panel')?.classList.add('hidden');
        document.getElementById('admin-video-panel')?.classList.add('hidden');
        const panel = document.getElementById('admin-news-panel');
        if (panel) {
          panel.classList.remove('hidden');
          panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
        const titleInput = document.getElementById('news-title');
        if (titleInput) titleInput.focus({ preventScroll: true });
      }

async function handleNewsFileUpload(input) {
        const file = input.files?.[0];
        if (!file) return;
        const token = Auth.getToken(); if (!token) return;
        const progress = document.getElementById('news-upload-progress');
        progress?.classList.remove('hidden');
        try {
          const fd = new FormData();
          fd.append('file', file);
          const res = await fetch('/api/upload', {
            method: 'POST',
            headers: { Authorization: 'Bearer ' + token },
            body: fd,
          });
          if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || 'ფაილი ვერ ატვირთა'); }
          const data = await res.json();
          document.getElementById('news-attachment-url').value = data.url;
          updateNewsAttachmentChip(data.url);
        } catch (e) {
          alert(e.message);
        } finally {
          progress?.classList.add('hidden');
          input.value = '';
        }
      }

function updateNewsAttachmentChip(url) {
        const chip = document.getElementById('news-current-attachment');
        if (!chip) return;
        if (url) {
          const link = chip.querySelector('a');
          if (link) { link.href = url; link.textContent = url.split('/').pop(); }
          chip.classList.remove('hidden'); chip.classList.add('inline-flex');
        } else {
          chip.classList.add('hidden'); chip.classList.remove('inline-flex');
        }
      }

function removeNewsAttachment() {
        document.getElementById('news-attachment-url').value = '';
        updateNewsAttachmentChip(null);
      }

async function viewNewsHistory(newsId) {
        const token = Auth.getToken(); if (!token) return;
        try {
          const res = await fetch('/api/news/' + newsId + '/history', {
            headers: { Authorization: 'Bearer ' + token },
          });
          if (!res.ok) throw new Error('ისტორია ვერ ჩაიტვირთა');
          const history = await res.json();
          ensureHistoryModal();
          const container = document.getElementById('history-modal-content');
          container.innerHTML = '';
          if (history.length === 0) {
            container.innerHTML = '<p class="text-sm text-gray-500">ისტორია ცარიელია (სიახლე ჯერ არ დარედაქტირებულა).</p>';
          } else {
            history.forEach((h, index) => {
              const date = new Date(h.updated_at).toLocaleString('ka-GE');
              container.insertAdjacentHTML('beforeend', `
                <div class="rounded-xl border border-gray-100 bg-gray-50 p-4">
                  <div class="mb-2 flex items-center justify-between">
                    <span class="rounded bg-purple-100 px-2 py-0.5 text-[10px] font-bold text-purple-700">ვერსია ${history.length - index}</span>
                    <div class="flex items-center gap-3">
                      <button onclick="restoreNewsVersion(${newsId}, ${h.id})" class="text-xs font-bold text-[#E30613] hover:text-red-700 hover:underline">აღდგენა</button>
                      <span class="text-[11px] font-medium text-gray-500">${date}</span>
                    </div>
                  </div>
                  <p class="mb-1 text-sm font-bold text-gray-800">${escapeHtml(h.title)}</p>
                  <p class="mb-3 text-[11px] text-gray-500">რედაქტორი: <span class="font-medium text-gray-700">${escapeHtml(h.author_name || '—')}</span></p>
                  <details class="group text-xs text-gray-600">
                    <summary class="cursor-pointer font-semibold text-blue-600 outline-none hover:underline">ტექსტის ნახვა</summary>
                    <div class="mt-3 whitespace-pre-line rounded border border-gray-200 bg-white p-3 leading-relaxed">${escapeHtml(h.content)}</div>
                  </details>
                </div>`);
            });
          }
          const modal = document.getElementById('history-modal');
          modal.classList.remove('hidden'); modal.classList.add('flex');
        } catch (e) { alert(e.message); }
      }

async function restoreNewsVersion(newsId, historyId) {
        if (!confirm('ნამდვილად გსურთ ამ ვერსიის აღდგენა?')) return;
        const token = Auth.getToken(); if (!token) return;
        try {
          const res = await fetch(`/api/news/${newsId}/history/${historyId}/restore`, {
            method: 'POST',
            headers: { Authorization: 'Bearer ' + token },
          });
          if (!res.ok) throw new Error('აღდგენა ვერ მოხერხდა');
          alert('სიახლე წარმატებით აღდგა.');
          closeHistoryModal();
          fetchAndRenderAdminNews(token);
          fetchAndRenderNewsPage(token);
        } catch (e) { alert(e.message); }
      }

async function deleteNews(newsId) {
        if (!confirm('ნამდვილად გსურთ სიახლის წაშლა?')) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        try {
          const response = await fetch('/api/news/' + newsId, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Deletion failed');

          showToast('სიახლე წაიშალა', '', { variant: 'success' });
          fetchAndRenderAdminNews(token); // Refresh admin list
          fetchNews(token).then(renderNews).catch(() => { }); // Refresh dashboard list
          fetchAndRenderNewsPage(token); // Refresh dedicated news page
        } catch (error) {
          console.error(error);
          alert('წაშლა ვერ მოხერხდა.');
        }
      }

function toggleVideoDueDate() {
        const isMandatory = document.getElementById('video-mandatory').checked;
        const dueDateContainer = document.getElementById('video-due-date-container');
        const dueDateInput = document.getElementById('video-due-date');
        if (isMandatory) {
          dueDateContainer.classList.remove('hidden');
          dueDateContainer.classList.add('flex');
          dueDateInput.required = true;
        } else {
          dueDateContainer.classList.add('hidden');
          dueDateContainer.classList.remove('flex');
          dueDateInput.required = false;
        }
      }

function cancelVideoEdit() {
        document.getElementById('admin-video-panel')?.classList.add('hidden');
        document.getElementById('create-video-form').reset();
        toggleVideoDueDate();
      }

async function handleVideoUpload(input) {
        if (!input.files || input.files.length === 0) return;
        const file = input.files[0];
        const formData = new FormData();
        formData.append('file', file);

        const token = Auth.getToken();
        const progressEl = document.getElementById('video-upload-progress');
        progressEl.classList.remove('hidden');

        try {
          const response = await fetch('/api/upload', {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` },
            body: formData
          });
          if (!response.ok) {
            let msg = 'ატვირთვა ვერ მოხერხდა (შესაძლოა ფაილი ზედმეტად დიდია)';
            try { const err = await response.json(); if (err.detail) msg = err.detail; } catch (e) { }
            throw new Error(msg);
          }
          const data = await response.json();
          document.getElementById('video-url').value = data.url;
          showToast('წარმატება', 'ვიდეო ფაილი აიტვირთა ლოკალურად');
        } catch (err) {
          console.error(err);
          alert(err.message);
        } finally {
          progressEl.classList.add('hidden');
          input.value = '';
        }
      }

async function submitVideoForm(event) {
        event.preventDefault();
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const title = document.getElementById('video-title').value;
        const videoUrl = document.getElementById('video-url').value;
        const category = document.getElementById('video-category').value;
        const targetDepartment = document.getElementById('video-department').value;
        const isMandatory = document.getElementById('video-mandatory').checked;
        const dueDate = document.getElementById('video-due-date').value;

        const payload = { title, video_url: videoUrl, category, target_department: targetDepartment };

        try {
          // 1. Primary API call to create the Video
          const videoRes = await fetch('/api/videos', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify(payload)
          });
          if (!videoRes.ok) throw new Error('ვიდეოს შექმნა ვერ მოხერხდა');
          const videoData = await videoRes.json();

          // 2. Secondary API call if marked as Mandatory
          if (isMandatory && dueDate) {
            const reqDate = new Date(dueDate);
            const reqRes = await fetch('/api/compliance/required-readings', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
              body: JSON.stringify({ item_type: 'video', item_id: videoData.id, target_department: targetDepartment, due_date: reqDate.toISOString(), priority: 'high' })
            });
            if (!reqRes.ok) throw new Error('სავალდებულოდ გასაცნობის მინიჭება ვერ მოხერხდა');
          }

          showToast('ვიდეო დაემატა', '', { variant: 'success' });
          event.target.reset();
          toggleVideoDueDate();
          cancelVideoEdit();
          fetchAndRenderVideos(token); // Refresh video list
        } catch (error) {
          console.error(error);
          alert('დაფიქსირდა შეცდომა: ' + error.message);
        }
      }

async function downloadExport() {
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        try {
          const response = await fetch('/api/export/readings', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Export failed');
          const blob = await response.blob();
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'readings_export.csv';
          document.body.appendChild(a);
          a.click();
          a.remove();
        } catch (error) {
          console.error(error);
          alert('ექსპორტი ვერ მოხერხდა.');
        }
      }

function toggleNewsFavFilter() {
        newsOnlyStarred = !newsOnlyStarred;
        const btn = document.getElementById('news-fav-toggle');
        if (btn) {
          if (newsOnlyStarred) {
            btn.classList.add('bg-yellow-50', 'border-yellow-200', 'text-yellow-700');
            btn.classList.remove('bg-white', 'border-gray-200', 'text-gray-600');
            btn.querySelector('i').className = 'fa-solid fa-star text-yellow-500';
            btn.querySelector('i').classList.remove('fa-regular', 'text-gray-400');
          } else {
            btn.classList.remove('bg-yellow-50', 'border-yellow-200', 'text-yellow-700');
            btn.classList.add('bg-white', 'border-gray-200', 'text-gray-600');
            btn.querySelector('i').className = 'fa-regular fa-star text-gray-400';
            btn.querySelector('i').classList.remove('fa-solid', 'text-yellow-500');
          }
        }
        runNewsFilter();
      }

function runNewsFilter() {
        if (!window.allNewsItems) return;

        const searchVal = (document.getElementById('news-search-input')?.value || '').toLowerCase().trim();
        const deptVal = document.getElementById('news-dept-filter')?.value || '';
        const sortVal = document.getElementById('news-sort-order')?.value || 'newest';

        let filtered = [...window.allNewsItems];

        // 1. Department Filter
        if (deptVal) {
          filtered = filtered.filter(item => item.target_department === deptVal);
        }

        // 2. Starred Filter
        if (newsOnlyStarred) {
          filtered = filtered.filter(item => window.userFavorites && window.userFavorites[`news_${item.id}`]);
        }

        // 3. Text Search Filter (Title or Content match)
        if (searchVal) {
          filtered = filtered.filter(item => {
            const titleMatch = (item.title || '').toLowerCase().includes(searchVal);
            const contentMatch = (item.content || '').toLowerCase().includes(searchVal);
            return titleMatch || contentMatch;
          });
        }

        // 4. Sort order
        if (sortVal === 'newest') {
          filtered.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
        } else if (sortVal === 'oldest') {
          filtered.sort((a, b) => new Date(a.created_at) - new Date(b.created_at));
        } else if (sortVal === 'alphabetical') {
          filtered.sort((a, b) => (a.title || '').localeCompare(b.title || '', 'ka-GE'));
        }

        renderNewsList(filtered);
      }

function toggleAdminSubmenu(event) {
        if (event) event.preventDefault();
        const submenu = document.getElementById('sidebar-admin-submenu');
        const chev = document.getElementById('admin-submenu-chevron');
        if (!submenu) return;
        const isOpen = !submenu.classList.contains('hidden');
        if (isOpen) {
          submenu.classList.add('hidden');
          if (chev) chev.classList.remove('rotate-90');
        } else {
          submenu.classList.remove('hidden');
          if (chev) chev.classList.add('rotate-90');
        }
      }

function toggleSidebar() {
        if (window.innerWidth < 768) {
          document.body.classList.toggle('sidebar-open');
        } else {
          document.body.classList.toggle('sidebar-collapsed');
        }
      }

async function logoutUser() {
        if (!confirm('ნამდვილად გსურთ სისტემიდან გასვლა?')) return;
        if (window._magtiES) { window._magtiES.close(); window._magtiES = null; }
        // Best-effort: also clear the server-side httpOnly auth cookie.
        try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }); } catch (e) { }
        Auth.clear();
        window.location.href = 'login.html';
      }

function quickSearch(term) {
        const input = document.getElementById('global-search-input');
        if (!input) return;
        input.value = term;
        input.focus();
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }

function mountQuickLinks() {
        const tpl = document.getElementById('quick-links-tpl');
        if (tpl) {
          document.querySelectorAll('[data-quick-links]:not([data-mounted])').forEach(host => {
            const frag = tpl.content.cloneNode(true);
            const grid = frag.querySelector('div');
            QUICK_LINKS.forEach(q => {
              const btn = document.createElement('button');
              btn.type = 'button';
              btn.className = 'quick-link-card group flex flex-col items-center justify-center p-5 text-center transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-[#E30613]';
              btn.innerHTML = `
                <i aria-hidden="true" class="fa-solid ${q.icon} mb-3 text-2xl text-gray-300 dark:text-zinc-500 transition-colors group-hover:text-[#E30613] dark:group-hover:text-red-400"></i>
                <span class="text-sm font-semibold text-gray-700 dark:text-zinc-300 transition-colors group-hover:text-gray-950 dark:group-hover:text-white">${q.label}</span>`;
              btn.addEventListener('click', () => quickSearch(q.label));
              grid.appendChild(btn);
            });
            host.appendChild(frag);
            host.dataset.mounted = '1';
          });
        }
        // Sidebar dock buttons (recommended row at bottom of sidebar) — unchanged behaviour.
        const terms = QUICK_LINKS.map(q => q.label);
        document.querySelectorAll('aside button[aria-label]').forEach(btn => {
          const label = btn.getAttribute('aria-label');
          if (terms.includes(label)) btn.onclick = () => quickSearch(label);
        });
      }

function wireQuickLinks() { mountQuickLinks(); }

function ensureArticleModal() {
        if (document.getElementById('article-modal')) return;
        document.body.insertAdjacentHTML('beforeend', `
          <div id="article-modal" role="dialog" aria-modal="true" class="fixed inset-0 z-50 hidden items-center justify-center p-4">
            <div class="absolute inset-0 bg-black/50" onclick="closeArticleModal()"></div>
            <div class="relative flex max-h-[88vh] w-full max-w-[920px] flex-col overflow-hidden rounded-2xl bg-white shadow-2xl">

              <!-- Sticky header — always visible while scrolling -->
              <header class="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-gray-100 bg-white/95 px-7 py-4 backdrop-blur">
                <div class="min-w-0 flex-1">
                  <h3 id="article-modal-title" class="truncate text-xl font-bold text-gray-900">
                    <a id="article-modal-title-link" href="#" target="_blank" class="hover:text-[#E30613] hover:underline" title="გახსნა ცალკე გვერდზე"></a>
                  </h3>
                  <p id="article-modal-meta" class="mt-1 text-xs text-gray-500"></p>
                </div>
                <div class="flex shrink-0 items-center gap-2">
                  <button onclick="copyFirstScript()" id="modal-copy-script-btn"
                    class="hidden items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-bold text-white shadow-sm transition-all hover:bg-emerald-700 active:scale-95"
                    title="Ctrl+Shift+C">
                    <i aria-hidden="true" class="fa-solid fa-clipboard"></i><span>კოპირება</span>
                    <kbd class="ml-1 rounded border border-emerald-400 bg-emerald-700/40 px-1 text-[10px]">Ctrl+Shift+C</kbd>
                  </button>
                  <button onclick="copyArticleBody(this)" class="no-print flex h-9 w-9 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900" aria-label="ტექსტის კოპირება" title="მთლიანი ტექსტის კოპირება">
                    <i aria-hidden="true" class="fa-regular fa-copy text-base"></i>
                  </button>
                  <button onclick="toggleModalTheme(this)" class="no-print flex h-9 w-9 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900" aria-label="თემის შეცვლა" title="მოდალის თემის შეცვლა (Light/Dark)">
                    <i aria-hidden="true" class="fa-regular fa-moon text-base"></i>
                  </button>
                  <button onclick="window.print()" class="no-print flex h-9 w-9 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900" aria-label="ბეჭდვა" title="ბეჭდვა / PDF-ად შენახვა">
                    <i aria-hidden="true" class="fa-solid fa-print"></i>
                  </button>
                  <button onclick="closeArticleModal()" class="flex h-9 w-9 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-900" aria-label="დახურვა">
                    <i aria-hidden="true" class="fa-solid fa-xmark text-lg"></i>
                  </button>
                </div>
              </header>

              <!-- [P0-1] Sticky scripts panel — ALWAYS above article body when present -->
              <div id="article-modal-scripts" class="hidden border-b-2 border-emerald-100 bg-emerald-50/50 px-7 py-3">
                <div class="mb-2 flex items-center justify-between">
                  <span class="text-[11px] font-bold uppercase tracking-wider text-emerald-700">
                    <i aria-hidden="true" class="fa-solid fa-headset mr-1"></i>საკონტაქტო სკრიპტები
                  </span>
                  <kbd class="hidden md:inline-block rounded border border-emerald-300 bg-white px-1.5 py-0.5 text-[10px] text-emerald-700">Ctrl+Shift+C</kbd>
                </div>
                <div id="article-scripts-list" class="grid grid-cols-1 gap-2 md:grid-cols-2"></div>
              </div>

              <!-- [P1-9] Staleness banner — severity is applied dynamically -->
              <div id="article-modal-staleness-banner" class="hidden items-center justify-between gap-4 border-b px-7 py-3">
                <div class="flex items-center gap-2.5">
                  <i aria-hidden="true" class="fa-solid fa-triangle-exclamation text-lg"></i>
                  <span id="article-modal-staleness-text" class="text-sm font-semibold"></span>
                </div>
                <button id="modal-verify-btn" onclick="verifyArticleFromModal()" class="hidden shrink-0 rounded-lg bg-amber-600 px-3.5 py-1.5 text-xs font-bold text-white transition-colors hover:bg-amber-700">
                  აქტუალურია
                </button>
              </div>

              <!-- Scrollable body -->
              <div class="flex-1 overflow-y-auto px-7 py-6" id="article-modal-scroll">
                <!-- ავტომატური სარჩევი (TOC) -->
                <div id="article-modal-toc" class="mx-auto mb-5 hidden max-w-[72ch] rounded-xl border border-gray-100 bg-gray-50/50 p-4"></div>

                <!-- Article body — constrained to 72ch for comfortable reading -->
                <div id="article-modal-content" class="article-content-optimized prose-magti mx-auto mb-5 max-w-[72ch] text-gray-700"></div>

                <a id="article-modal-attachment" href="#" target="_blank" class="hidden items-center gap-2 rounded-xl border border-gray-200 bg-gray-50 px-4 py-2.5 text-sm font-medium text-[#E30613] hover:bg-red-50">
                  <i aria-hidden="true" class="fa-solid fa-paperclip"></i> თანდართული ფაილი
                </a>

                <!-- Pin / Report row -->
                <div class="mt-6 flex flex-wrap items-center justify-between gap-4 border-t border-gray-100 pt-5">
                  <div class="flex items-center gap-2">
                    <button id="modal-pin-btn" onclick="togglePinArticleFromModal()" class="rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-gray-700 shadow-sm transition-colors hover:bg-gray-50 flex items-center gap-1.5">
                      <i aria-hidden="true" class="fa-solid fa-thumbtack"></i><span>ჩანიშვნა</span>
                    </button>
                  </div>
                </div>

                <!-- Personal note -->
                <div class="mt-4 border-t border-gray-100 pt-4 w-full">
                  <label for="article-modal-note" class="mb-1.5 block text-[11px] font-bold uppercase tracking-wider text-gray-400">ჩემი პირადი ჩანაწერები</label>
                  <textarea id="article-modal-note" rows="2" onchange="saveArticleNote()"
                    class="w-full rounded-xl border border-gray-200 bg-yellow-50/40 p-3 text-sm text-gray-800 outline-none placeholder-gray-400 focus:border-yellow-400 focus:bg-yellow-50 focus:ring-1 focus:ring-yellow-400 transition-colors"
                    placeholder="ჩაწერეთ პირადი შენიშვნა ან მოკლე ინსტრუქცია ამ სტატიისთვის (ავტომატურად ინახება)..."></textarea>
                </div>

                <!-- Related Procedures Dock -->
                <div id="article-modal-related" class="mt-4 border-t border-gray-100 pt-4 w-full hidden">
                  <label class="mb-2 block text-[11px] font-bold uppercase tracking-wider text-gray-400">
                    <i aria-hidden="true" class="fa-solid fa-link mr-1"></i>დაკავშირებული ინსტრუქციები
                  </label>
                  <div id="article-related-list" class="grid grid-cols-1 sm:grid-cols-2 gap-2"></div>
                </div>
              </div>
            </div>
          </div>`);
      }

function copyFirstScript() {
        const card = document.querySelector('#article-scripts-list [data-script-text]');
        if (!card) {
          if (typeof showToast === 'function') showToast('სკრიპტი არ არის', 'ამ სტატიას [copy] ბლოკი არ აქვს.');
          return;
        }
        navigator.clipboard.writeText(card.dataset.scriptText).then(() => {
          const btn = document.getElementById('modal-copy-script-btn');
          if (btn) {
            const label = btn.querySelector('span');
            const original = label ? label.textContent : '';
            if (label) label.textContent = 'კოპირებულია!';
            btn.classList.add('bg-green-500');
            setTimeout(() => {
              if (label) label.textContent = original || 'კოპირება';
              btn.classList.remove('bg-green-500');
            }, 1500);
          }
        }).catch(err => console.error('Clipboard write failed:', err));
      }

function openArticleModal(article) {
        ensureArticleModal();
        window.activeArticleId = article.id;
        window.activeArticleTitle = article.title;

        const modal = document.getElementById('article-modal');
        const titleLink = document.getElementById('article-modal-title-link');
        if (titleLink) {
          titleLink.textContent = article.title;
          // Click the title -> open the full-screen standalone in-SPA view.
          titleLink.href = `#/article/${article.id}`;
          titleLink.removeAttribute('target');
          titleLink.onclick = function (e) { e.preventDefault(); navToArticleView(article.id); return false; };
        } else {
          document.getElementById('article-modal-title').textContent = article.title;
        }
        const date = new Date(article.created_at).toLocaleDateString('ka-GE');
        document.getElementById('article-modal-meta').textContent =
          `${(article.target_departments || []).join(', ')} · ვერსია ${article.version} · ${date}${article.tags ? ' · ' + article.tags : ''}`;

        const contentDiv = document.getElementById('article-modal-content');
        const body = article.content || '';

        // Render via the shared helper (same engine as the full-page view).
        renderArticleBody(contentDiv, body);

        // ── ავტომატური სარჩევის (TOC) გენერაცია ──
        const tocContainer = document.getElementById('article-modal-toc');
        if (tocContainer) {
          const headings = contentDiv.querySelectorAll('h1, h2, h3');
          if (headings.length > 2) { // ვაჩვენოთ მხოლოდ მაშინ, თუ 2-ზე მეტი ქვესათაურია
            tocContainer.classList.remove('hidden');
            let tocHtml = '<p class="mb-2 text-[11px] font-bold uppercase tracking-wider text-gray-500"><i aria-hidden="true" class="fa-solid fa-list-ul mr-1.5"></i>სარჩევი</p><ul class="space-y-1.5 text-[13px] text-gray-600">';
            headings.forEach((h, i) => {
              h.id = 'heading-' + i; // ვანიჭებთ უნიკალურ ID-ს რომ ლინკმა იმუშაოს
              const isH3 = h.tagName.toLowerCase() === 'h3';
              tocHtml += `<li class="${isH3 ? 'pl-4 text-gray-500' : 'font-medium'}"><a href="#${h.id}" class="hover:text-[#E30613] transition-colors">${h.textContent}</a></li>`;
            });
            tocHtml += '</ul>';
            tocContainer.innerHTML = tocHtml;
          } else {
            tocContainer.classList.add('hidden');
            tocContainer.innerHTML = '';
          }
        }

        const att = document.getElementById('article-modal-attachment');
        if (article.attachment_url) {
          att.href = article.attachment_url;
          att.classList.remove('hidden');
          att.classList.add('inline-flex');
        } else {
          att.classList.add('hidden');
          att.classList.remove('inline-flex');
        }

        // [P1-9] Tiered staleness banner — medium (≥180d) / high (≥270d) / critical (≥365d).
        // Operators need to know the difference between "review soon" and "DO NOT USE".
        const banner = document.getElementById('article-modal-staleness-banner');
        const bannerText = document.getElementById('article-modal-staleness-text');
        if (banner) {
          // Reset banner: strip any prior tier classes and hide
          banner.className = 'hidden items-center justify-between gap-4 border-b px-7 py-3';

          const lastVerified = article.last_verified_at ? new Date(article.last_verified_at) : new Date(article.created_at);
          const daysOld = Math.floor((new Date() - lastVerified) / (1000 * 60 * 60 * 24));

          const tier = daysOld >= 365 ? 'critical'
            : daysOld >= 270 ? 'high'
              : daysOld >= 180 ? 'medium'
                : null;

          if (tier) {
            const cfg = {
              medium: { classes: 'bg-amber-50 border-amber-200 text-amber-800', msg: `ინფორმაცია საჭიროებს გადამოწმებას — ${daysOld} დღის წინ განახლდა` },
              high: { classes: 'bg-orange-50 border-orange-300 text-orange-800', msg: `გადაამოწმეთ ხელმძღვანელთან — სტატია ${daysOld} დღის წინ განახლდა` },
              critical: { classes: 'bg-red-50 border-red-300 text-red-800', msg: `⛔ არ გამოიყენოთ ხელმძღვანელის თანხმობის გარეშე — ${daysOld} დღის წინ განახლდა` },
            }[tier];
            banner.className = `flex items-center justify-between gap-4 border-b px-7 py-3 ${cfg.classes}`;
            if (bannerText) bannerText.textContent = cfg.msg;

            // Check if user is admin/content_admin to show the Verify button
            const verifyBtn = document.getElementById('modal-verify-btn');
            if (verifyBtn) {
              const role = window.currentUser ? window.currentUser.role : '';
              if (role === 'admin' || role === 'content_admin') {
                verifyBtn.classList.remove('hidden');
              } else {
                verifyBtn.classList.add('hidden');
              }
            }
          }
        }

        // Reset and fetch personal notes
        const noteArea = document.getElementById('article-modal-note');
        if (noteArea) {
          noteArea.value = '';
          const token = Auth.getToken();
          if (token) {
            fetch(`/api/articles/${article.id}/note`, {
              headers: { 'Authorization': 'Bearer ' + token }
            })
              .then(res => res.ok ? res.json() : null)
              .then(data => {
                if (data && data.content) {
                  noteArea.value = data.content;
                }
              })
              .catch(err => console.error('Failed to load note', err));
          }
        }

        // Save to Recently Viewed in localStorage
        try {
          let recent = JSON.parse(localStorage.getItem('magti_recently_viewed')) || [];
          recent = recent.filter(x => x.id !== article.id);
          recent.unshift({ id: article.id, title: article.title });
          recent = recent.slice(0, 5);
          localStorage.setItem('magti_recently_viewed', JSON.stringify(recent));
          if (typeof renderRecentlyViewed === 'function') renderRecentlyViewed();
        } catch (e) {
          console.error('Recently viewed storage error:', e);
        }

        // Set pin button state
        updatePinButtonState(article.id);

        // ── Quick-Copy Scripts Widget ──────────────────────────────────
        const scriptsContainer = document.getElementById('article-modal-scripts');
        const scriptsList = document.getElementById('article-scripts-list');
        if (scriptsContainer && scriptsList) {
          scriptsList.innerHTML = '';
          scriptsContainer.classList.add('hidden');

          // Parse [copy]...[/copy] blocks from article content
          const copyRegex = /\[copy\]([\s\S]*?)\[\/copy\]/gi;
          const rawContent = article.content || '';
          let match;
          const scripts = [];
          while ((match = copyRegex.exec(rawContent)) !== null) {
            scripts.push(match[1].trim());
          }

          // Also extract <code> blocks as copyable scripts
          const codeRegex = /<code[^>]*>([\s\S]*?)<\/code>/gi;
          while ((match = codeRegex.exec(rawContent)) !== null) {
            const decoded = match[1].replace(/<[^>]*>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').trim();
            if (decoded.length > 5 && !scripts.includes(decoded)) {
              scripts.push(decoded);
            }
          }

          if (scripts.length > 0) {
            scriptsContainer.classList.remove('hidden');
            // [P0-1] Reveal the header "Copy" button only when there's actually a script to copy.
            const copyBtn = document.getElementById('modal-copy-script-btn');
            if (copyBtn) { copyBtn.classList.remove('hidden'); copyBtn.classList.add('inline-flex'); }
            scripts.forEach((script, idx) => {
              const card = document.createElement('div');
              card.className = 'flex items-start gap-2 rounded-xl border border-emerald-200 bg-white p-3 shadow-sm';
              card.innerHTML = `
                <pre class="flex-1 whitespace-pre-wrap text-sm text-gray-800 font-mono leading-snug">${escapeHtml(script)}</pre>
                <button onclick="copyScript(this, ${idx})"
                  class="shrink-0 rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-bold text-white shadow-sm transition-all hover:bg-emerald-700 active:scale-95 flex items-center gap-1"
                  title="კოპირება">
                  <i aria-hidden="true" class="fa-solid fa-copy"></i> <span>კოპირება</span>
                </button>`;
              card.dataset.scriptText = script;
              scriptsList.appendChild(card);
            });
          } else {
            // No scripts → keep header button hidden so it never confuses the operator.
            const copyBtn = document.getElementById('modal-copy-script-btn');
            if (copyBtn) { copyBtn.classList.add('hidden'); copyBtn.classList.remove('inline-flex'); }
          }
        }

        // ── Related Procedures Dock ──────────────────────────────────
        const relatedContainer = document.getElementById('article-modal-related');
        const relatedList = document.getElementById('article-related-list');
        if (relatedContainer && relatedList) {
          relatedList.innerHTML = '';
          relatedContainer.classList.add('hidden');

          const token = Auth.getToken();
          if (token) {
            fetch(`/api/articles/${article.id}/related`, {
              headers: { 'Authorization': 'Bearer ' + token }
            })
              .then(res => res.ok ? res.json() : [])
              .then(related => {
                if (related.length > 0) {
                  relatedContainer.classList.remove('hidden');
                  related.forEach(r => {
                    const card = document.createElement('button');
                    card.className = 'flex items-center gap-2.5 rounded-xl border border-gray-200 bg-white p-3 text-left text-sm text-gray-700 shadow-sm transition-all hover:border-[#E30613]/40 hover:shadow-md hover:bg-red-50/30 active:scale-[0.98]';
                    card.innerHTML = `<i aria-hidden="true" class="fa-solid fa-file-lines text-[#E30613]/60 text-xs shrink-0"></i><span class="line-clamp-2">${escapeHtml(r.title)}</span>`;
                    card.onclick = () => { closeArticleModal(); setTimeout(() => openArticleModalById(r.id), 200); };
                    relatedList.appendChild(card);
                  });
                }
              })
              .catch(err => console.error('Failed to load related articles', err));
          }
        }

        modal.classList.remove('hidden');
        modal.classList.add('flex');
        setDockMode(); // [P1-12] dim pinned dock so it stops fighting the modal for the bottom-right corner
      }

async function openArticleModalById(articleId) {
        // Resolve from cache; if the user clicked before the list finished
        // loading, fetch on demand instead of failing silently.
        let article = Store.articles[articleId] || (window.adminArticles || {})[articleId];
        // If the article exists but is only a summary (lacks content), fetch full details
        if (!article || article.content === undefined) {
          article = await Store.getArticle(articleId);
        }
        if (article) {
          openArticleModal(article);
          const token = Auth.getToken();
          if (token) {
            fetch(`/api/articles/${articleId}/view`, {
              method: 'POST',
              headers: { 'Authorization': 'Bearer ' + token }
            }).catch(e => console.error('Failed to log article view', e));
          }
        }
        else alert('სტატია ვერ მოიძებნა.');
      }

function closeArticleModal() {
        const modal = document.getElementById('article-modal');
        if (modal) { modal.classList.add('hidden'); modal.classList.remove('flex'); }
        // Reset forced theme modes when closing
        const modalBody = document.querySelector('#article-modal .relative');
        if (modalBody) {
          modalBody.classList.remove('force-dark', 'force-light');
          const themeToggle = document.querySelector('button[onclick^="toggleModalTheme"] i');
          if (themeToggle) themeToggle.className = 'fa-regular fa-moon text-base';
        }
        setDockMode(); // [P1-12]
      }

function copyArticleBody(btn) {
        const contentDiv = document.getElementById('article-modal-content');
        if (!contentDiv) return;
        const text = contentDiv.innerText || contentDiv.textContent;
        navigator.clipboard.writeText(text).then(() => {
          const icon = btn.querySelector('i');
          if (icon) {
            icon.className = 'fa-solid fa-check text-green-500 text-base';
            setTimeout(() => { icon.className = 'fa-regular fa-copy text-base'; }, 2000);
          }
          if (typeof showToast === 'function') {
            showToast('კოპირება', 'სტატიის ტექსტი კოპირებულია!');
          }
        }).catch(err => {
          console.error('Failed to copy text', err);
        });
      }

function toggleModalTheme(btn) {
        const modalBody = document.querySelector('#article-modal .relative');
        if (!modalBody) return;
        const isForceDark = modalBody.classList.toggle('force-dark');
        const isForceLight = modalBody.classList.toggle('force-light', !isForceDark);

        const icon = btn.querySelector('i');
        if (icon) {
          if (isForceDark) {
            icon.className = 'fa-solid fa-sun text-yellow-500 text-base';
            btn.title = 'მოდალის თემის შეცვლა (Light)';
          } else {
            icon.className = 'fa-regular fa-moon text-base';
            btn.title = 'მოდალის თემის შეცვლა (Dark)';
          }
        }
      }

      function toggleMinimizeArticleModal() {
        const modal = document.getElementById('article-modal');
        if (!modal) return;
        
        const backdrop = modal.querySelector('.bg-black\\/50');
        const dialog = modal.querySelector('.relative');
        const minBtnIcon = document.querySelector('#modal-minimize-btn i');

        if (modal.classList.contains('is-minimized')) {
          modal.classList.remove('is-minimized', 'pointer-events-none');
          if (backdrop) backdrop.classList.remove('hidden');
          
          dialog.classList.remove('fixed', 'bottom-4', 'right-4', 'w-[400px]', 'h-[50vh]', 'pointer-events-auto', 'shadow-2xl', 'border', 'border-gray-200', 'z-[160]');
          dialog.classList.add('max-w-[920px]', 'w-full', 'max-h-[88vh]');
          
          if (minBtnIcon) minBtnIcon.className = 'fa-solid fa-compress text-base';
        } else {
          modal.classList.add('is-minimized', 'pointer-events-none');
          if (backdrop) backdrop.classList.add('hidden');
          
          dialog.classList.remove('max-w-[920px]', 'w-full', 'max-h-[88vh]');
          dialog.classList.add('fixed', 'bottom-4', 'right-4', 'w-[400px]', 'h-[50vh]', 'pointer-events-auto', 'shadow-2xl', 'border', 'border-gray-200', 'z-[160]');
          
          if (minBtnIcon) minBtnIcon.className = 'fa-solid fa-expand text-base';
        }
      }

function setDockMode() {
        const dock = document.getElementById('pinned-articles-dock');
        if (!dock) return;
        const modalOpen = !!document.querySelector('[id$="-modal"]:not(.hidden).flex');
        dock.classList.toggle('opacity-30', modalOpen);
        dock.classList.toggle('pointer-events-none', modalOpen);
        if (dock) {
          dock.classList.toggle('opacity-30', modalOpen);
          dock.classList.toggle('pointer-events-none', modalOpen);
        }
        
        // [P1] Focus Trapping: Make underlying page elements inert when a modal is open
        const bgElements = [document.getElementById('sidebar'), document.getElementById('main-header')];
        document.querySelectorAll('.page-section').forEach(el => bgElements.push(el));
        
        bgElements.forEach(el => {
          if (el) { modalOpen ? el.setAttribute('inert', 'true') : el.removeAttribute('inert'); }
        });
      }

function ensureHistoryModal() {
        if (document.getElementById('history-modal')) return;
        document.body.insertAdjacentHTML('beforeend', `
          <div id="history-modal" role="dialog" aria-modal="true" class="fixed inset-0 z-[100] hidden items-center justify-center p-4">
            <div class="absolute inset-0 bg-black/50 backdrop-blur-sm" onclick="closeHistoryModal()"></div>
            <div class="relative max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-white p-8 shadow-2xl">
              <button onclick="closeHistoryModal()" class="absolute right-5 top-5 text-xl text-gray-400 hover:text-gray-700" aria-label="დახურვა">
                <i aria-hidden="true" class="fa-solid fa-xmark"></i>
              </button>
              <h3 class="mb-5 pr-8 text-2xl font-bold text-gray-800">ცვლილებების ისტორია</h3>
              <div id="history-modal-content" class="flex flex-col gap-3"></div>
            </div>
          </div>
        `);
      }

async function viewArticleHistory(articleId) {
        const token = Auth.getToken();
        if (!token) return;
        try {
          const response = await fetch('/api/articles/' + articleId + '/history', {
            headers: { 'Authorization': 'Bearer ' + token }
          });
          if (!response.ok) throw new Error('Failed to fetch history');
          const history = await response.json();

          ensureHistoryModal();
          const container = document.getElementById('history-modal-content');
          container.innerHTML = '';
          const esc = str => (str || '').replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

          if (history.length === 0) {
            container.innerHTML = '<p class="text-sm text-gray-500">ისტორია ცარიელია (სტატია ჯერ არ დარედაქტირებულა).</p>';
          } else {
            history.forEach((h, index) => {
              const date = new Date(h.updated_at).toLocaleString('ka-GE');
              container.insertAdjacentHTML('beforeend', `
                <div class="rounded-xl border border-gray-100 bg-gray-50 p-4 transition-colors hover:bg-gray-100">
                  <div class="mb-2 flex items-center justify-between">
                    <span class="rounded bg-purple-100 px-2 py-0.5 text-[10px] font-bold text-purple-700">ვერსია ${history.length - index}</span>
                    <div class="flex items-center gap-3">
                      <button onclick="restoreArticleVersion(${articleId}, ${h.id})" class="text-xs font-bold text-[#E30613] hover:text-red-700 hover:underline">აღდგენა</button>
                      <span class="text-[11px] font-medium text-gray-500">${date}</span>
                    </div>
                  </div>
                  <p class="mb-1 text-sm font-bold text-gray-800">${esc(h.title)}</p>
                  <p class="mb-3 text-[11px] text-gray-500">რედაქტორი: <span class="font-medium text-gray-700">${esc(h.author_name)}</span></p>
                  <details class="group text-xs text-gray-600">
                    <summary class="cursor-pointer font-semibold text-blue-600 outline-none hover:underline">ტექსტის ნახვა</summary>
                    <div class="mt-3 whitespace-pre-line rounded border border-gray-200 bg-white p-3 leading-relaxed">${esc(h.content)}</div>
                  </details>
                </div>
              `);
            });
          }
          const modal = document.getElementById('history-modal');
          modal.classList.remove('hidden');
          modal.classList.add('flex');
        } catch (e) {
          console.error(e);
          alert('ისტორიის ჩატვირთვა ვერ მოხერხდა.');
        }
      }

function closeHistoryModal() {
        const modal = document.getElementById('history-modal');
        if (modal) { modal.classList.add('hidden'); modal.classList.remove('flex'); }
      }

async function restoreArticleVersion(articleId, historyId) {
        if (!confirm('ნამდვილად გსურთ სტატიის ამ ვერსიის აღდგენა?')) return;
        const token = Auth.getToken();
        if (!token) return;
        try {
          const response = await fetch(`/api/articles/${articleId}/history/${historyId}/restore`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${token}`
            }
          });
          if (handleSessionExpiry(response)) return;
          if (!response.ok) throw new Error('Restore failed');
          alert('სტატიის ვერსია წარმატებით აღდგა.');
          closeHistoryModal();
          fetchAndRenderKnowledgeBase(token);
          if (window.currentPageId === 'page-admin') {
            fetchAndRenderAdminContent(token);
          }
        } catch (error) {
          console.error(error);
          alert('აღდგენა ვერ მოხერხდა.');
        }
      }

function selectKbCategoryByName(catName) {
        const select = document.getElementById('kb-category-filter');
        if (!select) return;

        let targetId = '';
        if (catName) {
          targetId = Object.keys(Store.categories).find(id => Store.categories[id] === catName) || '';
        }

        if (select.value === targetId) {
          select.value = '';
        } else {
          select.value = targetId;
        }

        updateBentoActiveState();
        runKbSearch();
      }

function updateBentoActiveState() {
        const select = document.getElementById('kb-category-filter');
        const activeId = select ? String(select.value || '') : '';
        document.querySelectorAll('[data-bento-cat-id]').forEach(el => {
          const id = el.getAttribute('data-bento-cat-id');
          const icon = el.querySelector('span i');
          if (id === activeId && activeId) {
            el.classList.add('border-[#E30613]', 'bg-red-50/30', 'dark:bg-red-950/20');
            el.classList.remove('border-gray-150', 'dark:border-zinc-800');
            if (icon) {
              icon.className = 'fa-solid fa-circle-check text-[#E30613]';
            }
          } else {
            el.classList.remove('border-[#E30613]', 'bg-red-50/30', 'dark:bg-red-950/20');
            el.classList.add('border-gray-150', 'dark:border-zinc-800');
            if (icon) {
              icon.className = 'fa-solid fa-arrow-right text-gray-400 dark:text-zinc-500';
            }
          }
        });
      }

function setupKbSearch(token) {
        const input = document.getElementById('kb-search-input');
        if (!input) return;
        let timer;
        input.addEventListener('input', () => {
          clearTimeout(timer);
          timer = setTimeout(runKbSearch, 300);
        });
      }

async function runKbSearch() {
        const token = localStorage.getItem('magti_token');
        const container = document.getElementById('kb-articles-container');
        const q = (document.getElementById('kb-search-input') || {}).value || '';
        const categoryId = (document.getElementById('kb-category-filter') || {}).value || '';
        if (!token || !container) return;

        // Update Bento active border highlights
        updateBentoActiveState();

        // Empty query and no filter: restore the full grid
        if (!q.trim() && !categoryId) { fetchAndRenderKnowledgeBase(token); return; }

        container.className = 'mb-10 grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4';
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

        try {
          let url = `/api/search?q=${encodeURIComponent(q.trim())}`;
          if (categoryId) url += `&category_id=${categoryId}`;
          const response = await fetch(url, { headers: { 'Authorization': `Bearer ${token}` } });
          if (handleSessionExpiry(response)) return;
          if (!response.ok) throw new Error('Search failed');
          const articles = await response.json();

          clearObj(Store.articles);
          articles.forEach(a => { Store.articles[a.id] = a; });

          container.innerHTML = '';
          if (articles.length === 0) {
            container.innerHTML = '<p class="col-span-full text-sm text-gray-500">შედეგი ვერ მოიძებნა.</p>';
            return;
          }
          articles.forEach(article => {
            // Mark as NEW if created in the last 4 days
            const isNew = (new Date() - new Date(article.created_at)) < (4 * 24 * 60 * 60 * 1000);
            const newBadge = isNew ? `<span class="inline-flex items-center gap-1 rounded-full bg-red-50 dark:bg-red-950/40 px-1.5 py-0.5 text-[9px] font-semibold text-[#E30613] dark:text-red-400 border border-red-100/50 dark:border-red-900/30"><span class="h-1 w-1 rounded-full bg-[#E30613] dark:bg-red-500"></span>ახალი</span>` : '';
            const date = new Date(article.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
            const catName = Store.categories[article.category_id] || 'ზოგადი';
            const iconClass = getCategoryIcon(article.category_id, article.title);

            container.insertAdjacentHTML('beforeend', `
              <div onclick="openArticleModalById(${article.id})" 
                   onkeydown="if(event.key==='Enter'||event.key===' '){openArticleModalById(${article.id});event.preventDefault();}" 
                   tabindex="0" 
                   role="button" 
                   class="kb-card group relative flex cursor-pointer flex-col overflow-hidden rounded-2xl border border-gray-200/60 bg-white p-5 shadow-sm transition-all duration-300 hover:border-[#E30613]/40 hover:shadow-lg hover:-translate-y-1 focus:outline-none focus:ring-2 focus:ring-[#E30613]">
                
                <!-- Icon -->
                <div class="mb-4">
                  <i aria-hidden="true" class="fa-solid ${iconClass} text-2xl text-[#E30613] opacity-70 transition-opacity group-hover:opacity-100"></i>
                </div>

                <!-- Title -->
                <h4 class="text-base font-bold text-gray-800 leading-snug flex-grow group-hover:text-black" title="${escapeHtml(article.title)}">
                  ${escapeHtml(article.title)}
                </h4>

                <!-- Category & Footer -->
                <div class="mt-4 pt-4 border-t border-gray-100 text-xs">
                  <div class="flex items-center justify-between text-gray-500">
                    <span class="font-semibold text-[#E30613] uppercase tracking-wider text-[10px]">${escapeHtml(catName)}</span>
                    <div class="flex items-center gap-3">
                      ${newBadge}
                      <button onclick="toggleFavorite('article', ${article.id}, this); event.stopPropagation();" data-fav-type="article" data-fav-id="${article.id}"
                        class="flex h-7 w-7 items-center justify-center rounded-full text-gray-400 transition-all hover:bg-yellow-50 hover:text-yellow-500 focus:outline-none focus:ring-2 focus:ring-yellow-400 dark:text-zinc-400"
                        aria-label="რჩეულებში დამატება">
                        <i aria-hidden="true" class="fa-regular fa-star text-sm"></i>
                      </button>
                    </div>
                  </div>
                </div>
              </div>`);
          });
          updateStarIcons();
        } catch (error) {
          console.error(error);
          container.innerHTML = '<p class="col-span-full text-red-500">ძიებისას დაფიქსირდა შეცდომა.</p>';
        }
      }

function filterVideos(query) {
        const q = query.trim().toLowerCase();
        document.querySelectorAll('.video-card').forEach(card => {
          card.classList.toggle('hidden', q !== '' && !card.getAttribute('data-video-title').includes(q));
        });
      }

async function viewVideo(videoId) {
        const token = Auth.getToken();
        if (!token) {
          if (typeof showToast === 'function') {
            showToast('ვიდეო', 'ავტორიზაცია საჭიროა.', { variant: 'error' });
          }
          return;
        }
        if (videoId == null) {
          console.warn('viewVideo called without a videoId');
          return;
        }

        // Resolve from cache, or fetch on demand if clicked before videos loaded.
        // Belt-and-braces: Store / Store.videos / Store.getVideo may all be undefined
        // if the user clicked during the very early page load.
        let video = null;
        try {
          const cacheById = (window.Store && window.Store.videos) ? window.Store.videos[videoId] : null;
          video = cacheById || (window.Store && typeof window.Store.getVideo === 'function'
            ? await window.Store.getVideo(videoId)
            : null);
        } catch (e) {
          console.warn('Video cache lookup failed, falling back to API:', e);
        }

        // Final fallback: hit the API directly. This guarantees an empty Store
        // doesn't translate to "click does nothing".
        if (!video) {
          try {
            const r = await fetch(`/api/videos`, { headers: { 'Authorization': `Bearer ${token}` } });
            if (r.ok) {
              const all = await r.json();
              video = (all || []).find(v => v && v.id === Number(videoId)) || null;
            }
          } catch (e) {
            console.error('Direct video fetch failed:', e);
          }
        }

        if (!video || !video.video_url) {
          if (typeof showToast === 'function') {
            showToast('ვიდეო', 'ვიდეო ვერ ჩაიტვირთა.', { variant: 'error' });
          }
          return;
        }

        const modal = document.getElementById('video-detail-modal');
        const iframe = document.getElementById('video-modal-iframe');
        const player = document.getElementById('video-modal-player');
        const titleEl = document.getElementById('video-modal-title');
        const deptEl  = document.getElementById('video-modal-dept');

        if (!modal || !iframe || !player) {
          // Modal markup missing — fail safely without opening a new tab.
          if (typeof showToast === 'function') {
            showToast('ვიდეო', 'ვიდეო პლეერი ვერ ჩაიტვირთა.', { variant: 'error' });
          }
          return;
        }

        if (titleEl) titleEl.textContent = video.title || 'ვიდეო';
        if (deptEl) {
          const dept = video.target_department || 'All';
          deptEl.textContent = dept === 'All' ? 'ყველა დეპარტამენტი' : dept;
        }

        const embed = _toYouTubeEmbed(video.video_url);
        if (embed) {
          iframe.src = embed;
          iframe.classList.remove('hidden');
          player.classList.add('hidden');
          player.removeAttribute('src');
        } else {
          // Self-hosted file or unknown provider — use native player.
          player.src = video.video_url;
          player.classList.remove('hidden');
          iframe.classList.add('hidden');
          iframe.src = '';
        }

        modal.classList.remove('hidden');
        modal.classList.add('flex');

        // Best-effort view counter — never blocks the modal open.
        try {
          const response = await api(`/api/videos/${videoId}/view`, { method: 'POST' });
          if (response && response.ok && typeof fetchAndRenderVideos === 'function') {
            fetchAndRenderVideos(token);
          }
        } catch (error) {
          console.error('Error registering video view:', error);
        }
      }

function closeVideoModal() {
        const modal = document.getElementById('video-detail-modal');
        const iframe = document.getElementById('video-modal-iframe');
        const player = document.getElementById('video-modal-player');
        if (modal) { modal.classList.add('hidden'); modal.classList.remove('flex'); }
        // Stop playback — clearing src forces the browser to release the media.
        if (iframe) iframe.src = '';
        if (player) { try { player.pause(); } catch (_) {} player.removeAttribute('src'); player.load && player.load(); }
      }

function openMessagesTab() {
        navTo('page-profile');
        const tabBtn = document.querySelectorAll('.profile-tab-btn')[2];
        switchProfileTab('profile-tab-3', tabBtn);
      }

async function markMessageRead(messageId) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        try {
          const response = await fetch(`/api/messages/${messageId}/read`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to mark message read');
          fetchAndRenderMessages(token);
        } catch (error) {
          console.error(error);
        }
      }

async function deleteMessage(messageId) {
        if (!confirm('ნამდვილად გსურთ შეტყობინების წაშლა?')) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        try {
          const response = await fetch(`/api/messages/${messageId}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error('Failed to delete message');
          fetchAndRenderMessages(token);
        } catch (error) {
          console.error(error);
        }
      }

function applySettings() {
        const choice = (document.querySelector('input[name="font-style"]:checked') || {}).value || 'default';
        const sizes = { default: '16px', medium: '17.5px', big: '19px' };
        document.documentElement.style.fontSize = sizes[choice];
        localStorage.setItem('magti_font', choice);

        const bgInput = document.getElementById('settings-bg-color');
        if (bgInput) {
          const bgColor = bgInput.value;
          document.body.style.backgroundColor = bgColor;
          localStorage.setItem('magti_bg_color', bgColor);
        }

        const themeSelect = document.getElementById('settings-theme-color');
        if (themeSelect) {
          const theme = themeSelect.value;
          document.body.classList.remove('theme-navy', 'theme-emerald', 'theme-slate');
          if (theme !== 'red') {
            document.body.classList.add('theme-' + theme);
          }
          localStorage.setItem('magti_theme', theme);
        }

        // Apply Card Design Pack
        const cardStyleSelect = document.getElementById('settings-card-style');
        if (cardStyleSelect) {
          const cardStyle = cardStyleSelect.value;
          document.body.classList.remove('card-theme-corporate', 'card-theme-glass', 'card-theme-bold', 'card-theme-minimal', 'card-theme-colorful');
          document.body.classList.add('card-theme-' + cardStyle);
          localStorage.setItem('magti_card_style', cardStyle);

          // Save to backend database user profile via PUT /api/users/me
          const token = localStorage.getItem('magti_token');
          if (token && window.currentUser) {
            fetch('/api/users/me', {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
              body: JSON.stringify({
                name: window.currentUser.name,
                position: window.currentUser.position || null,
                phone: window.currentUser.phone || null,
                card_style: cardStyle
              })
            }).then(res => {
              if (res.ok) return res.json();
            }).then(user => {
              if (user) {
                window.currentUser = user;
                updateUserInfo(user);
              }
            }).catch(err => console.error('Failed to sync card style settings to DB:', err));
          }
        }

        alert('პარამეტრები შენახულია.');
      }

function toggleDarkMode() {
        const isDark = document.getElementById('settings-dark-mode').checked;
        if (isDark) {
          document.body.classList.add('dark-mode', 'dark');
          document.documentElement.classList.add('dark');
          localStorage.setItem('magti_dark_mode', 'true');
        } else {
          document.body.classList.remove('dark-mode', 'dark');
          document.documentElement.classList.remove('dark');
          localStorage.setItem('magti_dark_mode', 'false');
        }
      }

/** Header moon/sun button: self-contained dark-mode toggle that also keeps the
 *  settings-page checkbox (#settings-dark-mode) and stored preference in sync. */
function toggleHeaderTheme() {
        const willDark = !document.documentElement.classList.contains('dark');
        document.documentElement.classList.toggle('dark', willDark);
        document.body.classList.toggle('dark', willDark);
        document.body.classList.toggle('dark-mode', willDark);
        localStorage.setItem('magti_dark_mode', willDark ? 'true' : 'false');
        const cb = document.getElementById('settings-dark-mode');
        if (cb) cb.checked = willDark;
        syncHeaderThemeIcon();
      }

/** Swap the header theme icon to reflect the current mode (sun in dark, moon in light). */
function syncHeaderThemeIcon() {
        const icon = document.getElementById('header-theme-icon');
        if (!icon) return;
        const dark = document.documentElement.classList.contains('dark');
        icon.className = dark ? 'fa-solid fa-sun text-[17px]' : 'fa-regular fa-moon text-[17px]';
      }

function applyStoredSettings() {
        const stored = localStorage.getItem('magti_font') || 'default';
        const sizes = { default: '16px', medium: '17.5px', big: '19px' };
        if (sizes[stored]) document.documentElement.style.fontSize = sizes[stored];
        const radio = document.querySelector(`input[name="font-style"][value="${stored}"]`);
        if (radio) radio.checked = true;

        const storedBg = localStorage.getItem('magti_bg_color');
        if (storedBg) {
          document.body.style.backgroundColor = storedBg;
          const bgInput = document.getElementById('settings-bg-color');
          if (bgInput) bgInput.value = storedBg;
        }

        const storedTheme = localStorage.getItem('magti_theme') || 'red';
        document.body.classList.remove('theme-navy', 'theme-emerald', 'theme-slate');
        if (storedTheme !== 'red') {
          document.body.classList.add('theme-' + storedTheme);
        }
        const themeSelect = document.getElementById('settings-theme-color');
        if (themeSelect) themeSelect.value = storedTheme;

        const isDark = localStorage.getItem('magti_dark_mode') === 'true';
        if (isDark) {
          document.body.classList.add('dark-mode', 'dark');
          document.documentElement.classList.add('dark');
          const toggle = document.getElementById('settings-dark-mode');
          if (toggle) toggle.checked = true;
        } else {
          document.documentElement.classList.remove('dark');
        }
        // Restore Card Design Pack
        const storedCardStyle = localStorage.getItem('magti_card_style') || (window.currentUser ? window.currentUser.card_style : 'corporate') || 'corporate';
        document.body.classList.remove('card-theme-corporate', 'card-theme-glass', 'card-theme-bold', 'card-theme-minimal', 'card-theme-colorful');
        document.body.classList.add('card-theme-' + storedCardStyle);
        const cardStyleSelect = document.getElementById('settings-card-style');
        if (cardStyleSelect) cardStyleSelect.value = storedCardStyle;

        syncHeaderThemeIcon();
      }

async function verifyArticleFromModal() {
        const articleId = window.activeArticleId;
        if (!articleId) return;

        const token = Auth.getToken();
        if (!token) return;

        try {
          const response = await fetch(`/api/articles/${articleId}/verify`, {
            method: 'POST',
            headers: {
              'Authorization': 'Bearer ' + token
            }
          });
          if (handleSessionExpiry(response)) return;
          if (!response.ok) throw new Error('Verification failed');
          const updatedArticle = await response.json();

          alert('სტატია წარმატებით მონიშნა როგორც აქტუალური.');

          // Hide the banner immediately
          const banner = document.getElementById('article-modal-staleness-banner');
          if (banner) {
            banner.classList.add('hidden');
            banner.classList.remove('flex');
          }

          // Update the cached/stored article details
          if (Store.articles[articleId]) {
            Store.articles[articleId] = updatedArticle;
          }
          if (window.adminArticles && window.adminArticles[articleId]) {
            window.adminArticles[articleId] = updatedArticle;
          }

          // Re-render categories/knowledge base/admin tables to show new state
          fetchAndRenderKnowledgeBase(token);
          if (window.currentPageId === 'page-admin') {
            fetchAndRenderAdminContent(token);
          }
        } catch (error) {
          console.error(error);
          alert('სტატიის გადამოწმება ვერ მოხერხდა: ' + error.message);
        }
      }

function copyScript(btn, idx) {
        const card = btn.closest('[data-script-text]');
        const text = card ? card.dataset.scriptText : '';
        if (!text) return;

        navigator.clipboard.writeText(text).then(() => {
          const icon = btn.querySelector('i');
          const label = btn.querySelector('span');
          if (icon) icon.className = 'fa-solid fa-check';
          if (label) label.textContent = 'კოპირებულია!';
          btn.classList.remove('bg-emerald-600', 'hover:bg-emerald-700');
          btn.classList.add('bg-green-500');
          setTimeout(() => {
            if (icon) icon.className = 'fa-solid fa-copy';
            if (label) label.textContent = 'კოპირება';
            btn.classList.remove('bg-green-500');
            btn.classList.add('bg-emerald-600', 'hover:bg-emerald-700');
          }, 1500);
        }).catch(err => console.error('Clipboard write failed:', err));
      }

function getPinnedArticles() {
        try {
          const raw = JSON.parse(localStorage.getItem('magti_pinned')) || [];
          const seen = new Set();
          return raw.filter(item => {
            if (!item || !item.id) return false;
            if (seen.has(item.id)) return false;
            seen.add(item.id);
            return true;
          });
        } catch (e) {
          return [];
        }
      }

function savePinnedArticles(list) {
        localStorage.setItem('magti_pinned', JSON.stringify(list));
      }

function togglePinArticleFromModal() {
        const articleId = window.activeArticleId;
        const title = window.activeArticleTitle;
        if (!articleId || !title) return;

        let list = getPinnedArticles();
        const exists = list.some(item => item.id === articleId);

        if (exists) {
          list = list.filter(item => item.id !== articleId);
        } else {
          list.push({ id: articleId, title: title });
        }

        savePinnedArticles(list);
        renderPinnedDock();
        updatePinButtonState(articleId);

        if (!exists) {
          const container = document.getElementById('pinned-items-container');
          if (container) {
            container.classList.remove('hidden');
            container.classList.add('flex');
          }
        }
      }

function updatePinButtonState(articleId) {
        const btn = document.getElementById('modal-pin-btn');
        if (!btn) return;

        const list = getPinnedArticles();
        const isPinned = list.some(item => item.id === articleId);

        if (isPinned) {
          btn.innerHTML = '<span>📌 ჩანიშნულია</span>';
          btn.className = 'rounded-xl border border-red-200 bg-red-50 px-4 py-2 text-sm font-semibold text-[#E30613] shadow-sm transition-colors hover:bg-red-100 flex items-center gap-1.5';
        } else {
          btn.innerHTML = '<span>📌 ჩანიშნვა</span>';
          btn.className = 'rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-semibold text-gray-600 shadow-sm transition-colors hover:bg-gray-50 flex items-center gap-1.5';
        }
      }

function unpinArticle(articleId) {
        let list = getPinnedArticles();
        list = list.filter(item => item.id !== articleId);
        savePinnedArticles(list);
        renderPinnedDock();
        if (window.activeArticleId === articleId) {
          updatePinButtonState(articleId);
        }
      }

function togglePinnedDock() {
        const container = document.getElementById('pinned-items-container');
        if (!container) return;
        if (container.classList.contains('hidden')) {
          container.classList.remove('hidden');
          container.classList.add('flex');
        } else {
          container.classList.add('hidden');
          container.classList.remove('flex');
        }
      }

function saveArticleNote() {
        const articleId = window.activeArticleId;
        const noteArea = document.getElementById('article-modal-note');
        if (!articleId || !noteArea) return;

        const content = noteArea.value;
        const token = Auth.getToken();
        if (!token) return;

        fetch(`/api/articles/${articleId}/note`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + token
          },
          body: JSON.stringify({ content: content })
        })
          .then(res => {
            if (!res.ok) throw new Error('Failed to save note');
            return res.json();
          })
          .then(data => {

          })
          .catch(err => {
            console.error('Save note error:', err);
            alert('ჩანაწერის შენახვა ვერ მოხერხდა: ' + err.message);
          });
      }


function updateBroadcastCounter() {
        const input = document.getElementById('broadcast-message-input');
        const counter = document.getElementById('broadcast-char-counter');
        if (input && counter) {
          const len = input.value.length;
          counter.textContent = `${len} / 200`;
          counter.className = len > 180
            ? 'text-[11px] text-red-500 font-medium'
            : 'text-[11px] text-gray-400';
        }
      }

function submitBroadcastForm(event) {
        event.preventDefault();
        const input = document.getElementById('broadcast-message-input');
        const btn = document.getElementById('broadcast-send-btn');
        if (!input || !btn) return;

        const message = input.value.trim();
        if (!message) return;
        const token = Auth.getToken();
        if (!token) return;

        // Disable button during request
        btn.disabled = true;
        const originalBtnHtml = btn.innerHTML;
        btn.innerHTML = '<i aria-hidden="true" class="fa-solid fa-spinner animate-spin mr-1"></i> იგზავნება...';

        fetch('/api/broadcast', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + token
          },
          body: JSON.stringify({ message: message })
        })
          .then(res => {
            if (!res.ok) throw new Error('Failed to send broadcast');
            return res.json();
          })
          .then(data => {
            input.value = '';
            updateBroadcastCounter();
            if (typeof showToast === 'function') {
              showToast('ტრანსლაცია ✅', 'საგანგებო განცხადება წარმატებით გაიგზავნა ყველა თანამშრომელს!');
            }
            // Cooldown: disable for 10 seconds
            const cooldownLabel = document.getElementById('broadcast-cooldown-label');
            let remaining = 10;
            btn.innerHTML = `<i aria-hidden="true" class="fa-solid fa-clock mr-1"></i> ${remaining}წმ`;
            if (cooldownLabel) {
              cooldownLabel.classList.remove('hidden');
              cooldownLabel.textContent = 'მოიცადეთ ხელახალ გაგზავნამდე...';
            }
            const timer = setInterval(() => {
              remaining--;
              if (remaining <= 0) {
                clearInterval(timer);
                btn.disabled = false;
                btn.innerHTML = originalBtnHtml;
                if (cooldownLabel) cooldownLabel.classList.add('hidden');
              } else {
                btn.innerHTML = `<i aria-hidden="true" class="fa-solid fa-clock mr-1"></i> ${remaining}წმ`;
              }
            }, 1000);
          })
          .catch(err => {
            console.error('Broadcast error:', err);
            btn.disabled = false;
            btn.innerHTML = originalBtnHtml;
            if (typeof showToast === 'function') {
              showToast('შეცდომა ❌', 'განცხადების გაგზავნა ვერ მოხერხდა: ' + err.message);
            }
          });
      }

function searchHistoryItemClick(term) {
        navTo('page-dashboard');
        setTimeout(() => {
          quickSearch(term);
        }, 100);
      }

function clearSearchHistoryUI() {
        const tbody = document.getElementById('search-history-table-body');
        if (tbody) {
          tbody.innerHTML = `
            <tr>
              <td colspan="3" class="px-6 py-4 text-center text-gray-400">ისტორია ცარიელია</td>
            </tr>
          `;
        }
      }

function startProfileEdit() {
        if (document.getElementById('profile-name-input')) return;
        const nameEl = document.getElementById('profile-name');
        const posEl = document.getElementById('profile-position');
        const name = nameEl.textContent;
        const position = posEl.textContent === '—' ? '' : posEl.textContent;
        nameEl.innerHTML = `<input id="profile-name-input" value="${name}" class="w-full rounded-lg border border-gray-300 px-3 py-1.5 text-xl font-bold text-gray-800 focus:border-[#E30613] focus:outline-none">`;
        posEl.innerHTML = `<input id="profile-position-input" value="${position}" placeholder="პოზიცია" class="rounded-lg border border-gray-300 px-3 py-1 text-sm text-gray-800 focus:border-[#E30613] focus:outline-none">`;
        const btn = document.getElementById('profile-edit-btn');
        btn.textContent = 'შენახვა';
        btn.onclick = saveProfileEdit;
      }

async function saveProfileEdit() {
        const token = localStorage.getItem('magti_token');
        const name = document.getElementById('profile-name-input').value.trim();
        const position = document.getElementById('profile-position-input').value.trim();
        if (!token || !name) { alert('სახელი სავალდებულოა.'); return; }
        try {
          const response = await fetch('/api/users/me', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ name, position: position || null })
          });
          if (!response.ok) throw new Error('Profile update failed');
          const user = await response.json();
          window.currentUser = user;
          updateUserInfo(user);
          const btn = document.getElementById('profile-edit-btn');
          btn.textContent = 'პროფილის რედაქტირება';
          btn.onclick = startProfileEdit;
        } catch (error) {
          console.error(error);
          alert('პროფილის განახლება ვერ მოხერხდა.');
        }
      }

async function toggleUserStatus(userId, isActive) {
        const action = isActive ? 'გააქტიურება' : 'გათიშვა';
        if (!confirm(`ნამდვილად გსურთ მომხმარებლის ${action}?`)) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        try {
          const response = await fetch(`/api/users/${userId}/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ is_active: isActive })
          });
          if (!response.ok) throw new Error('Status update failed');
          fetchAndRenderUsers(token);
          fetchKPIs(token);
        } catch (error) {
          console.error(error);
          alert('სტატუსის შეცვლა ვერ მოხერხდა.');
        }
      }

function updatePermissionsUI(role, selectedPerms) {
        const defaults = {
          operator: [],
          manager: ['compliance:manage', 'reports:export'],
          content_admin: ['content:editor', 'content:publisher', 'compliance:manage', 'reports:view_global', 'reports:export', 'communication:broadcast', 'system:audit'],
          admin: ['users:manage', 'content:editor', 'content:publisher', 'compliance:manage', 'reports:view_global', 'reports:export', 'reports:export_sensitive', 'communication:broadcast', 'system:audit']
        };
        const rolePerms = defaults[role] || [];
        document.querySelectorAll('.user-perm-checkbox').forEach(cb => {
          const val = cb.value;
          const isInherited = rolePerms.includes(val);
          if (isInherited) {
            cb.checked = true;
            cb.disabled = true;
          } else {
            cb.disabled = false;
            if (selectedPerms) {
              cb.checked = selectedPerms.includes(val);
            } else {
              cb.checked = false;
            }
          }
          const label = cb.closest('label');
          if (label) {
            const indicator = label.querySelector('.role-inherited-indicator');
            if (indicator) {
              if (isInherited) {
                indicator.classList.remove('hidden');
              } else {
                indicator.classList.add('hidden');
              }
            }
          }
        });
      }

function openUserEditModal(userId) {
        const user = (window.adminUsersData || []).find(u => u.id === userId);
        if (!user) return;
        document.getElementById('edit-user-id').value = user.id;
        document.getElementById('edit-user-role').value = user.role;
        document.getElementById('edit-user-department').value = user.department || 'All';
        document.getElementById('edit-user-position').value = user.position || '';

        // Reset and check granular permissions checkboxes based on user permissions
        const userPerms = user.permissions || [];
        updatePermissionsUI(user.role, userPerms);

        const modal = document.getElementById('user-edit-modal');
        modal.classList.remove('hidden');
        modal.classList.add('flex');
      }

function onEditUserRoleChange(role) {
        updatePermissionsUI(role, null);
      }

function closeUserEditModal() {
        const modal = document.getElementById('user-edit-modal');
        if (modal) { modal.classList.add('hidden'); modal.classList.remove('flex'); }
      }

async function submitUserEditForm(event) {
        event.preventDefault();
        const token = localStorage.getItem('magti_token');
        if (!token) return;
        const userId = document.getElementById('edit-user-id').value;
        const role = document.getElementById('edit-user-role').value;
        const department = document.getElementById('edit-user-department').value;
        const position = document.getElementById('edit-user-position').value;
        
        // Collect checked permissions
        const checkedPerms = [...document.querySelectorAll('.user-perm-checkbox:checked')].map(cb => cb.value);

        try {
          // 1. Update basic details
          const response = await fetch(`/api/users/${userId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ role, department: department === 'All' ? null : department, position: position || null })
          });
          if (!response.ok) throw new Error('User details update failed');

          // 2. Update granular permissions
          const permResponse = await fetch(`/api/users/${userId}/permissions`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify({ permissions: checkedPerms })
          });
          if (!permResponse.ok) throw new Error('User permissions update failed');

          showToast('მომხმარებელი განახლდა', '', { variant: 'success' });
          closeUserEditModal();
          fetchAndRenderUsers(token);
          // If the role console is the visible admin panel, keep it in sync too.
          const rolesPanel = document.getElementById('admin-roles');
          if (rolesPanel && !rolesPanel.classList.contains('hidden') && typeof renderRoleConsole === 'function') {
            renderRoleConsole(token);
          }
        } catch (error) {
          console.error(error);
          showToast('მომხმარებლის განახლება ვერ მოხერხდა', error.message, { variant: 'error' });
        }
      }

function focusCreateForm() {
        document.getElementById('admin-news-panel')?.classList.add('hidden');
        document.getElementById('admin-video-panel')?.classList.add('hidden');
        const panel = document.getElementById('admin-panel');
        if (!panel) return;
        panel.classList.remove('hidden');
        setTimeout(() => panel.classList.remove('translate-x-full'), 10);
        document.getElementById('admin-panel-backdrop')?.classList.remove('hidden');
        
        // Populate category dropdown
        populateArticleCategorySelect();
        
        // Reset autosave state
        initAutosave();
        
        const title = document.getElementById('article-title');
        if (title) title.focus({ preventScroll: true });

        if (typeof window.updateArticlePreview === 'function') {
          window.updateArticlePreview();
        }
        if (typeof window.setPreviewDevice === 'function') {
          window.setPreviewDevice('desktop');
        }
      }

function focusNewsForm() {
        document.getElementById('admin-panel')?.classList.add('hidden');
        document.getElementById('admin-video-panel')?.classList.add('hidden');
        const panel = document.getElementById('admin-news-panel');
        if (!panel) return;
        panel.classList.remove('hidden');
        panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        const title = document.getElementById('news-title');
        if (title) title.focus({ preventScroll: true });
      }

function focusVideoForm() {
        document.getElementById('admin-panel')?.classList.add('hidden');
        document.getElementById('admin-news-panel')?.classList.add('hidden');
        const panel = document.getElementById('admin-video-panel');
        if (!panel) return;
        panel.classList.remove('hidden');
        panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
        const title = document.getElementById('video-title');
        if (title) title.focus({ preventScroll: true });
      }

async function archiveSelected() {
        const checked = [...document.querySelectorAll('.archive-check:checked')];
        if (checked.length === 0) { showToast('მონიშნეთ მინიმუმ ერთი სტატია', '', { variant: 'error' }); return; }
        if (!confirm(`დაარქივდეს ${checked.length} სტატია? ისინი აღარ გამოჩნდება ოპერატორების ცოდნის ბაზაში.`)) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        try {
          for (const box of checked) {
            const id = parseInt(box.getAttribute('data-article-id'));
            const response = await fetch(`/api/articles/${id}/archive`, {
              method: 'POST',
              headers: { 'Authorization': `Bearer ${token}` }
            });
            if (!response.ok) throw new Error(`Failed to archive article ${id}`);
          }
          fetchAndRenderAdminContent(token);
          fetchAndRenderKnowledgeBase(token);
          if (typeof showToast === 'function') showToast('არქივი', `${checked.length} სტატია წარმატებით დაარქივდა.`);
        } catch (error) {
          console.error(error);
          showToast('დაარქივება ვერ მოხერხდა', error.message, { variant: 'error' });
        }
      }

async function unarchiveSelected() {
        const checked = [...document.querySelectorAll('.archive-check:checked')];
        if (checked.length === 0) { showToast('მონიშნეთ მინიმუმ ერთი სტატია', '', { variant: 'error' }); return; }
        if (!confirm(`ამოვიღოთ არქივიდან ${checked.length} სტატია?`)) return;
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        try {
          for (const box of checked) {
            const id = parseInt(box.getAttribute('data-article-id'));
            const response = await fetch(`/api/articles/${id}/unarchive`, {
              method: 'POST',
              headers: { 'Authorization': `Bearer ${token}` }
            });
            if (!response.ok) throw new Error(`Failed to unarchive article ${id}`);
          }
          fetchAndRenderAdminContent(token);
          fetchAndRenderKnowledgeBase(token);
          if (typeof showToast === 'function') showToast('არქივიდან ამოღება', `${checked.length} სტატია წარმატებით აღდგა.`);
        } catch (error) {
          console.error(error);
          showToast('არქივიდან ამოღება ვერ მოხერხდა', error.message, { variant: 'error' });
        }
      }

async function toggleArticleArchive(articleId, shouldArchive) {
        const token = localStorage.getItem('magti_token');
        if (!token) return;

        const actionText = shouldArchive ? 'დაარქივება' : 'არქივიდან ამოღება';
        const confirmMsg = shouldArchive 
          ? 'ნამდვილად გსურთ ამ სტატიის დაარქივება? ის აღარ გამოჩნდება ოპერატორებისთვის.' 
          : 'ნამდვილად გსურთ ამ სტატიის არქივიდან ამოღება?';

        if (!confirm(confirmMsg)) return;

        try {
          const url = `/api/articles/${articleId}/${shouldArchive ? 'archive' : 'unarchive'}`;
          const response = await fetch(url, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` }
          });
          if (!response.ok) throw new Error(`Failed to ${shouldArchive ? 'archive' : 'unarchive'} article ${articleId}`);
          
          showToast(shouldArchive ? 'არქივი' : 'არქივიდან ამოღება', `სტატია წარმატებით ${shouldArchive ? 'დაარქივდა' : 'აღდგა'}.`, { variant: 'success' });
          if (typeof fetchAndRenderAdminContent === 'function') fetchAndRenderAdminContent(token);
          if (typeof fetchAndRenderKnowledgeBase === 'function') fetchAndRenderKnowledgeBase(token);
        } catch (error) {
          console.error(error);
          showToast('შეცდომა', `სტატიის ${shouldArchive ? 'დაარქივება' : 'არქივიდან ამოღება'} ვერ მოხერხდა.`, { variant: 'error' });
        }
      }

function defaultProfileForDept(dept) {
        const d = (dept || '').toLowerCase();
        if (d.indexOf('ტექნიკ') >= 0 || d === 'support') return 'tech';
        if (d.indexOf('საინფორმაციო') >= 0 || d === 'informational') return 'info';
        return 'all';
      }

async function loadTaxonomyData(force) {
        if (window._taxonomyLoaded && !force) return;
        const token = Auth.getToken();
        if (!token) return;
        try {
          const [catRes, artRes] = await Promise.all([
            fetch('/api/categories', { headers: { Authorization: 'Bearer ' + token } }),
            fetch('/api/articles?limit=1000', { headers: { Authorization: 'Bearer ' + token } })
          ]);
          const cats = catRes.ok ? await catRes.json() : [];
          const arts = artRes.ok ? await artRes.json() : [];
          window.taxonomyCategories = cats;   // Include all categories to prevent empty routing resolution
          window._allArticlesCache = arts;
          window._taxonomyLoaded = true;
        } catch (e) { console.error('taxonomy load failed', e); }
      }

function setCategoryProfile(profile) {
        window._categoryProfile = profile;
        _applyProfilePillState();
        renderCategoryArticles();
      }

async function downloadExportXlsx() {
        const token = Auth.getToken();
        if (!token) return;
        try {
          const res = await fetch('/api/export/readings.xlsx', { headers: { Authorization: 'Bearer ' + token } });
          if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.detail || `XLSX ექსპორტი ჩავარდა (${res.status})`);
          }
          const blob = await res.blob();
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url; a.download = 'readings_export.xlsx';
          document.body.appendChild(a); a.click(); a.remove();
          URL.revokeObjectURL(url);
        } catch (e) {
          alert(e.message);
        }
      }

function toggleNotificationsPopover(event) {
        if (event) event.stopPropagation();
        const popover = document.getElementById('notifications-popover');
        if (popover) {
          const isHidden = popover.classList.contains('hidden');
          if (isHidden) {
            renderNotificationsPopoverList();
            popover.classList.remove('hidden');
            closeMessagesPopover();
          } else {
            popover.classList.add('hidden');
          }
        }
      }

function closeNotificationsPopover() {
        const popover = document.getElementById('notifications-popover');
        if (popover) popover.classList.add('hidden');
      }

function toggleMessagesPopover(event) {
        if (event) event.stopPropagation();
        const popover = document.getElementById('messages-popover');
        if (!popover) return;
        if (popover.classList.contains('hidden')) {
          renderMessagesPopoverList();
          popover.classList.remove('hidden');
          // If the bell popover is open, close it (we never want two open at once).
          closeNotificationsPopover();
        } else {
          popover.classList.add('hidden');
        }
      }

function closeMessagesPopover() {
        const popover = document.getElementById('messages-popover');
        if (popover) popover.classList.add('hidden');
      }

function openCategoryCreateForm() {
        document.getElementById('cat-editing-id').value = '';
        document.getElementById('cat-name').value = '';
        document.getElementById('cat-slug').value = '';
        document.getElementById('cat-icon').value = '';
        document.getElementById('cat-parent').value = '';
        document.getElementById('cat-pastel-color').value = 'general';
        document.getElementById('cat-icon-preview').className = 'fa-solid fa-layer-group text-gray-400';
        document.getElementById('admin-category-create-panel').classList.remove('hidden');
      }

function closeCategoryCreateForm() {
        document.getElementById('admin-category-create-panel').classList.add('hidden');
      }

function editCategory(id, name, parentId, slug, icon, pastelColor) {
        document.getElementById('cat-editing-id').value = id;
        document.getElementById('cat-name').value = name;
        document.getElementById('cat-slug').value = slug || '';
        document.getElementById('cat-icon').value = icon || '';
        document.getElementById('cat-parent').value = parentId || '';
        document.getElementById('cat-pastel-color').value = pastelColor || 'general';
        document.getElementById('cat-icon-preview').className = 'fa-solid ' + (icon || 'fa-layer-group') + ' text-gray-500';
        document.getElementById('admin-category-create-panel').classList.remove('hidden');
      }

async function submitCategoryForm(event) {
        event.preventDefault();
        const token = Auth.getToken(); if (!token) return;
        const editingId = document.getElementById('cat-editing-id').value;
        const slugVal = document.getElementById('cat-slug').value.trim();
        const iconVal = document.getElementById('cat-icon').value.trim();
        const payload = {
          name: document.getElementById('cat-name').value.trim(),
          parent_id: document.getElementById('cat-parent').value ? parseInt(document.getElementById('cat-parent').value, 10) : null,
          slug: slugVal || null,
          icon: iconVal || null,
          pastel_color_class: document.getElementById('cat-pastel-color').value || 'general',
          is_active: true
        };
        const url = editingId ? `/api/categories/${editingId}` : '/api/categories';
        try {
          const res = await fetch(url, {
            method: editingId ? 'PUT' : 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            body: JSON.stringify(payload),
          });
          if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || 'ვერ შენახულდა'); }
          closeCategoryCreateForm();
          fetchAndRenderCategoriesAdmin(token);
          if (typeof fetchKbCategories === 'function') fetchKbCategories(token);
          window._taxonomyLoaded = false;
        } catch (e) { alert(e.message); }
      }

async function deleteCategory(id) {
        if (!confirm('წავშალო ეს კატეგორია? დაკავშირებული სტატიების კატეგორიის ID გახდება null.')) return;
        const token = Auth.getToken(); if (!token) return;
        try {
          const res = await fetch(`/api/categories/${id}`, { method: 'DELETE', headers: { Authorization: 'Bearer ' + token } });
          if (!res.ok && res.status !== 204) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || 'წაშლა ვერ მოხერხდა'); }
          fetchAndRenderCategoriesAdmin(token);
          if (typeof fetchKbCategories === 'function') fetchKbCategories(token);
          window._taxonomyLoaded = false;
        } catch (e) { alert(e.message); }
      }

function openUserCreateForm() {
        document.getElementById('admin-user-create-panel').classList.remove('hidden');
        document.getElementById('cu-name').focus();
      }

function closeUserCreateForm() {
        document.getElementById('admin-user-create-form').reset();
        document.getElementById('admin-user-create-panel').classList.add('hidden');
      }

async function submitCreateUserForm(event) {
        event.preventDefault();
        const token = Auth.getToken(); if (!token) return;
        const payload = {
          name: document.getElementById('cu-name').value.trim(),
          email: document.getElementById('cu-email').value.trim(),
          department: document.getElementById('cu-department').value.trim() || null,
          position: document.getElementById('cu-position').value.trim() || null,
          role: document.getElementById('cu-role').value,
          password: document.getElementById('cu-password').value,
        };
        try {
          const res = await fetch('/api/users', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            body: JSON.stringify(payload),
          });
          if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || 'მომხმარებლის შექმნა ვერ მოხერხდა'); }
          closeUserCreateForm();
          if (typeof fetchAndRenderUsers === 'function') fetchAndRenderUsers(token);
        } catch (e) { alert(e.message); }
      }

async function submitChangePassword(event) {
        event.preventDefault();
        const token = Auth.getToken(); if (!token) return;
        const cur = document.getElementById('pw-current').value;
        const nw = document.getElementById('pw-new').value;
        const cfm = document.getElementById('pw-confirm').value;
        if (nw !== cfm) { alert('ახალი პაროლი და გამეორება არ ემთხვევა.'); return; }
        try {
          const res = await fetch('/api/users/me/password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            body: JSON.stringify({ current_password: cur, new_password: nw }),
          });
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data.detail || 'პაროლი ვერ შეიცვალა');
          alert(data.detail || 'პაროლი წარმატებით შეიცვალა.');
          event.target.reset();
        } catch (e) { alert(e.message); }
      }

async function setFeedbackStatus(feedbackId, newStatus) {
        const token = Auth.getToken(); if (!token) return;
        try {
          const res = await fetch(`/api/admin/feedback/${feedbackId}/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            body: JSON.stringify({ status: newStatus }),
          });
          if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.detail || 'სტატუსი ვერ შეიცვალა'); }
          if (typeof fetchAndRenderAdminFeedback === 'function') fetchAndRenderAdminFeedback(token);
        } catch (e) { alert(e.message); }
      }

function initCharts() {
        if (window._chartsInit) return; // Prevent canvas re-initialization errors

        const activityData = window.activityStats || [];
        const hasActivity = activityData.some(d => d.count > 0);
        const activityCanvas = document.getElementById('activityChart');
        const activityEmpty = document.getElementById('activityChart-empty');
        if (activityCanvas && activityEmpty) {
          if (hasActivity) {
            activityCanvas.classList.remove('hidden');
            activityEmpty.classList.add('hidden');
            new Chart(activityCanvas, {
              type: 'line',
              data: {
                labels: activityData.map(d => d.date),
                datasets: [{
                  data: activityData.map(d => d.count),
                  borderColor: '#E30613',
                  backgroundColor: 'rgba(227, 6, 19, 0.08)',
                  fill: true,
                  tension: 0.3,
                  pointRadius: 3,
                }]
              },
              options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }
            });
          } else {
            activityCanvas.classList.add('hidden');
            activityEmpty.classList.remove('hidden');
          }
        }

        const statsData = window.dashboardStats;

        const readVal = statsData ? statsData.read_percentage : 31;
        const unreadVal = statsData ? statsData.unread_percentage : 69;

        new Chart(document.getElementById('readingChart'), {
          type: 'doughnut',
          data: {
            labels: [`წაკითხული (${readVal}%)`, `წაუკითხავი (${unreadVal}%)`],
            datasets: [{ data: [readVal, unreadVal], backgroundColor: ['#22c55e', '#3b82f6'], borderWidth: 0 }]
          },
          options: { responsive: true, plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, font: { size: 11 } } } } }
        });

        let top5Labels = [];
        let top5Data = [];

        if (statsData && statsData.top_articles && statsData.top_articles.length > 0) {
          top5Labels = statsData.top_articles.map(a => a.title);
          // Use read_count if the backend provides it, otherwise fallback to descending visual bars
          top5Data = statsData.top_articles.map((a, i) => a.read_count || Math.max(95 - (i * 15), 10));
        } else {
          top5Labels = ['მონაცემები არ არის'];
          top5Data = [0];
        }

        new Chart(document.getElementById('top5Chart'), {
          type: 'bar',
          data: {
            labels: top5Labels,
            datasets: [{ data: top5Data, backgroundColor: '#E30613', borderRadius: 4, barThickness: 18 }]
          },
          options: { indexAxis: 'y', responsive: true, plugins: { legend: { display: false } }, scales: { x: { max: 100, ticks: { display: false }, grid: { display: false } }, y: { ticks: { font: { size: 11 } } } } }
        });

        window._chartsInit = true;
      }

function showMagtiAiComingSoon() {
        if (typeof showToast === 'function') {
          showToast('Magti AI', 'მალე იქნება!', { variant: 'info' });
        } else {
          alert('Magti AI — მალე იქნება!');
        }
      }


function toggleAiDrawer() {
        const overlay = document.getElementById('magti-ai-overlay');
        const drawer = document.getElementById('magti-ai-drawer');
        if (!overlay || !drawer) return;

        const isOpen = !drawer.classList.contains('translate-x-full');
        if (isOpen) {
          drawer.classList.add('translate-x-full');
          overlay.classList.add('opacity-0', 'pointer-events-none');
          overlay.classList.remove('opacity-100', 'pointer-events-auto');
        } else {
          drawer.classList.remove('translate-x-full');
          overlay.classList.remove('opacity-0', 'pointer-events-none');
          overlay.classList.add('opacity-100', 'pointer-events-auto');

          setTimeout(() => {
            const input = document.getElementById('magti-ai-input');
            if (input) input.focus();
          }, 150);
        }
      }

function appendAiChatMessage(sender, text) {
        const container = document.getElementById('magti-ai-messages');
        if (!container) return;

        const isUser = sender === 'user';
        const avatar = isUser
          ? `<div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-600 text-[10px] font-bold">შენ</div>`
          : `<div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#E30613] text-white text-[10px] font-bold">AI</div>`;

        const bubbleBg = isUser
          ? 'bg-slate-800 text-white'
          : 'bg-white text-gray-800 border border-gray-100/50 shadow-sm';

        const bubbleRadius = isUser ? 'rounded-tr-none' : 'rounded-tl-none';

        const html = `
          <div class="flex gap-3 ${isUser ? 'flex-row-reverse' : ''}">
            ${avatar}
            <div class="rounded-2xl ${bubbleRadius} ${bubbleBg} p-3.5 max-w-[85%] text-xs leading-relaxed break-words">
              ${text}
            </div>
          </div>
        `;

        container.insertAdjacentHTML('beforeend', html);
        container.scrollTop = container.scrollHeight;
      }

function searchArticlesForAi(query) {
        if (!query) return [];
        const terms = query.toLowerCase().split(/\s+/).filter(t => t.length > 1);
        if (terms.length === 0) return [];

        const articles = Store.articles || [];
        const matches = [];

        articles.forEach(art => {
          let score = 0;
          const title = (art.title || '').toLowerCase();
          const content = (art.content || '').toLowerCase();
          const category = (art.category || '').toLowerCase();

          terms.forEach(term => {
            if (title.includes(term)) score += 10;
            if (category.includes(term)) score += 5;
            if (content.includes(term)) score += 1;
          });

          if (score > 0) {
            matches.push({ article: art, score: score });
          }
        });

        matches.sort((a, b) => b.score - a.score);
        return matches.slice(0, 3).map(m => m.article);
      }

function handleAiChatSubmit(event) {
        event.preventDefault();
        const input = document.getElementById('magti-ai-input');
        if (!input) return;

        const text = input.value.trim();
        if (!text) return;

        input.value = '';
        appendAiChatMessage('user', escapeHtml(text));

        setTimeout(() => {
          const matched = searchArticlesForAi(text);
          let responseText = '';

          if (matched.length > 0) {
            responseText = 'ვიპოვე შესაბამისი ინფორმაცია ჩვენს ცოდნის ბაზაში:<br><br>';
            matched.forEach(art => {
              responseText += `
                <div class="mb-2.5 last:mb-0">
                  <a href="#" onclick="event.preventDefault(); openArticleModalById(${art.id});" class="text-xs font-bold text-[#E30613] hover:underline flex items-center gap-1">
                    <i aria-hidden="true" class="fa-solid fa-file-lines shrink-0"></i> ${escapeHtml(art.title)}
                  </a>
                  <p class="mt-0.5 text-[10px] text-gray-500 line-clamp-2">${escapeHtml(art.content ? art.content.substring(0, 100) + '...' : '')}</p>
                </div>
              `;
            });
          } else {
            responseText = 'სამწუხაროდ, ამ თემაზე ინფორმაცია ვერ მოვიძიე. სცადეთ სხვა სიტყვებით ძებნა (მაგალითად: "როუმინგი", "IPTV", "მობილური").';
          }

          appendAiChatMessage('ai', responseText);
        }, 600);
      }

function toggleUserKebab(userId, event) {
        event.stopPropagation();

        document.querySelectorAll('[id^="user-kebab-"]').forEach(menu => {
          if (menu.id !== `user-kebab-${userId}`) {
            menu.classList.add('hidden');
          }
        });

        const menu = document.getElementById(`user-kebab-${userId}`);
        if (menu) {
          menu.classList.toggle('hidden');
        }
      }

async function triggerKebabAction(userId, action, event) {
        if (event) {
          event.stopPropagation();
        }

        const menu = document.getElementById(`user-kebab-${userId}`);
        if (menu) {
          menu.classList.add('hidden');
        }

        if (action === 'nudge') {
          const btn = event ? event.target.closest('button') : null;
          await nudgeUser(userId, btn);
        } else if (action === 'progress') {
          const user = (window.userProgressData || []).find(u => u.user_id === userId);
          if (user) {
            showToast('პროგრესი 📊', `${user.user_name} (${user.department}): წაკითხულია ${user.read_count} / ${user.required_count} (${user.percentage}%)`);
          }
        } else if (action === 'message') {
          let draft = '';
          const user = (window.userProgressData || []).find(u => u.user_id === userId);
          if (user) {
             const missing = user.required_count - user.read_count;
             if (missing > 0) {
                 const activeTitle = window._activeCategory ? window._activeCategory.name : (document.getElementById('article-view-title') ? document.getElementById('article-view-title').textContent : 'სავალდებულო მასალა');
                 draft = `გამარჯობა ${user.user_name}, გთხოვ გაეცნო ${activeTitle}-ს სიახლეებს, რომელიც შენი გუნდისთვის პრიორიტეტულია.`;
             } else {
                 draft = `მადლობა ${user.user_name}, თქვენი ყველა სავალდებულო მასალა წაკითხულია.`;
             }
          }
          const text = prompt('შეიყვანეთ შეტყობინების ტექსტი:', draft);
          if (!text || !text.trim()) return;
          const token = localStorage.getItem('magti_token');
          if (!token) return;
          try {
            const response = await fetch('/api/messages', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`
              },
              body: JSON.stringify({ user_id: userId, content: text.trim() })
            });
            if (!response.ok) throw new Error('Failed to send message');
            showToast('შეტყობინება 💬', 'შეტყობინება წარმატებით გაიგზავნა!');
          } catch (error) {
            console.error(error);
            alert('შეტყობინების გაგზავნა ვერ მოხერხდა.');
          }
        }
      }


      // Navigation page switch logic
      /**
       * TASK 1 — open the full-screen standalone article view (#page-article-view).
       * Remembers the section the operator came from so "უკან დაბრუნება" returns
       * there, fetches the full article (cache-first), and renders it at page
       * width via the shared .article-content-optimized engine.
       */
      

      /** Return from the standalone view to the previously active section. */
      

      // ──────────────────────────────────────────────────────────────────
      // TASK 2 — KB taxonomy grid + context-aware category view.
      // ──────────────────────────────────────────────────────────────────

      /** Default audience profile inferred from the operator's department. */
      

      /** Fetch (once) the taxonomy categories + full article list used by the
       *  dashboard grid counts and the category view. Cached on window. */
      

      /** Render the 5×2 category card grid on the dashboard. */
      

      /** Open the standalone category view for a slug. */
      

      /** Toggle the audience-profile filter within the category view. */
      

      

      /** Render the active category's article cards, filtered by profile. */
      

      

      

      // Item 9: back/forward navigation honours the hash. We guard against
      // recursive updates by only acting when the hash actually changed.
      

      // Profile Tabs switch logic
      

      

      // ══════════════════════════════════════════════════════════════════════
      // [SPEC-COMPLETION] Helpers added 2026 for: categories admin, audit log,
      // user create, feedback resolve, XLSX export, password change. Each block
      // calls only the existing API client (Auth.getToken + fetch) — no new
      // global state, no test-mode bypass.
      // ══════════════════════════════════════════════════════════════════════

      // ── XLSX export ────────────────────────────────────────────────────
      

      // ── Notifications Popover ──────────────────────────────────────────
      
      
      

      // ── Messages Popover (envelope) — Item 1: behaves as a relative dropdown,
      // never navigates away from the current page. ───────────────────────
      

      

      

      // Close popovers when clicking outside their button + box. One handler
      // covers both popovers so they don't ever stay stuck open.
      document.addEventListener('click', function(event) {
        const notif = document.getElementById('notifications-popover');
        const bellBtn = document.getElementById('bell-button');
        if (notif && !notif.classList.contains('hidden')) {
          if (!notif.contains(event.target) && (!bellBtn || !bellBtn.contains(event.target))) {
            closeNotificationsPopover();
          }
        }
        const msgs = document.getElementById('messages-popover');
        const envelopeBtn = document.getElementById('envelope-button');
        if (msgs && !msgs.classList.contains('hidden')) {
          if (!msgs.contains(event.target) && (!envelopeBtn || !envelopeBtn.contains(event.target))) {
            closeMessagesPopover();
          }
        }
        const avatarPopover = document.getElementById('user-avatar-popover');
        const avatarBtn = document.getElementById('user-avatar-button');
        if (avatarPopover && !avatarPopover.classList.contains('hidden')) {
          if (!avatarPopover.contains(event.target) && (!avatarBtn || !avatarBtn.contains(event.target))) {
            avatarPopover.classList.add('hidden');
          }
        }
        const sidebarPopover = document.getElementById('sidebar-user-popover');
        const sidebarBtn = document.getElementById('sidebar-user-button');
        if (sidebarPopover && !sidebarPopover.classList.contains('hidden')) {
          if (!sidebarPopover.contains(event.target) && (!sidebarBtn || !sidebarBtn.contains(event.target))) {
            sidebarPopover.classList.add('hidden');
          }
        }
      });

      

      // ── Admin: Categories CRUD ─────────────────────────────────────────
      

      
      
      
      
      

      // ── Admin: Audit log ───────────────────────────────────────────────
      

      // ── Admin: Create user ─────────────────────────────────────────────
      
      
      

      // ── Self: change own password ──────────────────────────────────────
      

      // ── Admin: Feedback resolve / reject ──────────────────────────────
      

      

      // Item 7: Magti AI is not active yet — show a toast instead of opening the drawer.
      // Kept as a tiny shim so the rest of the codebase (keyboard shortcuts, deep
      // links into toggleAiDrawer) keeps working when the feature does ship.
      

      // Magti AI Chat Assistant Drawer
      

      

      

      

      // User Kebab Dropdown Management
      

      

      document.addEventListener('click', function (e) {
        document.querySelectorAll('[id^="user-kebab-"]').forEach(menu => {
          menu.classList.add('hidden');
        });
      });

    

/* --- Magti AI Chat Immediate Click Interceptor --- */

    (function () {
      function isAiTrigger(target) {
        if (!target || target.nodeType !== 1) return false;
        // closest() walks up from text nodes / inner <i>/<span> children too.
        return !!(target.closest && target.closest('#magti-ai-btn, [data-magti-ai-trigger="1"]'));
      }
      function showComingSoon() {
        // Force the drawer + overlay shut in case anything else opened them.
        var drawer = document.getElementById('magti-ai-drawer');
        var overlay = document.getElementById('magti-ai-overlay');
        if (drawer) drawer.classList.add('translate-x-full');
        if (overlay) {
          overlay.classList.add('opacity-0', 'pointer-events-none');
          overlay.classList.remove('opacity-100', 'pointer-events-auto');
        }
        if (typeof window.showMagtiAiComingSoon === 'function') {
          window.showMagtiAiComingSoon();
        } else if (typeof window.showToast === 'function') {
          window.showToast('Magti AI', 'მალე იქნება!', { variant: 'info' });
        } else {
          // Last-ditch fallback: native alert so a user always gets feedback.
          window.alert('Magti AI — მალე იქნება!');
        }
      }
      // Capture phase on the document — guaranteed to run before any bubble-
      // phase listener attached later, on the button or any of its parents.
      document.addEventListener('click', function (e) {
        if (!isAiTrigger(e.target)) return;
        e.preventDefault();
        e.stopPropagation();
        if (typeof e.stopImmediatePropagation === 'function') {
          e.stopImmediatePropagation();
        }
        showComingSoon();
        return false;
      }, true);
      // Also intercept keyboard activation (Enter / Space) for accessibility.
      document.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        if (!isAiTrigger(e.target)) return;
        e.preventDefault();
        e.stopPropagation();
        if (typeof e.stopImmediatePropagation === 'function') {
          e.stopImmediatePropagation();
        }
        showComingSoon();
      }, true);
      // Expose so other call sites (sidebar deep links, etc.) can share it.
      window.showMagtiAiComingSoon = window.showMagtiAiComingSoon || showComingSoon;
    })();
  

/* --- Highlight to Note Interceptor --- */

        document.addEventListener('selectionchange', () => {
          const selection = window.getSelection();
          let tooltip = document.getElementById('selection-note-tooltip');
          
          if (!selection || selection.isCollapsed || selection.toString().trim() === '') {
            if (tooltip) tooltip.classList.add('hidden');
            return;
          }

          if (selection.rangeCount === 0) return;
          const range = selection.getRangeAt(0);
          const container = range.commonAncestorContainer;
          
          const articleContent = document.getElementById('article-modal-content');
          if (articleContent && articleContent.contains(container.nodeType === 1 ? container : container.parentNode)) {
            if (!tooltip) {
              tooltip = document.createElement('button');
              tooltip.id = 'selection-note-tooltip';
              tooltip.className = 'fixed z-[200] hidden items-center gap-1.5 rounded-lg bg-gray-900 px-3 py-2 text-xs font-bold text-white shadow-xl hover:bg-gray-800 transition-colors';
              tooltip.innerHTML = '<i class="fa-solid fa-pen-clip text-yellow-400"></i> <span>ჩანაწერებში დამატება</span>';
              tooltip.onmousedown = (e) => { e.preventDefault(); }; // Selection-ის გაქრობის პრევენცია დაკლიკებისას
              tooltip.onclick = () => {
                const text = window.getSelection().toString().trim();
                const noteArea = document.getElementById('article-modal-note');
                if (noteArea && text) {
                  noteArea.value = noteArea.value ? noteArea.value + '\n\n' + text : text;
                  if (typeof saveArticleNote === 'function') saveArticleNote();
                  if (typeof showToast === 'function') showToast('ჩანაწერი', 'ტექსტი დაემატა პირად ჩანაწერებში', {variant: 'success'});
                }
                window.getSelection().removeAllRanges();
                tooltip.classList.add('hidden');
              };
              document.body.appendChild(tooltip);
            }
            
            const rect = range.getBoundingClientRect();
            tooltip.style.top = `${rect.top - 40}px`;
            tooltip.style.left = `${rect.left + (rect.width / 2) - 60}px`;
            tooltip.classList.remove('hidden');
            tooltip.classList.add('flex');
          } else {
            if (tooltip) tooltip.classList.add('hidden');
          }
        });

/* --- Manager Stats Binding Interceptor --- */

        // Item 5 (QA regression fix): all listeners are bound via addEventListener
        // at runtime against the live DOM nodes. This guarantees:
        //   • Enter key triggers a fetch (was missing — Cometa flagged this).
        //   • Typing triggers a 300ms-debounced fetch.
        //   • The search button triggers an immediate fetch.
        //   • The department <select> change triggers a fetch.
        //   • The clear button resets BOTH inputs and re-fetches.
        // The IIFE uses requestAnimationFrame so binding happens after the
        // surrounding template has been mounted and after fetchAndRenderManagerStats
        // (defined in frontend_api.js) is in scope.
        (function () {
          function getToken() {
            return (window.Auth && typeof window.Auth.getToken === 'function')
              ? window.Auth.getToken()
              : localStorage.getItem('magti_token');
          }
          function runFetch() {
            if (typeof window.fetchAndRenderManagerStats !== 'function') {
              console.warn('fetchAndRenderManagerStats not yet defined');
              return;
            }
            window.fetchAndRenderManagerStats(getToken());
          }
          var _timer = null;
          function debounced() {
            clearTimeout(_timer);
            _timer = setTimeout(runFetch, 300);
          }
          function bind() {
            var nameInput = document.getElementById('team-stats-name-filter');
            var deptSel   = document.getElementById('team-stats-dept-filter');
            var searchBtn = document.getElementById('team-stats-search-btn');
            var clearBtn  = document.getElementById('team-stats-clear-btn');
            if (!nameInput || !searchBtn || !clearBtn) {
              // Page not yet mounted (e.g. user hasn't visited page-manager).
              // Try again on next frame. The handler is idempotent thanks to dataset.bound.
              return false;
            }
            if (nameInput.dataset.bound === '1') return true;
            nameInput.dataset.bound = '1';

            // EVERY user input path gets a fetch:
            // 1) typing — 300ms debounced so we don't spam the API
            nameInput.addEventListener('input', debounced);
            // 2) Enter key — immediate, debounce flushed
            nameInput.addEventListener('keydown', function (e) {
              if (e.key === 'Enter') {
                e.preventDefault();
                clearTimeout(_timer);
                runFetch();
              }
            });
            // 3) Loss of focus — immediate (e.g. Tab away)
            nameInput.addEventListener('change', runFetch);
            // 4) Department dropdown change
            if (deptSel) deptSel.addEventListener('change', runFetch);
            // 5) Search button click — immediate
            searchBtn.addEventListener('click', function (e) {
              e.preventDefault();
              clearTimeout(_timer);
              runFetch();
            });
            // 6) Clear button — reset both inputs then re-fetch
            clearBtn.addEventListener('click', function (e) {
              e.preventDefault();
              nameInput.value = '';
              if (deptSel) deptSel.value = '';
              clearTimeout(_timer);
              runFetch();
            });
            return true;
          }
          // Try immediately, then again on DOMContentLoaded for safety.
          if (!bind()) {
            document.addEventListener('DOMContentLoaded', function tryAgain () {
              if (bind()) document.removeEventListener('DOMContentLoaded', tryAgain);
            });
            // And keep trying on idle for the case where the script runs before
            // its own template was attached.
            var ticks = 0;
            var iv = setInterval(function () {
              if (bind() || ++ticks > 40) clearInterval(iv); // give up after ~4s
            }, 100);
          }

          // Back-compat: expose the old debounced wrapper in case any other
          // call site still references it (e.g. legacy inline onclicks).
          window.debouncedTeamStatsFetch = debounced;
        })();
      

/* --- Accessibility Icon Observers --- */

    (function () {
      function hideIcons(root) {
        if (!root || !root.querySelectorAll) return;
        root.querySelectorAll('i[class*="fa-"]:not([aria-hidden])').forEach(function (i) {
          i.setAttribute('aria-hidden', 'true');
        });
      }
      function start() {
        hideIcons(document.body);
        new MutationObserver(function (muts) {
          for (var m = 0; m < muts.length; m++) {
            var added = muts[m].addedNodes;
            for (var a = 0; a < added.length; a++) {
              var n = added[a];
              if (n.nodeType !== 1) continue;
              if (n.matches && n.matches('i[class*="fa-"]') && !n.hasAttribute('aria-hidden')) {
                n.setAttribute('aria-hidden', 'true');
              }
              hideIcons(n);
            }
          }
        }).observe(document.body, { childList: true, subtree: true });
      }
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
      else start();
    })();
  


// Export to global window scope for backwards compatibility
window.Auth = Auth;
window.Store = Store;
window.api = api;
window.appendAiChatMessage = appendAiChatMessage;
window.appendComplianceConfirmButton = appendComplianceConfirmButton;
window.applyRBAC = applyRBAC;
window.applySettings = applySettings;
window.applyStoredSettings = applyStoredSettings;
window.archiveSelected = archiveSelected;
window.cancelEdit = cancelEdit;
window.cancelNewsEdit = cancelNewsEdit;
window.cancelVideoEdit = cancelVideoEdit;
window.clearObj = clearObj;
window.clearSearchHistoryUI = clearSearchHistoryUI;
window.closeArticleModal = closeArticleModal;
window.closeCategoryCreateForm = closeCategoryCreateForm;
window.closeHistoryModal = closeHistoryModal;
window.closeMessagesPopover = closeMessagesPopover;
window.closeNewsDetailModal = closeNewsDetailModal;
window.closeNotificationsPopover = closeNotificationsPopover;
window.closeReadingModal = closeReadingModal;
window.closeUserCreateForm = closeUserCreateForm;
window.closeUserEditModal = closeUserEditModal;
window.closeVideoModal = closeVideoModal;
window.copyArticleBody = copyArticleBody;
window.copyFirstScript = copyFirstScript;
window.copyScript = copyScript;
window.defaultProfileForDept = defaultProfileForDept;
window.deleteArticle = deleteArticle;
window.deleteCategory = deleteCategory;
window.deleteMessage = deleteMessage;
window.deleteNews = deleteNews;
window.downloadExport = downloadExport;
window.downloadExportXlsx = downloadExportXlsx;
window.editArticle = editArticle;
window.editCategory = editCategory;
window.editNews = editNews;
window.ensureArticleModal = ensureArticleModal;
window.ensureHistoryModal = ensureHistoryModal;
window.exitEditMode = exitEditMode;
window.exitNewsEditMode = exitNewsEditMode;
window.filterReadings = filterReadings;
window.filterVideos = filterVideos;
window.focusCreateForm = focusCreateForm;
window.focusNewsForm = focusNewsForm;
window.focusVideoForm = focusVideoForm;
window.getPinnedArticles = getPinnedArticles;
window.handleAiChatSubmit = handleAiChatSubmit;
window.handleArticleFileUpload = handleArticleFileUpload;
window.handleLiveEvent = handleLiveEvent;
window.handleNewsFileUpload = handleNewsFileUpload;
window.handleSessionExpiry = handleSessionExpiry;
window.handleVideoUpload = handleVideoUpload;
window.initCharts = initCharts;
window.loadTaxonomyData = loadTaxonomyData;
window.logoutUser = logoutUser;
window.markAsRead = markAsRead;
window.markMessageRead = markMessageRead;
window.mountQuickLinks = mountQuickLinks;
window.nudgeUser = nudgeUser;
window.openAndMarkRead = openAndMarkRead;
window.openArticleModal = openArticleModal;
window.openArticleModalById = openArticleModalById;
window.openCategoryCreateForm = openCategoryCreateForm;
window.openMessagesTab = openMessagesTab;
window.openNewsDetailModal = openNewsDetailModal;
window.openReadingItem = openReadingItem;
window.openReadingModal = openReadingModal;
window.openUserCreateForm = openUserCreateForm;
window.openUserEditModal = openUserEditModal;
window.onEditUserRoleChange = onEditUserRoleChange;
window.populateArticleCategorySelect = populateArticleCategorySelect;
window.quickSearch = quickSearch;
window.removeCurrentAttachment = removeCurrentAttachment;
window.removeFavorite = removeFavorite;
window.removeNewsAttachment = removeNewsAttachment;
window.restoreArticleVersion = restoreArticleVersion;
window.restoreNewsVersion = restoreNewsVersion;
window.runKbSearch = runKbSearch;
window.runNewsFilter = runNewsFilter;
window.saveArticleNote = saveArticleNote;
window.savePinnedArticles = savePinnedArticles;
window.saveProfileEdit = saveProfileEdit;
window.searchArticlesForAi = searchArticlesForAi;
window.searchHistoryItemClick = searchHistoryItemClick;
window.selectKbCategoryByName = selectKbCategoryByName;
window.setCategoryProfile = setCategoryProfile;
window.setDockMode = setDockMode;
window.setFeedbackStatus = setFeedbackStatus;
window.setupGlobalSearch = setupGlobalSearch;
window.setupKbSearch = setupKbSearch;
window.showMagtiAiComingSoon = showMagtiAiComingSoon;
window.showToast = showToast;
window.startEventStream = startEventStream;
window.startProfileEdit = startProfileEdit;
window.submitArticleForm = submitArticleForm;
window.submitBroadcastForm = submitBroadcastForm;
window.submitCategoryForm = submitCategoryForm;
window.submitChangePassword = submitChangePassword;
window.submitCreateUserForm = submitCreateUserForm;
window.submitNewsForm = submitNewsForm;
window.submitUserEditForm = submitUserEditForm;
window.submitVideoForm = submitVideoForm;
window.switchContentTab = switchContentTab;
window.syncMandatoryFor = syncMandatoryFor;
window.toggleAdminSubmenu = toggleAdminSubmenu;
window.toggleArticleArchive = toggleArticleArchive;
window.toggleAiDrawer = toggleAiDrawer;
window.toggleDarkMode = toggleDarkMode;
window.toggleHeaderTheme = toggleHeaderTheme;
window.syncHeaderThemeIcon = syncHeaderThemeIcon;
window.toggleDueDate = toggleDueDate;
window.toggleFavorite = toggleFavorite;
window.toggleMessagesPopover = toggleMessagesPopover;
window.toggleModalTheme = toggleModalTheme;
window.toggleNewsDueDate = toggleNewsDueDate;
window.toggleNewsFavFilter = toggleNewsFavFilter;
window.toggleNotificationsPopover = toggleNotificationsPopover;
window.togglePinArticleFromModal = togglePinArticleFromModal;
window.toggleMinimizeArticleModal = toggleMinimizeArticleModal;
window.togglePinnedDock = togglePinnedDock;
window.toggleScheduledDate = toggleScheduledDate;
window.toggleSidebar = toggleSidebar;
window.toggleUserKebab = toggleUserKebab;
window.toggleUserStatus = toggleUserStatus;
window.toggleVideoDueDate = toggleVideoDueDate;
window.triggerKebabAction = triggerKebabAction;
window.unarchiveSelected = unarchiveSelected;
window.unpinArticle = unpinArticle;
window.updateBentoActiveState = updateBentoActiveState;
window.updateBroadcastCounter = updateBroadcastCounter;
window.updateNewsAttachmentChip = updateNewsAttachmentChip;
window.updatePinButtonState = updatePinButtonState;
window.updateStarIcons = updateStarIcons;
window.updateUserInfo = updateUserInfo;
window.verifyArticleFromModal = verifyArticleFromModal;
window.verifyStaleArticle = verifyStaleArticle;
window.viewArticleHistory = viewArticleHistory;
window.viewNewsHistory = viewNewsHistory;
window.viewVideo = viewVideo;
window.wireQuickLinks = wireQuickLinks;

// Initialize global namespace
window.MagtiPortal = window.MagtiPortal || {};

// ToastNotification Class implementation
class ToastNotification {
  constructor() {
    this.hostId = 'toast-host';
  }
  
  _getHost() {
    let host = document.getElementById(this.hostId);
    if (!host) {
      host = document.createElement('div');
      host.id = this.hostId;
      host.className = 'fixed bottom-5 right-5 z-[200] flex flex-col gap-2.5';
      document.body.appendChild(host);
    }
    return host;
  }
  
  show(title, message, options = {}) {
    const host = this._getHost();
    const variant = options.variant || 'info';
    const onClick = options.onClick || null;
    const duration = options.duration || 6000;

    const variantStyles = {
      success: { box: 'bg-emerald-50 text-emerald-700 border-emerald-100', icon: 'fa-circle-check' },
      error:   { box: 'bg-red-50 text-red-700 border-red-100', icon: 'fa-triangle-exclamation' },
      info:    { box: 'bg-blue-50 text-blue-700 border-blue-100', icon: 'fa-bell' },
      warning: { box: 'bg-amber-50 text-amber-700 border-amber-100', icon: 'fa-triangle-exclamation' }
    };
    
    const v = variantStyles[variant] || variantStyles.info;
    const card = document.createElement('div');
    card.className = 'flex w-80 cursor-pointer items-start gap-3 rounded-xl border border-gray-150 bg-white p-4 shadow-lg ring-1 ring-black/5 transition-all duration-300';
    card.innerHTML = `
      <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${v.box} border">
        <i aria-hidden="true" class="fa-solid ${v.icon}"></i>
      </div>
      <div class="flex-1 overflow-hidden">
        <p class="toast-title text-[13px] font-brand font-bold text-gray-800"></p>
        <p class="toast-sub truncate text-xs text-gray-500"></p>
      </div>
    `;
    
    card.querySelector('.toast-title').textContent = title;
    card.querySelector('.toast-sub').textContent = message || '';
    card.onclick = () => {
      if (onClick) onClick();
      card.remove();
    };
    
    host.appendChild(card);
    
    setTimeout(() => {
      card.classList.add('opacity-0', 'translate-y-2');
      setTimeout(() => card.remove(), 300);
    }, duration);
  }
  
  success(title, message, options = {}) {
    this.show(title, message, { ...options, variant: 'success' });
  }
  
  error(title, message, options = {}) {
    this.show(title, message, { ...options, variant: 'error' });
  }
  
  info(title, message, options = {}) {
    this.show(title, message, { ...options, variant: 'info' });
  }
  
  warning(title, message, options = {}) {
    this.show(title, message, { ...options, variant: 'warning' });
  }
}

// Instantiate and expose the Toast utility
window.MagtiPortal.Toast = new ToastNotification();

// Global Double-Submit Button Disable Guard
document.addEventListener('submit', function (e) {
  const form = e.target;
  if (form.tagName !== 'FORM') return;
  if (form.dataset.submitting === 'true') {
    e.preventDefault();
    return;
  }
  
  const submitBtn = form.querySelector('button[type="submit"], input[type="submit"]');
  if (submitBtn) {
    form.dataset.submitting = 'true';
    submitBtn.disabled = true;
    submitBtn.classList.add('bg-slate-100', 'text-slate-400', 'opacity-50', 'cursor-not-allowed');
  }
  
  form.resetSubmitGuard = function () {
    delete form.dataset.submitting;
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.classList.remove('bg-slate-100', 'text-slate-400', 'opacity-50', 'cursor-not-allowed');
    }
  };
});

// Live Preview / Responsive Device Simulation
window.setPreviewDevice = function(device) {
  const container = document.getElementById('preview-frame-container');
  const desktopBtn = document.getElementById('preview-desktop-btn');
  const mobileBtn = document.getElementById('preview-mobile-btn');
  if (!container) return;

  if (device === 'mobile') {
    container.classList.remove('w-full');
    container.classList.add('w-[375px]');
    if (mobileBtn) {
      mobileBtn.classList.add('bg-white', 'text-gray-800', 'shadow-sm');
      mobileBtn.classList.remove('text-gray-600', 'hover:text-gray-800');
    }
    if (desktopBtn) {
      desktopBtn.classList.remove('bg-white', 'text-gray-800', 'shadow-sm');
      desktopBtn.classList.add('text-gray-600', 'hover:text-gray-800');
    }
  } else {
    container.classList.remove('w-[375px]');
    container.classList.add('w-full');
    if (desktopBtn) {
      desktopBtn.classList.add('bg-white', 'text-gray-800', 'shadow-sm');
      desktopBtn.classList.remove('text-gray-600', 'hover:text-gray-800');
    }
    if (mobileBtn) {
      mobileBtn.classList.remove('bg-white', 'text-gray-800', 'shadow-sm');
      mobileBtn.classList.add('text-gray-600', 'hover:text-gray-800');
    }
  }
};

window.updateArticlePreview = function() {
  const iframe = document.getElementById('article-preview-iframe');
  if (!iframe) return;
  const doc = iframe.contentDocument || iframe.contentWindow.document;
  if (!doc) return;

  const title = document.getElementById('article-title')?.value || 'სათაური';
  const bodyContent = window.articleQuill ? window.articleQuill.root.innerHTML : '';

  const titleEl = doc.getElementById('preview-title');
  const bodyEl = doc.getElementById('preview-body');

  if (titleEl && bodyEl) {
    titleEl.textContent = title;
    bodyEl.innerHTML = bodyContent;
  } else {
    doc.open();
    doc.write(`
      <!DOCTYPE html>
      <html>
        <head>
          <meta charset="utf-8">
          <script src="https://cdn.tailwindcss.com"></script>
          <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css" crossorigin="anonymous" />
          <link href="https://fonts.googleapis.com/css2?family=Noto+Sans+Georgian:wght@400;500;600;700&family=Teko:wght@500;600&display=swap" rel="stylesheet">
          <script>
            tailwind.config = {
              theme: {
                extend: {
                  colors: {
                    magti: "#E30613",
                  },
                },
              },
            };
          </script>
          <style>
            body {
              font-family: "Noto Sans Georgian", system-ui, -apple-system, sans-serif;
              background: white;
              padding: 24px;
              margin: 0;
            }
            h1, h2, h3, h4 {
              font-family: 'Teko', 'Noto Sans Georgian', sans-serif;
              letter-spacing: 0.05em;
            }
            .prose-content h1 {
              font-size: 1.8rem;
              font-weight: 700;
              margin-top: 1.5rem;
              margin-bottom: 0.5rem;
              color: #111827;
              border-bottom: 1px solid #e5e7eb;
              padding-bottom: 0.25rem;
            }
            .prose-content h2 {
              font-size: 1.5rem;
              font-weight: 600;
              margin-top: 1.25rem;
              margin-bottom: 0.5rem;
              color: #1f2937;
            }
            .prose-content h3 {
              font-size: 1.25rem;
              font-weight: 600;
              margin-top: 1rem;
              margin-bottom: 0.5rem;
              color: #374151;
            }
            .prose-content p {
              margin-bottom: 1rem;
              line-height: 1.7;
              color: #4b5563;
            }
            .prose-content ul {
              list-style-type: disc;
              padding-left: 1.5rem;
              margin-bottom: 1rem;
            }
            .prose-content ol {
              list-style-type: decimal;
              padding-left: 1.5rem;
              margin-bottom: 1rem;
            }
            .prose-content li {
              margin-bottom: 0.25rem;
            }
            .prose-content img {
              max-width: 100%;
              height: auto;
              display: block;
              margin: 1.5rem 0;
              border-radius: 0.75rem;
            }
          </style>
        </head>
        <body class="prose-content">
          <h1 class="text-3xl font-extrabold text-gray-900 mb-6 leading-tight" id="preview-title"></h1>
          <div id="preview-body"></div>
        </body>
      </html>
    `);
    doc.close();

    const titleElNew = doc.getElementById('preview-title');
    const bodyElNew = doc.getElementById('preview-body');
    if (titleElNew) titleElNew.textContent = title;
    if (bodyElNew) bodyElNew.innerHTML = bodyContent;
  }
};
