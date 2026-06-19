/* ════════════════════════════════════════════════════
   Magti Portal - Dynamic HTML Renderers
   Handles category grids, bento cards, news widgets, and template parsing
   ════════════════════════════════════════════════════ */

function renderNews(newsItems) {
        // Recent-news indicators: stat card + sidebar pill count items from the last 7 days
        const recentCount = newsItems.filter(n => (new Date() - new Date(n.created_at)) < 7 * 24 * 60 * 60 * 1000).length;
        const statNews = document.getElementById('stat-recent-news');
        if (statNews) statNews.textContent = recentCount;
        const newsBadge = document.getElementById('sidebar-news-badge');
        if (newsBadge) {
          newsBadge.textContent = recentCount;
          newsBadge.classList.toggle('hidden', recentCount === 0);
          newsBadge.classList.toggle('inline-flex', recentCount > 0);
        }

        const container = document.getElementById('latest-news-container');
        if (!container) return;

        container.innerHTML = ''; // Clear static content

        if (newsItems.length === 0) {
          container.innerHTML = '<p class="text-sm text-gray-500">სიახლეები არ მოიძებნა.</p>';
          return;
        }

        newsItems.slice(0, 3).forEach(item => {
          const date = formatDate(item.created_at);
          const safeTitle = item.title.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
          const newsHtml = `
                    <li onclick="openNewsDetailModal(${item.id})" 
                        onkeydown="if(event.key==='Enter'||event.key===' '){openNewsDetailModal(${item.id});event.preventDefault();}" 
                        tabindex="0" 
                        role="button" 
                        title="${safeTitle}"
                        class="flex cursor-pointer items-center justify-between rounded-xl bg-white p-4 shadow-sm transition-all hover:border-[#E30613]/30 hover:shadow-md border border-transparent focus:outline-none focus:ring-2 focus:ring-[#E30613]">
                      <div class="flex min-w-0 items-center gap-3">
                        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-50 text-gray-400">
                          <i aria-hidden="true" class="fa-solid fa-file-lines"></i>
                        </div>
                        <div class="min-w-0">
                          <h4 class="text-[13.5px] font-semibold leading-snug text-gray-800 line-clamp-2" title="${safeTitle}">${safeTitle}</h4>
                          <p class="mt-1 text-[11px] text-gray-400">${date}</p>
                        </div>
                      </div>
                    </li>`;
          container.insertAdjacentHTML('beforeend', newsHtml);
        });
      }

function renderSearchResults(results, container, query) {
        container.innerHTML = '';
        const total = results.articles.length + results.news.length + results.videos.length;

        if (total === 0) {
          container.innerHTML = '<div class="p-3 text-sm text-gray-500 text-center">შედეგი ვერ მოიძებნა</div>';
          container.classList.remove('hidden');
          return;
        }

        const sections = [
          { label: 'სტატია', icon: 'fa-file-lines', items: results.articles, onClick: item => { openArticleModalById(item.id); } },
          { label: 'სიახლე', icon: 'fa-bullhorn', items: results.news, onClick: item => { window.cachedNewsItems = window.cachedNewsItems || {}; window.cachedNewsItems[item.id] = item; openNewsDetailModal(item.id); } },
          { label: 'ვიდეო', icon: 'fa-circle-play', items: results.videos, onClick: item => { window.kbVideos = window.kbVideos || {}; window.kbVideos[item.id] = item; viewVideo(item.id); } }
        ];

        function highlight(text) {
          if (!query || !text) return text;
          const safeText = String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
          const safeQuery = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
          return safeText.replace(new RegExp(`(${safeQuery})`, 'gi'), '<mark class="bg-yellow-200 text-gray-900 rounded px-0.5">$1</mark>');
        }

        // [P0-4] Wider snippet (~140 chars) clamped on word boundaries.
        function makeSnippet(content, q) {
          if (!content) return '';
          const lower = content.toLowerCase();
          const needle = (q || '').trim().toLowerCase();
          if (!needle) return content.length > 140 ? content.slice(0, 140) + '…' : content;
          const idx = lower.indexOf(needle);
          if (idx === -1) return content.length > 140 ? content.slice(0, 140) + '…' : content;
          const rawStart = Math.max(0, idx - 80);
          const rawEnd = Math.min(content.length, idx + needle.length + 100);
          // Snap to word boundaries when we trimmed mid-word.
          const start = rawStart > 0 ? content.indexOf(' ', rawStart) + 1 || rawStart : 0;
          const end = rawEnd < content.length ? (content.lastIndexOf(' ', rawEnd) || rawEnd) : content.length;
          return (start > 0 ? '…' : '') + content.slice(start, end).replace(/\s+/g, ' ') + (end < content.length ? '…' : '');
        }

        sections.forEach(section => {
          if (section.items.length === 0) return;

          const header = document.createElement('p');
          header.className = 'px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-widest text-gray-400';
          header.textContent = section.label;
          container.appendChild(header);

          const ul = document.createElement('ul');
          ul.className = 'flex flex-col gap-1';

          section.items.forEach(item => {
            const li = document.createElement('li');
            li.className = 'cursor-pointer rounded-lg p-3 transition-colors hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-[#E30613]';
            li.tabIndex = 0;
            li.setAttribute('role', 'button');
            li.setAttribute('data-search-item', '1'); // marker for keyboard nav
            const clickHandler = () => { container.classList.add('hidden'); section.onClick(item); };
            li.onclick = clickHandler;
            li.onkeydown = (ev) => {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                clickHandler();
              }
            };

            // [Defect B] Defensive category resolution — the global-search payload
            // serializes ArticleSummaryResponse / NewsSummaryResponse /
            // VideoInstructionResponse, none of which expose a `category` name
            // field. Resolve via the shared Store.categories map (id -> name)
            // when available; otherwise fall back to target_department or '' so
            // we never render the literal string "undefined".
            const categoryName = (typeof Store !== 'undefined' && Store.categories && item.category_id != null)
              ? (Store.categories[item.category_id] || '')
              : (item.category || '');

            const snippet = item.content ? makeSnippet(item.content, query) : (categoryName || item.target_department || '');

            li.innerHTML = `
              <div class="flex items-start gap-3">
                <div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-red-50 text-[#E30613]">
                  <i aria-hidden="true" class="fa-solid ${section.icon} text-sm"></i>
                </div>
                <div class="flex-1 overflow-hidden">
                  <div class="flex items-center gap-2">
                    <span class="rounded bg-gray-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-gray-600">${section.label}</span>
                    ${categoryName ? `<span class="truncate text-[10px] text-gray-400">${escapeHtml(categoryName)}</span>` : ''}
                  </div>
                  <h4 class="mt-0.5 truncate text-sm font-semibold text-gray-900">${highlight(item.title)}</h4>
                  <p class="mt-0.5 line-clamp-2 text-xs text-gray-500">${highlight(snippet)}</p>
                </div>
                <kbd class="hidden md:inline-block shrink-0 self-center rounded border border-gray-200 bg-gray-50 px-1.5 py-0.5 text-[10px] text-gray-400">↵</kbd>
              </div>
            `;
            ul.appendChild(li);
          });
          container.appendChild(ul);
        });

        container.classList.remove('hidden');

        // ── [P0-2] Keyboard nav: Arrow/Enter/Escape on the input only. ─────────────
        //     We intercept ONLY those four keys so regular typing is untouched.
        const input = document.getElementById('global-search-input');
        if (!input) return;
        const items = container.querySelectorAll('[data-search-item]');
        let activeIdx = -1;
        function setActive(i) {
          items.forEach(el => el.classList.remove('bg-red-50', 'ring-2', 'ring-[#E30613]'));
          if (i >= 0 && i < items.length) {
            activeIdx = i;
            const el = items[i];
            el.classList.add('bg-red-50', 'ring-2', 'ring-[#E30613]');
            el.scrollIntoView({ block: 'nearest' });
          }
        }
        // Replace any prior keyboard handler for this dropdown lifecycle.
        input.onkeydown = (e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActive(Math.min(activeIdx + 1, items.length - 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive(Math.max(activeIdx - 1, 0));
          } else if (e.key === 'Enter') {
            if (activeIdx >= 0 && items[activeIdx]) {
              e.preventDefault();
              items[activeIdx].click();
            }
          } else if (e.key === 'Escape') {
            container.classList.add('hidden');
            input.blur();
          }
          // All other keys (typing, backspace, etc.) flow through untouched.
        };
      }

function renderFilteredReadings(filter) {
        const container = document.getElementById('readings-list');
        if (!container) return;

        const unreadStatuses = ['unread', 'overdue'];
        const filtered = window.myReadings.filter(item => {
          if (filter === 'all') return true;
          if (filter === 'read') return item.status === 'read';
          if (filter === 'unread') return unreadStatuses.includes(item.status) || item.is_overdue;
          return false;
        });

        container.innerHTML = '';
        if (filtered.length === 0) {
          container.innerHTML = '<p class="text-gray-500">სია ცარიელია.</p>';
          return;
        }

        filtered.forEach(item => {
          const { reading, status, is_overdue, item_title } = item;
          const dueDate = new Date(reading.due_date).toLocaleDateString('ka-GE');

          let statusBadge = '';
          let actionBtn = '';

          if (status === 'read') {
            statusBadge = '<span class="rounded-full bg-green-100 px-3 py-1 text-xs font-bold text-green-700">წაკითხულია</span>';
            // [Fix] Use the type-aware dispatcher so already-read articles open with full content too.
            actionBtn = `<button onclick="openReadingItem(${reading.id})" class="rounded-full border border-gray-300 bg-white px-4 py-1 text-xs font-bold text-gray-600 transition-colors hover:bg-gray-50">გახსნა</button>`;
          } else {
            if (status === 'overdue' || is_overdue) {
              statusBadge = '<span class="rounded-full bg-red-100 px-3 py-1 text-xs font-bold text-[#E30613]">ვადაგადაცილებული</span>';
            } else {
              statusBadge = '<span class="rounded-full bg-gray-100 px-3 py-1 text-xs font-bold text-gray-600">წასაკითხი</span>';
            }
            actionBtn = `<button onclick="openAndMarkRead(${reading.id})" class="rounded-full border border-[#E30613] px-4 py-1 text-xs font-bold text-[#E30613] transition-colors hover:bg-red-50">გახსნა და წაკითხვა</button>`;
          }

          const html = `
            <div class="flex items-center justify-between rounded-xl border border-gray-100 bg-white p-4 shadow-sm transition-colors hover:border-gray-200">
              <div class="flex items-center gap-4">
                <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-50 text-gray-400">
                  <i aria-hidden="true" class="fa-solid fa-file-lines"></i>
                </div>
                <div>
                  <h4 class="font-bold text-gray-800">${item_title || `მასალა #${reading.item_id}`}</h4>
                  <p class="text-xs text-gray-500 mt-1">ვადა: ${dueDate}</p>
                </div>
              </div>
              <div class="flex items-center gap-4">
                ${statusBadge}
                ${actionBtn}
                <button onclick="toggleFavorite('${reading.item_type}', ${reading.item_id}, this)" data-fav-type="${reading.item_type}" data-fav-id="${reading.item_id}" class="text-xl text-gray-300 transition-all hover:scale-110 hover:text-yellow-400"><i aria-hidden="true" class="fa-regular fa-star"></i></button>
              </div>
            </div>
          `;
          container.insertAdjacentHTML('beforeend', html);
        });
        updateStarIcons();
      }

function renderProgressRows() {
        const tbody = document.getElementById('user-progress-tbody');
        if (!tbody) return;

        const filter = document.getElementById('progress-dept-filter');
        const dept = filter ? filter.value : '';
        const data = (window.userProgressData || []).filter(u => !dept || u.department === dept);

        tbody.innerHTML = '';
        if (data.length === 0) {
          tbody.innerHTML = '<tr><td colspan="5" class="py-4 text-center text-gray-500">მონაცემები არ არის</td></tr>';
          return;
        }

        data.forEach(user => {
          let rowClass = '';
          let readColor = 'text-gray-400';
          let reqColor = 'text-gray-400';
          let percColor = 'text-gray-400';

          const perc = parseInt(user.percentage);
          if (user.required_count > 0) {
            if (perc >= 80) rowClass = 'bg-green-50/50';
            else if (perc <= 30) rowClass = 'bg-red-50/40';
            readColor = 'text-green-600 font-semibold';
            reqColor = 'text-[#E30613] font-semibold';
            percColor = 'text-gray-800 font-bold';
          }

          const readText = user.required_count > 0 ? user.read_count : '—';
          const reqText = user.required_count > 0 ? user.required_count : '—';
          const percText = user.required_count > 0 ? user.percentage + '%' : '—';

          const isSelf = window.currentUser && user.user_id === window.currentUser.id;
          const showNudge = !(isSelf || user.required_count === 0 || perc === 100);

          const nudgeOptionClass = showNudge
            ? 'flex items-center gap-2 w-full px-4 py-2 text-xs text-gray-700 hover:bg-gray-50 transition-colors'
            : 'flex items-center gap-2 w-full px-4 py-2 text-xs text-gray-400 cursor-not-allowed opacity-50';

          const nudgeOptionAttr = showNudge
            ? `onclick="triggerKebabAction(${user.user_id}, 'nudge', event)"`
            : 'disabled';

          const nudgeBtn = `
            <div class="relative inline-block text-left">
              <button onclick="toggleUserKebab(${user.user_id}, event)" class="flex items-center justify-center h-8 w-8 rounded-full text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none transition-colors" aria-label="მოქმედებები">
                <i aria-hidden="true" class="fa-solid fa-ellipsis-vertical"></i>
              </button>
              <div id="user-kebab-${user.user_id}" class="hidden absolute right-0 mt-1 w-48 rounded-lg bg-white border border-gray-100 shadow-lg py-1 z-50 text-left">
                <button ${nudgeOptionAttr} class="${nudgeOptionClass}">
                  <i aria-hidden="true" class="fa-solid fa-bell text-[#E30613]"></i> ნუჯის გაგზავნა
                </button>
                <button onclick="triggerKebabAction(${user.user_id}, 'progress', event)" class="flex items-center gap-2 w-full px-4 py-2 text-xs text-gray-700 hover:bg-gray-50 transition-colors">
                  <i aria-hidden="true" class="fa-solid fa-chart-line text-blue-500"></i> პროგრესის ნახვა
                </button>
                <button onclick="triggerKebabAction(${user.user_id}, 'message', event)" class="flex items-center gap-2 w-full px-4 py-2 text-xs text-gray-700 hover:bg-gray-50 transition-colors">
                  <i aria-hidden="true" class="fa-solid fa-paper-plane text-emerald-500"></i> შეტყობინება
                </button>
              </div>
            </div>
          `;

          const tr = `
              <tr class="${rowClass}">
                <td class="py-2 pr-4 font-medium text-gray-800">${user.user_name}</td>
                <td class="py-2 pr-4 text-center ${readColor}">${readText}</td>
                <td class="py-2 pr-4 text-center ${reqColor}">${reqText}</td>
                <td class="py-2 pr-4 text-center ${percColor}">${percText}</td>
                <td class="py-2 text-center overflow-visible relative">${nudgeBtn}</td>
              </tr>
            `;
          tbody.insertAdjacentHTML('beforeend', tr);
        });
      }

function getCategoryIcon(categoryId, titleText) {
        const categoryName = (Store.categories[categoryId] || '').toLowerCase();
        const title = (titleText || '').toLowerCase();

        // High-intuition, pixel-distinct icon per Magti service domain.
        // No two domains share a glyph so operators scan the grid at a glance.
        if (categoryName.includes('როუმინგ') || title.includes('როუმინგ') || title.includes('roaming')) return 'fa-plane-up';            // ✈️  Roaming / abroad
        if (categoryName.includes('ინტერნეტ') || title.includes('ინტერნეტ') || title.includes('ბოჭკოვ') || title.includes('fiber') || title.includes('isp')) return 'fa-tower-cell';  // 📡 Fiber / ISP
        if (title.includes('wifi') || title.includes('ვაი') || title.includes('wi-fi')) return 'fa-wifi';                                // 📶 Wi-Fi config
        if (categoryName.includes('iptv') || categoryName.includes('ტელევიზ') || title.includes('iptv') || title.includes('ტელევიზ') || title.includes('არხებ') || title.includes('set-top')) return 'fa-tv';  // 📺 IPTV
        if (title.includes('სიჩქარ') || title.includes('ტესტ') || title.includes('speed') || title.includes('speedtest')) return 'fa-gauge-high';  // ⚡ Speed test
        if (categoryName.includes('მობილურ') || title.includes('მობილურ') || title.includes('სიმ ბარათ') || title.includes('sim') || title.includes('ტელეფონ')) return 'fa-mobile-screen-button';  // 📱 Mobile
        if (title.includes('პორტირებ') || title.includes('პორტ') || title.includes('mnp')) return 'fa-arrow-right-arrow-left';           // 🔁 Porting
        if (categoryName.includes('ბილინგ') || title.includes('ბილინგ') || title.includes('გადახდ') || title.includes('დავალიან') || title.includes('ფინანს') || title.includes('ინვოის')) return 'fa-file-invoice-dollar';  // 🧾 Billing
        if (categoryName.includes('ლოიალობ') || title.includes('ლოიალობ') || title.includes('ქულებ') || title.includes('აქცია') || title.includes('საჩუქ') || title.includes('შეთავაზ') || title.includes('bonus')) return 'fa-gift';  // 🎁 Loyalty
        if (title.includes('უსაფრთხ') || title.includes('პოლიტიკ') || title.includes('დაცვა') || title.includes('vpn') || title.includes('2fa') || title.includes('პაროლ')) return 'fa-shield-halved';  // 🛡️ Security
        if (categoryName.includes('ტექნიკურ') || title.includes('ტექნიკურ') || title.includes('ინსტრუქცი') || title.includes('კონფიგ') || title.includes('პარამეტრ') || title.includes('router')) return 'fa-screwdriver-wrench';  // 🔧 Tech support
        if (title.includes('კომპიუტერ') || title.includes('pc') || title.includes('ლეპტოპ')) return 'fa-laptop-code';                  // 💻 PC/Client setup
        if (title.includes('ხმა') || title.includes('აუდიო') || title.includes('voip')) return 'fa-volume-high';                        // 🔊 Voice / VoIP
        if (title.includes('მართვა') || title.includes('cabinet') || title.includes('self-service') || title.includes('პროფილ')) return 'fa-sliders';  // 🎛️ Self-service cabinet
        if (title.includes('დომენ') || title.includes('რეგისტრაც') || title.includes('hosting') || title.includes('სერვერ')) return 'fa-server';  // 🗄️ Hosting / Domains
        if (title.includes('email') || title.includes('მეილ') || title.includes('ფოსტა')) return 'fa-envelope-open-text';                // ✉️ Email
        if (categoryName.includes('ციფრულ') || title.includes('ციფრულ') || title.includes('digital') || title.includes('app') || title.includes('apk')) return 'fa-mobile-button';  // 📲 Digital / App
        if (categoryName.includes('სერვის') || title.includes('მოწვევა') || title.includes('ოსტატ') || title.includes('technician')) return 'fa-helmet-safety';  // 👷 Field service / technician
        if (title.includes('ახალ') || title.includes('news') || title.includes('განახლებ')) return 'fa-bullhorn';                       // 📣 News / Announcements

        return 'fa-folder-tree'; // Sleek multi-folder fallback (distinct from any domain glyph)
      }

function getCategoryCardStyles(catName) {
        const name = (catName || '').toLowerCase();
        if (name.includes('როუმინგ')) return { borderHover: 'hover:border-blue-200', borderAccent: 'border-l-4 border-l-blue-500', iconBg: 'bg-blue-50 text-blue-600', textAccent: 'group-hover:text-blue-700' };
        if (name.includes('ინტერნეტ') || name.includes('isp')) return { borderHover: 'hover:border-emerald-200', borderAccent: 'border-l-4 border-l-emerald-500', iconBg: 'bg-emerald-50 text-emerald-600', textAccent: 'group-hover:text-emerald-700' };
        if (name.includes('iptv') || name.includes('ტელევიზ')) return { borderHover: 'hover:border-purple-200', borderAccent: 'border-l-4 border-l-purple-500', iconBg: 'bg-purple-50 text-purple-600', textAccent: 'group-hover:text-purple-700' };
        if (name.includes('ტექნიკურ') || name.includes('მხარდაჭერ')) return { borderHover: 'hover:border-orange-200', borderAccent: 'border-l-4 border-l-orange-500', iconBg: 'bg-orange-50 text-orange-600', textAccent: 'group-hover:text-orange-700' };
        if (name.includes('ბილინგ') || name.includes('გადახდ')) return { borderHover: 'hover:border-teal-200', borderAccent: 'border-l-4 border-l-teal-500', iconBg: 'bg-teal-50 text-teal-600', textAccent: 'group-hover:text-teal-700' };
        if (name.includes('ლოიალობ') || name.includes('ქულებ')) return { borderHover: 'hover:border-pink-200', borderAccent: 'border-l-4 border-l-pink-500', iconBg: 'bg-pink-50 text-pink-600', textAccent: 'group-hover:text-pink-700' };
        if (name.includes('მობილურ') || name.includes('სიმ')) return { borderHover: 'hover:border-cyan-200', borderAccent: 'border-l-4 border-l-cyan-500', iconBg: 'bg-cyan-50 text-cyan-600', textAccent: 'group-hover:text-cyan-700' };
        return { borderHover: 'hover:border-[#E30613]/40', borderAccent: '', iconBg: 'bg-gray-50 text-gray-500', textAccent: 'group-hover:text-black' };
      }

function renderNewsList(items) {
        const container = document.getElementById('news-page-container');
        if (!container) return;

        container.innerHTML = '';
        if (items.length === 0) {
          container.innerHTML = '<p class="text-sm text-gray-500 py-8 text-center col-span-full">სიახლეები არ მოიძებნა.</p>';
          return;
        }

        items.forEach(item => {
          const date = new Date(item.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
          const safeTitle = item.title.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");

          // Department badge color mapping
          let deptBadgeColor = 'bg-gray-100 text-gray-600';
          let deptBadgeText = 'საერთო';
          if (item.target_department === 'Support') {
            deptBadgeColor = 'bg-blue-50 text-blue-600 border border-blue-100';
            deptBadgeText = 'ტექნიკური';
          } else if (item.target_department === 'Informational') {
            deptBadgeColor = 'bg-emerald-50 text-emerald-600 border border-emerald-100';
            deptBadgeText = 'საინფორმაციო';
          } else if (item.target_department === 'All') {
            deptBadgeColor = 'bg-purple-50 text-purple-600 border border-purple-100';
            deptBadgeText = 'საერთო';
          }

          const html = `
            <div onclick="openNewsDetailModal(${item.id})" class="group flex cursor-pointer items-center justify-between rounded-xl border border-gray-100 bg-white p-4 shadow-sm transition-all hover:border-gray-200 hover:bg-gray-50 hover:shadow-md hover:translate-y-[-1px]">
              <div class="flex items-center gap-4 min-w-0 flex-1 mr-4">
                <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-50 text-[#E30613] group-hover:scale-105 transition-transform">
                  <i aria-hidden="true" class="fa-solid fa-bullhorn"></i>
                </div>
                <div class="min-w-0 flex-1">
                  <h4 class="font-bold text-gray-800 truncate" title="${safeTitle}">${safeTitle}</h4>
                  <div class="flex items-center gap-2 mt-1 flex-wrap">
                    <span class="inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${deptBadgeColor}">${deptBadgeText}</span>
                    <span class="text-[12px] text-gray-400"><i aria-hidden="true" class="fa-regular fa-clock mr-1"></i>${date}</span>
                  </div>
                </div>
              </div>
              <div class="flex items-center gap-4 shrink-0" onclick="event.stopPropagation()">
                <button onclick="toggleFavorite('news', ${item.id}, this)" data-fav-type="news" data-fav-id="${item.id}" class="text-xl text-gray-300 transition-all hover:scale-110 hover:text-yellow-400"><i aria-hidden="true" class="fa-regular fa-star"></i></button>
              </div>
            </div>
          `;
          container.insertAdjacentHTML('beforeend', html);
        });
        updateStarIcons();
      }

function renderArticleBody(targetEl, rawBody) {
        if (!targetEl) return;
        let formattedBody = rawBody || '';
        const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
        if (codeBlockRegex.test(formattedBody)) {
          formattedBody = formattedBody.replace(codeBlockRegex, (m, lang, code) =>
            `<pre class="my-4 overflow-x-auto rounded-xl bg-gray-950 p-4 font-mono text-sm text-green-400 border border-gray-800 shadow-inner"><code class="language-${lang}">${escapeHtml(code.trim())}</code></pre>`);
        }
        const inlineCodeRegex = /`([^`\n]+)`/g;
        formattedBody = formattedBody.replace(inlineCodeRegex, (m, code) =>
          `<code class="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs text-red-600 dark:bg-zinc-800 dark:text-red-400">${escapeHtml(code)}</code>`);

        const ALLOWED_TAGS = ['p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol',
                              'li', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'br', 'a',
                              'strong', 'b', 'em', 'i', 'img', 'pre', 'code', 'blockquote', 'hr'];
        const ALLOWED_ATTRS = ['href', 'src', 'alt', 'target', 'title', 'class', 'id', 'style'];
        if (/<(p|div|h[1-6]|ul|ol|li|table|br|a|strong|b|em|img|pre|code)\b/i.test(formattedBody)) {
          targetEl.innerHTML = (typeof DOMPurify !== 'undefined')
            ? DOMPurify.sanitize(formattedBody, { ALLOWED_TAGS, ALLOWED_ATTR: ALLOWED_ATTRS, KEEP_CONTENT: true })
            : formattedBody;
        } else {
          targetEl.innerHTML = escapeHtml(formattedBody).replace(/\n/g, '<br>');
        }
      }

function renderKbBento(topLevels) {
        const grid = document.getElementById('bento-categories-grid');
        if (!grid) return;
        if (!topLevels || topLevels.length === 0) {
          grid.innerHTML = '<p class="col-span-full text-sm text-gray-500">კატეგორიები ჯერ არ არის. ადმინისტრატორმა უნდა დაამატოს.</p>';
          return;
        }
        // Friendly icon + descriptor heuristic. Falls back to a folder icon.
        const meta = {
          'როუმინგი': { icon: 'fa-globe', desc: 'ტარიფები, პარტნიორები და აქტივაცია' },
          'ინტერნეტი': { icon: 'fa-wifi', desc: 'ოპტიკა, 4G/5G და კავშირის პარამეტრები' },
          'IPTV': { icon: 'fa-tv', desc: 'არხები, Set-Top Box და მართვა' },
          'ტექნიკური': { icon: 'fa-screwdriver-wrench', desc: 'ხარვეზები, კონფიგურაცია, დახმარება' },
          'ბილინგი': { icon: 'fa-receipt', desc: 'გადახდები, ანგარიშები, შეცდომები' },
          'ლოიალობა': { icon: 'fa-heart', desc: 'შეთავაზებები და აქციები' },
          'მობილური': { icon: 'fa-mobile-screen', desc: 'მობილური სერვისები და ნომრები' },
        };
        grid.innerHTML = topLevels.slice(0, 8).map(c => {
          const m = meta[c.name] || { icon: 'fa-folder-open', desc: 'სტატიები ამ კატეგორიაში' };
          const safeName = escapeHtml(c.name).replace(/'/g, "&#39;");
          return `
            <div onclick="selectKbCategoryByName('${safeName}')" data-bento-cat-id="${c.id}"
                 class="bento-cat-card group relative flex cursor-pointer flex-col justify-between overflow-hidden rounded-2xl border border-gray-200/60 bg-white p-5 shadow-sm transition-all duration-300 hover:border-[#E30613]/40 hover:shadow-lg hover:-translate-y-1 focus:outline-none focus:ring-2 focus:ring-[#E30613]">
              <div>
                <div class="mb-4 flex h-12 w-12 items-center justify-center rounded-xl bg-red-50 text-2xl text-[#E30613] transition-all group-hover:bg-red-100 group-hover:scale-110">
                  <i aria-hidden="true" class="fa-solid ${m.icon}"></i>
                </div>
                <h4 class="text-base font-bold text-gray-800 group-hover:text-[#E30613] transition-colors">${escapeHtml(c.name)}</h4>
                <p class="text-[11px] text-gray-400 dark:text-zinc-500 mt-1">${escapeHtml(m.desc)}</p>
              </div>
              <div class="mt-4 flex justify-end">
                <i aria-hidden="true" class="fa-solid fa-arrow-right text-gray-300 transition-transform group-hover:translate-x-1 group-hover:text-[#E30613]"></i>
              </div>
            </div>`;
        }).join('');
      }

function _toYouTubeEmbed(rawUrl) {
        try {
          const u = new URL(rawUrl);
          // Already an embed link
          if (u.pathname.startsWith('/embed/')) return u.href;
          // youtu.be/<id>
          if (u.hostname.endsWith('youtu.be')) {
            const id = u.pathname.replace(/^\//, '').split('/')[0];
            if (id) return `https://www.youtube.com/embed/${id}?rel=0`;
          }
          // youtube.com/watch?v=<id> or /shorts/<id>
          if (u.hostname.includes('youtube.com')) {
            const id = u.searchParams.get('v') || (u.pathname.match(/^\/shorts\/([^/?]+)/) || [])[1];
            if (id) return `https://www.youtube.com/embed/${id}?rel=0`;
          }
        } catch (_) { /* not a URL */ }
        return null;
      }

function renderMessages() {
        const container = document.getElementById('messages-list');
        if (!container) return;

        const filterEl = document.querySelector('input[name="msg-filter"]:checked');
        const filter = filterEl ? filterEl.value : 'all';
        const messages = (window.userMessages || []).filter(m =>
          filter === 'all' || (filter === 'read' ? m.is_read : !m.is_read));

        container.innerHTML = '';
        if (messages.length === 0) {
          container.innerHTML = '<p class="rounded-xl border border-gray-100 bg-white p-4 text-sm text-gray-500 shadow-sm">შეტყობინებები არ მოიძებნა.</p>';
          return;
        }

        messages.forEach(msg => {
          const date = new Date(msg.created_at).toLocaleDateString('ka-GE', { day: '2-digit', month: '2-digit', year: 'numeric' });
          const unreadBorder = msg.is_read ? '' : 'border-l-4 border-l-[#E30613]';
          const textStyle = msg.is_read ? 'text-gray-600' : 'font-bold text-gray-800';
          const dateStyle = msg.is_read ? 'text-gray-400' : 'font-bold text-[#E30613]';
          const envelopeBtn = msg.is_read
            ? '<i aria-hidden="true" class="fa-regular fa-envelope-open text-lg text-gray-300" title="წაკითხულია"></i>'
            : `<button onclick="markMessageRead(${msg.id})" class="text-lg text-gray-400 transition-colors hover:text-gray-600" aria-label="წაკითხულად მონიშვნა"><i aria-hidden="true" class="fa-solid fa-envelope"></i></button>`;

          container.insertAdjacentHTML('beforeend', `
            <div class="flex items-start gap-4 rounded-xl border border-gray-100 ${unreadBorder} bg-white p-4 shadow-sm">
              <i aria-hidden="true" class="fa-solid fa-arrow-right mt-0.5 ${msg.is_read ? 'text-gray-400' : 'text-[#E30613]'}"></i>
              <p class="flex-1 text-[13px] leading-relaxed ${textStyle}">${msg.content}</p>
              <div class="flex shrink-0 items-center gap-4">
                <span class="text-[13px] ${dateStyle}">${date}</span>
                ${envelopeBtn}
                <button onclick="deleteMessage(${msg.id})" class="text-lg text-gray-400 transition-colors hover:text-[#E30613]" aria-label="წაშლა"><i aria-hidden="true" class="fa-solid fa-trash-can"></i></button>
              </div>
            </div>`);
        });
      }

function escapeHtml(str) {
        if (str == null) return '';
        // Coerce to string so numbers/objects can't bypass via toString side
        // effects, and escape backtick too (defence-in-depth for any future
        // template-literal contexts).
        return String(str)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#039;')
          .replace(/`/g, '&#096;');
      }

function safeUrl(u) {
        if (u == null) return '#';
        const s = String(u).trim();
        if (!s) return '#';
        // Allow same-origin relative paths and the http/https/mailto/tel schemes.
        if (/^\/[^/]/.test(s) || /^https?:|^mailto:|^tel:/i.test(s)) return s;
        // Block javascript:, data:, vbscript:, file: …
        if (/^[a-z][a-z0-9+.\-]*:/i.test(s)) return '#';
        // Relative URL without leading slash, fragment, or query — accept.
        return s;
      }

function renderPinnedDock() {
        const dock = document.getElementById('pinned-articles-dock');
        const container = document.getElementById('pinned-items-container');
        const badge = document.getElementById('pinned-count-badge');
        if (!dock || !container || !badge) return;

        const list = getPinnedArticles();
        badge.textContent = list.length;

        if (list.length > 0) {
          dock.classList.remove('hidden');
          dock.classList.add('flex');

          container.innerHTML = list.map(item => `
            <div class="flex items-center gap-2 rounded-xl bg-white border border-gray-200 p-2.5 shadow-lg transition-all hover:border-[#E30613]/30 max-w-[240px] pointer-events-auto">
              <button onclick="openArticleModalById(${item.id})" class="truncate text-xs font-semibold text-gray-700 hover:text-[#E30613] text-left flex-1" title="${escapeHtml(item.title)}">
                📌 ${escapeHtml(item.title)}
              </button>
              <button onclick="unpinArticle(${item.id}); event.stopPropagation();" class="text-gray-400 hover:text-red-500 p-0.5 text-xs focus:outline-none" aria-label="Unpin">
                <i aria-hidden="true" class="fa-solid fa-xmark"></i>
              </button>
            </div>
          `).join('');
        } else {
          dock.classList.add('hidden');
          dock.classList.remove('flex');
          container.classList.add('hidden');
          container.classList.remove('flex');
        }
      }

function renderRecentlyViewed() {
        const container = document.getElementById('recently-viewed-container');
        const list = document.getElementById('recently-viewed-list');
        if (!container || !list) return;

        let recent = [];
        try {
          recent = JSON.parse(localStorage.getItem('magti_recently_viewed')) || [];
        } catch (e) {
          recent = [];
        }

        if (recent.length === 0) {
          container.classList.add('hidden');
          return;
        }

        container.classList.remove('hidden');
        list.innerHTML = recent.map(item => `
          <button onclick="openArticleModalById(${item.id})"
            class="flex min-w-[72px] shrink-0 items-center justify-center gap-1.5 rounded-xl border border-gray-250 dark:border-zinc-800 bg-white dark:bg-zinc-900 px-4 py-2 text-xs font-semibold text-gray-700 dark:text-zinc-300 transition-all hover:border-[#E30613]/30 hover:bg-slate-50 active:scale-95 shadow-sm dark:hover:bg-zinc-800">
            <i class="fa-regular fa-file-lines text-xs text-gray-400" aria-hidden="true"></i>
            <span class="truncate max-w-[150px]">${escapeHtml(item.title)}</span>
          </button>
        `).join('');
      }

function showBroadcastBanner(message) {
        const container = document.getElementById('broadcast-banner-container');
        if (!container) return;

        const bannerId = 'broadcast-' + Date.now();
        const banner = document.createElement('div');
        banner.id = bannerId;
        banner.className = 'pointer-events-auto flex items-center justify-between gap-4 rounded-xl border border-red-200 bg-red-600 px-6 py-4 text-white shadow-2xl transition-all duration-300 transform translate-y-[-20px] opacity-0';
        banner.innerHTML = `
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-700 text-white animate-pulse">
              <i aria-hidden="true" class="fa-solid fa-triangle-exclamation text-lg"></i>
            </div>
            <div>
              <p class="text-[11px] font-bold uppercase tracking-wider text-red-200">საგანგებო განცხადება / BROADCAST</p>
              <p class="text-sm font-semibold leading-snug">${escapeHtml(message)}</p>
            </div>
          </div>
          <button onclick="document.getElementById('${bannerId}').remove()" class="rounded-lg p-1.5 hover:bg-red-700 text-red-200 hover:text-white transition-colors" aria-label="Dismiss">
            <i aria-hidden="true" class="fa-solid fa-xmark text-lg"></i>
          </button>
        `;

        container.appendChild(banner);

        setTimeout(() => {
          banner.classList.remove('translate-y-[-20px]', 'opacity-0');
          banner.classList.add('translate-y-0', 'opacity-100');
        }, 50);
      }

async function renderDashboardCategoryGrid() {
        const grid = document.getElementById('dashboard-category-grid');
        if (!grid) return;
        await loadTaxonomyData();
        const arts = window._allArticlesCache || [];
        const counts = {};
        arts.forEach(a => { counts[a.category_id] = (counts[a.category_id] || 0) + 1; });
        const cats = (window.taxonomyCategories || [])
          .filter(c => (counts[c.id] || 0) > 0 && c.slug !== 'roaming' && c.name !== 'როუმინგი');
        if (cats.length === 0) {
          grid.innerHTML = '<p class="col-span-full text-sm text-gray-500">კატეგორიები ვერ მოიძებნა.</p>';
          return;
        }
        // Premium monochromatic card architecture: a uniform neutral surface
        // for all categories, with Magti Red reserved as the hover accent
        // (icon color + glow) so it reads as a deliberate brand signal rather
        // than a per-category badge.
        grid.innerHTML = cats.map(c => {
          const targetSlug = c.slug || c.id;

          const recentTime = Date.now() - (48 * 60 * 60 * 1000);
          const hasRecent = arts.some(a => a.category_id === c.id && new Date(a.created_at).getTime() > recentTime);
          const indicatorHtml = hasRecent ? `<span class="absolute top-4 right-4 h-2 w-2 rounded-full bg-[#E30613] shadow-[0_0_6px_rgba(227,6,19,0.5)]" title="ბოლო 48 საათში დამატებულია ახალი მასალა"></span>` : '';
          const iconToUse = c.icon || getCategoryIcon(c.id, c.name);

          return `
          <a href="#/category/${targetSlug}" onclick="location.hash='#/category/${targetSlug}'"
            class="kb-cat-card group relative flex flex-col justify-between overflow-hidden rounded-2xl border border-slate-200 bg-white p-3 md:p-4 w-full h-full text-left shadow-[0_2px_8px_-4px_rgba(0,0,0,0.05)] transition-all duration-300 ease-out hover:shadow-[0_0_12px_rgba(227,6,19,0.15)] hover:scale-[1.01] hover:-translate-y-0.5 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#E30613] dark:border-zinc-700 dark:bg-zinc-900/60">
            <span class="absolute inset-x-0 top-0 h-[2px] origin-left scale-x-0 bg-[#E30613] transition-transform duration-300 ease-out group-hover:scale-x-100"></span>
            ${indicatorHtml}
            <div class="flex flex-col items-start gap-3 w-full mt-1">
              <span class="cat-tile-icon mb-1 flex h-12 w-12 md:h-14 md:w-14 items-center justify-center rounded-2xl text-xl md:text-2xl bg-slate-50 text-slate-600 transition-colors duration-300 group-hover:text-[#E30613] dark:bg-zinc-800 dark:text-neutral-400 shadow-sm">
                <i class="fa-solid ${iconToUse}" aria-hidden="true"></i>
              </span>
              <span class="text-base md:text-lg font-bold leading-snug tracking-tight text-slate-800 dark:text-zinc-200 transition-colors group-hover:text-slate-950 dark:group-hover:text-white">${escapeHtml(c.name)}</span>
            </div>
            <div class="mt-4 flex items-center justify-between w-full">
              <span class="inline-flex items-center rounded-full bg-slate-100/80 px-2.5 py-0.5 text-[10px] md:text-xs font-semibold tracking-wide text-slate-500 dark:bg-zinc-800/80 dark:text-neutral-400 transition-colors group-hover:bg-slate-200/80 dark:group-hover:bg-zinc-700/80">${counts[c.id] || 0} მასალა</span>
              <i class="fa-solid fa-arrow-right text-gray-300 transition-transform group-hover:translate-x-1 group-hover:text-[#E30613] text-[10px] md:text-xs"></i>
            </div>
          </a>`;
        }).join('');
      }

function _applyProfilePillState() {
        const active = window._categoryProfile || 'all';
        document.querySelectorAll('#page-category-view [data-profile-pill]').forEach(btn => {
          const on = btn.getAttribute('data-profile-pill') === active;
          btn.classList.toggle('bg-white', on);
          btn.classList.toggle('text-slate-900', on);
          btn.classList.toggle('shadow-sm', on);
          btn.classList.toggle('font-medium', on);
          btn.classList.toggle('text-slate-500', !on);
          btn.classList.toggle('hover:text-slate-800', !on);
        });
      }

function renderCategoryArticles() {
        const grid = document.getElementById('category-view-grid');
        const cat = window._activeCategory;
        if (!grid || !cat) return;
        const profile = window._categoryProfile || 'all';
        const arts = (window._allArticlesCache || []).filter(a => {
          if (a.category_id !== cat.id) return false;
          if (profile === 'all') return true;
          return a.audience_profile === profile || a.audience_profile === 'all';
        });
        const countEl = document.getElementById('category-view-count');
        if (countEl) countEl.textContent = `${arts.length} მასალა`;

        if (arts.length === 0) {
          grid.innerHTML = '<p class="col-span-full rounded-xl border border-dashed border-slate-200 bg-white/40 p-8 text-center text-xs font-medium tracking-wide text-slate-400 dark:border-zinc-700 dark:bg-zinc-800/40">ამ ფილტრით მასალა ვერ მოიძებნა.</p>';
          return;
        }
        const profBadge = { info: 'საინფ.', tech: 'ტექ.', all: 'ყველა' };
        const profStyles = {
          info:  'bg-sky-50 text-sky-600 dark:bg-sky-950/40 dark:text-sky-300',
          tech:  'bg-red-50 text-red-600 dark:bg-red-950/40 dark:text-red-300',
          all:   'bg-slate-100 text-slate-500 dark:bg-zinc-800 dark:text-zinc-400'
        };
        grid.innerHTML = arts.map(a => {
          const profKey = a.audience_profile || 'all';
          return `
          <button onclick="openArticleModalById(${a.id})"
            class="kb-item-card group relative flex flex-col justify-between gap-3 overflow-hidden rounded-xl border border-slate-100 bg-white p-4 text-left transition-all duration-300 ease-out hover:-translate-y-0.5 hover:border-slate-200 focus:outline-none focus-visible:ring-2 focus-visible:ring-[#E30613] dark:border-zinc-800 dark:bg-zinc-900">
            <span class="absolute inset-x-0 top-0 h-[2px] origin-left scale-x-0 bg-[#E30613] transition-transform duration-300 ease-out group-hover:scale-x-100"></span>
            <span class="text-[13px] font-semibold leading-snug tracking-tight text-slate-800 line-clamp-2 dark:text-zinc-200">${escapeHtml(a.title)}</span>
            <span class="flex items-center gap-2 text-[11px] tracking-wide text-slate-400">
              <span class="rounded-md px-1.5 py-0.5 font-medium transition-colors duration-300 ${profStyles[profKey] || profStyles.all}">${profBadge[profKey] || 'ყველა'}</span>
              <i aria-hidden="true" class="fa-regular fa-clock text-[10px]"></i> <span>${a.read_time || 1} წთ</span>
            </span>
          </button>`;
        }).join('');
      }

async function renderMessagesPopoverList() {
        const listContainer = document.getElementById('messages-list-popover');
        if (!listContainer) return;
        listContainer.innerHTML = '<div class="text-center py-4 text-gray-400">იტვირთება...</div>';
        const token = Auth.getToken();
        if (!token) {
          listContainer.innerHTML = '<div class="text-center py-4 text-red-500">ავტორიზაცია საჭიროა</div>';
          return;
        }
        try {
          const res = await fetch('/api/messages', { headers: { 'Authorization': `Bearer ${token}` } });
          if (!res.ok) throw new Error('Failed to fetch messages');
          const messages = await res.json();
          const unread = messages.filter(m => !m.is_read).slice(0, 8);
          if (unread.length === 0) {
            listContainer.innerHTML = '<p class="text-center text-gray-400 py-4">ახალი შეტყობინებები არ არის</p>';
            return;
          }
          listContainer.innerHTML = unread.map(m => {
            const dateStr = new Date(m.created_at).toLocaleDateString('ka-GE');
            return `
              <div onclick="markMessageRead(${m.id}); closeMessagesPopover();"
                   class="p-2.5 rounded-lg border border-gray-100 hover:bg-red-50/30 cursor-pointer transition-colors flex items-start gap-2">
                <span class="h-2 w-2 mt-1.5 shrink-0 rounded-full bg-[#E30613]"></span>
                <div class="flex-1 min-w-0">
                  <p class="text-gray-800 line-clamp-2">${escapeHtml(m.content)}</p>
                  <p class="text-[10px] text-gray-400 mt-0.5">${dateStr}</p>
                </div>
              </div>`;
          }).join('');
        } catch (e) {
          console.error('Error rendering messages popover:', e);
          listContainer.innerHTML = '<div class="text-center py-4 text-red-500">შეტყობინებების ჩატვირთვა ვერ მოხერხდა</div>';
        }
      }

async function renderNotificationsPopoverList() {
        const listContainer = document.getElementById('notifications-list');
        if (!listContainer) return;
        
        listContainer.innerHTML = '<div class="text-center py-4 text-gray-400">იტვირთება...</div>';
        
        const token = Auth.getToken();
        if (!token) {
          listContainer.innerHTML = '<div class="text-center py-4 text-red-500">ავტორიზაცია საჭიროა</div>';
          return;
        }
        
        try {
          // Fetch readings if not loaded or just fetch fresh
          const readingsRes = await fetch('/api/compliance/my-readings', {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          let unreadReadings = [];
          if (readingsRes.ok) {
            const readings = await readingsRes.json();
            window.myReadings = readings; // keep cache updated
            unreadReadings = readings.filter(r => r.status === 'unread' || r.status === 'overdue' || r.is_overdue);
          }
          
          // Fetch news
          let recentNews = [];
          try {
            const newsRes = await fetch('/api/news', {
              headers: { 'Authorization': `Bearer ${token}` }
            });
            if (newsRes.ok) {
              const news = await newsRes.json();
              // Store in cache for modal view
              news.forEach(item => { window.cachedNewsItems[item.id] = item; });
              // Get news from the last 7 days
              recentNews = news.filter(n => (new Date() - new Date(n.created_at)) < 7 * 24 * 60 * 60 * 1000);
            }
          } catch (e) {
            console.error('Failed to fetch news for notifications:', e);
          }
          
          listContainer.innerHTML = '';
          
          if (unreadReadings.length === 0 && recentNews.length === 0) {
            listContainer.innerHTML = '<p class="text-center text-gray-400 py-4">ახალი შეტყობინებები არ არის</p>';
            return;
          }
          
          // Render unread readings
          unreadReadings.forEach(item => {
            const due = new Date(item.reading.due_date).toLocaleDateString('ka-GE');
            const overdue = item.status === 'overdue' || item.is_overdue;
            const displayTitle = item.item_title || `სავალდებულო მასალა #${item.reading.item_id}`;
            const itemHtml = `
              <div onclick="openAndMarkRead(${item.reading.id}); closeNotificationsPopover();"
                   class="p-2.5 rounded-lg border border-gray-100 hover:bg-red-50/30 cursor-pointer transition-colors flex items-start gap-2">
                <span class="h-2 w-2 mt-1.5 shrink-0 rounded-full ${overdue ? 'bg-[#E30613]' : 'bg-orange-400'}"></span>
                <div class="flex-1 min-w-0">
                  <p class="font-bold text-gray-800 truncate">${escapeHtml(displayTitle)}</p>
                  <p class="text-[10px] text-gray-400 mt-0.5">ვადა: ${due} • <span class="${overdue ? 'text-[#E30613] font-bold' : 'text-orange-500'}">${overdue ? 'ვადაგადაცილებული' : 'სავალდებულო'}</span></p>
                </div>
              </div>
            `;
            listContainer.insertAdjacentHTML('beforeend', itemHtml);
          });
          
          // Render recent news
          recentNews.forEach(item => {
            const dateStr = new Date(item.created_at).toLocaleDateString('ka-GE');
            const itemHtml = `
              <div onclick="openNewsDetailModal(${item.id}); closeNotificationsPopover();"
                   class="p-2.5 rounded-lg border border-gray-100 hover:bg-gray-50 cursor-pointer transition-colors flex items-start gap-2">
                <span class="h-2 w-2 mt-1.5 shrink-0 rounded-full bg-emerald-500"></span>
                <div class="flex-1 min-w-0">
                  <p class="font-bold text-gray-800 truncate">${escapeHtml(item.title)}</p>
                  <p class="text-[10px] text-gray-400 mt-0.5">${dateStr} • <span class="text-emerald-500">სიახლე</span></p>
                </div>
              </div>
            `;
            listContainer.insertAdjacentHTML('beforeend', itemHtml);
          });
          
        } catch (error) {
          console.error('Error rendering notifications list:', error);
          listContainer.innerHTML = '<div class="text-center py-4 text-red-500">შეტყობინებების ჩატვირთვა ვერ მოხერხდა</div>';
        }
      }


// Export to global window scope for backwards compatibility
window._applyProfilePillState = _applyProfilePillState;
window._toYouTubeEmbed = _toYouTubeEmbed;
window.escapeHtml = escapeHtml;
window.getCategoryCardStyles = getCategoryCardStyles;
window.getCategoryIcon = getCategoryIcon;
window.renderArticleBody = renderArticleBody;
window.renderCategoryArticles = renderCategoryArticles;
window.renderDashboardCategoryGrid = renderDashboardCategoryGrid;
window.renderFilteredReadings = renderFilteredReadings;
window.renderKbBento = renderKbBento;
window.renderMessages = renderMessages;
window.renderMessagesPopoverList = renderMessagesPopoverList;
window.renderNews = renderNews;
window.renderNewsList = renderNewsList;
window.renderNotificationsPopoverList = renderNotificationsPopoverList;
window.renderPinnedDock = renderPinnedDock;
window.renderProgressRows = renderProgressRows;
window.renderRecentlyViewed = renderRecentlyViewed;
window.renderSearchResults = renderSearchResults;
window.safeUrl = safeUrl;
window.showBroadcastBanner = showBroadcastBanner;

// Initialize MagtiPortal namespace if not already initialized
window.MagtiPortal = window.MagtiPortal || {};

// In-memory records cache and filters state
window.MagtiPortal.adminRecords = window.MagtiPortal.adminRecords || [];
window.MagtiPortal.adminFilters = window.MagtiPortal.adminFilters || {
  search: '',
  category: '',
  status: ''
};

/**
 * Renders administrative records in a table with support for in-memory filtering.
 * 
 * @param {Array} [records] - Optional records to update the cache and render. If omitted, uses the cached records.
 * @param {Object} [filters] - Optional filters to update the filter state and render. If omitted, uses current filters.
 */
window.MagtiPortal.renderAdminTable = function (records, filters) {
  const tbody = document.getElementById('admin-records-table-body');
  if (!tbody) {
    // Fail silently if DOM element is not present (e.g. on other pages)
    return;
  }

  // Update internal state if new records or filters are provided
  if (records) {
    window.MagtiPortal.adminRecords = records;
  } else {
    records = window.MagtiPortal.adminRecords;
  }

  if (filters) {
    window.MagtiPortal.adminFilters = { ...window.MagtiPortal.adminFilters, ...filters };
  }
  
  const activeFilters = window.MagtiPortal.adminFilters;

  // Filter records in-memory
  const filtered = records.filter(record => {
    // 1. Search filter (case-insensitive check against title)
    if (activeFilters.search) {
      const query = activeFilters.search.toLowerCase();
      const title = (record.title || '').toLowerCase();
      if (!title.includes(query)) {
        return false;
      }
    }

    // 2. Category filter (matches against category_id or category name)
    if (activeFilters.category && activeFilters.category !== 'all') {
      const cat = String(activeFilters.category).toLowerCase();
      const recordCatId = String(record.category_id || '').toLowerCase();
      const recordCatName = String(record.category || '').toLowerCase();
      if (recordCatId !== cat && recordCatName !== cat) {
        return false;
      }
    }

    // 3. Status filter
    if (activeFilters.status && activeFilters.status !== 'all') {
      const stat = String(activeFilters.status).toLowerCase();
      const recordStatus = String(record.status || '').toLowerCase();
      if (recordStatus !== stat) {
        return false;
      }
    }

    return true;
  });

  // Clear current table contents
  tbody.innerHTML = '';

  // Strict empty-state handling
  if (filtered.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="5" class="px-6 py-12 text-center text-sm text-gray-500">
          <div class="flex flex-col items-center justify-center gap-2">
            <i class="fa-solid fa-folder-open text-gray-300 text-3xl" aria-hidden="true"></i>
            <span class="font-medium text-gray-600">ჩანაწერები არ მოიძებნა</span>
            <p class="text-xs text-gray-400 mt-1">სცადეთ შეცვალოთ ფილტრაციის პარამეტრები</p>
          </div>
        </td>
      </tr>
    `;
    return;
  }

  // Predefined static Tailwind color classes mapping to avoid dynamic JIT strings
  const statusStylesMap = {
    published: { text: 'აქტიური', badge: 'bg-emerald-50 text-emerald-700 border-emerald-100', dot: 'bg-emerald-500' },
    draft: { text: 'დრაფტი', badge: 'bg-gray-50 text-gray-700 border-gray-100', dot: 'bg-gray-400' },
    scheduled: { text: 'დაგეგმილი', badge: 'bg-blue-50 text-blue-700 border-blue-100', dot: 'bg-blue-500' },
    archived: { text: 'არქივი', badge: 'bg-amber-50 text-amber-700 border-amber-100', dot: 'bg-amber-500' }
  };

  // Safe escape utility reference or fallback
  const esc = (s) => (typeof escapeHtml === 'function' ? escapeHtml(String(s)) : String(s));

  // Populate the table
  filtered.forEach(record => {
    const title = esc(record.title || '');
    const categoryName = esc(record.category || record.category_name || `ID: ${record.category_id || 'უცნობი'}`);
    
    // Format date safely referencing window.formatDate if available
    const dateText = record.created_at
      ? (window.formatDate ? window.formatDate(record.created_at) : new Date(record.created_at).toLocaleDateString('ka-GE'))
      : '—';

    const rawStatus = (record.status || '').toLowerCase();
    const statusConfig = statusStylesMap[rawStatus] || {
      text: record.status || 'უცნობი',
      badge: 'bg-slate-50 text-slate-700 border-slate-100',
      dot: 'bg-slate-400'
    };

    const tr = document.createElement('tr');
    tr.className = 'border-b border-gray-100 hover:bg-gray-50/50 transition-colors duration-150';
    tr.innerHTML = `
      <td class="px-6 py-4 text-sm font-medium text-gray-900">
        <div class="flex items-center gap-2.5">
          <i class="fa-solid fa-file-lines text-slate-400" aria-hidden="true"></i>
          <span class="truncate max-w-[320px]" title="${title}">${title}</span>
        </div>
      </td>
      <td class="px-6 py-4 text-sm text-gray-500">${categoryName}</td>
      <td class="px-6 py-4 text-sm text-gray-500">${dateText}</td>
      <td class="px-6 py-4 text-sm">
        <span class="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border ${statusConfig.badge}">
          <span class="h-1.5 w-1.5 rounded-full ${statusConfig.dot}"></span>
          ${statusConfig.text}
        </span>
      </td>
      <td class="px-6 py-4 text-sm text-gray-500">
        <div class="flex items-center gap-3">
          <button onclick="window.editArticle ? window.editArticle(${record.id}) : console.log('Edit record:', ${record.id})" class="text-slate-400 hover:text-blue-600 transition-colors" aria-label="რედაქტირება">
            <i class="fa-solid fa-pen-to-square" aria-hidden="true"></i>
          </button>
          <button onclick="window.deleteArticle ? window.deleteArticle(${record.id}) : console.log('Delete record:', ${record.id})" class="text-slate-400 hover:text-[#E30613] transition-colors" aria-label="წაშლა">
            <i class="fa-solid fa-trash-can" aria-hidden="true"></i>
          </button>
        </div>
      </td>
    `;
    tbody.appendChild(tr);
  });
};

/**
 * Initializes listeners for admin table filters with a 150ms debounce on the search input.
 */
window.MagtiPortal.initAdminFilters = function () {
  const searchInput = document.getElementById('table-search');
  const categoryFilter = document.getElementById('table-filter-category');
  const statusFilter = document.getElementById('table-filter-status');

  let debounceTimer = null;

  const handleFilterChange = () => {
    const filters = {
      search: searchInput ? searchInput.value.trim() : '',
      category: categoryFilter ? categoryFilter.value : 'all',
      status: statusFilter ? statusFilter.value : 'all'
    };

    window.MagtiPortal.renderAdminTable(null, filters);
  };

  // Bind debounced search listener (150ms)
  if (searchInput) {
    searchInput.addEventListener('input', () => {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(handleFilterChange, 150);
    });
  }

  // Bind change listeners
  if (categoryFilter) {
    categoryFilter.addEventListener('change', handleFilterChange);
  }

  if (statusFilter) {
    statusFilter.addEventListener('change', handleFilterChange);
  }
};
