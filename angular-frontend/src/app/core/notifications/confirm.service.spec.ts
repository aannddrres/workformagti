import { describe, expect, it } from 'vitest';
import { ConfirmService } from './confirm.service';

describe('ConfirmService', () => {
  it('resolves with the answer the host reports', async () => {
    const service = new ConfirmService();

    const answer = service.ask('delete?');
    expect(service.pending()?.message).toBe('delete?');

    service.respond(true);
    await expect(answer).resolves.toBe(true);
    expect(service.pending()).toBeNull();
  });

  it('declines a question that is replaced before it is answered', async () => {
    const service = new ConfirmService();

    // A stranded promise is worse than a "no": the caller would wait forever.
    const first = service.ask('first');
    const second = service.ask('second');

    await expect(first).resolves.toBe(false);
    expect(service.pending()?.message).toBe('second');

    service.respond(true);
    await expect(second).resolves.toBe(true);
  });

  it('ignores a response when nothing is pending', () => {
    const service = new ConfirmService();
    expect(() => service.respond(true)).not.toThrow();
  });
});
