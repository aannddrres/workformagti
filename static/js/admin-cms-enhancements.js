/* ════════════════════════════════════════════════════
   Magti Portal - Admin CMS UX Enhancements (additive only)
   Drag-and-drop attachment zone, Quill char counter, and a
   live status badge in the article drawer. Never edits
   app-core.js / app-renderers.js / app-router.js — only
   attaches extra listeners and wraps existing functions.
   ════════════════════════════════════════════════════ */

(function () {
  if (!document.getElementById('admin-panel')) return;

  // ── Prevent accidental drops outside the dropzone from navigating the tab ──
  window.addEventListener('dragover', function (e) { e.preventDefault(); });
  window.addEventListener('drop', function (e) { e.preventDefault(); });

  var FALLBACK_STATUS_STYLES = {
    published: { text: 'აქტიური', badge: 'bg-emerald-50 text-emerald-700 border-emerald-100' },
    draft: { text: 'დრაფტი', badge: 'bg-gray-50 text-gray-700 border-gray-100' },
    scheduled: { text: 'დაგეგმილი', badge: 'bg-blue-50 text-blue-700 border-blue-100' }
  };

  function updateCharCount() {
    var el = document.getElementById('article-content-charcount');
    if (!el || !window.articleQuill) return;
    var len = window.articleQuill.getText().replace(/\n$/, '').length;
    el.textContent = '(' + len + ' სიმბოლო)';
  }

  function renderStatusBadge() {
    var select = document.getElementById('article-status');
    var badge = document.getElementById('article-status-badge');
    if (!select || !badge) return;
    var map = window.statusStylesMap || FALLBACK_STATUS_STYLES;
    var config = map[select.value] || FALLBACK_STATUS_STYLES[select.value];
    if (!config) { badge.className = 'mt-2 inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border hidden'; return; }
    badge.className = 'mt-2 inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium border ' + config.badge;
    badge.textContent = config.text;
  }

  // ── Quill is created asynchronously in app-core.js's DOMContentLoaded; poll for it ──
  var quillPoll = setInterval(function () {
    if (!window.articleQuill) return;
    clearInterval(quillPoll);
    window.articleQuill.on('text-change', updateCharCount);
    updateCharCount();
  }, 100);

  var statusSelect = document.getElementById('article-status');
  if (statusSelect) statusSelect.addEventListener('change', renderStatusBadge);

  // ── Wrap drawer-open entry points so the badge/counter reflect current state immediately ──
  ['focusCreateForm', 'editArticle'].forEach(function (fnName) {
    var original = window[fnName];
    if (typeof original !== 'function') return;
    window[fnName] = function () {
      var result = original.apply(this, arguments);
      if (window.articleQuill) {
        updateCharCount();
        renderStatusBadge();
      }
      return result;
    };
  });

  // ── Drag-and-drop wired to the existing upload pipeline ──
  var dropzone = document.getElementById('article-dropzone');
  var fileInput = document.getElementById('article-upload-file');
  if (dropzone && fileInput) {
    var activeClass = ['border-magti', 'bg-red-50/30'];

    ['dragenter', 'dragover'].forEach(function (evt) {
      dropzone.addEventListener(evt, function (e) {
        e.preventDefault();
        e.stopPropagation();
        dropzone.classList.add.apply(dropzone.classList, activeClass);
      });
    });

    dropzone.addEventListener('dragleave', function (e) {
      e.preventDefault();
      e.stopPropagation();
      dropzone.classList.remove.apply(dropzone.classList, activeClass);
    });

    dropzone.addEventListener('drop', function (e) {
      e.preventDefault();
      e.stopPropagation();
      dropzone.classList.remove.apply(dropzone.classList, activeClass);
      var files = e.dataTransfer && e.dataTransfer.files;
      if (!files || files.length === 0) return;
      var transfer = new DataTransfer();
      transfer.items.add(files[0]);
      fileInput.files = transfer.files;
      fileInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }
})();
