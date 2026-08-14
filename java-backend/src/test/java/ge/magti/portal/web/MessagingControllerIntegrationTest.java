package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.MessageRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- covers all 6
 * durable Messaging endpoints (the SSE stream is deliberately not built,
 * see {@link MessagingController}'s javadoc).
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessagingControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი " + email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String messageJson(long userId, String content) {
        return "{\"user_id\":" + userId + ",\"content\":\"" + content + "\"}";
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorCannotSendOrViewSentMessages() throws Exception {
        User operator = createUser("msg-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(operator.getId(), "გამარჯობა")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));

        mockMvc.perform(authed(get("/api/messages/sent"), tokenFor(operator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanSendToOwnDepartmentOperatorAndNamesArePopulated() throws Exception {
        User manager = createUser("msg-mgr1@magti.ge", Role.MANAGER, "ოფისი");
        User operator = createUser("msg-op2@magti.ge", Role.OPERATOR, "ოფისი");

        String body = mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(operator.getId(), "გთხოვთ გაეცნოთ ახალ მასალას")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("გთხოვთ გაეცნოთ ახალ მასალას"))
                .andExpect(jsonPath("$.is_read").value(false))
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(body);
        assertEquals(manager.getName(), node.get("sender_name").asText());
        assertEquals(operator.getName(), node.get("recipient_name").asText());

        mockMvc.perform(authed(get("/api/messages"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("გთხოვთ გაეცნოთ ახალ მასალას"))
                .andExpect(jsonPath("$[0].sender_name").value(manager.getName()));
    }

    @Test
    void managerCannotSendToAnotherDepartment() throws Exception {
        User manager = createUser("msg-mgr2@magti.ge", Role.MANAGER, "ოფისი");
        User outsider = createUser("msg-op3@magti.ge", Role.OPERATOR, "ტექნიკური");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(outsider.getId(), "სცადე")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(
                        "მენეჯერებს შეუძლიათ შეტყობინების გაგზავნა მხოლოდ საკუთარი დეპარტამენტის თანამშრომლებისთვის"));
    }

    @Test
    void managerCanReachASubGroupOfTheirOwnDepartment() throws Exception {
        // DirectMessagePermission's prefix-aware fix: a "ტექნიკური" manager
        // can reach "ტექნიკური — ჯგუფი 03" without the department strings matching exactly.
        User manager = createUser("msg-mgr3@magti.ge", Role.MANAGER, "ტექნიკური");
        User subGroupOp = createUser("msg-op4@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 03");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(subGroupOp.getId(), "ქვეჯგუფს")))
                .andExpect(status().isOk());
    }

    @Test
    void systemAdminCanMessageAnyDepartment() throws Exception {
        User admin = createUser("msg-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        User anyOp = createUser("msg-op5@magti.ge", Role.OPERATOR, "სულ სხვა დეპარტამენტი");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(anyOp.getId(), "ადმინის შეტყობინება")))
                .andExpect(status().isOk());
    }

    @Test
    void sendingToAMissingRecipientIs404() throws Exception {
        User manager = createUser("msg-mgr4@magti.ge", Role.MANAGER, "All");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(999999999L, "ვინმეს")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("მიმღები ვერ მოიძებნა"));
    }

    @Test
    void sentMessagesOnlyShowsThisManagersOwnSentMessages() throws Exception {
        User managerA = createUser("msg-mgr5@magti.ge", Role.MANAGER, "ოფისი");
        User managerB = createUser("msg-mgr6@magti.ge", Role.MANAGER, "ტექნიკური");
        User opA = createUser("msg-op6@magti.ge", Role.OPERATOR, "ოფისი");
        User opB = createUser("msg-op7@magti.ge", Role.OPERATOR, "ტექნიკური");

        mockMvc.perform(authed(post("/api/messages"), tokenFor(managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(opA.getId(), "A-სგან")))
                .andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/messages"), tokenFor(managerB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(opB.getId(), "B-სგან")))
                .andExpect(status().isOk());

        String body = mockMvc.perform(authed(get("/api/messages/sent"), tokenFor(managerA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var arr = objectMapper.readTree(body);
        assertEquals(1, arr.size());
        assertEquals("A-სგან", arr.get(0).get("content").asText());
    }

    @Test
    void markMessageReadUpdatesStatusAndIsScopedToOwner() throws Exception {
        User manager = createUser("msg-mgr7@magti.ge", Role.MANAGER, "All");
        User recipient = createUser("msg-op8@magti.ge", Role.OPERATOR, "All");
        User intruder = createUser("msg-op9@magti.ge", Role.OPERATOR, "All");

        String sendBody = mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(recipient.getId(), "წაიკითხე")))
                .andReturn().getResponse().getContentAsString();
        long messageId = objectMapper.readTree(sendBody).get("id").asLong();

        mockMvc.perform(authed(post("/api/messages/" + messageId + "/read"), tokenFor(intruder)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("შეტყობინება ვერ მოიძებნა"));

        mockMvc.perform(authed(post("/api/messages/" + messageId + "/read"), tokenFor(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_read").value(true));

        assertTrue(messageRepository.findById(messageId).orElseThrow().isRead());
    }

    @Test
    void deleteMessageRemovesItAndIsScopedToOwner() throws Exception {
        User manager = createUser("msg-mgr8@magti.ge", Role.MANAGER, "All");
        User recipient = createUser("msg-op10@magti.ge", Role.OPERATOR, "All");
        User intruder = createUser("msg-op11@magti.ge", Role.OPERATOR, "All");

        String sendBody = mockMvc.perform(authed(post("/api/messages"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageJson(recipient.getId(), "წაშლადი")))
                .andReturn().getResponse().getContentAsString();
        long messageId = objectMapper.readTree(sendBody).get("id").asLong();

        mockMvc.perform(authed(delete("/api/messages/" + messageId), tokenFor(intruder)))
                .andExpect(status().isNotFound());
        assertTrue(messageRepository.findById(messageId).isPresent());

        mockMvc.perform(authed(delete("/api/messages/" + messageId), tokenFor(recipient)))
                .andExpect(status().isNoContent());
        assertFalse(messageRepository.findById(messageId).isPresent());
    }

    @Test
    void broadcastRequiresContentAdminNotManager() throws Exception {
        User manager = createUser("msg-mgr9@magti.ge", Role.MANAGER, "All");
        User admin = createUser("msg-admin2@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(post("/api/broadcast"), tokenFor(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"განცხადება\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(post("/api/broadcast"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"განცხადება ყველასთვის\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    /** Covers the 2026-08-14 fix -- broadcast now persists real Message rows
     *  instead of only writing an audit-log entry. */
    @Test
    void broadcastToAllCreatesMessagesButNotForTheSender() throws Exception {
        User admin = createUser("msg-broadcast-admin1@magti.ge", Role.CONTENT_ADMIN, "All");
        User recipient = createUser("msg-broadcast-recip1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/broadcast"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"საერთო განცხადება\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.recipients").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        boolean recipientGotMessage = messageRepository.findByUserIdOrderByCreatedAtDesc(recipient.getId()).stream()
                .anyMatch(m -> "საერთო განცხადება".equals(m.getContent()) && admin.getId().equals(m.getSenderId()));
        assertTrue(recipientGotMessage, "recipient should have a persisted broadcast message");

        boolean senderGotOwnBroadcast = messageRepository.findByUserIdOrderByCreatedAtDesc(admin.getId()).stream()
                .anyMatch(m -> "საერთო განცხადება".equals(m.getContent()));
        assertFalse(senderGotOwnBroadcast, "the broadcasting admin should not receive their own broadcast");
    }

    @Test
    void broadcastScopedByDepartmentOnlyReachesThatDepartment() throws Exception {
        String uniqueDept = "ტესტ-დეპარტამენტი-" + System.nanoTime();
        String otherDept = "სხვა-დეპარტამენტი-" + System.nanoTime();
        User admin = createUser("msg-broadcast-admin2@magti.ge", Role.SYSTEM_ADMIN, "All");
        User inDept = createUser("msg-broadcast-in2@magti.ge", Role.OPERATOR, uniqueDept);
        User outOfDept = createUser("msg-broadcast-out2@magti.ge", Role.OPERATOR, otherDept);

        String body = objectMapper.writeValueAsString(Map.of(
                "message", "დეპარტამენტული განცხადება",
                "target_department", uniqueDept));

        mockMvc.perform(authed(post("/api/broadcast"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(1));

        assertTrue(messageRepository.findByUserIdOrderByCreatedAtDesc(inDept.getId()).stream()
                .anyMatch(m -> "დეპარტამენტული განცხადება".equals(m.getContent())));
        assertTrue(messageRepository.findByUserIdOrderByCreatedAtDesc(outOfDept.getId()).stream()
                .noneMatch(m -> "დეპარტამენტული განცხადება".equals(m.getContent())));
    }

    @Test
    void broadcastScopedByRoleFurtherNarrowsRecipients() throws Exception {
        String uniqueDept = "როლური-დეპარტამენტი-" + System.nanoTime();
        User admin = createUser("msg-broadcast-admin3@magti.ge", Role.SYSTEM_ADMIN, "All");
        User operator = createUser("msg-broadcast-op3@magti.ge", Role.OPERATOR, uniqueDept);
        User manager = createUser("msg-broadcast-mgr3@magti.ge", Role.MANAGER, uniqueDept);

        String body = objectMapper.writeValueAsString(Map.of(
                "message", "მხოლოდ ოპერატორებისთვის",
                "target_department", uniqueDept,
                "target_role", "operator"));

        mockMvc.perform(authed(post("/api/broadcast"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(1));

        assertTrue(messageRepository.findByUserIdOrderByCreatedAtDesc(operator.getId()).stream()
                .anyMatch(m -> "მხოლოდ ოპერატორებისთვის".equals(m.getContent())));
        assertTrue(messageRepository.findByUserIdOrderByCreatedAtDesc(manager.getId()).stream()
                .noneMatch(m -> "მხოლოდ ოპერატორებისთვის".equals(m.getContent())));
    }
}
