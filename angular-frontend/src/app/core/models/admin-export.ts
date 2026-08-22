export type AdminExportFamily =
  | 'audit-ledger'
  | 'read-evidence'
  | 'article-views'
  | 'search-history'
  | 'quiz-attempts'
  | 'change-events';

export interface AdminExportDefinition {
  family: AdminExportFamily;
  title: string;
  description: string;
  icon: string;
  filename: string;
}
