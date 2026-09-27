# წვდომის კონტრაქტის მატრიცა

**სტატუსი:** Phase 1 — decision/contract lock **დასრულებულია**; D-1…D-8 დახურულია
**ბოლო განახლება:** 2026-09-28 (პირადი მონახაზის წვდომა და გაცნობის ხილვადობა)

**2026-09-28 — ქვემოთ მოცემული endpoint-ების დამატებითი ობიექტური შეზღუდვები:**

- სტატიისა და სიახლის private draft მხოლოდ ავტორისაა. არსებული permission gate-ის შემდეგ სხვისი მონახაზი 404-ია update/command/autosave/delete/archive/unarchive/verify, history/restore, receipts/views და სხვა ID-ით წვდომის გზებზე; ცარიელი autosave და payload-ში `is_draft=false` გამონაკლისს არ ქმნის. bulk archive/status/retarget ასეთ ID-ს `skipped_ids`-ში ტოვებს და არ ცვლის.
- სიახლის history list/summary/item იგივე ავტორის წესს იცავს. არარსებული ID-ის ისტორიის სიის ძველი ცარიელი პასუხი უცვლელია.
- სტატიისა და სიახლის history ownership მოწმდება კალათაში მყოფ მშობელზეც. კალათის entity filter-ს ამ წესის გვერდის ავლა არ შეუძლია.
- `GET /api/notifications/summary` და `GET /api/me/recently-viewed` სხვისი პირადი მასალის სათაურს არ აბრუნებს. `GET/POST /api/favorites` ასეთ სათაურს „მასალა #ID“-ით ცვლის, bookmark-ის წაშლის გარეშე.
- compliance-ის create/by-item/update/delete გზები სხვის private article/news-ზე 404-ია. `my-readings` და შეტყობინებების დავალებები სხვის პირად მასალას არ აჩვენებს; historical rows უცვლელია. ფონური reminder-ის სათაურში პირადი კონტენტის მიმდინარე სათაური არ გადადის.
- `GET /api/content-trash` სხვის პირად article/news-ს არ აჩვენებს; restore/purge/legal-hold ოპერაციები მისთვის 404-ია, შესაბამისი role/named-authority gate-ის გავლის შემდეგ. ვიდეოს არსებული წესები უცვლელია.
- სტატიის stale/related reference სიები პირად მონახაზს გამორიცხავს, მათ შორის ძველ შეუსაბამო `is_draft=true,status=published` ჩანაწერს.
- `POST /api/compliance/mark-read/{readingId}`: article assignment-ის არსებობა ხილვადობას არ ანაცვლებს. `ArticleVisibility` მოწმდება სრული აუდიტორიით receipt/status/audit ცვლილებამდე; დამალული სტატია 404 `READING_NOT_FOUND`-ია. PO-30-ის პირველი receipt უცვლელია.
**წყარო:** `java-backend/src/main/java` — ყველა `@*Mapping`, 150 endpoint
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

`articles.edit` · `articles.archive` · `videos.archive` ·
`compliance.assign` · `reports.export`

**ახალი, ამ ფაზაზე დასაფიქსირებელი** (Phase 6 ამატებს კატალოგში):

| capability | ფარავს | დასაბუთება |
|---|---|---|
| `content.manage` | news/video/category CRUD, upload, verify, history restore, quiz admin | წესი #9 ამ უფლებებს **ერთ კონა**დ აღწერს: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატიის რედაქტირება + კატეგორიების მართვა |
| `content.evidence` | ოფიციალური read-receipts | აგრეგატი შეიძლება `content.manage`-ს დარჩეს; სახელობითი ოფიციალური rows მხოლოდ leadership scope-ით, `SYSTEM_ADMIN` კი org-wide ხედავს. article view არის ცალკე SYSTEM_ADMIN-only log; feedback წაიშალა |
| `announcement.publish` | საიტის საერთო განცხადების გამოქვეყნება (`POST /api/broadcasts`) | პირადი messaging-ისგან განცალკევებული, ყველა ავტორიზებული თანამშრომლის მთავარ გვერდსა და პროფილში ხილული passive ინფორმაცია; აუდიტორია ფიქსირებულად მთელი კომპანიაა |
| `stats.view` | კომპანიის მასშტაბის აგრეგატული სტატისტიკა, სახელების გარეშე | **D-8 გადაწყვეტილია:** `content.manage`-ისგან დამოუკიდებელი უფლება; SYSTEM_ADMIN ცალკე გასცემს |
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
| `⚠` | კონტრაქტის ხარვეზია; 0 ღია პროდუქტული გადაწყვეტილების პირობებში მატრიცაში არ უნდა დარჩეს |

**უცვლელი წესი (გეგმის §8):** `ORG-CONTENT` და `ORG-AGG` **არასოდეს** გადადის
`GROUP/DEPT`-ში. კონტენტის ან export-ის permission თანამშრომლის მონაცემზე scope-ს
არ ქმნის; scope-ს იძლევა მხოლოდ `SYSTEM_ADMIN` bypass ან მოქმედი leadership assignment.

## ციფრებში

- **150** endpoint;
- **44** ატარებს თანამშრომლის საიდენტიფიკაციო მონაცემს (`PII = **yes**`; კიდევ 1 — `content-dependent`);
- **11** უკვე leadership-scoped (`scope` სვეტი `GROUP`-ით იწყება, ე.ი. leadership assignment-ით შემოსაზღვრულია);
- **0** ღია გადაწყვეტილება (D-1…D-8 დახურულია).

---

## მატრიცა

### AccessDiff (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/access-diff` | `AccessDiffController.getAccessDiff` | `requireSystemAdmin` | — | `ORG` | **yes** | Phase 9A. read-only cutover evidence: თითო განსხვავებული მომხმარებლის სახელი, legacy/proposed compliance და scope-ში მხოლოდ მომხმარებელთა რაოდენობები; არც apply და არც scope-ის წევრთა სახელები. |

### Article (30)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/articles/stale` | `ArticleController.getStaleArticles` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/articles` | `ArticleController.getArticles` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles` | `ArticleController.createArticle` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | `articles.edit` პირდაპირ გამოქვეყნებასაც მოიცავს; ცალკე publish permission აღარ არსებობს. |
| `POST /api/articles/command` | `ArticleCommandController.create` | — | articles.edit + compliance.assign | `ORG-CONTENT` | no | სტატია, სავალდებულო დავალება და ქვიზი ერთ ტრანზაქციაში ინახება; gate-ები დელეგირებულ controller ოპერაციებში სრულდება. |
| `POST /api/articles/bulk-archive` | `ArticleController.bulkArchiveArticles` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/articles/bulk-status` | `ArticleController.bulkSetArticleStatus` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | bulk-archive-ის განზოგადება: draft/published/archived. იგივე gate, რადგან სამივე გამოქვეყნების გადაწყვეტილებაა და არა შიგთავსის ცვლილება. თითოეული სტატია ცალკე აუდიტდება. |
| `POST /api/articles/bulk-retarget` | `ArticleController.bulkRetargetArticles` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | კატეგორიისა და აუდიტორიის მასობრივი შეცვლა. **archive-ზე მკაცრი gate განზრახ**: აუდიტორიის შეცვლა ერთადერთი მასობრივი ოპერაციაა, რომელსაც შეუძლია მასალა იმ ადამიანებამდე მიიტანოს, ვისთვისაც დაწერილი არ ყოფილა. ცარიელი დეპარტამენტების სია უარყოფილია. |
| `DELETE /api/articles/{id}` | `ArticleController.deleteArticle` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | R5: მხოლოდ უკვე არქივირებული სტატია გადადის 30-დღიან აღდგენად სანაგვეში; hard delete არაა. |
| `GET /api/articles/{id}` | `ArticleController.getArticle` | `requireAuthenticated` | AUTH | `CONTENT` | no | გამოქვეყნებულის ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`); `is_draft` მხოლოდ ავტორს ეხსნება, content/system admin-ის bypass-ის გარეშეც. სხვის მონახაზზე 404. |
| `PUT /api/articles/{id}` | `ArticleController.updateArticle` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | `articles.edit` პირდაპირ გამოქვეყნებასაც მოიცავს. |
| `PUT /api/articles/{id}/command` | `ArticleCommandController.update` | — | articles.edit + compliance.assign | `ORG-CONTENT` | no | სტატიის, დავალებისა და ქვიზის ცვლილება ატომურია; ნებისმიერი ნაწილის უარყოფა მთლიან ცვლილებას rollback-ს უკეთებს. |
| `POST /api/articles/{id}/archive` | `ArticleController.archiveArticle` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `PATCH /api/articles/{id}/autosave` | `ArticleController.autosaveArticle` | `requireArticlesEditPermission` | articles.edit | `ORG-CONTENT` | no | Autosave არ აქვეყნებს live ვერსიას; `articles.edit` არის ერთადერთი authoring capability. |
| `GET /api/articles/{id}/history` | `ArticleController.getArticleHistory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | 2026-09-25: არსებული სტატიისთვის დამატებით `ArticleVisibility`; სხვისი პირადი draft 404-ია კონტენტის ადმინისტრატორისთვისაც. არარსებული article ID ძველი კონტრაქტით ცარიელ სიას აბრუნებს. |
| `GET /api/articles/{id}/history-summary` | `ArticleController.getArticleHistorySummary` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | 2026-09-25: იგივე `ArticleVisibility` შემოწმება; სხვისი პირადი draft 404-ია. CLOB-free metadata list. |
| `GET /api/articles/{id}/history/{historyId}` | `ArticleController.getArticleHistoryItem` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | 2026-09-25: იგივე `ArticleVisibility` შემოწმება; სხვისი პირადი draft 404-ია. `{historyId}` owning article-ზეა scoped. |
| `GET /api/articles/{id}/history/{historyId}/diff` | `ArticleController.getArticleDiff` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით; base და explicit compare history IDs ორივე owning article-ზეა scoped. |
| `POST /api/articles/{id}/history/{historyId}/restore` | `ArticleController.restoreArticleVersion` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | 2026-09-25: დამატებით `ArticleVisibility`; სხვისი პირადი draft 404-ია და article/history/audit უცვლელია. Foreign-parent history ID-ც 404-ია. |
| `GET /api/articles/{id}/note` | `ArticleController.getUserNote` | `requireAuthenticated`, `requireVisibleArticle` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე; იგივე article-ზე სხვა მომხმარებლის note არ ჩანს და absent value literal JSON `null`-ია. |
| `PUT /api/articles/{id}/note` | `ArticleController.putUserNote` | `requireAuthenticated`, `requireVisibleArticle` | AUTH | `SELF` | no | ჩანაწერი `(user_id, article_id)` ownership key-ზე ინახება; ორი caller-ის notes ერთმანეთს არ overwrite-ავს. |
| `POST /api/articles/{id}/read-receipt` | `ArticleController.createArticleReadReceipt` | `requireAuthenticated`, `requireQuizPassed` | AUTH | `SELF` | no | PO-30: იმავე ვერსიის პირველი ქვითარი/დრო უცვლელია განმეორებაზე; covering mandatory status-ები იგივე ტრანზაქციაში ივსება და ყველა რეალური ცვლილება აუდიტდება. Retention purge-იდან გამორიცხული. |
| `GET /api/articles/{id}/read-receipt/me` | `ArticleController.getMyArticleReadReceiptStatus` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ caller-ის `(article, version, operator)` receipt; სხვა caller-ის receipt false მდგომარეობას არ ცვლის. |
| `GET /api/articles/{id}/read-receipts` | `ArticleController.getArticleReadReceipts` | `requireReadEvidenceAccess` | content.evidence | `GROUP` (`SYSTEM_ADMIN`: `ORG`; `content.manage`: aggregate-only) | **yes** | პირველი rollout: მოქმედი ჯგუფის assignment სახელობით rows-ს მხოლოდ საკუთარ ჯგუფზე ხსნის; `content.manage` org-wide საერთო რაოდენობებს, მაგრამ არა სახელებს; `SYSTEM_ADMIN` org-wide სახელობით rows-ს. დეპარტამენტის assignment ჯერ არააქტიურია. email response-ში არ შედის. |
| `GET /api/articles/{id}/related` | `ArticleController.getRelatedArticles` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles/{id}/unarchive` | `ArticleController.unarchiveArticle` | `requireArticlesArchivePermission` | articles.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/articles/{id}/verify` | `ArticleController.verifyArticle` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/articles/{id}/versions` | `ArticleController.getArticleVersions` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/articles/{id}/view` | `ArticleController.trackArticleView` | `requireAuthenticated` | AUTH | `SELF` | no | საკუთარი ჩანაწერი ხილულ კონტენტზე. |
| `GET /api/articles/{id}/views` | `ArticleController.getArticleViews` | `requireSystemAdmin` | SYSTEM_ADMIN-only log | `ORG` | **yes** | სტატიის უბრალო გახსნა ოფიციალური წაკითხვა არ არის და leadership evidence-ში არ ჩანს. export ცალკე SYSTEM_ADMIN-only log surface-ზე კეთდება. |
| `GET /api/me/recently-viewed` | `ArticleController.getMyRecentlyViewed` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის view rows dedupe-დება; სხვა caller-ის recently-viewed ჩანაწერი არ ჩანს. |

### AuditLog (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/audit-logs` | `AuditLogController.list` | `requireSystemAdmin` | SYSTEM_ADMIN role | `ORG` | **yes** | Raw audit/log მონაცემი მხოლოდ სისტემურ ადმინს აქვს; მენეჯერს რჩება scoped compliance UI. |
| `GET /api/audit-logs/chain-health` | `AuditLogController.chainHealth` | `requireSystemAdmin` | SYSTEM_ADMIN role | `ORG` | **yes** | Integrity tooling მხოლოდ სისტემური ადმინისთვისაა. |
| `GET /api/audit-logs/export` | `AuditLogController.export` | `requireSystemAdmin` | SYSTEM_ADMIN role | `ORG` | **yes** | სრული log-export მხოლოდ სისტემური ადმინისთვისაა. |
| `GET /api/audit-logs/{id}/verify` | `AuditLogController.verify` | `requireSystemAdmin` | SYSTEM_ADMIN role | `ORG` | **yes** | ჩანაწერის integrity verification მხოლოდ სისტემური ადმინისთვისაა. |

### Auth (6)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/auth/login` | `AuthController.login` | — | — | `NONE` | no | ავტორიზაციამდელი; rate limit + `ClientIpResolver`. `CORPORATE_AUTH_ENABLED=true`-ით პაროლს კომპანიის OAuth2 სერვისი (`ldap_auth`) ამოწმებს; ტოკენები არ ინახება. PO-31: მინიმუმ ერთი `OAUTH_ROLE_MAP`-ში ცნობილი InfoPortal როლი აუცილებელია; არქონა ახალ შესვლას უარყოფს და არსებული ანგარიშის ძველ token-ებს აუქმებს, ჩანაწერებს არ შლის. გათიშული ანგარიში სწორი პაროლითაც ვერ შედის (PO-24). ყველა წარუმატებელ შესვლაზე ერთი ტექსტი (PO-26); მიუწვდომელი სერვისი → 503, `LOGIN_FAILED` არ იწერება. production-ში ჩამრთველის გარეშე 403 (PO-25). |
| `POST /api/auth/logout` | `AuthController.logout` | `requireAuthenticated` | AUTH | `SELF` | no | ზრდის `token_version`-ს (SEC-14). PO-20 (2026-08-31): მოქმედ სესიას მოითხოვს; ანონიმური idempotent გასვლა მოხსნილია, რადგან frontend ვადაგასულ ჩანართში აღარავის ტოვებს. |
| `POST /api/auth/sso/start` | `AuthController.startCorporateSso` | — | — | `NONE` | no | ყოველთვის 503. IT-მ redirect-ის ნაცვლად `ldap_auth` grant აირჩია, ამიტომ კომპანიის შესვლა `POST /api/auth/login`-ით ხდება; ეს გზა redirect-flow-ს არ ელოდება. |
| `POST /api/auth/session/heartbeat` | `PortalSessionController.heartbeat` | `requireAuthenticated` | AUTH | `SELF` | no | global authenticated boundary + caller/session binding: valid-CSRF/no-auth-ზე stable JSON 401; valid token მხოლოდ caller-ის bind-ებულ session `last_seen_at`-ს touch-ავს და სხვა user-ის session-ს არ ცვლის. |
| `GET /api/auth/sessions` | `PortalSessionController.list` | `requireAuthenticated` | AUTH | `SELF` | **yes** | მხოლოდ მომძახებლის მოქმედი session-ები და მიმდინარე session marker. |
| `DELETE /api/auth/sessions/{sessionId}` | `PortalSessionController.revoke` | `requireAuthenticated` | AUTH | `SELF` | **yes** | მხოლოდ მომძახებლის session-ის revoke; უცხო valid id established 204 opaque no-op-ია, owner session/token აქტიური რჩება, უცხო list-ში არ ჩანს და success audit არ იწერება. |

### Category (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/categories` | `CategoryController.getCategories` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/categories` | `CategoryController.createCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `DELETE /api/categories/{id}` | `CategoryController.deleteCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: გამოყენებული კატეგორია `409`-ით იკეტება; ჩუმი fallback reassignment აღარ ხდება. |
| `PUT /api/categories/{id}` | `CategoryController.updateCategory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |

### Compliance (7)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/compliance/mark-read/{readingId}` | `ComplianceController.markRead` | `requireAuthenticated` | AUTH | `SELF` | no | PO-30: პირველი `read_at` და შესაბამისი versioned receipt უცვლელია განმეორებაზე; ორივე ცვლილება და განმეორებითი მცდელობა აუდიტდება. Status `(user_id, required_reading_id)`-ზე და receipt caller-ზე bind-დება; სხვა eligible caller `unread` რჩება. Retention purge-იდან გამორიცხულია. |
| `GET /api/compliance/my-progress` | `ComplianceController.getMyProgress` | `requireAuthenticated` | AUTH | `SELF` | no | eligible-reading total audience წესს მიჰყვება; completed/pending/percentage მხოლოდ caller-ის read-status rows-იდან ითვლება და სხვა caller-ის completion არ ერთვის. |
| `GET /api/compliance/my-readings` | `ComplianceController.getMyReadings` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი status ერთვის eligible reading-ს; სხვა caller-ის acknowledgement არ ჩანს. |
| `POST /api/compliance/required-readings` | `ComplianceController.createRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |
| `GET /api/compliance/required-readings/by-item/{itemType}/{itemId}` | `ComplianceController.getRequiredReadingForItem` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | Phase 6: read/edit drawer-იც იმავე capability-ით იმართება. |
| `DELETE /api/compliance/required-readings/{readingId}` | `ComplianceController.deleteRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |
| `PUT /api/compliance/required-readings/{readingId}` | `ComplianceController.updateRequiredReading` | `requireComplianceAssign` | compliance.assign | `ORG-CONTENT` | no | წესი #12: target არის დეპარტამენტი ან `All`, არასოდეს ჯგუფი. |

### Export (12)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/admin/exports/article-views` | `AdminExportController.articleViews` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | ცალკე XLSX; optional `from`/`through`; მხოლოდ owner ჩამოტვირთავს; `EXPORT_ADMIN_ARTICLE_VIEWS` audit. |
| `POST /api/admin/exports/audit-ledger` | `AdminExportController.auditLedger` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | audit + integrity/hash-chain metadata; JSON details credential-redaction-ით; `EXPORT_ADMIN_AUDIT_LEDGER` audit. |
| `POST /api/admin/exports/change-events` | `AdminExportController.changeEvents` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | user/content/admin/security მოვლენების ცალკე XLSX და audit action. |
| `POST /api/admin/exports/quiz-attempts` | `AdminExportController.quizAttempts` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | მხოლოდ რეალურად შენახული score/version/result; არჩეული პასუხები schema-ში არ არსებობს და არ იგონება. |
| `POST /api/admin/exports/read-evidence` | `AdminExportController.readEvidence` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | article receipt + required-reading status ერთ explicit allowlist-ში. |
| `POST /api/admin/exports/search-history` | `AdminExportController.searchHistory` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | **yes** | საძიებო ტექსტი, შედეგის ფაქტი/რაოდენობა და მომხმარებლის snapshot/current identity. |
| `GET /api/export/download/{jobId}` | `ExportController.downloadExport` | `requireReportsExport` | reports.export + leadership | `SELF` (`SYSTEM_ADMIN`: legacy owner bypass) | **yes** | D-3: classified `ADMIN_*` export მოითხოვს მოქმედ `SYSTEM_ADMIN` როლს და იმავე owner-ს; როლის დაკარგვის შემდეგ ჩამოტვირთვა 410-ია. სხვა job-ზე unknown owner fail-closed, legacy SYSTEM_ADMIN bypass რჩება. |
| `GET /api/export/readings` | `ExportController.exportReadingsCsv` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | PO-13 target allowlist გადაწყვეტილია; DPO validation და implementation pending — იხ. export allowlist. |
| `GET /api/export/readings.pdf` | `ExportController.exportReadingsPdf` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | PO-13 target allowlist გადაწყვეტილია; DPO validation და implementation pending — იხ. export allowlist. |
| `GET /api/export/readings.xlsx` | `ExportController.exportReadingsXlsx` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | PO-13 target allowlist გადაწყვეტილია; DPO validation და implementation pending — იხ. export allowlist. |
| `GET /api/export/status/{jobId}` | `ExportController.getExportStatus` | `requireReportsExport` | reports.export + leadership | `SELF` (`SYSTEM_ADMIN`: legacy owner bypass) | **yes** | classified `ADMIN_*` job მოითხოვს მოქმედ `SYSTEM_ADMIN` როლს და იმავე owner-ს; როლის დაკარგვის შემდეგ სტატუსი 404-ია. სხვა job-ზე D-3 owner/legacy SYSTEM_ADMIN წესი მოქმედებს. |
| `GET /api/export/team-stats.pdf` | `ExportController.exportTeamStatsPdf` | `requireReportsExport` | reports.export + leadership | `GROUP/DEPT` | **yes** | სვეტები allowlist-ით (§ export allowlist). raw log ველი აკრძალულია. |

### Favorite (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/favorites` | `FavoriteController.getFavorites` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ caller-ის rows; იმავე item-ზე სხვა caller-ის favorite list-ში არ ჩანს. |
| `POST /api/favorites` | `FavoriteController.addFavorite` | `requireAuthenticated` | AUTH | `SELF` | no | uniqueness caller+item-ზეა: იგივე item-ზე ორი caller ცალკე favorite row-ს ქმნის. |
| `DELETE /api/favorites/{id}` | `FavoriteController.removeFavorite` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ caller-ის row; უცხო owner-ის valid favorite ID 404-ია, ხოლო owner row უცვლელი რჩება. საკუთარი delete სხვა caller-ის იმავე-item row-ს არ შლის. |

### Health (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/health` | `HealthController.health` | — | — | `NONE` | no | ინფრასტრუქტურული probe. |

### Broadcast (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/broadcasts` | `BroadcastController.getActive` | `requireAuthenticated` | AUTH | `ORG-CONTENT` | no | ყველა ავტორიზებული თანამშრომელი ხედავს მხოლოდ აქტიურ საერთო განცხადებებს; recipient/read/acknowledgment არ არსებობს. |
| `POST /api/broadcasts` | `BroadcastController.publish` | `requireAnnouncementPublisher` | announcement.publish | `ORG-CONTENT` | no | composite gate საკუთარ თავში ამოწმებს authentication-საც: `content.manage`, მოქმედი ჯგუფის PRIMARY/ACTING ლიდერი ან SYSTEM_ADMIN; აუდიტორია ყოველთვის მთელი კომპანიაა. |
| `GET /api/broadcasts/history` | `BroadcastController.getHistory` | `requireAnnouncementPublisher` | announcement.publish | `ORG-CONTENT` | no | paginated აქტიური/ვადაგასული/ადრე დასრულებული ისტორია; targeting არ არსებობს. |
| `POST /api/broadcasts/{broadcastId}/end` | `BroadcastController.endEarly` | `requireAnnouncementPublisher` | announcement.publish | `OWN-CONTENT` | no | დროზე ადრე ასრულებს მხოლოდ გამომქვეყნებელი ან SYSTEM_ADMIN; ოპტიმისტური lock იცავს კონკურენტულ ცვლილებას. |

### Reminders (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/reminders` | `ReminderController.inbox` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ caller recipient ID-ის paginated fixed-template rows; სხვა recipient-ის reminder ID არ ჩანს; reply/chat/free text არ არსებობს. |
| `POST /api/reminders/{reminderId}/read` | `ReminderController.markRead` | `requireAuthenticated` | AUTH | `SELF` | no | ownership lookup უცხო და არარსებულ id-ს ერთნაირ `404`-ად აბრუნებს; ერთი caller-ის read state სხვა recipient row-ს არ ცვლის; განმეორებითი read იდემპოტენტურია. |
| `POST /api/reminders/users/{userId}/send` | `ReminderController.sendManual` | `requireAuthenticated` | AUTH + `ScopeResolver.resolveGroupLeadership` | `GROUP` (`SYSTEM_ADMIN`: `ORG`) | **yes** | PRIMARY/ACTING ჯგუფის უფროსი მხოლოდ საკუთარ აქტიურ წევრს უგზავნის server-owned ფიქსირებულ ტექსტს; 24-საათიანი recipient cooldown, audit და DB lock სავალდებულოა. |

### ContentTrash (5)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/content-trash` | `ContentTrashController.listTrash` | `requireContentManage` | content.manage | `ORG-CONTENT` | **yes** | R5: article/news/video-ის საერთო 30-დღიანი სანაგვე; აჩვენებს ჩამგდები პირის სახელს და legal-hold მდგომარეობას. |
| `POST /api/content-trash/{itemType}/{itemId}/restore` | `ContentTrashController.restore` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: მხოლოდ 30-დღიანი ფანჯრის შიგნით; მასალა და მისი orphaned attachment ისევ არქივში ბრუნდება. |
| `DELETE /api/content-trash/{itemType}/{itemId}` | `ContentTrashController.purge` | `requireSystemAdmin` | SYSTEM_ADMIN | `ORG` | no | R5: მხოლოდ ვადის გასვლის შემდეგ, legal hold-ის გარეშე; explicit/manual purge, evidence-safe და სრულად აუდიტირებული. |
| `POST /api/content-trash/{itemType}/{itemId}/legal-hold` | `ContentTrashController.setLegalHold` | `requireLegalHoldAuthority` | DPO/Legal-approved named authority | `ORG` | no | default allowlist ცარიელია და ყველა როლს fail-closed უარყოფს; set სრულად აუდიტირდება. |
| `DELETE /api/content-trash/{itemType}/{itemId}/legal-hold` | `ContentTrashController.releaseLegalHold` | `requireLegalHoldAuthority` | DPO/Legal-approved named authority | `ORG` | no | release მხოლოდ recoverable trash-ზეა, named authority-ით და reconstructable audit-ით. |

### News (14)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/news` | `NewsController.getNews` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `POST /api/news` | `NewsController.createNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `POST /api/news/command` | `NewsCommandController.create` | — | content.manage + compliance.assign | `ORG-CONTENT` | no | სიახლე და სავალდებულო დავალება ერთ ტრანზაქციაში ინახება; დელეგირებული gate-ები ძალაში რჩება. |
| `DELETE /api/news/{id}` | `NewsController.deleteNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: მხოლოდ უკვე არქივირებული სიახლე გადადის 30-დღიან აღდგენად სანაგვეში. |
| `GET /api/news/{id}` | `NewsController.getNewsItem` | `requireAuthenticated` | AUTH | `CONTENT` | no | `NewsVisibility`: პირადი მონახაზი მხოლოდ მის content-admin ავტორს; სხვას 404, ადმინისტრატორსაც. ვადაგასულ/არქივირებულ სიახლეზე ოპერატორს 404, content admin-ს წვდომა აქვს. მოქმედ გამოქვეყნებულზე admin bypass ან `DepartmentMatcher`. |
| `PUT /api/news/{id}` | `NewsController.updateNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PUT /api/news/{id}/command` | `NewsCommandController.update` | — | content.manage + compliance.assign | `ORG-CONTENT` | no | სიახლისა და დავალების ცვლილება ატომურია. |
| `POST /api/news/{id}/archive` | `NewsController.archiveNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: explicit archive; ოპერატორის ხედიდან და search index-იდან იმალება. |
| `PATCH /api/news/{id}/autosave` | `NewsController.autosaveNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/news/{id}/history` | `NewsController.getNewsHistory` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `GET /api/news/{id}/history-summary` | `NewsController.getNewsHistorySummary` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | იგივე history scope; CLOB-free metadata list, content ცალკე selected-detail request-ით. |
| `GET /api/news/{id}/history/{historyId}` | `NewsController.getNewsHistoryItem` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | იგივე history scope; ერთი selected revision payload. `{historyId}` owning news-ზეა scoped; foreign-parent ID 404-ია. |
| `POST /api/news/{id}/history/{historyId}/restore` | `NewsController.restoreNewsVersion` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. Foreign-parent history ID 404-ია და target/audit უცვლელია. წესი #9 უცვლელია. |
| `POST /api/news/{id}/unarchive` | `NewsController.unarchiveNews` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: explicit unarchive და search reindex. |

### Org (4)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `DELETE /api/admin/org/assignments/{assignmentId}` | `OrgAdminController.deactivateAssignment` | `requireSystemAdmin` | — | `ORG` | **yes** | Phase 8. soft deactivate; ისტორიული მწკრივი რჩება და actor-ით აუდიტდება. `org.manage` განზრახ არ გამოიყენება (SEC-06). |
| `GET /api/admin/org/assignments` | `OrgAdminController.getAssignments` | `requireSystemAdmin` | — | `ORG` | **yes** | Phase 8. მოქმედი და ისტორიული PRIMARY/ACTING დანიშვნები resolved employee/scope სახელებით. |
| `GET /api/admin/org/structure` | `OrgAdminController.getStructure` | `requireSystemAdmin` | — | `ORG` | **yes** | Phase 8. AD-owned დეპარტამენტები → ჯგუფები → აქტიური წევრების რაოდენობა; read-only. |
| `POST /api/admin/org/assignments` | `OrgAdminController.createAssignment` | `requireSystemAdmin` | — | `ORG` | **yes** | Phase 8. ჯგუფის ან დეპარტამენტის PRIMARY/ACTING ლიდერი; PRIMARY collision გასაგები conflict-ით უარყოფილია და ცვლილება აუდიტდება. |

### Platform (2)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/notifications/summary` | `PlatformController.getNotificationsSummary` | `requireAuthenticated` | AUTH | `SELF` | no | unread readings/status და reminder count caller ID-ზეა scoped; სხვა recipient-ის unread reminder aggregate-ში არ შედის. |
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
| `GET /api/articles/{id}/quiz` | `QuizController.getArticleQuiz` | `requireAuthenticated` | AUTH | `SELF` | no | სტატიის პირდაპირი ID-ის ხილვადობის წესი მოქმედებს ქვიზზეც: სხვისი `is_draft` 404-ია. compliance-ის მტკიცებულება retention purge-იდან გამორიცხულია. |
| `GET /api/articles/{id}/quiz/admin` | `QuizController.getArticleQuizAdmin` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate; `content.manage` სხვის პირად `is_draft` ქვიზს არ ხსნის — 404, როგორც სტატიის ID-ზე. |
| `PUT /api/articles/{id}/quiz/admin` | `QuizController.updateArticleQuizAdmin` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate; სხვა ავტორის პირადი `is_draft` ქვიზის შეცვლა 404-ით იკრძალება. |
| `POST /api/articles/{id}/quiz/attempt` | `QuizController.submitArticleQuizAttempt` | `requireAuthenticated` | AUTH | `SELF` | no | სტატიის პირდაპირი ID-ის ხილვადობის წესი მოქმედებს; სხვის `is_draft`-ზე მცდელობა 404-ია და არ ინახება. attempt number `(article_id, version, user_id)`-ზე ითვლება; retention purge-იდან გამორიცხულია. |
| `GET /api/users/me/knowledge-score` | `QuizController.getMyKnowledgeScore` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის საკუთარი attempts-ის aggregate; სხვა caller-ის pass/score არ აისახება. |

### Search (3)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/search` | `SearchController.search` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `GET /api/search/global` | `SearchController.searchGlobal` | `requireAuthenticated` | AUTH | `CONTENT` | no | ხილვადობა target-department-ით (`ArticleQueryService`/`DepartmentMatcher`), არა role-ით. |
| `GET /api/search/history` | `SearchController.searchHistory` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის search-log rows; სხვა caller-ის terms/history არ ჩანს. |

### Stats (13)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/critical-operators` | `StatsController.getCriticalOperators` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/admin/departments/{department}/groups/{groupName}/users` | `StatsController.getGroupUsers` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Unassigned legacy caller კვლავ `ManagerScope`-ზეა. Assignment-backed caller-ის path ჯერ ერთ active canonical department/team target-ად უნდა resolve-დეს: missing/ambiguous/foreign target 403-ია; valid assigned empty group 200/empty რჩება; rows exact `team_id`-ით იფილტრება. |
| `GET /api/admin/stats/team/{teamId}` | `StatsController.getAdminTeamStats` | `requireSystemAdmin` | AUTH + leadership | `ORG` | **yes** | Phase 0: SYSTEM_ADMIN-only. Phase 4: `{teamId}` scope-ზე უნდა შემოწმდეს, არა role-ზე. |
| `GET /api/manager/department-stats` | `StatsController.getDepartmentStats` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/manager/leadership-options` | `StatsController.getLeadershipOptions` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP` (`SYSTEM_ADMIN`: `ORG`) | **yes** | ძირითადი გუნდი ნაგულისხმევია; მოქმედი დროებითი ჯგუფები მხოლოდ UI selector-ს აფართოებს და ექსპორტის scope-ში არ შედის. |
| `GET /api/manager/team-stats` | `StatsController.getTeamStats` | `requireManagerOrAdmin` | AUTH + leadership | `GROUP/DEPT` | **yes** | Phase 0: `ManagerScope`. Phase 4: `ScopeResolver`; nested group/department row-ებიც იფილტრება. |
| `GET /api/statistics/activity` | `StatsController.getActivityTrend` | `requireStatsView` | stats.view | `ORG-AGG` | no | D-8 implemented: მხოლოდ აგრეგატები; `content.manage` წვდომას არ ხსნის. |
| `GET /api/statistics/breakdown` | `StatsController.getStatisticsBreakdown` | `requireStatsView` | stats.view | `ORG-AGG` | no | დაშვებული dimension: department/role/status — `COUNT`, სახელების გარეშე; `content.manage` დამოუკიდებელია. |
| `GET /api/statistics/compliance` | `StatsController.getComplianceStatistics` | `requireStatsView` | stats.view | `ORG-AGG` | no | მხოლოდ კომპანიის aggregate; სახელობით `/user-progress` კვლავ SYSTEM_ADMIN-only-ია. |
| `GET /api/statistics/failed-searches` | `StatsController.getFailedSearches` | `requireStatsView` | stats.view | `ORG-AGG` | no | მხოლოდ aggregate terms/count; `content.manage` დამოუკიდებელია. |
| `GET /api/statistics/kpi` | `StatsController.getKpiCounts` | `requireStatsView` | stats.view | `ORG-AGG` | no | მხოლოდ aggregate counts; `content.manage` დამოუკიდებელია. |
| `GET /api/statistics/popular-searches` | `StatsController.getPopularSearches` | `requireStatsView` | stats.view | `ORG-AGG` | no | მხოლოდ aggregate terms/count; `content.manage` დამოუკიდებელია. |
| `GET /api/statistics/user-progress` | `StatsController.getUserProgress` | `requireSystemAdmin` | AUTH + leadership | `ORG` | **yes** | ყველა თანამშრომლის სახელი + დეპარტამენტი + პროცენტი. სწორად SYSTEM_ADMIN-only; Phase 4-ზე scope-ით უნდა გაიხსნას leadership-ისთვის, არა role-ით. |

### Upload (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `POST /api/upload` | `UploadController.uploadFile` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |

### UploadedFile (1)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /uploads/{filename}` | `UploadedFileController.serve` | — | AUTH | `AUTH` | content-dependent | `@AuthenticationPrincipal` null-ზე 401; წარმატებული წვდომა აუდიტირდება და პასუხი `no-store`-ია. ავთენტიფიკაციის **შემდეგ** `FileAccessPolicy` ამოწმებს, აქვს თუ არა წვდომა მიმთითებელ კონტენტზე (DEC-P01) — უარი 404-ია. სტატიის სრული შენახული აუდიტორია გამოიყენება (1000-მდე); `is_draft` დანართი მხოლოდ ავტორს ეხსნება. პირადი სიახლის reference ავტორის გარდა წვდომას არ იძლევა (`NewsVisibility`); სხვა ხილული reference კვლავ საკმარისია. ვიდეოს reference დამატებით `VideoVisibility`-ის department/prefix/All/admin წესს ამოწმებს. ფაილის archive/trash უარი უცვლელია. enforcement `ROLLOUT_FILE_ENTITLEMENT`-ზეა: `false` = shadow (ითვლება და აუდიტში იწერება `FILE_ACCESS_SHADOW_DENY`-ად, ფაილი მაინც გაიცემა). იხ. `docs/ROLLOUT_ROLLBACK_KA.md`. |

### User (15)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/admin/group-leaders` | `UserController.getGroupLeaders` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | დღეს `role == MANAGER`-იდან; Phase 4-ზე leadership assignment-იდან. |
| `POST /api/admin/roles/bulk-reassign` | `UserController.bulkReassignRoles` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). როცა როლებს კომპანიის დირექტორია მართავს (`CORPORATE_AUTH_ENABLED=true`) — 409: აქ დანიშნული როლი მომდევნო შესვლაზე დაბრუნდებოდა. |
| `POST /api/admin/users/bulk-deactivate` | `UserController.bulkDeactivateUsers` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | PO-24-ის „წასულების“ გავლა: მხოლოდ გამორთვა (გააქტიურება ჯგუფურად არ ხდება) და მხოლოდ SYSTEM_ADMIN-ს. მომძახებლის საკუთარი ანგარიში ნაკრებიდან ამოდის, ამიტომ ერთი აქტიური ადმინი ყოველთვის რჩება; უკვე გამორთული მწკრივი ხელახლა არ იწერება. თითოეული გამორთვა ცალკე აუდიტის ჩანაწერია (`BULK_DEACTIVATE`, კატეგორია SECURITY). |
| `GET /api/teams` | `UserController.getTeams` | `requireAuthenticated` | AUTH | `NONE` | no | ორგანიზაციული სტრუქტურა კითხვადია; AD-owned, mutation fail-closed. |
| `POST /api/teams` | `UserController.createTeam` | `requireSystemAdmin` | — | `NONE` | no | AD-owned: fail-closed `403` (Phase 0). dev fixture მხოლოდ seeder-ით. |
| `GET /api/me/effective-access` | `UserController.getEffectiveAccess` | `requireAuthenticated` | AUTH | `SELF` | no | Phase 7. მომძახებლის საკუთარი effective permission-ები; სხვა caller-ის explicit override არ ერთვის. `bypass` სისტემური ადმინის შემოვლას აშკარას ხდის. |
| `GET /api/users` | `UserController.listUsers` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `POST /api/users` | `UserController.createUserAdmin` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `GET /api/users/me` | `UserController.getCurrentUser` | `requireAuthenticated` | AUTH | `SELF` | no | პროფილის endpoint caller identity-ს აბრუნებს; Phase 7-ის `/api/me/effective-access` UI authorization-ის ცალკე, `bypass`-ით გამჭვირვალე წყაროა. `roles_managed_by_directory` ადმინის ეკრანებს ეუბნება, რომ როლი მხოლოდ საჩვენებელია. |
| `PUT /api/users/me` | `UserController.updateCurrentUser` | `requireAuthenticated` | AUTH | `SELF` | no | მხოლოდ მომძახებლის დაშვებული profile fields იცვლება; email/role/department და სხვა user უცვლელია. |
| `POST /api/users/me/password` | `UserController.changeOwnPassword` | `requireAuthenticated` | AUTH | `SELF` | no | AD/directory-owned fail-closed 403; password hash არ იცვლება და success audit არ იწერება. Production local password fallback აკრძალულია. |
| `PUT /api/users/{userId}` | `UserController.updateUserAdmin` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. Phase 6: profile/role/permission delta ერთ atomic transaction-ში ინახება drawer-ის თავდაპირველი `lock_version`-ით; `bulk-reassign` override-ს არ შლის. როცა როლებს დირექტორია მართავს, **როლის ცვლილება** 409-ია; დეპარტამენტი, პოზიცია და ცალკეული უფლებები კვლავ იცვლება. |
| `PUT /api/users/{userId}/permissions` | `UserController.adminUpdatePermissions` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | no | Phase 6: flat replace → `INHERIT`/`ALLOW`/`DENY` delta + required optimistic concurrency token; malformed nested delta `400`-ია. |
| `POST /api/users/{userId}/reset-password` | `UserController.adminResetPassword` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |
| `PUT /api/users/{userId}/status` | `UserController.updateUserStatus` | `requireSystemAdmin` | org.manage `NEW` | `ORG` | **yes** | SYSTEM_ADMIN. `bulk-reassign` permission override-ს არ შლის (Phase 6). |

### Video (9)

| endpoint | handler | gate (დღეს) | capability (სამიზნე) | scope | PII | შენიშვნა |
|---|---|---|---|---|---|---|
| `GET /api/videos` | `VideoController.getVideos` | `requireAuthenticated` | AUTH | `CONTENT` | no | `VideoVisibility`/`DepartmentMatcher`: target department, ჯგუფის prefix ან All; content admin-ის არსებული bypass რჩება. იგივე audience წესი იცავს ვიდეოს ფაილს. |
| `POST /api/videos` | `VideoController.createVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `POST /api/videos/command` | `VideoCommandController.create` | — | content.manage + compliance.assign | `ORG-CONTENT` | no | ვიდეო და სავალდებულო დავალება ერთ ტრანზაქციაში ინახება; დელეგირებული gate-ები ძალაში რჩება. |
| `DELETE /api/videos/{id}` | `VideoController.deleteVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | R5: მხოლოდ უკვე არქივირებული ვიდეო გადადის 30-დღიან აღდგენად სანაგვეში. |
| `PUT /api/videos/{id}` | `VideoController.updateVideo` | `requireContentManage` | content.manage | `ORG-CONTENT` | no | Phase 6 permission-gate. წესი #9: კომპანიის მასშტაბით გამოქვეყნება + სხვისი სტატია + კატეგორიები = ერთი capability. |
| `PUT /api/videos/{id}/command` | `VideoCommandController.update` | — | content.manage + compliance.assign | `ORG-CONTENT` | no | ვიდეოსა და დავალების ცვლილება ატომურია. |
| `POST /api/videos/{id}/archive` | `VideoController.archiveVideo` | `requireVideosArchivePermission` | videos.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/videos/{id}/unarchive` | `VideoController.unarchiveVideo` | `requireVideosArchivePermission` | videos.archive | `ORG-CONTENT` | no | უკვე permission-ზეა. |
| `POST /api/videos/{id}/view` | `VideoController.viewVideo` | `requireAuthenticated` | AUTH | `SELF` | no | საკუთარი view count მხოლოდ list-ისავე ხილულ კონტენტზე (`VideoVisibility`): non-admin caller-ს archived ან სხვა department target opaque 404-ია და count არ იცვლება; content admin existing all/archived access-ს ინარჩუნებს. ფაილზე archive უარი admin-საც ეხება. |
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

**სამიზნე readings allowlist — PO-13 გადაწყვეტილია, DPO validation და
implementation pending:** სამივე ფორმატს ექნება ერთი და იგივე ქართული სვეტები:
`თანამშრომელი` · `დეპარტამენტი` · `ჯგუფი` · `მასალის სათაური` · `მასალის ტიპი` ·
`სტატუსი` · `წაკითხვის დრო` · `ვადა`. `email`, employee/user ID და material/item
ID სამიზნე export-იდან ამოსაღებია. მიმდინარე factual ცხრილი ზემოთ უცვლელად რჩება,
სანამ კოდი და snapshot tests არ შეიცვლება.

**ხელმძღვანელის export-ში აკრძალული კატეგორიები (წესი #14).** readings/team
export-ში არ დაიშვება ველი, რომელიც წარმოიშობა: `audit_logs` ·
`article_view_logs` · `search_logs` · session/token მდგომარეობა · უსაფრთხოების
მოვლენები · IP მისამართი · `hashed_password` · `token_version`.

SYSTEM_ADMIN-ის სრული log/data export არის ცალკე surface და შეიძლება შეიცავდეს
audit/view/search/security/IP მონაცემებს, მაგრამ არასოდეს password-ს,
`hashed_password`-ს, access/refresh token-ს, session credential-ს, private key-ს,
database password-ს ან სხვა secret-ს. მისი DTO-ები და tests ხელმძღვანელის
`ReadingExportRow`-ისგან განცალკევებულია.

**აღსრულება:** `ExportColumnAllowlistTest`. სვეტები დამაგრებულია სამივე
ფორმატზე ცალ-ცალკე, დამატებით კი დამაგრებულია `ReadingExportRow` — ის ერთადერთი
shape-ია, საიდანაც სამივე readings-export კითხულობს, ე.ი. ველი, რომელიც იქ ვერ
გაჩნდება, ვერცერთ ფაილში ვერ მოხვდება. ეს განზრახ ორივე დონეზეა: მარტო
სათაურების დამაგრება არ დაიჭერდა ველს, რომელიც სხვა ეკრანისთვის დაემატა და
export-მა ავტომატურად აიტაცა.

> csv სათაურები დღეს ინგლისურია, xlsx/pdf — ქართული. პროდუქტის გადაწყვეტილებით
> სამივე ფორმატის მომხმარებლისთვის ხილული სათაურები ქართულად უნდა გახდეს;
> implementation და snapshot tests ჯერ დარჩენილია.

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
| `ArticleReadReceiptResponse` | `article_id` · `article_title` · `current_version` · `eligible_count` · `read_count` · `unread_count` · `late_read_count` · `receipts` | per-material aggregate + access-scoped named rows |
| `ArticleReadReceiptRowResponse` | `operator_id` · `operator_name` · `department` · `read_at` · `article_version` · `has_read` · `is_late` · `deadline` · `status` | ოფიციალური read evidence; email მიზანმიმართულად არ შედის |
| `ArticleViewRowResponse` | `operator_id` · `operator_name` · `operator_email` · `department` · `article_version` · `viewed_at` | article views — მიმდინარე shape; სამიზნე მხოლოდ SYSTEM_ADMIN-only log surface-ია |
| `GroupLeaderResponse` | `id` · `name` | group-leaders |

**წესი:** ამ ცხრილში ველის დამატება ნიშნავს კითხვას „ვის აქვს მისი ნახვის
უფლება და რომელ scope-ზე?" — და პასუხი მატრიცაში უნდა ჩაიწეროს **ველის
გაშვებამდე**, არა მას შემდეგ, რაც ვინმე მას პასუხში შეამჩნევს.

> `LeaderboardEntryResponse` D-1-ის გადაწყვეტისას endpoint-თან ერთად ამოღებულია;
> მისი ხელახალი დამატება ახალი პროდუქტული გადაწყვეტილებისა და contract test-ის
> გარეშე დაუშვებელია.

---

## პროდუქტული გადაწყვეტილებები

D-1…D-8 დახურულია. ქვემოთ თითოეული გადაწყვეტილება და მისი განხორციელების
სტატუსია დაფიქსირებული.

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

### D-2. ოფიციალური read evidence და view log — ✅ გადაწყვეტილია (2026-08-22)

**გადაწყვეტილება:** სახელობითი ოფიციალური read evidence leadership scope-ით
იზღუდება. ჯგუფის
უფროსი ხედავს მხოლოდ საკუთარი ჯგუფის თანამშრომლებს; დეპარტამენტის ხელმძღვანელი
ხედავს დეპარტამენტის ყველა ჯგუფს; `SYSTEM_ADMIN` ხედავს ყველას. ეს არის მარტივი
scope-პირამიდა. მარტო `content.manage` სახელობით წვდომას არ იძლევა — შესაბამისი
leadership scope-ის გარეშე კონტენტის მმართველს მხოლოდ საერთო რაოდენობები რჩება.

პირველ production rollout-ში აქტიურია მხოლოდ ჯგუფის უფროსი + `SYSTEM_ADMIN`;
დეპარტამენტის ხელმძღვანელის დონე target architecture-ში რჩება, მაგრამ ჯერ არ
ირთვება. სტატიის უბრალოდ გახსნა (`article_view_logs`) ოფიციალურ წაკითხვად არ
ითვლება და leadership სიაში არ ჩანს — მას მხოლოდ `SYSTEM_ADMIN` ხედავს/გამოაქვს.
თანამშრომლის feedback ფუნქცია მთლიანად ამოღებულია.

სახელობით rows-ში `operator_name` საკმარისია; `operator_email` ამოღებულია და
response-shape contract ამ საზღვარს იცავს.

#### წინა მდგომარეობა და განხილული ვარიანტები

ცვლილებამდე `GET /api/articles/{id}/read-receipts` `operator_email`-იან სახელობით
rows-ს **org-wide**, მხოლოდ `requireContentAdmin`-ით აბრუნებდა, ხოლო `/views`-საც
იგივე ძველი role-gate ჰქონდა. ახლა read evidence aggregate/scope კონტრაქტზეა,
`/views` კი SYSTEM_ADMIN-only-ია. feedback endpoint-ები ამოღებულია.

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

**შენიშვნა `SYSTEM_ADMIN` bypass-ზე (2026-08-29, merge).**
`claude/enterprise-readiness-verify` ხაზმა იგივე პრობლემა დამოუკიდებლად
გადაწყვიტა, ოღონდ **bypass-ის გარეშე**: მისი არგუმენტი ის იყო, რომ
`ExportQueryService`-ში `SYSTEM_ADMIN` ისედაც scope-გარეშეა, ე.ი. მისი
საკუთარი ექსპორტი სხვისის ზედსიმრავლეა და სხვისი job-ის გახსნა არასოდეს
სჭირდება — სამაგიეროდ ამის დაშვება არღვევს SEC-02-ის მთავარ მონაპოვარს:
აუდიტის ჩანაწერს, რომელიც პასუხობს *ვისი* პერსონალური მონაცემი გავიდა და
*ვინ* წაიღო.

არგუმენტი ძლიერია, მაგრამ D-3 უკვე გადაწყვეტილია და ის ხაზი ამ
გადაწყვეტილებამდე იყო აღებული. merge-მა შეინარჩუნა **აქ ჩაწერილი ქცევა**
(`ExportControllerScopeGateTest.aJobWithNoRecordedOwnerIsNobodysExceptTheAdmins`
სწორედ მას ამოწმებს). გამკაცრება ღია წინადადებაა და პროდუქტის მფლობელის
გადასაწყვეტია, არა merge-ის.

ერთი დაზუსტება მაინც შევიდა: `export_family = ADMIN_*` job-ებზე bypass **არ
მოქმედებს** (`V41`), ე.ი. ყველაზე მგრძნობიარე ექსპორტები უკვე მკაცრად
მფლობელზეა მიბმული.

**2026-09-24 დაზუსტება:** `ADMIN_*`-ისთვის მარტო ძველი owner-ის დამთხვევა აღარ
კმარა: ჩამოტვირთვისა და სტატუსის ნახვის მომენტშიც `SYSTEM_ADMIN` როლი სავალდებულოა.
როლის ჩამორთმევის შემდეგ ძველი job აღარ ჩანს, სხვა ოჯახის ექსპორტის D-3 წესი
უცვლელია.

### D-4. `GET /uploads/{filename}` — ✅ გადაწყვეტილია (2026-08-22)

**გადაწყვეტილება:** ყველა ატვირთული სურათი, PDF და სხვა დანართი მხოლოდ პორტალში
ავტორიზებულ თანამშრომელს გაეხსნება. დაკოპირებული `/uploads/<uuid>` URL login-ის
გარეშე არ მუშაობს; საჯარო/დაცული ტიპების არჩევანი არ ემატება.

`UploadedFileController.serve` უკვე მოითხოვს მოქმედ portal session-ს. განხორციელებამ
უნდა დაამატოს authentication gate, Angular-ის image/download ქცევა და regression
tests. `QUESTIONS_FOR_IT.md` §9-ში ღია რჩება მხოლოდ ingress/cache/scanning-ის
ინფრასტრუქტურული ნაწილი.

**დამატება (2026-08-29) — ავთენტიფიკაცია საკმარისი არ აღმოჩნდა.** UAT-ის
ადვერსარიულმა რაუნდმა (`docs/uat/UAT_06_ADVERSARIAL_KA.md`, F-1) აჩვენა, რომ
ავთენტიფიცირებული ოფისის ოპერატორი **ტექნიკურის** სტატიის სურათს ტვირთავდა —
იმ სტატიისას, რომელიც მას 404-ს პასუხობს. ე.ი. ფაილს იცავდა მხოლოდ 64-სიმბოლოიანი
სახელის გამოცნობის სირთულე, სახელები კი ვრცელდება.

პროდუქტის მფლობელის გადაწყვეტილება: **ფაილი მიჰყვება იმ კონტენტის აუდიტორიას,
რომელიც მასზე მიუთითებს** (ვარიანტი „ბ"). `FileAccessPolicy` + `V46`-ის
`stored_file_references`. მიმთითებლის ხილვადობა `ArticleVisibility`-ს ებარება —
იმავე პრედიკატს, რომელსაც `ArticleController` იყენებს, რომ ფაილმა და სტატიამ
„ხილვადობაზე" ერთმანეთს არ დაუპირისპირდნენ.

პროდაქშენზე enforcement **პირველივე დღიდან ჩართულია**
(`ROLLOUT_FILE_ENTITLEMENT: "true"`, `k8s/10-configmap.yaml`): shadow-ის
ორკვირიანი ფანჯარა პროდუქტის მფლობელმა მოხსნა. shadow (`false`) rollback-ის
გზად რჩება; დაკვირვების სიგნალები და უკან დაბრუნება — `docs/ROLLOUT_ROLLBACK_KA.md`.

> **შესწორება (2026-09-05).** აქამდე ეს აბზაცი წერდა, რომ პროდაქშენზე „ჯერ
> shadow-ით გადის (`ROLLOUT_FILE_ENTITLEMENT=false`)". 2026-08-31-ის
> გადაწყვეტილებამდე ეს სწორი იყო; შემდეგ `k8s/10-configmap.yaml` შეიცვალა და
> ეს აბზაცი ჩამორჩა. `PRODUCT_OWNER_DECISIONS_KA.md`,
> `ROLLOUT_ROLLBACK_KA.md` და `ENTERPRISE_READINESS_ACCEPTANCE_MATRIX_KA.md`
> სწორად წერდნენ — ე.ი. ერთი და იგივე დროშა ორნაირად ეწერა ორ ისეთ
> დოკუმენტში, რომელიც ორივე „ჭეშმარიტების წყაროდაა" გამოცხადებული.

### D-5. `content.manage`-ის მარცვლოვნება — ✅ გადაწყვეტილია (2026-08-22)

**გადაწყვეტილება:** რჩება ერთი საერთო capability. `content.manage` ერთად რთავს
კონტენტის შექმნას, გამოქვეყნებას, სხვისი მასალის რედაქტირებასა და კატეგორიების
მართვას. ცალკე `content.create` / `content.publish` / `content.edit_any` /
`categories.manage` permission-ები და UI ჩამრთველები არ ემატება.

### D-6. csv-ის ინგლისური სათაურები — ✅ გადაწყვეტილია (2026-08-22)

**გადაწყვეტილება:** პროექტი მხოლოდ ქართულ ენაზეა. CSV/Excel export-ის ყველა
მომხმარებლისთვის ხილული სვეტის სათაური ქართულად იქნება; ენის არჩევანი არ
ემატება. ტექნიკური API field-ები/კოდის identifier-ები ინგლისურად შეიძლება
დარჩეს, რადგან მომხმარებლის ინტერფეისის ნაწილი არ არის. შესაბამისმა
implementation commit-მა export allowlist/header mapping და tests უნდა განაახლოს.

### D-7. `messaging` — ✅ გადაწყვეტილია (2026-08-22)

**გადაწყვეტილება:** თანამშრომლებს შორის პირადი messaging პროდუქტის ნაწილი არ
არის. `GET/POST /api/messages`, sent/delete/read endpoint-ები,
`DirectMessagePermission` და შესაბამისი UI ამოღებულია; missing-route regression
ხუთივე ძველ მისამართზე `404`-ს კეტავს. legacy `messages` ცხრილს runtime Java
entity/repository/API/UI აღარ აქვს; ცხრილი და მისი გაურკვეველი ისტორიული მონაცემი
ინვენტარიზაციამდე ფიზიკურად არ იშლება. R3 reminder-ები დამოუკიდებელ `reminders`
ledger-ში ინახება და ძველი message rows ავტომატურად არ backfill-დება.

ძველი `POST /api/broadcast` პირად messaging domain-თან ერთად იცვლება დამოუკიდებელი
`POST /api/broadcasts` კონტრაქტით: მისი პროდუქტული მნიშვნელობა საიტის საერთო
განცხადებაა. მაგალითად: ოფისი დაიკეტა ან რომელიმე მიმართულებაზე ტექნიკური
პრობლემაა. განცხადება ყველა ავტორიზებული თანამშრომლის მთავარ გვერდსა და პროფილში
ჩანს; ინტერნეტში ან ავტორიზაციის გარეშე საჯარო არ არის.

### D-8. `/api/statistics/*` — ✅ გადაწყვეტილია (2026-08-22)

**მფლობელის გადაწყვეტილება:** ექვსი კომპანიის მასშტაბის აგრეგატული endpoint
(`activity`, `breakdown`, `compliance`, `failed-searches`, `kpi`,
`popular-searches`) გადავა ცალკე `stats.view` capability-ზე. `content.manage`
მხოლოდ კონტენტის მართვას ეხება და სტატისტიკის წვდომას ავტომატურად აღარ გახსნის.
SYSTEM_ADMIN ამ ორ უფლებას ერთმანეთისგან დამოუკიდებლად გასცემს.

განხორციელებულია: endpoint-ები `requireStatsView`-ითაა დაცული; `content.manage`
აღარ ხსნის aggregate stats-ს. Backend/Angular regression და permission UI
იმავე readiness batch-შია მიბმული.

---

## production-მდე გარე gate-ები

გეგმის §10-ის კონკრეტიზაცია. **არცერთი მათგანი კოდით არ იხურება.**

| # | gate | ვისგან | სტატუსი |
|---|---|---|---|
| G-1 | უფროსის export-ში თანამშრომლის სახელების დაშვება | იურიდიული / DPO | 🟡 product target გადაწყვეტილია; DPO validation ღიაა |
| G-2 | export-ის საბოლოო სვეტების whitelist | იურიდიული / DPO | 🟡 PO-13 allowlist გადაწყვეტილია; DPO validation ღიაა |
| G-3 | D-1 (leaderboard) — პერსონალური მონაცემის გავრცელება თანაკოლეგებზე | იურიდიული / DPO + product owner | ✅ ჩაკეტილია — endpoint ამოღებულია |
| G-4 | departments/groups/users stable external ID-ები | Magti IT | 🔲 ღიაა — `QUESTIONS_FOR_IT.md` §2 |
| G-5 | ჯგუფის ცვლილებების feed და deactivation semantics | Magti IT | 🔲 ღიაა — `QUESTIONS_FOR_IT.md` §2 |
| G-6 | დაცული დანართის ინფრასტრუქტურული წინაპირობები | Magti IT | 🔲 ტექნიკური ნაწილი ღიაა — D-4 გადაწყვეტილია; `QUESTIONS_FOR_IT.md` §9 |

G-1…G-3 **product/DPO** gate-ებია და ამ ფაილში ცხოვრობენ; G-4…G-6 IT-ის
კომპეტენციაა და `QUESTIONS_FOR_IT.md`-ში, რომელსაც მფლობელი IT-სთან ერთად
პერიოდულად გადახედავს (CLAUDE.md).
