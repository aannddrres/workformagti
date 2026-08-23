export type TrashItemType = 'article' | 'news' | 'video';

export interface ContentTrashItem {
  item_type: TrashItemType;
  item_id: number;
  title: string;
  trashed_at: string;
  purge_after: string;
  trashed_by: number;
  trashed_by_name: string | null;
  legal_hold: boolean;
}
