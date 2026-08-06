/**
 * Port of renderArticleBody's markdown-ish formatting step
 * (app-renderers.js:502-560) -- fenced/backtick code transforms and the
 * plain-paragraph fallback for bodies with no recognizable block tags.
 *
 * Sanitization is handled differently on purpose: the original calls
 * `DOMPurify.sanitize(...)` (falling back to **unsanitized** raw
 * `innerHTML` if the CDN script failed to load -- a real XSS gap). This
 * port drops DOMPurify and instead binds the formatted HTML via Angular's
 * `[innerHTML]`, which always runs through Angular's own DomSanitizer with
 * no unsanitized fallback path -- the migration doc's own §2.3 risk #3
 * calls for exactly this verification-and-replace, not a re-add of the
 * CDN dependency.
 */
export function formatArticleContent(rawBody: string | null | undefined): string {
  let body = rawBody || '';

  const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
  if (codeBlockRegex.test(body)) {
    body = body.replace(
      codeBlockRegex,
      (_match, lang: string, code: string) =>
        `<pre class="my-4 overflow-x-auto rounded-xl bg-gray-950 p-4 font-mono text-sm text-green-400 border border-gray-800 shadow-inner"><code class="language-${escapeHtml(lang)}">${escapeHtml(code.trim())}</code></pre>`
    );
  }

  const inlineCodeRegex = /`([^`\n]+)`/g;
  body = body.replace(
    inlineCodeRegex,
    (_match, code: string) => `<code class="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs text-red-600 dark:bg-zinc-800 dark:text-red-400">${escapeHtml(code)}</code>`
  );

  const hasBlockTags = /<(p|div|h[1-6]|ul|ol|li|table|br|a|strong|b|em|img|pre|code)\b/i.test(body);
  if (!hasBlockTags) {
    const paragraphs = body.split(/\n+/).filter((p) => p.trim() !== '');
    body = paragraphs.map((p) => `<p class="leading-relaxed mb-3">${escapeHtml(p)}</p>`).join('');
  }

  return body;
}

function escapeHtml(value: string): string {
  const div = document.createElement('div');
  div.textContent = value;
  return div.innerHTML;
}
