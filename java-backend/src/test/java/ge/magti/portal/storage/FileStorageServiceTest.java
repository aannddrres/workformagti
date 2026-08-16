package ge.magti.portal.storage;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.StoredFile;
import ge.magti.portal.repository.StoredFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free cover for the storage layer PR-03 introduced.
 *
 * <p>The traversal cases matter more than they look: {@code /uploads/**} is a
 * public path, and the disk fallback resolves a request-supplied name against
 * a real directory. Serving a file out of the database is safe by
 * construction; serving one off the filesystem is only safe because of the
 * filter these tests pin.
 */
class FileStorageServiceTest {

    private final StoredFileRepository repository = mock(StoredFileRepository.class);

    private FileStorageService serviceWithUploadsDir(Path dir) {
        PortalProperties properties = new PortalProperties();
        properties.setUploadsDir(dir.toString());
        return new FileStorageService(repository, properties);
    }

    private static StoredFile row(String filename, String contentType, byte[] content) {
        StoredFile file = new StoredFile();
        file.setFilename(filename);
        file.setContentType(contentType);
        file.setByteSize(content.length);
        file.setContent(content);
        return file;
    }

    @Test
    void storedBytesComeBackWithTheirDeclaredContentType(@TempDir Path dir) {
        byte[] content = {(byte) 0x89, 'P', 'N', 'G'};
        when(repository.findById("a.png")).thenReturn(Optional.of(row("a.png", "image/png", content)));

        Optional<FileStorageService.StoredContent> loaded = serviceWithUploadsDir(dir).load("a.png");

        assertTrue(loaded.isPresent());
        assertArrayEquals(content, loaded.get().content());
        assertEquals("image/png", loaded.get().contentType());
    }

    @Test
    void storeWritesARowAndNeverTouchesTheFilesystem(@TempDir Path dir) throws IOException {
        FileStorageService service = serviceWithUploadsDir(dir);

        service.store("b.pdf", "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8), 7L);

        verify(repository).save(any(StoredFile.class));
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries.count(), "PR-03: an upload must not create a file on this container");
        }
    }

    /** Attachments written before PR-03 are still on the pod that took them. */
    @Test
    void aFileOnDiskIsStillServedWhenTheDatabaseHasNoRow(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("legacy.txt"), "old".getBytes(StandardCharsets.UTF_8));
        when(repository.findById("legacy.txt")).thenReturn(Optional.empty());

        Optional<FileStorageService.StoredContent> loaded = serviceWithUploadsDir(dir).load("legacy.txt");

        assertTrue(loaded.isPresent());
        assertArrayEquals("old".getBytes(StandardCharsets.UTF_8), loaded.get().content());
    }

    @Test
    void aTraversingNameIsRejectedBeforeTheDatabaseIsEvenAsked(@TempDir Path dir) throws IOException {
        Path secret = dir.getParent().resolve("secret.txt");
        Files.write(secret, "do not serve me".getBytes(StandardCharsets.UTF_8));

        FileStorageService service = serviceWithUploadsDir(dir);

        assertFalse(service.load("../secret.txt").isPresent());
        assertFalse(service.load("../../etc/passwd").isPresent());
        assertFalse(service.load("/etc/passwd").isPresent());
        assertFalse(service.load("..").isPresent());
        verify(repository, org.mockito.Mockito.never()).findById(anyString());
    }

    @Test
    void anUnknownNameIsEmptyRatherThanAnError(@TempDir Path dir) {
        when(repository.findById("missing.png")).thenReturn(Optional.empty());

        assertFalse(serviceWithUploadsDir(dir).load("missing.png").isPresent());
    }

    @Test
    void aNullNameIsEmpty(@TempDir Path dir) {
        assertFalse(serviceWithUploadsDir(dir).load(null).isPresent());
    }
}
