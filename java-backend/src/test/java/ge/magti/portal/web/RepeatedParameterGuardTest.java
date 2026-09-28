package ge.magti.portal.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ASVS V15.3.7, HTTP parameter pollution. Left to itself Spring resolves a
 * repeated single-valued parameter without a word -- measured on 2026-09-26
 * before this guard existed: a number took the first value
 * ({@code ?limit=5&limit=500} was 5), text joined them ({@code ?q=a&q=b} was
 * "a,b"), and a form field merged with the query string's
 * ({@code ?role=admin} plus a body of {@code role=user} was "admin,user").
 */
class RepeatedParameterGuardTest {

    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String q) {
            return "limit=" + limit + " q=" + q;
        }

        @PostMapping("/probe")
        String form(@RequestParam String role) {
            return "role=" + role;
        }

        @GetMapping("/probe/list")
        String list(@RequestParam List<String> status) {
            return String.join("|", status);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .addInterceptors(new RepeatedParameterGuard())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void aRepeatedSingleValuedParameterIsRefused() throws Exception {
        mvc.perform(get("/probe?limit=5&limit=500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("მოთხოვნის პარამეტრი არასწორია"))
                .andExpect(content().string(not(containsString("500"))));
        mvc.perform(get("/probe?q=a&q=b")).andExpect(status().isBadRequest());
    }

    @Test
    void aFormFieldCannotJoinTheQueryStringsValue() throws Exception {
        mvc.perform(post("/probe?role=admin")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("role=user"))
                .andExpect(status().isBadRequest());
    }

    /** Only what a handler takes as one value is held to one; a list is sent by repeating its name. */
    @Test
    void singleValuesAndListsPassUntouched() throws Exception {
        mvc.perform(get("/probe?limit=5&q=a"))
                .andExpect(status().isOk())
                .andExpect(content().string("limit=5 q=a"));
        mvc.perform(get("/probe/list?status=a&status=b"))
                .andExpect(status().isOk())
                .andExpect(content().string("a|b"));
    }
}
