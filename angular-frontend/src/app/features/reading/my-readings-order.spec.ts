import { describe, expect, it } from 'vitest';
import { MyReading } from '../../core/models/compliance';
import { orderReadings } from './my-readings-page';

function reading(id: number, status: string, due: string | null, readAt: string | null = null, overdue = false): MyReading {
  return {
    reading: { id, item_type: 'article', item_id: id, target_department: 'All', due_date: due, priority: 'normal' },
    status,
    read_at: readAt,
    is_overdue: overdue,
    item_title: `item ${id}`,
    item_content: null,
    changed_since_read: false
  };
}

describe('orderReadings', () => {
  it('puts overdue first, then unread by nearest deadline, then read newest first', () => {
    const ordered = orderReadings([
      reading(1, 'read', '2026-09-01', '2026-09-10T10:00:00Z'),
      reading(2, 'pending', '2026-10-20'),
      reading(3, 'pending', '2026-09-05', null, true),
      reading(4, 'read', '2026-09-01', '2026-09-28T10:00:00Z'),
      reading(5, 'pending', '2026-10-01'),
      reading(6, 'pending', null)
    ]);

    expect(ordered.map((item) => item.reading.id)).toEqual([3, 5, 2, 6, 4, 1]);
  });

  it('leaves the list it was given untouched', () => {
    const list = [reading(1, 'read', null, '2026-09-10T10:00:00Z'), reading(2, 'pending', '2026-10-01')];
    orderReadings(list);
    expect(list.map((item) => item.reading.id)).toEqual([1, 2]);
  });
});
