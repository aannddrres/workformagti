package ge.magti.portal.storage;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.StoredFile;
import ge.magti.portal.repository.StoredFileRepository;
import ge.magti.portal.util.TbilisiTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single place uploaded attachments are written and read (audit PR-03).
 *
 * <h2>Why Oracle BLOBs and not a volume or object storage</h2>
 *
 * Before this class, {@code UploadController} wrote to {@code
 * portal.uploads-dir} -- a relative path resolving to {@code /app/uploads}
 * inside the image -- with no {@code VOLUME} in the Dockerfile and no
 * Kubernetes manifests anywhere in the repository. Two consequences, both
 * confirmed by the audit: every restart permanently deleted every attachment
 * while the database kept the now-dangling {@code /uploads/<uuid>} link, and
 * with <i>n</i> replicas a given file was visible on about 1/<i>n</i> of the
 * requests. Three ways out were considered:
 *
 * <ol>
 *   <li><b>ReadWriteMany PersistentVolume at /app/uploads.</b> Smallest code
 *       change -- none at all, in fact -- and the best fit if the files were
 *       large or numerous. But RWX is not something every storage class
 *       offers (it usually means NFS or CephFS), this repository has no
 *       manifests to put the claim in, and docs/QUESTIONS_FOR_IT.md still has
 *       open questions about the cluster. Choosing it would mean shipping a
 *       fix whose working half lives in infrastructure nobody here can see or
 *       test, and the code would look fixed while still being broken.
 *   <li><b>S3-compatible object storage.</b> The conventional answer, and the
 *       right one at a larger scale. It needs a bucket, credentials, a
 *       lifecycle policy and an endpoint -- four things that must come from
 *       Magti IT -- plus a new SDK dependency. Same objection as (1), with
 *       more moving parts.
 *   <li><b>Bytes in Oracle. Chosen.</b> Oracle is already a hard dependency,
 *       already backed up, already replicated, and already where the
 *       {@code export_jobs} row lives -- so the file and its metadata stop
 *       being able to disagree. It needs nothing from IT to start working,
 *       which is what makes it the honest choice for a repository that cannot
 *       yet describe its own deployment.
 * </ol>
 *
 * <h2>What this costs, stated plainly</h2>
 *
 * BLOBs in the transactional database are not free. Uploads are capped at
 * 10 MB each ({@code UploadController.MAX_UPLOAD_SIZE_BYTES}) and the portal
 * is an internal knowledge base for ~600 people, so the volume is small --
 * but the bytes now land in database backups, and the tablespace has to be
 * sized for them. That requirement is written down in
 * docs/QUESTIONS_FOR_IT.md as a prerequisite. If the answer from IT is
 * eventually "use the object store", only this class changes: nothing else
 * touches a {@link Path}.
 *
 * <h2>Legacy files</h2>
 *
 * {@link #load(String)} falls back to {@code portal.uploads-dir} when the
 * filename is not in the table, so attachments written before this change
 * still resolve for as long as that pod's disk survives. New writes never go
 * to disk.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /**
     * Filenames this service will look up. Uploads are named
     * {@code <uuid>.<ext>} by {@code UploadController}, so this is far wider
     * than what we generate -- it exists to make the disk fallback below
     * safe. {@code /uploads/**} is a public path (see {@code SecurityConfig}),
     * and {@code Path.resolve} on an unvalidated segment is how a request for
     * {@code ..%2f..%2fapplication.yml} turns into an arbitrary file read.
     */
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final StoredFileRepository storedFileRepository;
    private final PortalProperties portalProperties;

    public FileStorageService(StoredFileRepository storedFileRepository, PortalProperties portalProperties) {
        this.storedFileRepository = storedFileRepository;
        this.portalProperties = portalProperties;
    }

    /** Stores the bytes and returns the filename they are addressable by. */
    public String store(String filename, String contentType, byte[] content, Long uploadedBy) {
        StoredFile stored = new StoredFile();
        stored.setFilename(filename);
        stored.setContentType(contentType);
        stored.setByteSize(content.length);
        stored.setUploadedBy(uploadedBy);
        stored.setCreatedAt(TbilisiTime.now());
        stored.setContent(content);
        storedFileRepository.save(stored);
        return filename;
    }

    /**
     * Database first, then the local uploads directory for anything written
     * before PR-03 was fixed. Returns empty for an unknown or unsafe name --
     * the caller turns that into a 404, and must not distinguish the two.
     */
    public Optional<StoredContent> load(String filename) {
        if (filename == null || !SAFE_FILENAME.matcher(filename).matches()) {
            return Optional.empty();
        }
        Optional<StoredFile> row = storedFileRepository.findById(filename);
        if (row.isPresent()) {
            StoredFile file = row.get();
            return Optional.of(new StoredContent(file.getContent(), file.getContentType()));
        }
        return loadLegacyFromDisk(filename);
    }

    private Optional<StoredContent> loadLegacyFromDisk(String filename) {
        Path dir = Path.of(portalProperties.getUploadsDir()).toAbsolutePath().normalize();
        Path path = dir.resolve(filename).normalize();
        // SAFE_FILENAME already rules out traversal; this is the second lock
        // on the same door, because the cost of being wrong here is an
        // arbitrary file read on a public endpoint.
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredContent(Files.readAllBytes(path), probeContentType(path)));
        } catch (IOException e) {
            log.warn("legacy upload {} could not be read from disk: {}", filename, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Only for legacy disk files -- rows written through {@link #store} carry
     * the server-detected type that passed {@code UploadController}'s MIME
     * allowlist. A probe that fails degrades to {@code
     * application/octet-stream}, which downloads rather than renders; the
     * response also carries {@code X-Content-Type-Options: nosniff} so a
     * wrong guess cannot be re-interpreted by the browser.
     */
    private static String probeContentType(Path path) {
        try {
            String probed = Files.probeContentType(path);
            return probed == null ? "application/octet-stream" : probed;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /** Bytes plus the content type to serve them as. */
    public record StoredContent(byte[] content, String contentType) {
    }
}
