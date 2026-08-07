/** Mirrors web.VideoInstructionResponse field-for-field. */
export interface VideoInstruction {
  id: number;
  title: string;
  video_url: string;
  category: string | null;
  target_department: string;
  tags: string | null;
  created_at: string;
  views_count: number;
  is_archived: boolean;
}
