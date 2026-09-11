/**
 * Port of renderArticleBody's markdown-ish formatting step
 * (app-renderers.js:502-560) -- fenced/backtick code transforms and the
 * plain-paragraph fallback for bodies with no recognizable block tags.
 *
 * The backend now sanitizes content before persistence. Angular's
 * `[innerHTML]` sanitization is retained as an independent rendering-layer
 * defence for legacy rows and presentation-only transformations below.
 */
export interface ArticleLinkTarget {
  id: number;
  title: string;
}

export function formatArticleContent(
  rawBody: string | null | undefined,
  linkTargets: readonly ArticleLinkTarget[] = [],
  title: string | null | undefined = null,
): string {
  let body = rawBody || '';

  const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
  if (codeBlockRegex.test(body)) {
    body = body.replace(
      codeBlockRegex,
      (_match, lang: string, code: string) =>
        `<pre class="my-4 overflow-x-auto rounded-lg bg-slate-950 p-4 font-mono text-sm text-green-400 border border-slate-800 shadow-inner"><code class="language-${escapeHtml(lang)}">${escapeHtml(code.trim())}</code></pre>`,
    );
  }

  const inlineCodeRegex = /`([^`\n]+)`/g;
  body = body.replace(
    inlineCodeRegex,
    (_match, code: string) =>
      `<code class="rounded-sm bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-red-600 dark:bg-slate-800 dark:text-red-400">${escapeHtml(code)}</code>`,
  );

  const hasBlockTags = /<(p|div|h[1-6]|ul|ol|li|table|br|a|strong|b|em|img|pre|code)\b/i.test(body);
  if (!hasBlockTags) {
    const paragraphs = body.split(/\n+/).filter((p) => p.trim() !== '');
    body = paragraphs.map((p) => `<p class="leading-relaxed mb-3">${escapeHtml(p)}</p>`).join('');
  }

  return decorateTechnicalTokens(body, linkTargets, title);
}

/**
 * Adds presentation-only hooks around call-centre codes such as `*105#` and
 * `**62*0300#OK`. The source HTML stored in the database is never mutated and
 * the visible text (including character order) remains identical.
 *
 * Links and existing code/pre blocks are deliberately skipped: they already
 * carry their own visual and semantic meaning. Angular sanitises the returned
 * HTML again when it is bound through `[innerHTML]`.
 */
export function decorateTechnicalTokens(
  body: string,
  linkTargets: readonly ArticleLinkTarget[] = [],
  title: string | null | undefined = null,
): string {
  if (!body || typeof DOMParser === 'undefined') {
    return body;
  }

  const document = new DOMParser().parseFromString(body, 'text/html');
  dropRepeatedTitle(document, title);
  demoteHeadings(document);
  decorateDenseClauses(document);
  rewriteLegacyArticleLinks(document, linkTargets);
  const walker = document.createTreeWalker(document.body, 4);
  const textNodes: Text[] = [];

  while (walker.nextNode()) {
    textNodes.push(walker.currentNode as Text);
  }

  for (const textNode of textNodes) {
    const parent = textNode.parentElement;
    if (!parent || parent.closest('a, code, pre, script, style, textarea')) {
      continue;
    }

    const matches = Array.from(
      textNode.data.matchAll(/(?:\*{1,2}[0-9*]+#(?:OK)?|#{1,2}[0-9*]+#(?:OK)?)/giu),
    );
    if (matches.length === 0) {
      continue;
    }

    const fragment = document.createDocumentFragment();
    let cursor = 0;

    for (const match of matches) {
      const start = match.index ?? 0;
      if (start > cursor) {
        fragment.append(document.createTextNode(textNode.data.slice(cursor, start)));
      }

      const token = document.createElement('span');
      token.className = 'article-reader__token';
      token.textContent = match[0];
      fragment.append(token);
      cursor = start + match[0].length;
    }

    if (cursor < textNode.data.length) {
      fragment.append(document.createTextNode(textNode.data.slice(cursor)));
    }

    textNode.replaceWith(fragment);
  }

  return document.body.innerHTML;
}

const LEGACY_TARGET_TITLE_ALIASES: Readonly<Record<string, string>> = {
  'დომენის რეგისტრაცია': 'ჰოსტინგი და დომენი',
  'ჰოსტინგის შეძენა': 'ჰოსტინგი და დომენი',
  'მობილური ტექნიკური': 'მობილური კავშირის საფუძვლები',
  'mob მონაცემთა გადაცემაინტერნეტი': 'MOB მონაცემთა გადაცემა/ინტერნეტი',
  'mob ემულატორი': 'Mob - ემულატორი',
};

/**
 * Replaces obsolete Google Sites links at render time. A high-confidence
 * title/path match opens the visible canonical article directly. When the old
 * target was a sub-page that was never imported as its own article, the link
 * stays inside the portal and opens a scoped knowledge-base search instead.
 *
 * This intentionally uses only the current user's visible article summaries:
 * an internal link can never reveal or deep-link content outside their scope.
 */
function rewriteLegacyArticleLinks(
  document: Document,
  linkTargets: readonly ArticleLinkTarget[],
): void {
  const articleByTitle = new Map<string, number | null>();
  for (const target of linkTargets) {
    const key = normalizeLegacyLinkKey(target.title);
    articleByTitle.set(key, articleByTitle.has(key) ? null : target.id);
  }

  for (const anchor of Array.from(document.body.querySelectorAll('a[href]'))) {
    const href = anchor.getAttribute('href')?.trim() ?? '';
    const legacyUrl = parseLegacyGoogleSitesUrl(href);
    if (!legacyUrl) {
      continue;
    }

    const linkText = (anchor.textContent ?? '').replace(/\s+/g, ' ').trim();
    const lastPathSegment = legacyUrl.pathname.split('/').filter(Boolean).at(-1) ?? '';
    const textKey = normalizeLegacyLinkKey(linkText);
    const pathKey = normalizeLegacyLinkKey(lastPathSegment);
    const aliasTitle = LEGACY_TARGET_TITLE_ALIASES[pathKey];
    const targetId =
      articleByTitle.get(textKey) ??
      articleByTitle.get(pathKey) ??
      (aliasTitle ? articleByTitle.get(normalizeLegacyLinkKey(aliasTitle)) : null);

    anchor.removeAttribute('target');
    anchor.removeAttribute('rel');
    anchor.classList.add('article-reader__portal-link');

    if (targetId != null) {
      anchor.setAttribute('href', `/article/${targetId}`);
      anchor.classList.add('article-reader__internal-link');
      continue;
    }

    const searchTerm = /^https?:\/\//i.test(linkText) ? '' : linkText;
    anchor.setAttribute('href', searchTerm ? `/info?q=${encodeURIComponent(searchTerm)}` : '/info');
    anchor.classList.add('article-reader__legacy-search');
  }
}

function parseLegacyGoogleSitesUrl(href: string): URL | null {
  try {
    const url = new URL(href);
    return url.hostname.toLowerCase() === 'sites.google.com' &&
      url.pathname.toLowerCase().startsWith('/view/magti-call-center')
      ? url
      : null;
  } catch {
    return null;
  }
}

function normalizeLegacyLinkKey(value: string): string {
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    // A malformed legacy URL must not break the article. Its undecoded value
    // can still be used as a safe search fallback.
  }

  return decoded
    .normalize('NFKC')
    .toLocaleLowerCase('ka-GE')
    .replace(/[\u200b-\u200d\ufeff]/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Legacy pages often encode a sequence of short conditions as one very long
 * plain paragraph separated by semicolons. For unambiguous, text-only cases
 * we keep every character in place and add block-level spans solely to create
 * a scannable visual rhythm. Paragraphs containing links or other inline
 * markup are left untouched so no authored semantics can be lost.
 */
function decorateDenseClauses(document: Document): void {
  for (const paragraph of Array.from(document.body.querySelectorAll('p'))) {
    if (paragraph.children.length > 0) {
      continue;
    }

    const text = paragraph.textContent ?? '';
    const separatorCount = (text.match(/;/g) ?? []).length;
    if (text.length < 300 || separatorCount < 4) {
      continue;
    }

    const clauses = text.match(/[^;]*;|[^;]+$/g) ?? [];
    if (clauses.filter((clause) => clause.trim().length > 0).length < 5) {
      continue;
    }

    paragraph.classList.add('article-reader__clauses');
    paragraph.replaceChildren(
      ...clauses.map((clause) => {
        if (clause.trim().length === 0) {
          return document.createTextNode(clause);
        }

        const row = document.createElement('span');
        row.className = 'article-reader__clause';
        row.textContent = clause;
        return row;
      }),
    );
  }
}

/**
 * Stored bodies often open by repeating the article's own title, so the reader
 * printed it twice: once as the page heading and again as the first line of the
 * text. Only an exact match is removed -- a heading that merely starts with the
 * same words is a real section and stays.
 */
function dropRepeatedTitle(document: Document, title: string | null | undefined): void {
  const wanted = (title || '').replace(/\s+/g, ' ').trim().toLocaleLowerCase('ka-GE');
  if (!wanted) return;

  const heading = document.body.querySelector('h1, h2, h3, h4, h5, h6');
  if (!heading) return;

  const normalise = (value: string | null) =>
    (value || '').replace(/\s+/g, ' ').trim().toLocaleLowerCase('ka-GE');
  const headingText = normalise(heading.textContent);
  if (headingText !== wanted) return;

  // Bodies are often wrapped in a <div>, so "is it the first element" is not
  // the question -- "does anything precede it" is.
  if (!normalise(document.body.textContent).startsWith(headingText)) return;

  heading.remove();
}

/**
 * A document gets one h1 and the page heading is already it, so headings inside
 * a stored body shift down one level. Relative structure is preserved, which a
 * flat h1 -> h2 rewrite would not do; h6 has nowhere lower to go and stays.
 */
function demoteHeadings(document: Document): void {
  for (let level = 5; level >= 1; level -= 1) {
    for (const heading of Array.from(document.querySelectorAll(`h${level}`))) {
      const replacement = document.createElement(`h${level + 1}`);
      for (const attribute of Array.from(heading.attributes)) {
        replacement.setAttribute(attribute.name, attribute.value);
      }
      replacement.replaceChildren(...Array.from(heading.childNodes));
      heading.replaceWith(replacement);
    }
  }
}

function escapeHtml(value: string): string {
  const div = document.createElement('div');
  div.textContent = value;
  return div.innerHTML;
}
