package ge.magti.portal.web;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.AuthenticationService;
import ge.magti.portal.security.ClientIpResolver;
import ge.magti.portal.security.CorporateLoginService;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.security.LoginRateLimiter;
import ge.magti.portal.security.PortalSessionService;
import ge.magti.portal.audit.MutationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PO-25 and ASVS V6.3.2/V6.3.4: production has exactly one way in, the
 * company directory. With it switched off, the local-password path (and the
 * development personas behind it) must not answer at all -- not even to the
 * throttle, which would otherwise count and audit attempts at a door that
 * does not exist. ProductionSafetyGuard lets such a deployment boot
 * (productionWithTheCompanyLoginOffBootsAndSaysNobodyCanSignIn); this is what
 * keeps it shut.
 */
class AuthControllerProductionTest {

    @Test
    void productionWithoutTheCompanyLoginRefusesEverySignIn() {
        PortalProperties properties = new PortalProperties();
        properties.setAppEnv("production");
        properties.getSecurity().getCorporate().setEnabled(false);
        AuthenticationService authentication = mock(AuthenticationService.class);
        LoginRateLimiter throttle = mock(LoginRateLimiter.class);
        MutationAuditService audit = mock(MutationAuditService.class);
        PortalSessionService sessions = mock(PortalSessionService.class);
        AuthController controller = new AuthController(authentication, mock(JwtService.class), audit, properties,
                throttle, mock(ClientIpResolver.class), mock(UserRepository.class), sessions,
                mock(CorporateLoginService.class), mock(TransactionTemplate.class));

        for (String email : new String[] {"admin@magti.ge", "test_operator_1@magti.ge", "someone@magti.ge"}) {
            ResponseEntity<?> answer = controller.login(new LoginRequest(email, "any password"),
                    mock(HttpServletRequest.class), mock(HttpServletResponse.class));
            assertEquals(HttpStatus.FORBIDDEN, answer.getStatusCode(), email);
        }
        verifyNoInteractions(authentication, throttle, audit, sessions);
    }
}
