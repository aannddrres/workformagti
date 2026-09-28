package ge.magti.portal.reminder;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReminderServiceCardinalityTest {

    @Test
    void oversizedDirectoryFailsBeforeTheFirstAssignmentReminderWrite() {
        ReminderRepository reminders = mock(ReminderRepository.class);
        RequiredReadingRepository readings = mock(RequiredReadingRepository.class);
        ReadStatusRepository statuses = mock(ReadStatusRepository.class);
        UserRepository users = mock(UserRepository.class);
        MutationAuditService audit = mock(MutationAuditService.class);
        ItemTitleResolver titles = mock(ItemTitleResolver.class);
        ScopeResolver scopes = mock(ScopeResolver.class);
        UserDirectoryQueryService directory = mock(UserDirectoryQueryService.class);
        OrgDirectoryQueryService orgDirectory = mock(OrgDirectoryQueryService.class);
        ReminderService service = new ReminderService(
                reminders, readings, statuses, users, audit, titles, scopes, orgDirectory,
                inForceReach(directory, orgDirectory));
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(42L);
        when(titles.resolve("article", 42L)).thenReturn(Optional.of("სათაური"));
        when(orgDirectory.listActiveAssignmentsWithinLimit()).thenReturn(List.of());
        when(directory.listActiveUsersWithinLimit())
                .thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());

        assertThrows(UserDirectoryQueryService.ActiveUserCardinalityExceededException.class,
                () -> service.deliverAssignment(reading, new User()));

        verify(directory).listActiveUsersWithinLimit();
        verify(users, never()).findByActiveTrue();
        verify(reminders, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void oversizedAssignmentSeedSnapshotFailsBeforeScheduledReminderWrites() {
        ReminderRepository reminders = mock(ReminderRepository.class);
        RequiredReadingRepository readings = mock(RequiredReadingRepository.class);
        ReadStatusRepository statuses = mock(ReadStatusRepository.class);
        UserRepository users = mock(UserRepository.class);
        MutationAuditService audit = mock(MutationAuditService.class);
        ItemTitleResolver titles = mock(ItemTitleResolver.class);
        ScopeResolver scopes = mock(ScopeResolver.class);
        UserDirectoryQueryService directory = mock(UserDirectoryQueryService.class);
        OrgDirectoryQueryService orgDirectory = mock(OrgDirectoryQueryService.class);
        ReminderService service = new ReminderService(
                reminders, readings, statuses, users, audit, titles, scopes, orgDirectory,
                inForceReach(directory, orgDirectory));
        RequiredReading reading = new RequiredReading();
        reading.setId(7L);
        reading.setItemType("article");
        reading.setItemId(42L);
        when(titles.resolve("article", 42L)).thenReturn(Optional.of("სათაური"));
        when(orgDirectory.listActiveAssignmentsWithinLimit()).thenReturn(List.of());
        List<Reminder> oversized = IntStream.range(0, CompleteResultGuard.MAX_ROWS + 1)
                .mapToObj(ignored -> new Reminder())
                .toList();
        when(reminders.findByRequiredReadingIdAndTypeOrderByIdAsc(
                any(), any(), any())).thenReturn(oversized);

        assertThrows(CompleteResultGuard.CompleteResultCardinalityExceededException.class,
                () -> service.deliverScheduled(reading, ReminderType.DUE_SOON));

        verify(reminders, never()).saveAndFlush(any());
        verifyNoInteractions(users, statuses, audit);
    }

    /**
     * A real {@link MandatoryReach} over mocks, with article 42 published for
     * "All": the reading is in force, so delivery reaches the directory.
     */
    private static MandatoryReach inForceReach(
            UserDirectoryQueryService directory, OrgDirectoryQueryService orgDirectory) {
        ArticleRepository articles = mock(ArticleRepository.class);
        ArticleTargetQueryService audiences = mock(ArticleTargetQueryService.class);
        Article article = new Article();
        article.setId(42L);
        article.setStatus("published");
        article.setDraft(false);
        when(articles.findAllById(any())).thenReturn(List.of(article));
        when(audiences.targetDepartmentsByArticleWithinLimit(any())).thenReturn(Map.of(42L, List.of("All")));
        return new MandatoryReach(mock(RequiredReadingRepository.class), articles, mock(NewsRepository.class),
                mock(VideoInstructionRepository.class), audiences, directory, orgDirectory);
    }
}
