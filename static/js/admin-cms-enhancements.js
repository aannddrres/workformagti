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

  // ── Upload a Base64 data: URI (extracted from pasted HTML) the same way app-core.js's
  //    uploadInlineImage() handles a real dropped/pasted image File ──
  function uploadDataUriImage(dataUri) {
    var match = /^data:([^;]+);base64,(.*)$/.exec(dataUri);
    if (!match) return Promise.reject(new Error('invalid data URI'));
    var mime = match[1];
    var binary = atob(match[2]);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    var ext = (mime.split('/')[1] || 'png').split('+')[0];
    var file = new File([bytes], 'pasted-image.' + ext, { type: mime });
    var formData = new FormData();
    formData.append('file', file);
    return fetch('/api/upload', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + Auth.getToken() },
      body: formData
    }).then(function (res) {
      if (!res.ok) throw new Error('upload failed');
      return res.json();
    });
  }

  // ── app-core.js's own paste listener only inspects clipboardData.items (real image
  //    files / clipboard image bytes). Some sites inline thumbnails as <img src="data:...">
  //    directly in their DOM, which arrives as text/html and slips past that check,
  //    landing as raw Base64 in the article body. Catch that case here, additively. ──
  function handleDataUriPaste(e) {
    if (e.defaultPrevented) return;
    if (!e.clipboardData || Array.prototype.indexOf.call(e.clipboardData.types || [], 'text/html') === -1) return;
    var html = e.clipboardData.getData('text/html');
    if (!html || !/<img[^>]+src=["']data:/i.test(html)) return;

    e.preventDefault();

    var clean = window.DOMPurify ? DOMPurify.sanitize(html) : html;

    var template = document.createElement('template');
    template.innerHTML = clean;
    var pending = [];
    var counter = 0;
    template.content.querySelectorAll('img[src^="data:"]').forEach(function (img) {
      // Quill's Image format strips unrecognized attributes (data-*, class, title) on
      // insert, keeping only src/alt/width/height — so the pending-upload marker has to
      // ride in `alt`, the one free-text attribute that survives the round-trip.
      var id = 'pending-upload:' + Date.now() + '-' + (counter++);
      pending.push({ id: id, dataUri: img.getAttribute('src'), originalAlt: img.getAttribute('alt') || '' });
      img.setAttribute('alt', id);
      // Quill's clipboard module also drops <img> tags that have no src at all, so swap the
      // multi-MB data URI for a 1x1 transparent placeholder rather than removing it outright.
      img.setAttribute('src', 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==');
    });

    var range = window.articleQuill.getSelection() || { index: window.articleQuill.getLength() };
    window.articleQuill.clipboard.dangerouslyPasteHTML(range.index, template.innerHTML, 'user');

    pending.forEach(function (item) {
      uploadDataUriImage(item.dataUri).then(function (data) {
        var placeholder = window.articleQuill.root.querySelector('img[alt="' + item.id + '"]');
        if (placeholder) {
          placeholder.setAttribute('src', data.url);
          placeholder.setAttribute('alt', item.originalAlt);
        }
      }).catch(function (err) {
        console.error('Pasted image upload failed:', err);
        var placeholder = window.articleQuill.root.querySelector('img[alt="' + item.id + '"]');
        if (placeholder) placeholder.setAttribute('alt', 'სურათის ატვირთვა ვერ მოხერხდა');
      });
    });
  }

  // ── Native Markdown shortcuts (#, ##, >) — Quill's structured API only
  //    (formatLine + deleteText), never innerHTML, so undo/redo stays intact ──
  var MARKDOWN_LINE_RULES = [
    { pattern: /^## $/, format: 'header', value: 2 },
    { pattern: /^# $/, format: 'header', value: 1 },
    { pattern: /^> $/, format: 'blockquote', value: true }
  ];

  function handleMarkdownShortcuts(delta, oldDelta, source) {
    if (source !== 'user') return;
    var ops = delta.ops || [];
    var lastOp = ops[ops.length - 1];
    if (!lastOp || lastOp.insert !== ' ') return;

    var quill = window.articleQuill;
    var sel = quill.getSelection();
    if (!sel) return;

    var line = quill.getLine(sel.index);
    if (!line || !line[0]) return;
    var lineStart = sel.index - line[1];
    var prefix = quill.getText(lineStart, sel.index - lineStart);

    var rule = MARKDOWN_LINE_RULES.find(function (r) { return r.pattern.test(prefix); });
    if (!rule) return;

    quill.formatLine(lineStart, prefix.length, rule.format, rule.value, 'user');
    quill.deleteText(lineStart, prefix.length, 'user');
  }

  // ── Contextual slash-command menu — "/" alone on a line opens a small floating
  //    picker; Enter applies it via the same formatLine + deleteText pattern as
  //    the markdown shortcuts above (structured API, undo/redo stays intact). ──
  var SLASH_COMMANDS = [
    { label: 'H1 სათაური', format: 'header', value: 1 },
    { label: 'H2 ქვესათაური', format: 'header', value: 2 },
    { label: 'ციტატის ბლოკი', format: 'blockquote', value: true }
  ];
  var slashMenu = { el: null, open: false, selectedIndex: 0, lineStart: 0 };

  function buildSlashMenu() {
    var el = document.createElement('div');
    el.id = 'quill-slash-menu';
    el.className = 'absolute z-50 w-48 rounded-lg border border-gray-200 bg-white py-1 shadow-lg hidden';
    SLASH_COMMANDS.forEach(function (cmd, i) {
      var item = document.createElement('button');
      item.type = 'button';
      item.className = 'slash-menu-item block w-full px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-100';
      item.textContent = cmd.label;
      item.addEventListener('mousedown', function (e) {
        e.preventDefault(); // don't let the click steal focus/selection from the editor
        executeSlashCommand(i);
      });
      el.appendChild(item);
    });
    return el;
  }

  function renderSlashMenuSelection() {
    if (!slashMenu.el) return;
    var items = slashMenu.el.querySelectorAll('.slash-menu-item');
    for (var i = 0; i < items.length; i++) {
      items[i].classList.toggle('bg-gray-100', i === slashMenu.selectedIndex);
    }
  }

  function openSlashMenu(lineStart) {
    var quill = window.articleQuill;
    if (!slashMenu.el) {
      slashMenu.el = buildSlashMenu();
      quill.container.appendChild(slashMenu.el);
    }
    var bounds = quill.getBounds(lineStart + 1);
    slashMenu.el.style.left = bounds.left + 'px';
    slashMenu.el.style.top = (bounds.top + bounds.height + 4) + 'px';
    slashMenu.el.classList.remove('hidden');
    slashMenu.open = true;
    slashMenu.selectedIndex = 0;
    slashMenu.lineStart = lineStart;
    renderSlashMenuSelection();
  }

  function closeSlashMenu() {
    if (slashMenu.el) slashMenu.el.classList.add('hidden');
    slashMenu.open = false;
  }

  function executeSlashCommand(index) {
    var quill = window.articleQuill;
    var cmd = SLASH_COMMANDS[index];
    if (!cmd) return;
    var lineStart = slashMenu.lineStart;
    closeSlashMenu();
    quill.formatLine(lineStart, 1, cmd.format, cmd.value, 'user');
    quill.deleteText(lineStart, 1, 'user');
    quill.setSelection(lineStart, 0, 'user');
  }

  function handleSlashMenuTrigger(delta, oldDelta, source) {
    if (source !== 'user') return;
    var quill = window.articleQuill;
    var sel = quill.getSelection();
    if (!sel) { closeSlashMenu(); return; }

    var line = quill.getLine(sel.index);
    if (!line || !line[0]) { closeSlashMenu(); return; }
    var lineStart = sel.index - line[1];
    var prefix = quill.getText(lineStart, sel.index - lineStart);

    if (prefix === '/') {
      openSlashMenu(lineStart);
    } else if (slashMenu.open) {
      closeSlashMenu();
    }
  }

  // Capture phase so this runs BEFORE Quill's own keyboard module (bubble phase) —
  // otherwise Quill would already have inserted a newline / moved the cursor by
  // the time our handler saw the event, even with preventDefault().
  function handleSlashMenuKeydown(e) {
    if (!slashMenu.open) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault(); e.stopPropagation();
      slashMenu.selectedIndex = (slashMenu.selectedIndex + 1) % SLASH_COMMANDS.length;
      renderSlashMenuSelection();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault(); e.stopPropagation();
      slashMenu.selectedIndex = (slashMenu.selectedIndex - 1 + SLASH_COMMANDS.length) % SLASH_COMMANDS.length;
      renderSlashMenuSelection();
    } else if (e.key === 'Enter') {
      e.preventDefault(); e.stopPropagation();
      executeSlashCommand(slashMenu.selectedIndex);
    } else if (e.key === 'Escape') {
      e.preventDefault(); e.stopPropagation();
      closeSlashMenu();
    }
  }

  // ── Quill is created asynchronously in app-core.js's DOMContentLoaded; poll for it ──
  var quillPoll = setInterval(function () {
    if (!window.articleQuill) return;
    clearInterval(quillPoll);
    window.articleQuill.on('text-change', updateCharCount);
    window.articleQuill.on('text-change', handleMarkdownShortcuts);
    window.articleQuill.on('text-change', handleSlashMenuTrigger);
    window.articleQuill.root.addEventListener('paste', handleDataUriPaste);
    window.articleQuill.root.addEventListener('keydown', handleSlashMenuKeydown, true);
    window.articleQuill.root.addEventListener('blur', closeSlashMenu);
    updateCharCount();
  }, 100);

  // ── Ctrl/Cmd+S saves the article drawer instead of triggering the browser's
  //    save-page dialog — only when the drawer is actually open, so this never
  //    hijacks the shortcut for users who aren't authoring. ──
  document.addEventListener('keydown', function (e) {
    if (!(e.ctrlKey || e.metaKey) || e.key !== 's') return;
    var panel = document.getElementById('admin-panel');
    if (!panel || panel.classList.contains('hidden')) return;
    e.preventDefault();
    var form = document.getElementById('create-article-form');
    if (form) form.requestSubmit();
  });

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
