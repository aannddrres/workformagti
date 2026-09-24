package ge.magti.portal.export;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Unique per backend process, so another replica's live exports remain untouched. */
@Component
public class ExportJobLeaseOwner {
    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }
}
