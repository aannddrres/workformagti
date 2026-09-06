> **არქივი / Archive.** დათარიღებული ჩანაწერი — მიმდინარე კოდს აღარ აღწერს. ტექსტი უცვლელია.
> A dated record; it does not describe the current code, and its original text is unchanged. Index: [`docs/README.md`](../../README.md).

# ტესტების კატალოგი — მიგრაციის მისაღები სპეციფიკაცია

დანართი [`JAVA_ORACLE_ANGULAR_MIGRATION.md`](JAVA_ORACLE_ANGULAR_MIGRATION.md)-ის, ფაზა 0.4-ის. სრული, ფაილ-ფაილზე გავლილი კატალოგი
**103 ტესტ ფუნქციის** (4,661 ხაზი, 27 ტესტ-ფაილი + `conftest.py`/`factories.py`), თითოეულზე — რომელ კონკრეტულ ბიზნეს-წესს ან
საზღვარს ამოწმებს, არა უბრალოდ ფუნქციის სახელი.

**როგორ გამოვიყენოთ:** როცა Java/Spring Boot ვერსია დაიწერება, ეს ცხრილი არის checklist — თითოეულ მწკრივს უნდა შეესაბამებოდეს
ეკვივალენტური ტესტი ახალ სისტემაში, იმავე დაკვირვებადი ქცევით (endpoint-ის პასუხი, status code, DB-მდგომარეობა). **არცერთი მწკრივი
არ უნდა დარჩეს "თარგმანის გარეშე".** ცხრილები დაჯგუფებულია დომენების მიხედვით, იმავე სტრუქტურით რაც მთავარი დოკუმენტის სექცია 4-ს აქვს.

ტესტების უმეტესობის დოკუმენტაცია თავად ციტირებს `docs/archive/audits/CODE_AUDIT_2026-07-11.md`-ს, როგორც იმ აუდიტს, რომელმაც აღმოაჩინა ტესტ-დაფარვის
ხარვეზი — ესე იგი, ეს ტესტები არა შემთხვევითი მაგალითებია, არამედ კონკრეტული, წინა ინციდენტებზე/აღმოჩენებზე დაფუძნებული რეგრესია-დაცვა.

---

## Auth / Identity (13 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_auth_production_gating.py` | `test_dev_bypass_then_production_gating` | dev-გარემოში bypass-ანგარიშისთვის (`admin@magti.ge`) ნებისმიერი პაროლი მუშაობს; `APP_ENV=production`-ზე იგივე bypass **სრულად** ითიშება |
| | `test_jit_provisioning_disabled_in_production` | `test_operator_*`-ტიპის ახალი ანგარიშის ავტომატური შექმნა login-ზე production-ში **არ ხდება** |
| | `test_login_endpoint_rejects_bypass_email_in_production` | სრული HTTP-გავლა (curl-ის ეკვივალენტი) — production-ში bypass-ელფოსტა არასწორი პაროლით 401-ს იძლევა, დამოკიდებულების override-ების გარეშე |
| `test_auth_routes.py` | `test_logout_clears_access_token_cookie` | logout შლის `access_token` cookie-ს (Set-Cookie header-ში ჩანს) |
| | `test_forgot_password_identical_response_for_known_and_unknown_email` | ცნობილი და უცნობი ელფოსტისთვის **იდენტური** პასუხი (enumeration-დაცვა); აუდიტ-ჩანაწერი იწერება მხოლოდ ცნობილისთვის (admin_id NOT NULL constraint) |
| | `test_sso_callback_issues_token_and_audit_log` | mock-SSO callback გასცემს namdvili JWT-ს და წერს `LOGIN_SSO` აუდიტს |
| `test_rbac_permissions.py` | `test_content_admin_can_archive_video_via_user_permissions_column` | `content_admin`-ის ნაგულისხმევი `videos.archive` უფლება (User.permissions column) რეალურად მუშაობს ვიდეოს დაარქივებაზე — **არა** იგივე, რაც ბაგი #4 (ცალკე endpoint-ის whitelist-გაპი, იხ. მთავარი დოკუმენტის §5) |
| | `test_operator_still_denied_video_archive` | ცარიელი permissions-ის მქონე ოპერატორი 403-ს იღებს — უფლების შემოწმება რეალურია, არა ყოველთვის-true |
| | `test_require_permission_returns_same_dependency_for_same_args` | `require_permission()`-ის `@lru_cache` აბრუნებს **იგივე ობიექტს** იდენტურ არგუმენტებზე — Java-ში ეს საჭირო აღარ არის (იხ. მთავარი დოკუმენტის §2.2, პუნქტი 6) |
| `test_users_rbac_routes.py` | `test_put_users_me_not_shadowed_by_user_id_route` | `PUT /api/users/me` არ ერევა `PUT /api/users/{id}`-ს როუტინგში (routers/users.py-ის საკუთარი docstring-ის მიერ დაცული ინვარიანტი) |
| | `test_bulk_reassign_guardrails` | უცნობი როლი უარყოფილია (400); მხოლოდ საკუთარი id-ის ჯგუფური გადაყვანა (self-exclusion-ის შემდეგ ცარიელი სია) ასევე 400-ია |
| | `test_update_user_status_cannot_deactivate_self` | ადმინს არ შეუძლია საკუთარი თავის დეაქტივაცია (400) |
| | `test_permissions_update_rejects_unknown_permission` | უცნობი permission-სტრიქონი უარყოფილია (400); ცნობილი — მიღებულია და დაბრუნებულ სიაშიც ჩანს |

## Content — Articles (15 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_article_crud.py` | `test_article_crud_lifecycle` | create→list→update(version 1→2)→history(2 ჩანაწერი)→delete→list — სრული round-trip |
| | `test_article_operations_404_for_missing_id` | GET/PUT/PATCH-autosave/DELETE — ყველა 404 არარსებულ id-ზე |
| | `test_autosave_partial_update_preserves_omitted_fields` | Autosave იყენებს `exclude_unset`-ს — გამოტოვებული ველები (title, target_departments) **უცვლელი რჩება**, არა ნაგულისხმევზე დაბრუნებული |
| `test_article_lifecycle.py` | `test_bulk_archive_reports_skipped_for_missing_and_already_target_state` | Bulk-archive-ს `skipped_ids`-ში ხვდება როგორც არარსებული, ისე უკვე-სამიზნე-მდგომარეობაში მყოფი id-ები |
| | `test_restore_archives_current_state_first_and_bumps_version` | ისტორიიდან restore ჯერ ინახავს მიმდინარე მდგომარეობას ისტორიაში, მერე აღადგენს ძველს — version იზრდება (არა მცირდება) |
| `test_article_misc.py` | `test_note_and_related_404_for_out_of_scope_article` | დეპარტამენტგარეშე სტატიაზე note/related endpoint-ები 404-ია (IDOR-დაცვა), არა 200-ცარიელი |
| | `test_stale_articles_180_day_cutoff` | `/api/admin/articles/stale` ზუსტად 180-დღიან ზღვარზეა აგებული |
| `test_article_quiz.py` | `test_quiz_attempt_grading_and_attempt_number_increments` | არასწორი პასუხი → `attempt_number=1`, `passed=false`; მეორედ სწორი → `attempt_number=2`, `passed=true` |
| | `test_knowledge_score_first_try_bonus` | ქულა = 10 (ჩაბარებისთვის) + 5 (**მხოლოდ** პირველივე ცდაზე ჩაბარებისთვის) = 15 |
| `test_article_read_receipts.py` | `test_check_quiz_gate_blocks_read_receipt_until_quiz_passed` | Read-receipt 403-ია ქვიზის ჩაბარებამდე, 200 — ჩაბარების შემდეგ |
| | `test_read_receipts_grid_excludes_management_roles` | Admin-ის read-receipts grid-ში მენეჯერული როლები **არ ჩანან** |
| `test_article_view_log.py` | `test_track_view_records_version_and_snapshots` | View-ჩანაწერი ინახავს ვერსიას + სნეპშოტებს (სახელი/ელფოსტა/დეპარტამენტი); ძველი ორმაგი AuditLog-ჩანაწერი (VIEW/READ_ARTICLE) **აღარ იწერება** |
| | `test_view_after_version_bump_records_new_version` | ვერსიის ცვლილების შემდეგ ახალი ნახვა ინახავს ახალ ვერსიას, არა ძველს |
| | `test_recently_viewed_lists_viewed_article` | "ბოლოს ნანახი" სათაური არის **ცოცხალი** (join-ით), არა გაყინული სნეპშოტი |
| | `test_admin_views_endpoint_totals_and_version_filter` | სულ-ნახვები/უნიკალური-მნახველი/მიმდინარე-ვერსია სწორია; `?version=N` ფილტრი მუშაობს |

## Content — News / Videos / Categories (9 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_news_crud.py` | `test_news_crud_lifecycle` | create→list→update(version 2)→delete round-trip |
| | `test_news_operations_404_for_missing_id` | PUT/DELETE/PATCH-autosave — 404 არარსებულზე |
| | `test_news_history_restore_reverts_content_and_archives_current` | Restore აბრუნებს v1-ს, აარქივებს v2-ს (version→3), history-ში ორივე ჩანს |
| `test_video_crud.py` | `test_video_crud_lifecycle` | create (YouTube URL ნორმალიზაცია) → view (counter+1) → update (ხელახალი ნორმალიზაცია) → archive → **განმეორებითი archive იდემპოტენტურია** → unarchive → **განმეორებითი unarchive არის 400** → delete |
| | `test_video_operations_404_for_missing_id` | view/PUT/DELETE/archive/unarchive — ყველა 404 არარსებულზე |
| | `test_operator_only_sees_own_department_and_active_videos` | ოპერატორს არ უჩანს სხვა დეპარტამენტის ან დაარქივებული ვიდეო |
| `test_category_crud.py` | `test_category_crud_lifecycle` | create→list→update→delete(soft, is_active=false)→list-გარეშე round-trip |
| | `test_update_delete_category_404_for_missing_id` | PUT/DELETE — 404 არარსებულზე |
| | `test_delete_category_reassigns_articles_to_fallback` | კატეგორიის წაშლისას მისი სტატიები გადადის fallback-კატეგორიაზე ("ზოგადი"), არა ობლივიონში |

## Compliance (20 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_compliance.py` | `test_read_receipt_flow` | 9-ნაბიჯიანი E2E: ვერსიები→read-receipt/me(false)→POST→me(true)→admin-grid→**დეპარტამენტის ცვლილება არ ცვლის grid-ის სნეპშოტს**→ვერსია-ფილტრი→**ოპერატორის hard-delete-ის შემდეგაც receipt ცოცხლობს** (`operator_id=NULL`, სახელის სნეპშოტი დარჩენილი)→**სტატიის hard-delete-ის შემდეგაც** |
| | `test_zero_required_readings_agree_across_implementations` | `_reading_progress` (live dashboard) და `compliance_utils.get_compliance_percentage` (cron) თანხმდებიან ნულოვან-საჭიროებაზე (ორივე 0%, არა 100%/0%) |
| | `test_group_suffixed_user_counts_department_reading_in_both_implementations` | ორივე იმპლემენტაცია (dashboard + cron) ერთნაირად ითვლის ქვე-ჯგუფის მომხმარებელს პრეფიქს-წესით |
| `test_department_stats.py` | `test_whitelist_has_three_departments` | `DEPARTMENT_WHITELIST` ზუსტად 3 დეპარტამენტია, ამ თანმიმდევრობით |
| | `test_match_department_bucket` | პრეფიქს-ამოცნობა 3 whitelisted bucket-ზე + `None` "All"/ცარიელისთვის |
| | `test_build_department_stats_always_three_buckets` | Dashboard ყოველთვის 3 ბარათს აჩვენებს, ცარიელიც რომ იყოს |
| | `test_build_department_stats_includes_office_operators` | "ოფისი — ჯგუფი NN" ოპერატორები ხვდებიან "ოფისი" bucket-ში |
| | `test_build_department_stats_bare_office_operator_counted` | დეპარტამენტი ზუსტად "ოფისი" (ჯგუფის სუფიქსის გარეშე) ითვლება **ორივეგან** — საკუთარ bucket-შიც და გლობალურ ჯამშიც (ადრე გლობალურიდან ჩუმად ვარდებოდა) |
| | `test_get_group_users_endpoint` | ჯგუფის drill-down იყენებს **იმავე** `_match_department_bucket`-ს, არა ცალკე hand-written `.like()` ფილტრს |
| | `test_department_stats_endpoint_returns_three` | HTTP endpoint იმეორებს 3-bucket გარანტიას |
| `test_my_readings.py` | `test_my_readings_lists_assigned_reading_as_unread` | დანიშნული, წაუკითხავი მასალა სწორად ჩანს სტატუსით და overdue-ალმით |
| | `test_my_readings_empty_for_management_roles` | მენეჯერული როლისთვის სია ცარიელია |
| | `test_my_progress_reflects_mark_read` | mark-read-ის შემდეგ progress-ის pending↓/completed↑ სწორად იცვლება |
| | `test_mark_read_rejects_department_mismatch` | **ამ სესიის ფიქსი**: სხვა-დეპარტამენტის mark-read 403-ია (ადრე საერთოდ არ იყო შემოწმება) |
| | `test_mark_read_allows_group_suffixed_department` | ქვე-ჯგუფის მომხმარებელი მაინც შეძლებს mark-read-ს მშობელი დეპარტამენტის მასალაზე |
| `test_read_bridge.py` | `test_mark_read_also_writes_version_receipt` | mark-read (ReadStatus) ავტომატურად წერს ვერსირებულ ArticleReadReceipt-საც |
| | `test_receipt_ack_also_marks_required_reading_read` | Read-receipt ack ავტომატურად ასრულებს დაფარულ RequiredReading-საც (პრეფიქს-შესატყვისობით) |
| | `test_receipt_ack_keeps_first_compliance_read_at` | განმეორებითი ack **არ გადაწერს** პირველი წაკითხვის თარიღს |
| `test_required_reading_crud.py` | `test_required_reading_crud_lifecycle` | create→by-item lookup→update→delete→lookup-null round-trip |
| | `test_update_required_reading_404_for_missing_id` | PUT/DELETE — 404 არარსებულზე |

## Stats / Reporting / Search (7 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_stats_routes.py` | `test_critical_operators_flags_below_threshold_only` | 30%-ის ქვემოთ მყოფი ოპერატორი სიაშია, 100%-იანი — არა |
| | `test_statistics_breakdown_rejects_unknown_dimension` | უცნობი `dimension` → 400; ცნობილი ("role") → 200 |
| | `test_team_stats_manager_pinned_to_own_department` | მენეჯერი ვერ გვერდს უვლის საკუთარ დეპარტამენტს query-პარამეტრით — სერვერი პინავს იძულებით |
| `test_search.py` | `test_search_finds_matching_article_by_title` | ძირითადი ტექსტური ძებნა (pg_trgm/ILIKE) პოულობს ზუსტ სათაურს |
| | `test_search_excludes_article_outside_operators_department` | ძებნაც პატივს სცემს დეპარტამენტ-ხილვადობას, არა მხოლოდ list-endpoint |
| | `test_search_global_returns_combined_shape` | გლობალური ძებნა აბრუნებს `{articles, news, videos}` ერთიან ფორმაში |
| | `test_search_history_records_recent_search` | სინქრონული `/api/search` წერს SearchLog-ს (განსხვავებით `/api/search/global`-ის queued ჩანაწერისგან) |

## Audit Trail (13 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_audit_trail.py` | `test_http_article_edit_writes_attributed_diff` | **სრული E2E მტკიცებულება ბაგი #1-ის უარყოფისთვის**: namdvili HTTP PUT (bearer token) → middleware → ORM listener → UPDATE-ჩანაწერი ველების diff-ით, ატრიბუტირებული JWT-ის მფლობელზე |
| | `test_audit_list_survives_deleted_actor_and_resolves_item_names` | წაშლილი actor-ის namdvili სახელი გადარჩება (სნეპშოტი), item-სახელი სწორად რეზოლვდება |
| | `test_snapshot_immutability_after_user_deletion` | admin_name/email/item_name სნეპშოტები უცვლელია actor-ისა და item-ის ორივეს წაშლის შემდეგ |
| | `test_chain_health_access_and_sqlite_degradation` | SQLite-ზე ყოველთვის 200/`unavailable` (არასდროს 5xx); `n` იჭრება [1,500]; permission-ის 3 დონე (admin/content_admin/operator-403) |
| | `test_manager_sees_only_own_department_audit_logs` | მენეჯერს მხოლოდ **საკუთარი** დეპარტამენტის მწკრივები უჩანს (out-of-group user_id → ცარიელი სია, არა 403/404); export/verify/chain-health მენეჯერისთვის **სრულად** დაბლოკილია |
| | `test_chain_health_tamper_detection_postgres` | **Postgres-only, დესტრუქციული.** 3 ფაზა: ველის ცვლილება→`hash_mismatches`; მწკრივის წაშლა→`link_breaks`; წაშლა window-საზღვარს ქვემოთ→მაინც დაჭერილია (boundary-predecessor query-ის წყალობით) |
| | `test_export_audit_logs_filters_by_user_id` | CSV export პატივს სცემს `?user_id=`-ს ზუსტად ისე, როგორც list-endpoint |
| | `test_ensure_current_version_archived_is_idempotent` | ორმაგი გამოძახება არ ქმნის დუბლირებულ history-მწკრივს |
| | `test_article_history_unique_index_rejects_duplicate_version` | DB-დონის unique index რეალურად მოქმედებს (IntegrityError დუბლიკატზე) |
| `test_logging_config.py` | `test_resolve_log_level_contract` | production→INFO, development→DEBUG, ცხადი override იმარჯვებს ორივეგან |
| | `test_sql_echo_disabled_by_default` | SQL echo **გამორთულია** ნაგულისხმევად |
| | `test_failed_login_writes_security_audit_row` | წარუმატებელი login არსებული ანგარიშისთვის წერს `LOGIN_FAILED`-ს, კატეგორია SECURITY, IP დეტალებში |
| | `test_failed_login_unknown_email_writes_no_audit_row` | უცნობი ელფოსტისთვის **არცერთი** ჩანაწერი (admin_id NOT NULL constraint) |

## Messaging / Exports (7 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_messaging.py` | `test_get_my_messages_returns_own_inbox` | Inbox მხოლოდ საკუთარ მესიჯებს აბრუნებს |
| | `test_delete_message_removes_own_message_not_others` | სხვისი მესიჯის წაშლა → 404; საკუთარის → 204 |
| | `test_broadcast_requires_admin_and_writes_audit_log` | არა-ადმინი → 403; ადმინი → წარმატება + `BROADCAST` აუდიტ-ჩანაწერი |
| `test_export_job_lifecycle.py` | `test_export_job_enqueue_poll_download_cleanup` | სრული ასინქრონული ციკლი: enqueue→worker→status(completed)→download→**ავტომატური cleanup წაშლის ჯობის ჩანაწერსაც** |
| | `test_export_status_404_for_unknown_job` | სტატუსი/download არარსებულ job-ზე → 404 |
| | `test_export_worker_marks_job_failed_on_error` | Worker-ის exception → job სტატუსი `failed`, არა უსასრულოდ `processing` |
| `test_export_routes.py` | `test_export_readings_csv_excludes_ineligible_management_roles` | CSV export გამორიცხავს მენეჯერულ როლებს (განსხვავებით XLSX/PDF-ისგან — ცნობილი ხარვეზი #7) |

## Cross-cutting / Infrastructure (19 ტესტი)

| ტესტ-ფაილი | ფუნქცია | რას ამოწმებს (რეალურად) |
|---|---|---|
| `test_resilience.py` | `test_xss_injection` | `<script>` შემცველი სათაური/კონტენტი არ ამტვრევს backend-ს, ინახება ისე, როგორც შემოვიდა (სანიტაცია frontend-ის პასუხისმგებლობაა) |
| | `test_malformed_idor` | არასწორი ID-ტიპი (სტრიქონი) → 422; უარყოფითი/არარსებული → 404, არა 500 |
| | `test_auth_missing` | ავტორიზაციის გარეშე admin-endpoint → 401/403 |
| | `test_youtube_normalization` | 12+ URL-ფორმატის ნორმალიზაცია ერთ კანონიკურ `embed`-ფორმაში (ცხადი input→output ცხრილი) |
| | `test_sent_messages` | მენეჯერს/ადმინს შეუძლია საკუთარი გაგზავნილი მესიჯების ნახვა, sender/recipient სახელებით |
| | `test_article_status_and_youtube_id` | create/update ინახავს status-ს/youtube_id-ს სწორად; DB-ში NULL-ის შემთხვევაში schema დაბრუნებს ნაგულისხმევს (`""`/`"draft"`) |
| | `test_draft_publish_via_edit_notifies` | draft→published რედაქტირება აგზავნის SSE `article` ივენთს (create-ის მსგავსად), მაგრამ **არა** revision-ივენთს |
| | `test_health_check_redis_fallback_status` | `/api/health` ასახავს **namdvili** Redis-კავშირის მდგომარეობას, არა მხოლოდ event-loop-ის ინიციალიზაციას; 503 multi-worker degraded-ზე |
| | `test_article_published_at_set` | `published_at` ივსება ავტომატურად create/update-ზე, თუ სტატუსი "published" და ველი ჯერ ცარიელია |
| | `test_orm_auto_audit_and_diff` | **ბაზური მტკიცებულება ORM auto-audit-ისთვის**: CREATE/UPDATE(deep diff)/DELETE სამივე იწერება ატრიბუტირებულად |
| | `test_auto_audit_skips_unattributable_writes` | Actor-ის გარეშე (background/seed/migration) **არცერთი** აუდიტ-ჩანაწერი არ იწერება |
| | `test_log_rotation_and_archiving` | `retention.rotate_audit_logs` არქივირებს+შლის ვადაგასულ (`AUDIT_RETENTION_DAYS`-ზე ძველ) ჩანაწერებს, ახალს ტოვებს |
| | `test_view_log_rotation_and_archiving` | იგივე garantia `article_view_logs`-ისთვის |
| | `test_article_history_comparison_diff` | Diff-endpoint ორ ისტორიულ ვერსიას შორის მუშაობს (`compare_history_id`) |
| | `test_article_diff_compare_to_predecessor` | `compare_to_predecessor=true`: v2 vs v1 გვიჩვენებს added/removed რაოდენობებს; v1-ს (წინამორბედის გარეშე) — 0/0 |
| `test_search.py`-ის გვერდით: `test_seed_portal_safety.py` | `test_cmd_users_refuses_in_production` | `scripts/seed_portal.py`-ის `cmd_users` production-ში `SystemExit`-ს იძლევა, DB უცვლელი რჩება |
| | `test_cmd_org_refuses_in_production` | იგივე `cmd_org`-ისთვის |
| | `test_cmd_demo_refuses_in_production` | იგივე `cmd_demo`-ისთვის |
| | `test_refuse_if_production_is_noop_in_dev` | dev-გარემოში დაცვა არ ერევა ჩვეულებრივ მუშაობას |

---

## რას ეს კატალოგი **არ** ფარავს

- `conftest.py` და `factories.py` (247 ხაზი ჯამში) — ინფრასტრუქტურაა (იზოლირებული ტესტ-ბაზა, `make_user`/`make_article`/და ა.შ. ფაბრიკები), არა ტესტები — Java-ში ეკვივალენტია `@SpringBootTest` + test-fixture builder-კლასები, არა ცალკე მისაღები კრიტერიუმები
- `test_chain_health_tamper_detection_postgres` **ნაგულისხმევად გამოტოვებულია ჩვეულებრივ გაშვებაზე** (`MAGTI_PG_TEST_URL` საჭირო) — მთავარი დოკუმენტის §3.2(ბ)-ში უკვე აღნიშნულია, რომ ეს სცენარები Oracle-ზეც ხელახლა უნდა გაეშვას, ცალკე, უსაფრთხოების გადამოწმებად
- ეს კატალოგი აღწერს **რას** ამოწმებს ყოველი ტესტი, არა **როგორ** — კონკრეტული Java/JUnit ეკვივალენტის დაწერა ფაზა 1-ის ამოცანაა, არა ამ დოკუმენტისა
