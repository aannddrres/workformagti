package ge.magti.portal.compliance;

import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Message;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.MessageRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Port of auto_generate_notifications_for_mandatory (routers/compliance.py:
 * 200-282) -- when a required reading is created, every affected active
 * operator gets one interim durable reminder {@link Message} row (which
 * drives the unread reminder count).
 *
 * <p><b>Deliberate, documented gap:</b> Python also fires SSE broadcast +
 * per-user real-time events here ({@code _safe_publish}, lines 258-282) so
 * open sessions get a live toast/refresh. The SSE broker isn't built in the
 * Java port yet, so only the durable reminder half is written. Private
 * messaging endpoints and UI do not expose these rows. R3 replaces this
 * interim persistence with the dedicated fixed-template reminder engine.
 *
 * <p>Department matching is prefix-aware ({@link DepartmentMatcher}) so a
 * reading targeted at "ტექნიკური" also notifies users in "ტექნიკური —
 * ჯგუფი 03", matching what those users' own "my readings" list will show
 * them -- routers/compliance.py:221-235.
 */
@Service
public class RequiredReadingNotifier {

    private static final DateTimeFormatter DUE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ItemTitleResolver itemTitleResolver;

    public RequiredReadingNotifier(
            UserRepository userRepository,
            MessageRepository messageRepository,
            ItemTitleResolver itemTitleResolver) {
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.itemTitleResolver = itemTitleResolver;
    }

    public void notifyAffectedUsers(RequiredReading reading, Long creatorId) {
        String itemTitle = itemTitleResolver.resolve(reading.getItemType(), reading.getItemId())
                .orElse("მასალა #" + reading.getItemId());

        String target = reading.getTargetDepartment();
        List<User> targets = new ArrayList<>();
        for (User u : userRepository.findByActiveTrue()) {
            if (u.getId().equals(creatorId)) {
                continue;
            }
            // BL-05: this was the ONE place in the codebase that did not
            // apply ComplianceCalculator::isEligible. Management roles are
            // excluded from required reading everywhere else -- most
            // directly at ComplianceController.getMyReadings, which returns
            // an empty list for them -- but they were still messaged about
            // every new obligation. A manager got an inbox item telling them
            // to read something that does not appear in their reading list
            // and that they are not measured on.
            if (!ComplianceCalculator.isEligible(u)) {
                continue;
            }
            if ("All".equals(target) || DepartmentMatcher.matches(u.getDepartment(), List.of(target))) {
                targets.add(u);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        // Normalise to Tbilisi wall-clock before taking the date part: the
        // stored value is Tbilisi-local (TbilisiTimestampConverter), so the
        // deadline the operator sees must be the Tbilisi date, not whatever
        // offset the client's datetime happened to arrive in.
        String dueStr = reading.getDueDate().withOffsetSameInstant(TbilisiTime.OFFSET).format(DUE_DATE_FORMAT);
        String content = "ახალი სავალდებულოდ გასაცნობი მასალა: '" + itemTitle + "' (ვადა: " + dueStr + ")";

        List<Message> messages = new ArrayList<>();
        for (User u : targets) {
            Message message = new Message();
            message.setUserId(u.getId());
            message.setSenderId(creatorId);
            message.setContent(content);
            message.setRead(false);
            message.setCreatedAt(TbilisiTime.now());
            messages.add(message);
        }
        messageRepository.saveAll(messages);
    }
}
