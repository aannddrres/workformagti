package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.export.ReadingExportRow;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Random sequences of what editors and operators do, checked after every step
 * against two things at once.
 *
 * <p><b>A model.</b> A few lines, written from the product decisions rather
 * than from the code, of who may open what: an article is open to an operator
 * when it is published (or its scheduled moment has passed) and addressed to
 * their department, a group of it, or everyone; a mandatory reading is owed
 * by those it addresses while its own department can open the article
 * (PO-40), and moving the audience gives the new departments readings of
 * their own (PO-40 §4). The portal must agree with that model on every screen.
 *
 * <p><b>The screens against each other.</b> What an operator is told they owe
 * is what the bell, their progress, the admin's tables, their manager's
 * screens, the readings export, the critical list and its tile and the
 * article's receipts say -- the class of bug RoleFlowIntegrationTest found
 * four of by hand on 2026-10-01, here searched for by machine.
 *
 * <p>Fixed seeds, so a failure is reproducible: the message names the seed
 * and lists every action that led there. A run also proves it reached the
 * states that matter (owed, overdue, confirmed, text changed, suspended...)
 * and fails if it did not -- its first version "passed" without a single
 * reading ever being owed. More sequences: {@code -Dflow.seeds=40 -Dflow.steps=40}.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class RoleFlowModelIntegrationTest {

    private static final int SEEDS = Integer.getInteger("flow.seeds", 6);
    private static final int STEPS = Integer.getInteger("flow.steps", 20);
    private static final int ARTICLES = 3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ExportQueryService exportQueryService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** How often each interesting state was reached: a run that never reached one proved nothing about it. */
    private final Map<String, Integer> reached = new TreeMap<>();
    private List<String> lastLog = List.of();

    // ── the model ────────────────────────────────────────────────────

    private static final class ReadingModel {
        long id;
        String target;
        OffsetDateTime due;
    }

    private static final class ArticleModel {
        long id;
        String status = "published";
        OffsetDateTime publishedAt;
        List<String> targets;
        int version;
        final List<ReadingModel> readings = new ArrayList<>();
    }

    private final class World {
        final String nonce = "MF" + System.nanoTime();
        final String dept = "ტექნიკური" + nonce;
        final String group = dept + " — ჯგუფი 01";
        final String other = "საინფორმაციო" + nonce;
        final Team team = team(dept);
        final Team otherTeam = team(other);
        final User inDept = user(Role.OPERATOR, dept, team);
        final User inGroup = user(Role.OPERATOR, group, team);
        final User inOther = user(Role.OPERATOR, other, otherTeam);
        final User manager = user(Role.MANAGER, dept, team);
        final User editor = user(Role.CONTENT_ADMIN, "All", null);
        final User admin = user(Role.SYSTEM_ADMIN, "All", null);
        final Category category = category();
        final List<User> operators = List.of(inDept, inGroup, inOther);
        final List<ArticleModel> articles = new ArrayList<>();
        /** operator id -> article id -> versions they confirmed. */
        final Map<Long, Map<Long, Set<Integer>>> confirmed = new HashMap<>();
        /** operator id -> reading ids they confirmed. */
        final Map<Long, Set<Long>> readingsRead = new HashMap<>();
        /** operator id -> reading id -> when they last confirmed it: after the deadline, the export says late (PO-49). */
        final Map<Long, Map<Long, OffsetDateTime>> readAt = new HashMap<>();
        final Map<Long, Set<Long>> viewed = new HashMap<>();
        final List<String> log = new ArrayList<>();

        List<String> departments() {
            return List.of(dept, group, other, "All");
        }
    }

    /** Department match as the decisions describe it: the department, a group of it, or everyone. */
    private static boolean addressed(String department, List<String> targets) {
        for (String target : targets) {
            if ("All".equals(target) || target.equals(department) || department.startsWith(target + " — ")) {
                return true;
            }
        }
        return false;
    }

    private static boolean live(ArticleModel a) {
        return "published".equals(a.status)
                || ("scheduled".equals(a.status) && a.publishedAt != null && !a.publishedAt.isAfter(TbilisiTime.now()));
    }

    private static boolean opens(User operator, ArticleModel a) {
        return live(a) && addressed(operator.getDepartment(), a.targets);
    }

    /** PO-40: in force while the reading's own department can open the article. */
    private static boolean inForce(ArticleModel a, ReadingModel r) {
        return live(a) && addressed(r.target, a.targets);
    }

    private static boolean owes(User operator, ArticleModel a, ReadingModel r) {
        return inForce(a, r) && addressed(operator.getDepartment(), List.of(r.target));
    }

    /** PO-40 §4: one reading per department of the audience; "All" covers everyone, a department its groups. */
    private static Set<String> readingTargetsFor(List<String> audience) {
        if (audience.contains("All")) {
            return Set.of("All");
        }
        return audience.stream()
                .filter(d -> audience.stream().noneMatch(other -> !other.equals(d) && d.startsWith(other + " — ")))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // ── the run ──────────────────────────────────────────────────────

    @Test
    void randomSequencesNeverLeaveTwoScreensDisagreeingOrTheModel() throws Exception {
        for (int seed = 1; seed <= SEEDS; seed++) {
            runSequence(seed);
        }
        System.out.println("RoleFlowModel: " + SEEDS + " sequences x " + STEPS + " steps reached " + reached);
        for (String state : List.of("owed and unread", "owed and overdue", "confirmed", "text changed after confirming",
                "obligation suspended", "group operator owes", "scheduled, moment passed", "scheduled, not yet",
                "retarget added a reading")) {
            if (!reached.containsKey(state)) {
                throw new AssertionError("the run never reached '" + state + "'; raise flow.steps or flow.seeds."
                        + " Last sequence:\n  " + String.join("\n  ", lastLog));
            }
        }
    }

    /**
     * One sequence, in a transaction of its own that is always rolled back: a
     * reading "for everyone" one sequence leaves behind would otherwise be owed
     * by the next sequence's people too (seen at 40 sequences, 2026-10-02).
     */
    private void runSequence(int seed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            World w = new World();
            Random random = new Random(seed);
            lastLog = w.log;
            try {
                for (int i = 0; i < ARTICLES; i++) {
                    w.articles.add(create(w, i));
                }
                assign(w, w.articles.getFirst(), w.dept, 200);
                check(w);
                for (int step = 1; step <= STEPS; step++) {
                    act(w, random);
                    check(w);
                }
            } catch (AssertionError e) {
                throw new AssertionError("seed " + seed + ", after:\n  " + String.join("\n  ", w.log)
                        + "\n" + e.getMessage(), e);
            } catch (Exception e) {
                throw new IllegalStateException("seed " + seed, e);
            }
        });
    }

    private void act(World w, Random random) throws Exception {
        ArticleModel a = w.articles.get(random.nextInt(w.articles.size()));
        User operator = w.operators.get(random.nextInt(w.operators.size()));
        ReadingModel r = a.readings.isEmpty() ? null : a.readings.get(random.nextInt(a.readings.size()));
        switch (random.nextInt(9)) {
            case 0 -> {
                String status = List.of("published", "draft", "archived").get(random.nextInt(3));
                call(post("/api/articles/bulk-status"), w.editor, Map.of("ids", List.of(a.id), "status", status), 200);
                a.status = status;
                w.log.add("status #" + a.id + " -> " + status);
            }
            case 1 -> {
                Article row = articleRepository.findById(a.id).orElseThrow();
                boolean past = random.nextBoolean();
                a.status = "scheduled";
                a.publishedAt = past ? TbilisiTime.now().minusMinutes(5) : TbilisiTime.now().plusDays(1);
                row.setStatus("scheduled");
                row.setPublishedAt(a.publishedAt);
                articleRepository.saveAndFlush(row);
                w.log.add("schedule #" + a.id + (past ? " (moment passed)" : " (tomorrow)"));
            }
            case 2 -> {
                List<String> targets = new ArrayList<>();
                for (String d : w.departments()) {
                    if (random.nextInt(3) == 0) {
                        targets.add(d);
                    }
                }
                if (targets.isEmpty()) {
                    targets.add(w.departments().get(random.nextInt(4)));
                }
                call(post("/api/articles/bulk-retarget"), w.editor, Map.of("ids", List.of(a.id),
                        "target_departments", targets), 200);
                a.targets = targets;
                w.log.add("retarget #" + a.id + " -> " + targets);
                // PO-40 §4: a department the re-aim adds gets a reading -- out of force while nobody can
                // open the article, in force with it (owner, 2026-10-02; until then it was dropped when
                // the article was archived, unpublished or scheduled past the due date, 19 times in 1,600 steps).
                boolean mandatory = !a.readings.isEmpty();
                boolean reachable = live(a);
                int before = a.readings.size();
                syncReadings(w, a);
                if (mandatory && !reachable && a.readings.size() > before) {
                    reached("re-aimed while unreachable: reading kept out of force");
                }
                if (mandatory) {
                    Set<String> have = a.readings.stream().map(x -> x.target).collect(Collectors.toSet());
                    for (String expected : readingTargetsFor(targets)) {
                        same(true, have.contains(expected), "PO-40 §4: retargeting a mandatory article to "
                                + targets + " gives " + expected + " a reading");
                    }
                }
                if (a.readings.size() > before) {
                    reached("retarget added a reading");
                    w.log.add("  readings now " + a.readings.stream().map(x -> x.target).toList());
                }
            }
            case 3 -> {
                if (!List.of("published", "draft").contains(a.status)) {
                    return;
                }
                JsonNode current = json(get("/api/articles/" + a.id), w.editor);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", current.get("title").asText());
                body.put("content", "<p>" + w.nonce + " " + System.nanoTime() + "</p>");
                body.put("category_id", w.category.getId());
                body.put("target_departments", a.targets);
                body.put("status", a.status);
                body.put("is_draft", false);
                body.put("quiz_enabled", false);
                body.put("lock_version", current.get("lock_version").asInt());
                a.version = call(put("/api/articles/" + a.id), w.editor, body, 200).get("version").asInt();
                w.log.add("edit text #" + a.id + " -> v" + a.version);
            }
            case 4 -> {
                if (!a.readings.isEmpty()) {
                    return;
                }
                // Editors mostly pick the article's own audience; a third of the time anything, to meet refusals.
                List<String> own = a.targets;
                List<String> any = w.departments();
                String target = random.nextInt(3) > 0
                        ? own.get(random.nextInt(own.size())) : any.get(random.nextInt(any.size()));
                assign(w, a, target, -1);
            }
            case 5 -> {
                if (r == null) {
                    return;
                }
                boolean past = random.nextBoolean();
                RequiredReading reading = requiredReadingRepository.findById(r.id).orElseThrow();
                r.due = past ? TbilisiTime.now().minusMinutes(5) : TbilisiTime.now().plusDays(3);
                reading.setDueDate(r.due);
                requiredReadingRepository.saveAndFlush(reading);
                w.log.add("due of reading " + r.id + " (" + r.target + ") " + (past ? "passed" : "in 3 days"));
            }
            case 6 -> {
                int code = perform(post("/api/articles/" + a.id + "/view"), operator, null).getResponse().getStatus();
                w.log.add("view #" + a.id + " by " + who(w, operator) + " -> " + code);
                expect(opens(operator, a) ? 200 : 404, code, "opening an article records a view only if it opens");
                if (code == 200) {
                    w.viewed.computeIfAbsent(operator.getId(), k -> new LinkedHashSet<>()).add(a.id);
                }
            }
            default -> {
                if (r == null) {
                    return;
                }
                int code = perform(post("/api/compliance/mark-read/" + r.id), operator, null).getResponse().getStatus();
                w.log.add("confirm reading " + r.id + " (" + r.target + ") of #" + a.id + " by " + who(w, operator)
                        + " -> " + code);
                int expected = owes(operator, a, r) ? 200
                        : addressed(operator.getDepartment(), List.of(r.target)) ? 409 : 403;
                expect(expected, code, "confirming");
                if (code == 200) {
                    w.readingsRead.computeIfAbsent(operator.getId(), k -> new LinkedHashSet<>()).add(r.id);
                    w.readAt.computeIfAbsent(operator.getId(), k -> new HashMap<>()).put(r.id, TbilisiTime.now());
                    w.confirmed.computeIfAbsent(operator.getId(), k -> new HashMap<>())
                            .computeIfAbsent(a.id, k -> new TreeSet<>()).add(a.version);
                }
            }
        }
    }

    // ── what must hold after every step ──────────────────────────────

    private void check(World w) throws Exception {
        OffsetDateTime now = TbilisiTime.now();
        Map<Long, String> adminProgress = new HashMap<>();
        for (JsonNode row : json(get("/api/statistics/user-progress?limit=1000"), w.admin)) {
            adminProgress.put(row.get("user_id").asLong(),
                    row.get("read_count").asInt() + "/" + row.get("required_count").asInt());
        }
        Map<Long, String> usersTable = new HashMap<>();
        for (JsonNode row : json(get("/api/users?limit=1000"), w.admin)) {
            usersTable.put(row.get("id").asLong(), row.get("read_count").asText() + "/" + row.get("required_count").asText());
        }
        Map<Long, String> team = new HashMap<>();
        for (JsonNode m : json(get("/api/manager/team-stats"), w.manager).get("members")) {
            team.put(m.get("user_id").asLong(), m.get("read_count").asInt() + "/" + m.get("required_count").asInt());
        }
        Map<Long, Integer> adminCritical = critical(w.admin);
        Map<Long, Integer> managerCritical = critical(w.manager);
        int tile = json(get("/api/manager/department-stats"), w.manager).get("insights").get("critical_operators").asInt();
        same(managerCritical.size(), tile, "manager's critical tile vs the list it opens");
        Set<Long> ours = w.articles.stream().map(x -> x.id).collect(Collectors.toSet());
        Map<String, Set<String>> export = new HashMap<>();
        for (ReadingExportRow row : exportQueryService.eligibleReadingRows(w.admin)) {
            if ("article".equals(row.itemType()) && ours.contains(row.itemId())) {
                export.computeIfAbsent(row.userId() + ":" + row.itemId(), k -> new TreeSet<>()).add(row.status());
            }
        }
        for (ArticleModel a : w.articles) {
            for (ReadingModel r : a.readings) {
                if (!inForce(a, r)) {
                    reached("obligation suspended");
                }
            }
            if ("scheduled".equals(a.status)) {
                reached(live(a) ? "scheduled, moment passed" : "scheduled, not yet");
            }
        }

        for (User op : w.operators) {
            String who = who(w, op);
            Set<Long> opensModel = w.articles.stream().filter(a -> opens(op, a)).map(a -> a.id).collect(Collectors.toSet());
            same(opensModel, ids(json(get("/api/articles?limit=100&q=" + w.nonce), op), "id"), who + ": article list");
            same(opensModel, ids(json(get("/api/search?q=" + w.nonce), op), "id"), who + ": search");
            for (ArticleModel a : w.articles) {
                expect(opens(op, a) ? 200 : 404,
                        perform(get("/api/articles/" + a.id), op, null).getResponse().getStatus(),
                        who + ": opening #" + a.id);
            }
            Set<Long> recent = ids(json(get("/api/me/recently-viewed"), op), "article_id");
            recent.retainAll(ours);
            Set<Long> recentModel = new TreeSet<>(w.viewed.getOrDefault(op.getId(), Set.of()));
            recentModel.retainAll(opensModel);
            same(recentModel, recent, who + ": recently viewed");

            Map<Long, JsonNode> mine = new TreeMap<>();
            for (JsonNode entry : json(get("/api/compliance/my-readings"), op)) {
                if (ours.contains(entry.get("reading").get("item_id").asLong())) {
                    mine.put(entry.get("reading").get("id").asLong(), entry);
                }
            }
            Map<Long, Boolean> bell = new TreeMap<>();
            for (JsonNode item : json(get("/api/notifications/summary"), op).get("unread_readings")) {
                if (ours.contains(item.get("item_id").asLong())) {
                    bell.put(item.get("id").asLong(), item.get("is_overdue").asBoolean());
                }
            }
            Set<Long> owedModel = new TreeSet<>();
            Map<Long, Boolean> bellModel = new TreeMap<>();
            Map<String, Set<String>> exportModel = new HashMap<>();
            int read = 0;
            int overdue = 0;
            Set<Long> readHere = w.readingsRead.getOrDefault(op.getId(), Set.of());
            for (ArticleModel a : w.articles) {
                for (ReadingModel r : a.readings) {
                    boolean isRead = readHere.contains(r.id);
                    String key = op.getId() + ":" + a.id;
                    OffsetDateTime confirmedAt = w.readAt.getOrDefault(op.getId(), Map.of()).get(r.id);
                    String exportedRead = confirmedAt != null && confirmedAt.isAfter(r.due) ? "late" : "read";
                    if (isRead) {
                        exportModel.computeIfAbsent(key, k -> new TreeSet<>()).add(exportedRead);
                    }
                    if (!owes(op, a, r)) {
                        continue;
                    }
                    owedModel.add(r.id);
                    boolean late = !isRead && r.due.isBefore(now);
                    String status = isRead ? "read" : late ? "overdue" : "unread";
                    JsonNode entry = mine.get(r.id);
                    if (entry == null) {
                        continue;
                    }
                    same(status, entry.get("status").asText(), who + ": status of reading " + r.id + " (#" + a.id + ")");
                    boolean changed = isRead && !w.confirmed.getOrDefault(op.getId(), Map.of())
                            .getOrDefault(a.id, Set.of()).contains(a.version);
                    same(changed, entry.get("changed_since_read").asBoolean(), who + ": 'text changed' on #" + a.id);
                    exportModel.computeIfAbsent(key, k -> new TreeSet<>()).add(isRead ? exportedRead : status);
                    if (isRead) {
                        read++;
                    } else {
                        bellModel.put(r.id, late);
                    }
                    if (late) {
                        overdue++;
                    }
                    reached(isRead ? "confirmed" : late ? "owed and overdue" : "owed and unread");
                    if (changed) {
                        reached("text changed after confirming");
                    }
                    if (op == w.inGroup) {
                        reached("group operator owes");
                    }
                }
            }
            same(owedModel, mine.keySet(), who + ": my readings");
            same(bellModel, bell, who + ": the bell (reading -> overdue)");
            for (ArticleModel a : w.articles) {
                String key = op.getId() + ":" + a.id;
                same(exportModel.getOrDefault(key, Set.of()), export.getOrDefault(key, Set.of()),
                        who + ": readings export for #" + a.id);
            }
            JsonNode progress = json(get("/api/compliance/my-progress"), op);
            String own = progress.get("read_completed").asInt() + "/" + progress.get("total_mandatory").asInt();
            // Departments are this world's own, so nothing else is owed by these people.
            same(read + "/" + owedModel.size(), own, who + ": my progress");
            same(own, adminProgress.get(op.getId()), who + ": admin's progress table");
            same(own, usersTable.get(op.getId()), who + ": admin's users table");
            if (op.getTeamId().equals(w.team.getId())) {
                same(own, team.get(op.getId()), who + ": manager's team screen");
            }
            int percentage = progress.get("percentage").asInt();
            boolean criticalModel = !owedModel.isEmpty() && (percentage < 30 || overdue > 0);
            same(criticalModel, adminCritical.containsKey(op.getId()), who + ": admin's critical list");
            if (criticalModel) {
                same(overdue, adminCritical.get(op.getId()), who + ": overdue count");
            }
            if (!op.getDepartment().equals(w.other)) {
                same(criticalModel, managerCritical.containsKey(op.getId()), who + ": manager's critical list");
            }
        }

        // The article's receipts: whom it addresses, and who confirmed this version.
        for (ArticleModel a : w.articles) {
            JsonNode receipts = json(get("/api/articles/" + a.id + "/read-receipts"), w.admin);
            Map<Long, Boolean> rows = new HashMap<>();
            for (JsonNode row : receipts.get("receipts")) {
                rows.put(row.get("operator_id").asLong(), row.get("has_read").asBoolean());
            }
            for (User op : w.operators) {
                boolean confirmedNow = w.confirmed.getOrDefault(op.getId(), Map.of())
                        .getOrDefault(a.id, Set.of()).contains(a.version);
                if (opens(op, a)) {
                    same(confirmedNow, rows.get(op.getId()), who(w, op) + ": receipts of #" + a.id);
                } else if (confirmedNow) {
                    same(Boolean.TRUE, rows.get(op.getId()), who(w, op) + ": a past confirmation of #" + a.id + " stays");
                }
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────

    private void reached(String what) {
        reached.merge(what, 1, Integer::sum);
    }

    private ArticleModel create(World w, int index) throws Exception {
        ArticleModel a = new ArticleModel();
        a.targets = List.of(w.dept);
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", "მოდელი " + index + " " + w.nonce);
        article.put("content", "<p>" + w.nonce + "</p>");
        article.put("category_id", w.category.getId());
        article.put("target_departments", a.targets);
        article.put("status", "published");
        article.put("is_draft", false);
        article.put("quiz_enabled", false);
        JsonNode created = call(post("/api/articles/command"), w.editor, Map.of("article", article, "mandatory", false), 200);
        a.id = created.get("id").asLong();
        a.version = created.get("version").asInt();
        w.log.add("create #" + a.id + " for " + a.targets);
        return a;
    }

    /** {@code expected} -1: whatever the portal says, as long as it never assigns what its department cannot open. */
    private void assign(World w, ArticleModel a, String target, int expected) throws Exception {
        MvcResult result = perform(post("/api/compliance/required-readings"), w.editor, Map.of(
                "item_type", "article", "item_id", a.id, "target_department", target,
                "due_date", TbilisiTime.now().plusDays(3).toString(), "priority", "high"));
        int code = result.getResponse().getStatus();
        w.log.add("mandatory #" + a.id + " for " + target + " -> " + code);
        if (expected != -1) {
            expect(expected, code, "assigning " + result.getResponse().getContentAsString());
        }
        if (code == 200) {
            ReadingModel r = new ReadingModel();
            r.id = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
            r.target = target;
            r.due = TbilisiTime.now().plusDays(3);
            a.readings.add(r);
            // PO-40 §1 and §3: its department can open the article now, or will at a scheduled moment.
            boolean upcoming = "scheduled".equals(a.status) && a.publishedAt != null;
            same(true, addressed(target, a.targets) && (live(a) || upcoming),
                    "PO-40 §1: assigned a reading its department cannot open");
        }
    }

    /** Readings the portal added on its own (PO-40 §4) join the model as they are. */
    private void syncReadings(World w, ArticleModel a) {
        Set<Long> known = a.readings.stream().map(x -> x.id).collect(Collectors.toSet());
        for (RequiredReading row : requiredReadingRepository.findByItemTypeAndItemId("article", a.id)) {
            if (!known.contains(row.getId())) {
                ReadingModel r = new ReadingModel();
                r.id = row.getId();
                r.target = row.getTargetDepartment();
                r.due = row.getDueDate();
                a.readings.add(r);
            }
        }
    }

    private Map<Long, Integer> critical(User watcher) throws Exception {
        Map<Long, Integer> overdue = new HashMap<>();
        for (JsonNode op : json(get("/api/admin/critical-operators"), watcher).get("operators")) {
            overdue.put(op.get("user_id").asLong(), op.get("overdue_count").asInt());
        }
        return overdue;
    }

    private static String who(World w, User op) {
        return op == w.inDept ? "operator in the department"
                : op == w.inGroup ? "operator in its group" : op == w.inOther ? "operator elsewhere" : op.getEmail();
    }

    private static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + " but the portal says " + actual);
        }
    }

    private static void expect(int expected, int actual, String what) {
        same(expected, actual, what + " (HTTP)");
    }

    private JsonNode call(MockHttpServletRequestBuilder request, User user, Object body, int status) throws Exception {
        MvcResult result = perform(request, user, body);
        expect(status, result.getResponse().getStatus(), request.toString()
                + " " + result.getResponse().getContentAsString());
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, User user, Object body) throws Exception {
        request.header("Authorization", "Bearer " + jwtService.createAccessTokenFor(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MockHttpServletRequestBuilder request, User user) throws Exception {
        return call(request, user, null, 200);
    }

    private static Set<Long> ids(JsonNode list, String field) {
        Set<Long> ids = new TreeSet<>();
        for (JsonNode item : list) {
            ids.add(item.get(field).asLong());
        }
        return ids;
    }

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private Category category() {
        Category category = new Category();
        category.setName("model-flow-" + System.nanoTime());
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private User user(Role role, String department, Team team) {
        User user = new User();
        user.setEmail("model-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("მოდელი " + role.value() + " " + System.nanoTime());
        user.setRole(role);
        user.setDepartment(department);
        user.setTeamId(team == null ? null : team.getId());
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }
}
