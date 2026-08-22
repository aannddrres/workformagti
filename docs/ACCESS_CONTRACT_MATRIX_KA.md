# წვდომის კონტრაქტის მატრიცა

**სტატუსი:** Phase 1 — decision/contract lock **დასრულებულია**; 6 გადაწყვეტილება ღიაა (D-2, D-4…D-8); D-1 და D-3 დახურულია
**ბოლო განახლება:** 2026-08-22 (Phase 6-ის review)
**წყარო:** `java-backend/src/main/java` — ყველა `@*Mapping`, 114 endpoint
**გეგმა:** `docs/ORG_ACCESS_ARCHITECTURE_PLAN_KA.md` (ფაზები, §9.1 სავალდებულო მტკიცებულებები)

ეს ფაილი არის ორგანიზაციული წვდომის **კონტრაქტი**: თითოეული backend endpoint-ისთვის
ერთ ადგილას წერია, დღეს რა კეტავს მას და სამიზნე მოდელში რა უნდა კეტავდეს.
Phase 3-ის shadow mode-ს და Phase 4-ის cutover-ს სჭირდებათ „სწორი პასუხის" ერთი
წყარო — ეს ის წყაროა.

## როგორ იკითხება

| სვეტი | ვინ ინახავს | CI ამოწმებს? |
|---|---|---|
| `endpoint`, `handler`, `gate (დღეს)` | **კოდი.** ავტომატურად სინქრონდება source-თან | **დიახ** — `AccessContractCoverageTest` |
| `capability (სამიზნე)`, `scope`, `PII`, `შენიშვნა` | **ადამიანი.** დაფიქსირებული გადაწყვეტილება | არა — ეს გადაწყვეტილებაა, არა ფაქტი |

`AccessContractCoverageTest` build-ს ტეხს, როცა:

1. კოდში ჩნდება `@*Mapping`, რომელიც ამ ცხრილში არ არის (**ახალი endpoint კონტრაქტის გარეშე**);
2. ცხრილში არის მწკრივი, რომელსაც კოდში endpoint აღარ შეესაბამება (**მოძველებული მწკრივი**);
3. endpoint-ის რეალური gate აღარ ემთხვევა `gate (დღეს)` სვეტს (**gate შეიცვალა კონტრაქტის განახლების გარეშე**).

მე-3 პუნქტი ყველაზე მნიშვნელოვანია: სწორედ ის აქცევს ამ ფაილს lock-ად და არა
ჩანაწერად, რომელიც პირველივე refactor-ზე მდუმარედ მოძველდება.

> **`gate (დღეს)` სვეტი ფაქტია, არა შეფასება.** თუ იგი გაძლიერდა ან დასუსტდა,
> ტესტი დაეცემა და ცხრილიც უნდა განახლდეს — ეს არის მოვლენა, რომელიც review-ს
> საჭიროებს, არა ჩუმად გასასწორებელი შეუსაბამობა.

## capability-ების ლექსიკონი

არსებული (`domain/Permission.java`):

`articles.edit` · `articles.publish` · `articles.archive` · `videos.archive` ·
`compliance.assign` · `reports.export` · `system.audit`

**ახალი, ამ ფაზაზე დასაფიქსირებელი** (Phase 6 ამატებს კატალოგში):

| capability | ფარავს | დასაბუთება |
|---|---|---|
| `content.manage` | news/video/category CRUD, upload, verify, history restore, quiz admin, აგრეგატული სტატისტიკა | წესი #9 ამ უფლებებს **ერთ კონა**დ აღწერს: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატიის რედაქტირება + კატეგორიების მართვა |
| `content.evidence` ⚠ | read-receipts, article views, feedback სია | **ცალკეა `content.manage`-ისგან განზრახ**, რადგან თანამშრომლის იდენტობას ატარებს — წესი #15 კრძალავს content permission-ით სტატისტიკის ხილვას |
| `messaging.broadcast` | `POST /api/broadcast` | Broadcast დამოუკიდებელი მოდულია (UI გეგმა §2), არა კონტენტის ქვესახეობა |
| `org.manage` | user CRUD, role, permissions, group-leaders | SYSTEM_ADMIN-ის სივრცე; AD-owned ნაწილი fail-closed |

> `content.manage`-ის დაშლა `news.edit`/`categories.manage`/`content.upload`-ად
> **განზრახ არ ხდება**. `PermissionChecker`-ის javadoc-ში აღწერილი ხაფანგი:
> permission, რომელსაც ერთადერთი role ატარებს, რომელიც bypass-ს აკეთებს,
> ვერასოდეს იმოქმედებს. ოთხი წვრილი switch, რომელიც ყოველთვის ერთად ინთება,
> ადმინს ეუბნება, რომ კონტროლი აქვს, რომელიც რეალურად არ აქვს.

## scope-ების ლექსიკონი

| scope | მნიშვნელობა |
|---|---|
| `NONE` | მომხმარებლის მონაცემი საერთოდ არ მონაწილეობს |
| `SELF` | მხოლოდ მომძახებლის საკუთარი მონაცემი |
| `CONTENT` | კონტენტის ხილვადობა target-department-ით (`DepartmentMatcher`), არა თანამშრომლის მონაცემი |
| `ORG-CONTENT` | კონტენტზე მოქმედება კომპანიის მასშტაბით — **არ** არის თანამშრომლის მონაცემზე წვდომა |
| `ORG-AGG` | ორგანიზაციის მასშტაბის აგრეგატი, იდენტობის გარეშე |
| `GROUP/DEPT` | მოქმედი leadership assignment-ით შემოსაზღვრული |
| `ORG` | მხოლოდ `SYSTEM_ADMIN` |
| `⚠` | გადასაწყვეტია — იხ. „ღია გადაწყვეტილებები" |

**უცვლელი წესი (გეგმის §8):** `ORG-CONTENT` და `ORG-AGG` **არასოდეს** გადადის
`GROUP/DEPT`-ში. კონტენტის ან export-ის permission თანამშრომლის მონაცემზე scope-ს
არ ქმნის; scope-ს იძლევა მხოლოდ `SYSTEM_ADMIN` bypass ან მოქმედი leadership assignment.

## ციფრებში

- **114** endpoint (111 + Phase 3-ის 3 დიაგნოსტიკური);
- **30** ატარებს თანამშრომლის საიდენტიფიკაციო მონაცემს (`PII = yes`);
- **11** უკვე leadership-scoped;
- **6** ღია გადაწყვეტილება (2 დახურულია; D-8 დაემატა Phase 6-ის review-ზე).

---

## მატრიცა

### Article (26)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/articles/stale` | `ArticleController.getStaleArticles` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/admin/feedback` | `ArticleController.getAdminFeedback` | `requireContentAdmin` | content.evidence `NEW` ⚠ | `⚠` | **yes** | **გადასაწყვეტი.** აბრუნებს operator-ის სახელს/email-ს/დეპარტამენტს/დაგვიანებას org-wide, მხოლოდ `requireContentAdmin`-ით. წესი #15 კრძალავს content permission-ით თანამშრომლის სტატისტიკას; მაგრამ per-article გაცნობის მტკიცებულება კონტენტის lifecycle-იცაა. |
| `GET /api/articles` | `ArticleController.getArticles` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles` | `ArticleController.createArticle` | `requireArticlesEditPermission`, `requireArticlesPublishPermission` | articles.edit + articles.publish | `ORG-CONTENT` | no | Phase 6: redundant content-admin role-gate მოიხსნა; explicit permission override მუშაობს. |
| `POST /api/articles/bulk-archive` | `ArticleController.bulkArchiveArticles` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `DELETE /api/articles/{id}` | `ArticleController.deleteArticle` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | Phase 6: redundant content-admin role-gate მოიხსნა. |
| `GET /api/articles/{id}` | `ArticleController.getArticle` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `PUT /api/articles/{id}` | `ArticleController.updateArticle` | `requireArticlesEditPermission`, `requireArticlesPublishPermission` | articles.edit + articles.publish | `ORG-CONTENT` | no | Phase 6: redundant content-admin role-gate მოიხსნა; explicit permission override მუშაობს. |
| `POST /api/articles/{id}/archive` | `ArticleController.archiveArticle` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `PATCH /api/articles/{id}/autosave` | `ArticleController.autosaveArticle` | `requireArticlesEditPermission`, `requireArticlesPublishPermission` | articles.edit + articles.publish | `ORG-CONTENT` | no | Phase 6: redundant content-admin role-gate მოიხსნა; explicit permission override მუშაობს. |
| `POST /api/articles/{id}/feedback` | `ArticleController.createArticleFeedback` | `requireAuthenticated` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
| `GET /api/articles/{id}/history` | `ArticleController.getArticleHistory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/articles/{id}/history/{historyId}/diff` | `ArticleController.getArticleDiff` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles/{id}/history/{historyId}/restore` | `ArticleController.restoreArticleVersion` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/articles/{id}/note` | `ArticleController.getUserNote` | `requireAuthenticated`, `requireVisibleArticle` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
| `PUT /api/articles/{id}/note` | `ArticleController.putUserNote` | `requireAuthenticated`, `requireVisibleArticle` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
| `POST /api/articles/{id}/read-receipt` | `ArticleController.createArticleReadReceipt` | `requireAuthenticated`, `requireQuizPassed` | AUTH | `SELF` | no | compliance-ის მტკიცებულება; retention purge-იდან გამორიცხული. |
| `GET /api/articles/{id}/read-receipt/me` | `ArticleController.getMyArticleReadReceiptStatus` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `GET /api/articles/{id}/read-receipts` | `ArticleController.getArticleReadReceipts` | `requireContentAdmin` | content.evidence `NEW` ⚠ | `⚠` | **yes** | **გადასაწყვეტი.** აბრუნებს operator-ის სახელს/email-ს/დეპარტამენტს/დაგვიანებას org-wide, მხოლოდ `requireContentAdmin`-ით. წესი #15 კრძალავს content permission-ით თანამშრომლის სტატისტიკას; მაგრამ per-article გაცნობის მტკიცებულება კონტენტის lifecycle-იცაა. |
| `GET /api/articles/{id}/related` | `ArticleController.getRelatedArticles` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles/{id}/unarchive` | `ArticleController.unarchiveArticle` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/articles/{id}/verify` | `ArticleController.verifyArticle` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/articles/{id}/versions` | `ArticleController.getArticleVersions` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles/{id}/view` | `ArticleController.trackArticleView` | `requireAuthenticated` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
| `GET /api/articles/{id}/views` | `ArticleController.getArticleViews` | `requireContentAdmin` | content.evidence `NEW` ⚠ | `⚠` | **yes** | **გადასაწყვეტი.** აბრუნებს operator-ის სახელს/email-ს/დეპარტამენტს/დაგვიანებას org-wide, მხოლოდ `requireContentAdmin`-ით. წესი #15 კრძალავს content permission-ით თანამშრომლის სტატისტიკას; მაგრამ per-article გაცნობის მტკიცებულება კონტენტის lifecycle-იცაა. |
| `GET /api/me/recently-viewed` | `ArticleController.getMyRecentlyViewed` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |

### AuditLog (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/audit-logs` | `AuditLogController.list` | `requireSystemAudit` | system.audit | `GROUP/DEPT` | **yes** | MANAGER-ს default-ად აქვს `system.audit`; Phase 4-ზე scope assignment-იდან. |
| `GET /api/audit-logs/chain-health` | `AuditLogController.chainHealth` | `requireSystemAuditNonManager` | system.audit + ORG | `ORG` | **yes** | წესი #14: manager-ს log-ის export ეკრძალება — უკვე ასეა (`requireSystemAuditNonManager`). |
| `GET /api/audit-logs/export` | `AuditLogController.export` | `requireSystemAuditNonManager` | system.audit + ORG | `ORG` | **yes** | წესი #14: manager-ს log-ის export ეკრძალება — უკვე ასეა (`requireSystemAuditNonManager`). |
| `GET /api/audit-logs/{id}/verify` | `AuditLogController.verify` | `requireSystemAuditNonManager` | system.audit + ORG | `ORG` | **yes** | წესი #14: manager-ს log-ის export ეკრძალება — უკვე ასეა (`requireSystemAuditNonManager`). |

### Auth (2)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/auth/login` | `AuthController.login` | — | — | `NONE` | no | ავტორიზაციამდელი; rate limit + `ClientIpResolver`. |
| `POST /api/auth/logout` | `AuthController.logout` | — | AUTH | `SELF` | no | ზრდის `token_version`-ს (SEC-14). |

### Category (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/categories` | `CategoryController.getCategories` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/categories` | `CategoryController.createCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `DELETE /api/categories/{id}` | `CategoryController.deleteCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PUT /api/categories/{id}` | `CategoryController.updateCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |

### Compliance (7)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/compliance/mark-read/{readingId}` | `ComplianceController.markRead` | `requireAuthenticated` | AUTH | `SELF` | no | compliance-ის მტკიცებულება; retention purge-იდან გამორიცხული. |
| `GET /api/compliance/my-progress` | `ComplianceController.getMyProgress` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `GET /api/compliance/my-readings` | `ComplianceController.getMyReadings` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `POST /api/compliance/required-readings` | `ComplianceController.createRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |
| `GET /api/compliance/required-readings/by-item/{itemType}/{itemId}` | `ComplianceController.getRequiredReadingForItem` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | Phase 6: read/edit drawer-იც იმავე capability-ით იმართება. |
| `DELETE /api/compliance/required-readings/{readingId}` | `ComplianceController.deleteRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |
| `PUT /api/compliance/required-readings/{readingId}` | `ComplianceController.updateRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |

### Export (6)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/export/download/{jobId}` | `ExportController.downloadExport` | `requireReportsExport` | reports.export + leadership | `⚠` | **yes** | **გადასაწყვეტი.** job-ის მფლობელი არ მოწმდება — id-ის მცოდნე სხვისი export-ს ჩამოტვირთავს. |
| `GET /api/export/readings` | `ExportController.exportReadingsCsv` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | სვეტები allowlist-ით (§ export allowlist). raw log ველი აკრძალულია. |
| `GET /api/export/readings.pdf` | `ExportController.exportReadingsPdf` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | სვეტები allowlist-ით (§ export allowlist). raw log ველი აკრძალულია. |
| `GET /api/export/readings.xlsx` | `ExportController.exportReadingsXlsx` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | სვეტები allowlist-ით (§ export allowlist). raw log ველი აკრძალულია. |
| `GET /api/export/status/{jobId}` | `ExportController.getExportStatus` | `requireReportsExport` | reports.export + leadership | `⚠` | **yes** | **გადასაწყვეტი.** job-ის მფლობელი არ მოწმდება — id-ის მცოდნე სხვისი export-ს ჩამოტვირთავს. |
| `GET /api/export/team-stats.pdf` | `ExportController.exportTeamStatsPdf` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | სვეტები allowlist-ით (§ export allowlist). raw log ველი აკრძალულია. |

### Favorite (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/favorites` | `FavoriteController.getFavorites` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `POST /api/favorites` | `FavoriteController.addFavorite` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `DELETE /api/favorites/{id}` | `FavoriteController.removeFavorite` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |

### Health (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/health` | `HealthController.health` | — | — | `NONE` | no | ინფრასტრუქტურული probe. |

### Messaging (6)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/broadcast` | `MessagingController.postBroadcast` | `requireContentAdmin` | messaging.broadcast `NEW` | `ORG-CONTENT` | no | დღეს role-gate. Broadcast დამოუკიდებელი მოდულია (UI გეგმა §2). |
| `GET /api/messages` | `MessagingController.getMyMessages` | `requireAuthenticated` | AUTH | `SELF` | no | პირადი messaging დადასტურებული scope-იდან ამოღებულია (UI გეგმა §2) — ახალ წესს არ იღებს. |
| `POST /api/messages` | `MessagingController.sendMessage` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | იგივე — scope-იდან ამოღებული. `DirectMessagePermission` „All"-ს wildcard-ად ინარჩუნებს. |
| `GET /api/messages/sent` | `MessagingController.getSentMessages` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | იგივე — scope-იდან ამოღებული. `DirectMessagePermission` „All"-ს wildcard-ად ინარჩუნებს. |
| `DELETE /api/messages/{messageId}` | `MessagingController.deleteMessage` | `requireAuthenticated` | AUTH | `SELF` | no | პირადი messaging დადასტურებული scope-იდან ამოღებულია (UI გეგმა §2) — ახალ წესს არ იღებს. |
| `POST /api/messages/{messageId}/read` | `MessagingController.markMessageRead` | `requireAuthenticated` | AUTH | `SELF` | no | პირადი messaging დადასტურებული scope-იდან ამოღებულია (UI გეგმა §2) — ახალ წესს არ იღებს. |

### News (8)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/news` | `NewsController.getNews` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/news` | `NewsController.createNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `DELETE /api/news/{id}` | `NewsController.deleteNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/news/{id}` | `NewsController.getNewsItem` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `PUT /api/news/{id}` | `NewsController.updateNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PATCH /api/news/{id}/autosave` | `NewsController.autosaveNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/news/{id}/history` | `NewsController.getNewsHistory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `POST /api/news/{id}/history/{historyId}/restore` | `NewsController.restoreNewsVersion` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |

### Platform (2)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/notifications/summary` | `PlatformController.getNotificationsSummary` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `GET /api/tags` | `PlatformController.getTags` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |

### PolicyDiagnostics (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/org-backfill/report` | `PolicyDiagnosticsController.getBackfillReport` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | dry run. ასახელებს მომხმარებლებს, რომელთა განთავსებაც ვერ მოხერხდა — ე.ი. თანამშრომლის მონაცემია. `blocks_cutover` არის §8-ის gate ერთ ველში. |
| `POST /api/admin/org-backfill/apply` | `PolicyDiagnosticsController.applyBackfill` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | იდემპოტენტური; აუდიტირდება `ORG_BACKFILL_APPLY`-ით. Phase 8 ამას ეკრანს დაადებს, არ შეცვლის ვის შეუძლია. |
| `GET /api/admin/policy-shadow` | `PolicyDiagnosticsController.getPolicyShadow` | `requireSystemAdmin` | org.manage `NEW` | `NONE` | no | მხოლოდ მრიცხველები decision point-ების მიხედვით; პერსონალურ მონაცემს არ ატარებს. `unexercised` რიცხვებზე ადრე უნდა წაიკითხოთ. |

### Quiz (5)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/articles/{id}/quiz` | `QuizController.getArticleQuiz` | `requireAuthenticated` | AUTH | `SELF` | no | compliance-ის მტკიცებულება; retention purge-იდან გამორიცხული. |
| `GET /api/articles/{id}/quiz/admin` | `QuizController.getArticleQuizAdmin` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PUT /api/articles/{id}/quiz/admin` | `QuizController.updateArticleQuizAdmin` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `POST /api/articles/{id}/quiz/attempt` | `QuizController.submitArticleQuizAttempt` | `requireAuthenticated` | AUTH | `SELF` | no | compliance-ის მტკიცებულება; retention purge-იდან გამორიცხული. |
| `GET /api/users/me/knowledge-score` | `QuizController.getMyKnowledgeScore` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |

### Search (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/search` | `SearchController.search` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `GET /api/search/global` | `SearchController.searchGlobal` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `GET /api/search/history` | `SearchController.searchHistory` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |

### Stats (12)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/critical-operators` | `StatsController.getCriticalOperators` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/admin/departments/{department}/groups/{groupName}/users` | `StatsController.getGroupUsers` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/admin/stats/team/{teamId}` | `StatsController.getAdminTeamStats` | `requireSystemAdmin` | AUTH + leadership | `ORG` | **yes** | Phase 0: SYSTEM_ADMIN-only. Phase 4: `{teamId}` scope-ზე უნდა შემოწმდეს, არა role-ზე. |
| `GET /api/manager/department-stats` | `StatsController.getDepartmentStats` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/manager/team-stats` | `StatsController.getTeamStats` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/statistics/activity` | `StatsController.getActivityTrend` | `requireContentManage` | content.manage | `ORG-AGG` | no | მხოლოდ აგრეგატები; იდენტობა არ ჟონავს. |
| `GET /api/statistics/breakdown` | `StatsController.getStatisticsBreakdown` | `requireContentManage` | content.manage | `ORG-AGG` | no | დაშვებული dimension: department/role/status — `COUNT`, სახელების გარეშე. |
| `GET /api/statistics/compliance` | `StatsController.getComplianceStatistics` | `requireContentManage` | content.manage | `ORG-AGG` | no | მხოლოდ აგრეგატები; იდენტობა არ ჟონავს. |
| `GET /api/statistics/failed-searches` | `StatsController.getFailedSearches` | `requireContentManage` | content.manage | `ORG-AGG` | no | მხოლოდ აგრეგატები; იდენტობა არ ჟონავს. |
| `GET /api/statistics/kpi` | `StatsController.getKpiCounts` | `requireContentManage` | content.manage | `ORG-AGG` | no | მხოლოდ აგრეგატები; იდენტობა არ ჟონავს. |
| `GET /api/statistics/popular-searches` | `StatsController.getPopularSearches` | `requireContentManage` | content.manage | `ORG-AGG` | no | მხოლოდ აგრეგატები; იდენტობა არ ჟონავს. |
| `GET /api/statistics/user-progress` | `StatsController.getUserProgress` | `requireSystemAdmin` | AUTH + leadership | `ORG` | **yes** | ყველა თანამშრომლის სახელი + დეპარტამენტი + პროცენტი. სწორად SYSTEM_ADMIN-only; Phase 4-ზე scope-ით უნდა გაიხსნას leadership-ისთვის, არა role-ით. |

### Upload (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/upload` | `UploadController.uploadFile` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |

### UploadedFile (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /uploads/{filename}` | `UploadedFileController.serve` | — | ⚠ | `⚠` | ⚠ | **გადასაწყვეტი:** დანართი ავტორიზაციას არ ითხოვს. იხ. `QUESTIONS_FOR_IT.md` §9. |

### User (13)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/group-leaders` | `UserController.getGroupLeaders` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | დღეს `role == MANAGER`-იდან; Phase 4-ზე leadership assignment-იდან. |
| `POST /api/admin/roles/bulk-reassign` | `UserController.bulkReassignRoles` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `GET /api/teams` | `UserController.getTeams` | `requireAuthenticated` | AUTH | `NONE` | no | ორგანიზაციული სტრუქტურა კითხვადია; AD-owned, mutation fail-closed. |
| `POST /api/teams` | `UserController.createTeam` | `requireSystemAdmin` | — | `NONE` | no | AD-owned: fail-closed `403` (Phase 0). dev fixture მხოლოდ seeder-ით. |
| `GET /api/users` | `UserController.listUsers` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `POST /api/users` | `UserController.createUserAdmin` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `GET /api/users/me` | `UserController.getCurrentUser` | `requireAuthenticated` | AUTH | `SELF` | no | `/api/me/effective-access` ამას ცვლის Phase 7-ზე; `bypass: true` აშკარად უნდა ჩანდეს. |
| `PUT /api/users/me` | `UserController.updateCurrentUser` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `POST /api/users/me/password` | `UserController.changeOwnPassword` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი მონაცემი. |
| `PUT /api/users/{userId}` | `UserController.updateUserAdmin` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `PUT /api/users/{userId}/permissions` | `UserController.adminUpdatePermissions` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | no | Phase 6: flat replace → `INHERIT`/`ALLOW`/`DENY` delta + optimistic concurrency. |
| `POST /api/users/{userId}/reset-password` | `UserController.adminResetPassword` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `PUT /api/users/{userId}/status` | `UserController.updateUserStatus` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |

### Video (7)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/videos` | `VideoController.getVideos` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/videos` | `VideoController.createVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `DELETE /api/videos/{id}` | `VideoController.deleteVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PUT /api/videos/{id}` | `VideoController.updateVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `POST /api/videos/{id}/archive` | `VideoController.archiveVideo` | `requireVideosArchivePermission` | videos.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/videos/{id}/unarchive` | `VideoController.unarchiveVideo` | `requireVideosArchivePermission` | videos.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/videos/{id}/view` | `VideoController.viewVideo` | `requireAuthenticated` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
---

## Export-ის სვეტების allowlist

გეგმის §8 ითხოვს server-side whitelist-ს, §9.1 — snapshot ტესტს. ქვემოთ არის
**დღეს რეალურად გენერირებული** სვეტები (`ExportController.java:129,160,184,201`).
ეს სია ხდება კონტრაქტი: ახალი სვეტი მდუმარედ ვერ გაჩნდება.

| endpoint | სვეტები |
|---|---|
| `GET /api/export/readings` (csv) | `User ID` · `User Name` · `Item Type` · `Item ID` · `Status` · `Read At` |
| `GET /api/export/readings.xlsx` | `თანამშრომელი` · `დეპარტამენტი` · `მასალის ტიპი` · `მასალის ID` · `სტატუსი` · `წაკითხვის თარიღი` · `ვადა` |
| `GET /api/export/readings.pdf` | `თანამშრომელი` · `დეპარტამენტი` · `ტიპი` · `ID` · `სტატუსი` · `წაკითხვა` · `ვადა` |
| `GET /api/export/team-stats.pdf` | `დეპარტამენტი` · `სულ მიკუთვნებული` · `წაკითხული` · `%` |

**აკრძალული კატეგორიები (წესი #14).** არცერთ export-ში არ დაიშვება ველი, რომელიც
წარმოიშობა: `audit_logs` · `article_view_logs` · `search_logs` · session/token
მდგომარეობა · უსაფრთხოების მოვლენები · IP მისამართი · `hashed_password` ·
`token_version`.

**აღსრულება:** `ExportColumnAllowlistTest`. სვეტები დამაგრებულია სამივე
ფორმატზე ცალ-ცალკე, დამატებით კი დამაგრებულია `ReadingExportRow` — ის ერთადერთი
shape-ია, საიდანაც სამივე readings-export კითხულობს, ე.ი. ველი, რომელიც იქ ვერ
გაჩნდება, ვერცერთ ფაილში ვერ მოხვდება. ეს განზრახ ორივე დონეზეა: მარტო
სათაურების დამაგრება არ დაიჭერდა ველს, რომელიც სხვა ეკრანისთვის დაემატა და
export-მა ავტომატურად აიტაცა.

> csv სათაურები ინგლისურია, xlsx/pdf — ქართული. ეს **დღევანდელი ფაქტია**, არა
> გადაწყვეტილება; პროდუქტი ქართულენოვანია, ე.ი. csv-ის სათაურები ცალკე,
> აშკარა UX გადაწყვეტილებას საჭიროებს — allowlist-ის ჩაკეტვამდე ან მის შემდეგ.

---

## Response-shape კონტრაქტები

Status code-ის შემოწმება ადვილია, ველისა — არა, ამიტომ scope-ის ტესტები
ჩვეულებრივ პირველს ამოწმებენ. SEC-03 ზუსტად ამ ხარვეზში ჩავარდა: gate სწორი
იყო, პასუხის **ფორმა** — არა. `members` სია დაიფარა, ხოლო sibling ჯგუფების
`compliance`/`output_volume`/`critical_count` იმავე payload-ით გავიდა.

ქვემოთ დაფიქსირებულია, რა JSON გასაღებები **შეიძლება** ატაროს თითოეულმა
პასუხმა, რომელიც თანამშრომლის იდენტობას ეხება. აღსრულება:
`ResponseShapeContractTest` — ველის დამატებაც და გასაღების გადარქმევაც build-ს
ტეხს.

| response | JSON გასაღებები | სად ჩანს |
|---|---|---|
| `DepartmentMember` | `user_id` · `user_name` · `position` · `read_count` · `required_count` · `percentage` · `is_critical` | department-stats, group drill-down |
| `DepartmentGroupStats` | `name` · `full_department` · `member_count` · `compliance` · `output_volume` · `critical_count` · `members` | department-stats — **SEC-03-ის გაჟონვის ადგილი** |
| `DepartmentStats` | `name` · `member_count` · `group_count` · `compliance` · `output_volume` · `critical_count` · `is_empty` · `groups` | department-stats |
| `TeamMemberCompletion` | `user_id` · `user_name` · `read_count` · `required_count` · `percentage` | team-stats |
| `GroupMemberCompletion` | `user_id` · `first_name` · `last_name` · `completion_percentage` | group users |
| `CriticalOperator` | `user_id` · `first_name` · `last_name` · `department` · `overdue_count` | critical-operators |
| `UserProgressItemResponse` | `user_id` · `user_name` · `department` · `read_count` · `required_count` · `percentage` | user-progress (`ORG`) |
| `ArticleReadReceiptRowResponse` | `operator_id` · `operator_name` · **`operator_email`** · `department` · `read_at` · `article_version` · `has_read` · `is_late` · `deadline` · `status` | read-receipts — **D-2** |
| `ArticleViewRowResponse` | `operator_id` · `operator_name` · **`operator_email`** · `department` · `article_version` · `viewed_at` | article views — **D-2** |
| `GroupLeaderResponse` | `id` · `name` | group-leaders |

**წესი:** ამ ცხრილში ველის დამატება ნიშნავს კითხვას „ვის აქვს მისი ნახვის
უფლება და რომელ scope-ზე?" — და პასუხი მატრიცაში უნდა ჩაიწეროს **ველის
გაშვებამდე**, არა მას შემდეგ, რაც ვინმე მას პასუხში შეამჩნევს.

> `LeaderboardEntryResponse` განზრახ არის დამაგრებული, სანამ **D-1** ღიაა:
> ფორმა თავად არის გადაწყვეტილება. თუ D-1 ანონიმიზაციით დაიხურება, სწორედ ეს
> ტესტი შეიცვლება — ე.ი. ცვლილება review-ში გამოჩნდება და არა მდუმარედ.

---

## ღია გადაწყვეტილებები

ეს რვა პუნქტი Phase 2-ის დაწყებას **არ** აჩერებს (schema მათზე არ არის
დამოკიდებული), მაგრამ Phase 4-ის cutover-მდე უნდა დაიხუროს.

### D-1. `GET /api/knowledge-leaderboard` — ✅ გადაწყვეტილია (2026-08-21)

**გადაწყვეტილება: endpoint ამოღებულია.** მფლობელის პასუხი: leaderboard, როგორც
ფუნქცია, არ არის საჭირო.

წაიშალა `QuizController`-ის endpoint, `LeaderboardEntryResponse` და
`LeaderboardResponse`. `GET /api/users/me/knowledge-score` **რჩება** — ის
მომძახებლის საკუთარი მონაცემია (`SELF`).

რატომ ამოვიდა ეს Phase 2-ზე და არა მოგვიანებით: endpoint-ის `scope=team`
კითხულობდა `users.team_id`-ს, რომელიც 0 row-ზეა შევსებული და მდუმარედ
department-ზე ჩამოდიოდა. `V36`-ის backfill ამ სვეტს ავსებს, ე.ი. ეს განშტოება
პირველად ამუშავდებოდა **schema მიგრაციის გვერდით ეფექტად**, პროდუქტული
გადაწყვეტილების გარეშე.

G-3 (DPO gate) ამით იხურება — გასავრცელებელი პერსონალური მონაცემი აღარ არსებობს.

### D-2. `content.evidence` — read-receipts / views / feedback

`GET /api/articles/{id}/read-receipts` აბრუნებს `operator_id` · `operator_name` ·
`operator_email` · `department` · `read_at` · `is_late` · `status`-ს **org-wide**,
მხოლოდ `requireContentAdmin`-ით. იგივე ეხება `/views`-ს და `/api/admin/feedback`-ს.

წესი #15: content permission თანამშრომლის სტატისტიკას არ ხსნის. მაგრამ „ვინ
გაეცნო ამ სტატიის ამ ვერსიას" კონტენტის lifecycle-ის ნაწილიცაა და ავტორს
ლეგიტიმურად სჭირდება.

*ვარიანტები:* (ა) `content.evidence` capability, org-wide — ავტორი ხედავს ვინ გაეცნო;
(ბ) leadership scope-ით შეზღუდვა — ავტორი ხედავს მხოლოდ აგრეგატს, სახელებს კი უფროსი;
(გ) აგრეგატი ყველას, სახელები მხოლოდ leadership-ით.
**რეკომენდაცია: (გ)** — ავტორს რჩება ის, რაც რეალურად სჭირდება (რამდენმა გაიცნო),
სახელები კი იმ წესს ემორჩილება, რომელსაც დანარჩენი თანამშრომლის მონაცემი.

### D-3. `GET /api/export/download/{jobId}` — ✅ გადაწყვეტილია (2026-08-21)

**გადაწყვეტილება: `export_jobs.owner_user_id` დაემატა `V36`-ს**, ხოლო download
და status მფლობელზე მოწმდება (`SYSTEM_ADMIN` bypass-ით).

სვეტი nullable-ია: `V36`-მდე შექმნილ row-ებს მფლობელი არ აქვთ ჩაწერილი.
უცნობი მფლობელი იკითხება როგორც „**არა შენი**", არა როგორც „შენი" — ე.ი. ძველი
row-ები fail-closed რჩება და არსებული TTL-ით ცვივა, backfill-ის გარეშე.

სხვისი job და არარსებული job **ერთნაირად** პასუხობს (`404` status-ზე, `410`
download-ზე): განსხვავებული პასუხი endpoint-ს აქცევდა oracle-ად იმისთვის,
რომელი job id არსებობს.

### D-4. `GET /uploads/{filename}` — ავტორიზაცია არ მოითხოვება

`QUESTIONS_FOR_IT.md` §9-ის ღია კითხვა. მატრიცაში ერთადერთი `⚠` scope, რომელიც
პროდუქტულ და არა ტექნიკურ პასუხს ელოდება.

### D-5. `content.manage`-ის მარცვლოვნება

დაფიქსირებულია ერთი capability (იხ. ლექსიკონი). თუ პროდუქტს სჭირდება „კატეგორიების
მართვა კონტენტის შექმნის გარეშე", მაშინ ეს დაშლა **ახლა** უნდა მოხდეს, არა Phase 6-ზე.

### D-6. csv-ის ინგლისური სათაურები

იხ. export-ის allowlist. მცირე, მაგრამ allowlist-ის ჩაკეტვამდე გადასაწყვეტი.

### D-7. `messaging` — scope-იდან ამოღებული, კოდში დარჩენილი

6 endpoint. UI გეგმა §2 პირადი messaging-ს დადასტურებული scope-იდან ხსნის, მაგრამ
`POST /api/messages` და `/api/messages/sent` თანამშრომლის მონაცემს ატარებს და
`DirectMessagePermission` „All"-ს wildcard-ად ინარჩუნებს.
**რეკომენდაცია:** მოდულს ახალი scope წესი **არ** მიეცეს; ის ან წაიშალოს Phase 6-ზე,
ან აშკარად გამოცხადდეს legacy-დ. შუალედური მდგომარეობა — ამოღებული მოდული, რომელიც
scope-ის მიგრაციას მაინც იღებს — ყველაზე ცუდი ვარიანტია.

### D-8. `/api/statistics/*` `content.manage`-ის ქვეშ vs. სამიზნე მოდელის წესი #8

**ეს გადაწყვეტილება ჩემი (Claude) იყო და Phase 6-ზე უკვე იმპლემენტირდა — ამიტომ
`⚠` არ დამიწერია არცერთ მწკრივზე. მაგრამ ის ეწინააღმდეგება წესს, რომელიც
პროდუქტის მფლობელმა დაადასტურა, და ამიტომ ღიად უნდა იდგეს.**

ექვსი აგრეგატული endpoint (`activity`, `breakdown`, `compliance`,
`failed-searches`, `kpi`, `popular-searches`) `requireContentAdmin`-იდან
`requireContentManage`-ზე გადავიდა. მათი scope `ORG-AGG`-ია: მხოლოდ `COUNT`-ები,
სახელების და იდენტობის გარეშე.

**კონფლიქტი.** სამიზნე მოდელის წესი #8 ამბობს: *ჯგუფის ლიდერს შეუძლია
content permission-ების ქონა სტატისტიკის ხილვადობის გაფართოების გარეშე*.
დღეს `MANAGER`, რომელსაც `content.manage` **ALLOW** მიენიჭება იმისთვის, რომ
სტატია გამოაქვეყნოს, ავტომატურად იღებს კომპანიის მასშტაბის აგრეგატულ
სტატისტიკასაც. ეს სწორედ ის გაფართოებაა, რომელსაც წესი #8 კრძალავს.

PII არ ჟონავს — ამიტომ ეს არ არის Phase 6-ის blocker და არაფერი გამისწორებია
კოდში. მაგრამ არჩევანი ორია და მფლობელისაა:

* **(ა)** `ORG-AGG` მისაღებია: აგრეგატი არ არის „სტატისტიკის ხილვადობა" წესი #8-ის
  გაგებით, რომელიც სახელობით მონაცემებზეა. მაშინ წესი #8-ის ფორმულირება უნდა
  დაზუსტდეს `PRODUCT_UX`-ში, თორემ შემდეგი მკითხველი იმავე კითხვას დასვამს.
* **(ბ)** ცალკე `stats.view` capability გამოიყოს და ეს ექვსი მასზე გადავიდეს.
  `content.manage` მაშინ მხოლოდ კონტენტს ეხება და წესი #8 ლიტერალურად სრულდება.

**ჩემი რეკომენდაცია: (ბ).** არა იმიტომ, რომ დღეს რამე ჟონავს, არამედ იმიტომ, რომ
`content.manage` სახელი უკვე ორ სხვადასხვა რამეს ნიშნავს, და capability, რომელიც
თავის სახელს არ შეესაბამება, ზუსტად ის მექანიზმია, რომლითაც მომდევნო ფაზაზე
სტატისტიკა ვიღაცას შემთხვევით გაუხსნება.

---

## production-მდე გარე gate-ები

გეგმის §10-ის კონკრეტიზაცია. **არცერთი მათგანი კოდით არ იხურება.**

| # | gate | ვისგან | სტატუსი |
|---|---|---|---|
| G-1 | უფროსის export-ში თანამშრომლის სახელების დაშვება | იურიდიული / DPO | 🔲 ღიაა |
| G-2 | export-ის statistical სვეტების საბოლოო whitelist | იურიდიული / DPO | 🔲 ღიაა |
| G-3 | D-1 (leaderboard) — პერსონალური მონაცემის გავრცელება თანაკოლეგებზე | იურიდიული / DPO + product owner | ✅ ჩაკეტილია — endpoint ამოღებულია |
| G-4 | departments/groups/users stable external ID-ები | Magti IT | 🔲 ღიაა — `QUESTIONS_FOR_IT.md` §2 |
| G-5 | ჯგუფის ცვლილებების feed და deactivation semantics | Magti IT | 🔲 ღიაა — `QUESTIONS_FOR_IT.md` §2 |
| G-6 | დანართის ავტორიზაცია (D-4) | Magti IT + product owner | 🔲 ღიაა — `QUESTIONS_FOR_IT.md` §9 |

G-1…G-3 **product/DPO** gate-ებია და ამ ფაილში ცხოვრობენ; G-4…G-6 IT-ის
კომპეტენციაა და `QUESTIONS_FOR_IT.md`-ში, რომელსაც მფლობელი IT-სთან ერთად
პერიოდულად გადახედავს (CLAUDE.md).
