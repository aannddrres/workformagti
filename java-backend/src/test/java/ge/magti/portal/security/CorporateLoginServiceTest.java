package ge.magti.portal.security;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The directory decides the role on every sign-in (owner decision,
 * 2026-09-21); the portal keeps deactivation, and department is never blanked.
 */
class CorporateLoginServiceTest {

    private CorporateAuthClient client;
    private UserRepository userRepository;
    private CorporateLoginService service;
    private MutationAuditService audit;

    @BeforeEach
    void setUp() {
        client = mock(CorporateAuthClient.class);
        userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$unusable");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PortalProperties properties = new PortalProperties();
        properties.getSecurity().getCorporate().setEnabled(true);
        properties.getSecurity().getCorporate().setDomain("@example.ge");
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        audit = mock(MutationAuditService.class);
        service = new CorporateLoginService(client, userRepository, passwordEncoder, properties, transactions, audit);
    }

    private void directorySays(String email, Set<String> authorities, String department, String name) {
        when(client.authenticate(email, "pw")).thenReturn(new CorporateAuthClient.Authenticated(
                new CorporateIdentity("test.user", email, "1001", authorities, department, name)));
    }

    private static User existing(Role role, String department, boolean active) {
        User user = new User();
        user.setId(7L);
        user.setEmail("test.user@example.ge");
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(active);
        return user;
    }

    /** Not an address the directory can know: its password is never sent anywhere. */
    @Test
    void anAddressOutsideTheCompanyDomainIsRefusedWithoutAskingTheDirectory() {
        assertInstanceOf(CorporateLoginService.Rejected.class, service.login("someone@elsewhere.com", "pw"));
        verifyNoInteractions(client);
    }

    @Test
    void theFirstSignInCreatesTheAccountWithTheDirectoryRoleAndNoDepartment() {
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge")).thenReturn(Optional.empty());
        directorySays("test.user@example.ge", Set.of("INFOPORTAL_CONTENT_ADMIN", "MAGTICOM_USER"), null, null);

        CorporateLoginService.SignedIn signedIn = assertInstanceOf(CorporateLoginService.SignedIn.class,
                service.login(" Test.User@Example.ge ", "pw"));

        User user = signedIn.user();
        assertTrue(signedIn.created());
        assertEquals(Role.CONTENT_ADMIN, user.getRole());
        assertEquals("test.user", user.getName(), "the login stands in until a real name arrives");
        assertNull(user.getDepartment(), "PO-23: unknown sees only content addressed to everyone");
        assertTrue(user.isActive());
        assertTrue(user.getPermissions().containsAll(
                Permission.defaultsFor(Role.CONTENT_ADMIN).stream().map(Permission::value).toList()));
    }

    /** A successful directory authentication without a portal role cannot create an account. */
    @Test
    void unknownOrEmptyAuthoritiesNeverCreateAPortalAccount() {
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge")).thenReturn(Optional.empty());
        directorySays("test.user@example.ge", Set.of("MAGTICOM_USER"), null, null);

        assertInstanceOf(CorporateLoginService.Rejected.class,
                service.login("test.user@example.ge", "pw"));
        verify(userRepository, never()).save(any(User.class));
    }

    /** Withdrawn in the directory: refuse entry and revoke existing portal tokens. */
    @Test
    void aRoleWithdrawnInTheDirectoryRevokesExistingSessions() {
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge"))
                .thenReturn(Optional.of(existing(Role.MANAGER, "ტექნიკური — ჯგუფი 03", true)));
        when(userRepository.revokeIssuedTokens(7L)).thenReturn(1);
        directorySays("test.user@example.ge", Set.of("MAGTICOM_USER"), null, null);

        CorporateLoginService.Rejected rejected = assertInstanceOf(CorporateLoginService.Rejected.class,
                service.login("test.user@example.ge", "pw"));

        assertFalse(rejected.deactivated());
        verify(userRepository).revokeIssuedTokens(7L);
        verify(userRepository, never()).save(any(User.class));
    }

    /** PO-24: an administrator's deactivation outranks a correct password. */
    @Test
    void aDeactivatedAccountStaysOutWhateverTheDirectorySays() {
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge"))
                .thenReturn(Optional.of(existing(Role.OPERATOR, null, false)));
        directorySays("test.user@example.ge", Set.of("INFOPORTAL_ADMIN"), null, null);

        CorporateLoginService.Rejected rejected = assertInstanceOf(CorporateLoginService.Rejected.class,
                service.login("test.user@example.ge", "pw"));

        assertTrue(rejected.deactivated());
        verify(userRepository, never()).save(any(User.class));
    }

    /** PO-29, rule one: a department typed in by an administrator survives a directory that sends none. */
    @Test
    void aDirectoryWithoutADepartmentNeverBlanksOne() {
        User user = existing(Role.OPERATOR, "საინფორმაციო — ჯგუფი 02", true);
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge")).thenReturn(Optional.of(user));
        directorySays("test.user@example.ge", Set.of("INFOPORTAL_OPERATOR"), null, null);

        service.login("test.user@example.ge", "pw");

        assertEquals("საინფორმაციო — ჯგუფი 02", user.getDepartment());
        assertEquals("ტესტ მომხმარებელი", user.getName());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void aDepartmentAndNameTheDirectorySendsAreTaken() {
        User user = existing(Role.OPERATOR, null, true);
        when(userRepository.findByEmailIgnoreCase("test.user@example.ge")).thenReturn(Optional.of(user));
        directorySays("test.user@example.ge", Set.of("INFOPORTAL_OPERATOR"), "ოფისი — ჯგუფი 01", "ახალი სახელი");

        service.login("test.user@example.ge", "pw");

        assertEquals("ოფისი — ჯგუფი 01", user.getDepartment());
        assertEquals("ახალი სახელი", user.getName());
    }

    @Test
    void theDirectorysRejectionAndOutageAreKeptApart() {
        when(client.authenticate("test.user@example.ge", "wrong")).thenReturn(new CorporateAuthClient.Rejected());
        when(client.authenticate("test.user@example.ge", "pw")).thenReturn(new CorporateAuthClient.Unavailable("HTTP 503"));

        CorporateLoginService.Rejected rejected = assertInstanceOf(CorporateLoginService.Rejected.class,
                service.login("test.user@example.ge", "wrong"));
        assertFalse(rejected.deactivated());
        assertInstanceOf(CorporateLoginService.Unavailable.class, service.login("test.user@example.ge", "pw"));
        verify(userRepository, never()).save(any(User.class));
    }
}
