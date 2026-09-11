import { describe, expect, it } from 'vitest';
import { decorateTechnicalTokens, formatArticleContent } from './format-article-content';

function textContent(html: string): string {
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? '';
}

describe('formatArticleContent', () => {
  it('drops a body heading that only repeats the article title', () => {
    const rendered = formatArticleContent(
      '<h1>მობილური ტელეფონი</h1><p>ტექსტი</p>',
      [],
      'მობილური ტელეფონი',
    );

    expect(rendered).not.toContain('მობილური ტელეფონი');
    expect(rendered).toContain('ტექსტი');
  });

  it('finds the repeated title even when the body is wrapped in a div', () => {
    const rendered = formatArticleContent(
      '<div><h1>მობილური</h1><p>ტექსტი</p></div>',
      [],
      'მობილური',
    );

    expect(rendered).not.toContain('<h2>მობილური</h2>');
    expect(rendered).toContain('ტექსტი');
  });

  it('keeps a heading that is not the first thing in the body', () => {
    const rendered = formatArticleContent('<p>შესავალი</p><h2>A</h2>', [], 'A');

    expect(rendered).toContain('>A<');
  });

  it('keeps a heading that merely starts with the same words', () => {
    const rendered = formatArticleContent('<h1>ტარიფები 2026</h1>', [], 'ტარიფები');

    expect(rendered).toContain('ტარიფები 2026');
  });

  it('shifts body headings down a level so the page keeps the only h1', () => {
    const rendered = formatArticleContent('<h1>A</h1><h2>B</h2><h6>C</h6>');

    expect(rendered).toContain('<h2>A</h2>');
    expect(rendered).toContain('<h3>B</h3>');
    expect(rendered).toContain('<h6>C</h6>');
  });

  it('preserves every visible character while decorating technical codes', () => {
    const source =
      '<p>სერვისის ჩართვა — *105#; გადამისამართება — **62*0300#OK.</p><ul><li>გამორთვა: ##62#OK</li></ul>';

    const rendered = decorateTechnicalTokens(source);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered.match(/article-reader__token/g)).toHaveLength(3);
    expect(rendered).toContain('>*105#</span>');
    expect(rendered).toContain('>**62*0300#OK</span>');
    expect(rendered).toContain('>##62#OK</span>');
  });

  it('does not decorate tokens already inside links or code blocks', () => {
    const source = '<p><a href="/help">*105#</a> <code>**62*0300#OK</code> და *111#</p>';

    const rendered = decorateTechnicalTokens(source);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered.match(/article-reader__token/g)).toHaveLength(1);
    expect(rendered).toContain('>*111#</span>');
  });

  it('turns only dense plain semicolon sequences into visual rows without changing text', () => {
    const source = `<p>${'მომსახურების პირობა და მისი სრული განმარტება უცვლელი ტექსტით; '.repeat(8)}</p>`;

    const rendered = decorateTechnicalTokens(source);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered).toContain('article-reader__clauses');
    expect(rendered.match(/article-reader__clause"/g)).toHaveLength(8);
  });

  it('does not regroup a dense paragraph that contains authored inline markup', () => {
    const source = `<p><strong>სათაური</strong>${' პირობა და განმარტება;'.repeat(20)}</p>`;

    const rendered = decorateTechnicalTokens(source);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered).not.toContain('article-reader__clauses');
  });

  it('rewrites a legacy related-page link to the matching visible portal article', () => {
    const source =
      '<p><a href="https://sites.google.com/view/magti-call-center/mobile/mob-services" target="_blank">MOB სერვისები</a></p>';

    const rendered = formatArticleContent(source, [{ id: 3028, title: 'MOB სერვისები' }]);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered).toContain('href="/article/3028"');
    expect(rendered).toContain('article-reader__internal-link');
    expect(rendered).not.toContain('sites.google.com');
    expect(rendered).not.toContain('target=');
  });

  it('can resolve a renamed canonical article from the legacy URL path', () => {
    const source =
      '<a href="https://sites.google.com/view/magti-call-center/technical/დომენის-რეგისტრაცია">დომენი</a>';

    const rendered = formatArticleContent(source, [{ id: 3001, title: 'ჰოსტინგი და დომენი' }]);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered).toContain('href="/article/3001"');
  });

  it('keeps an unresolved legacy sub-page inside the portal as a KB search', () => {
    const source =
      '<a href="https://sites.google.com/view/magti-call-center/mobile/mob-volte">MOB VoLTE</a>';

    const rendered = formatArticleContent(source, [
      { id: 3020, title: 'MOB მონაცემთა გადაცემა/ინტერნეტი' },
    ]);

    expect(textContent(rendered)).toBe(textContent(source));
    expect(rendered).toContain('href="/info?q=MOB%20VoLTE"');
    expect(rendered).toContain('article-reader__legacy-search');
    expect(rendered).not.toContain('sites.google.com');
  });

  it('leaves genuine external operational links unchanged', () => {
    const source = '<a href="https://ccare.magti.ge/CCareFE/">CCARE</a>';

    const rendered = formatArticleContent(source, [
      { id: 3054, title: 'ISP ინციდენტების მართვის სისტემა' },
    ]);

    expect(rendered).toContain('href="https://ccare.magti.ge/CCareFE/"');
    expect(rendered).not.toContain('article-reader__portal-link');
  });

  it('keeps the plain-text fallback and its content invariant', () => {
    const source = 'პირველი აბზაცი *105#\nმეორე აბზაცი';

    const rendered = formatArticleContent(source);

    expect(textContent(rendered)).toBe('პირველი აბზაცი *105#მეორე აბზაცი');
    expect(rendered.match(/<p/g)).toHaveLength(2);
    expect(rendered).toContain('article-reader__token');
  });
});
