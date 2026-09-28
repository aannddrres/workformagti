import { extractYoutubeId, toYoutubeEmbedUrl } from './youtube';

/**
 * toYoutubeEmbedUrl feeds the only bypassSecurityTrustResourceUrl in the
 * app (video-detail-page.ts). Whatever an editor stored as a video address,
 * the iframe may point at the YouTube player and nowhere else: the input
 * decides at most the 11-character id, never the scheme or the host.
 */
describe('toYoutubeEmbedUrl', () => {
  it('reads the id from every address form it supports', () => {
    for (const address of [
      'dQw4w9WgXcQ',
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
      'https://youtu.be/dQw4w9WgXcQ',
      'https://www.youtube.com/embed/dQw4w9WgXcQ',
      'https://www.youtube.com/shorts/dQw4w9WgXcQ'
    ]) {
      expect(toYoutubeEmbedUrl(address)).toBe('https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0');
    }
  });

  it('an embed address always points at www.youtube.com, whatever it is given', () => {
    const hostile = [
      'javascript:alert(1)',
      'data:text/html,<script>alert(1)</script>',
      'https://attacker.example/embed/dQw4w9WgXcQ',
      'https://attacker.example/watch?v=dQw4w9WgXcQ',
      'https://www.youtube.com.attacker.example/watch?v=abcdefghijk',
      '//attacker.example/embed/abcdefghijk',
      'https://www.youtube.com/watch?v=" onload="alert(1)',
      'https://www.youtube.com/watch?v=a/../../@x',
      'https://youtu.be/%2F%2Fattacker',
      'https://www.youtube.com/embed/..%2F..%2Fevil'
    ];
    for (const address of hostile) {
      const embed = toYoutubeEmbedUrl(address);
      if (embed !== null) {
        const url = new URL(embed);
        expect(url.protocol, address).toBe('https:');
        expect(url.host, address).toBe('www.youtube.com');
        expect(url.pathname.startsWith('/embed/'), address).toBe(true);
      }
    }
  });

  it('gives no id for anything that is not one', () => {
    expect(extractYoutubeId('')).toBeNull();
    expect(extractYoutubeId(null)).toBeNull();
    expect(extractYoutubeId('javascript:alert(1)')).toBeNull();
    expect(extractYoutubeId('https://example.com/watch')).toBeNull();
  });
});
