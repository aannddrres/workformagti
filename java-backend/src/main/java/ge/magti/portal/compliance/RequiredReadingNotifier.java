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
 * operator gets one inbox {@link Message} (which drives the unread-envelope
 * badge).
 *
 * <p><b>Deliberate, documented gap:</b> Python also fires SSE broadcast +
 * per-user real-time events here ({@code _safe_publish}, lines 258-282) so
 * open sessions get a live toast/refresh. The SSE broker isn't built in the
 * Java port yet (Messaging domain), so only the durable half -- the Message
 * rows -- is written. A user sees the notification on their next page load
 * (when the badge count is fetched) rather than in real time; graceful
 * degradation, identical to how every other SSE call site has been handled
 * so far. The Message rows themselves are the meaningful, persistent part.
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
