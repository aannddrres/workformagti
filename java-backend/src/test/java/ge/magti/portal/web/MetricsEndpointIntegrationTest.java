package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The operator's view of a running instance.
 *
 * <p>Before this, Actuator exposed {@code health} alone: an instance was
 * either up or down and there was nothing in between. That is the state
 * every incident starts in -- "it is slow" -- and the one it can never be
 * diagnosed from. These assertions are about what an operator can actually
 * see at 3am, so they check for the specific series that answer the first
 * three questions asked: how many requests, how slow, how many failing.
 *
 * <p>They also check what must NOT be there. The endpoint is reachable
 * without a token (SecurityConfig.ANONYMOUS_PROBES, safe only because
 * nothing routes {@code /actuator} in from outside), so anything it prints
 * is readable by everything in the cluster. Two things would make that a
 * leak, and both are asserted against: a resolved request path carrying an
 * article id or an email, and the configuration itself.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MetricsEndpointIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private String scrape() throws Exception {
        // One real request first, so the http.server.requests series exists
        // -- Micrometer registers a timer when a route is first served, not
        // at startup, and a scrape of an idle instance would pass this test
        // while telling an operator nothing.
        mockMvc.perform(get("/api/health")).andReturn();

        MvcResult result = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "the metrics endpoint must answer an in-cluster scraper that holds no portal token");
        return result.getResponse().getContentAsString();
    }

    @Test
    void theScrapeCarriesWhatAnIncidentIsDiagnosedFrom() throws Exception {
        String body = scrape();

        assertTrue(body.contains("http_server_requests_seconds"),
                "no request timing series: rate, latency and status breakdown all come from this one");
        assertTrue(body.contains("jvm_memory_used_bytes"),
                "no JVM memory series, so a slow leak looks the same as healthy until the pod dies");
        assertTrue(body.contains("hikaricp_connections"),
                "no connection-pool series -- pool exhaustion is the failure this application is most "
                        + "likely to hit under load, and the one that looks like 'the database is slow'");
    }

    @Test
    void everySeriesSaysWhichApplicationProducedIt() throws Exception {
        assertTrue(scrape().contains("application=\"magti-portal\""),
                "without the common tag, two replicas' series are averaged into one line and a single "
                        + "misbehaving pod becomes invisible");
    }

    @Test
    void theScrapeCarriesNoPersonalDataAndNoConfiguration() throws Exception {
        String body = scrape();

        // Micrometer tags request series with the TEMPLATED route. If a
        // resolved id ever appears here it is both a leak and an unbounded
        // number of series, which is how a metrics endpoint takes down the
        // monitoring system it was meant to feed.
        assertFalse(body.contains("@magti.ge"),
                "an email reached the metrics endpoint, which is readable by everything in the cluster");
        assertFalse(body.contains("jdbc:oracle"),
                "the datasource URL reached the metrics endpoint");
    }

    @Test
    void nothingElseWasOpenedAlongWithIt() throws Exception {
        // The exposure list is two entries. These three are the ones whose
        // accidental exposure matters most: env prints the resolved
        // configuration, heapdump hands out process memory, and beans maps
        // the entire application for anyone reading.
        for (String endpoint : new String[] {"env", "heapdump", "beans"}) {
            int status = mockMvc.perform(get("/actuator/" + endpoint)).andReturn().getResponse().getStatus();
            assertTrue(status == 401 || status == 404,
                    "/actuator/" + endpoint + " answered " + status
                            + "; only health and prometheus may be exposed");
        }
    }
}
