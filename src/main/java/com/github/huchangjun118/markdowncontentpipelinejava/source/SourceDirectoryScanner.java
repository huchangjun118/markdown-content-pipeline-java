package com.github.huchangjun118.markdowncontentpipelinejava.source;

import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;
import com.github.huchangjun118.markdowncontentpipelinejava.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans a source directory into immutable, content-hashed snapshots.
 *
 * <p>Document ids are content-relative paths with normalized {@code /}
 * separators. Results are inserted in id-sorted order so every downstream
 * component iterates sources in one well-defined order regardless of the
 * platform's filesystem ordering. A missing or unreadable root yields an
 * empty source set rather than failing the whole build.
 */
@Component
public class SourceDirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceDirectoryScanner.class);

    /** Scan the given root. A missing/non-directory root yields an empty set, never an exception. */
    public Map<String, SourceSnapshot> scan(Path root, String suffix) {
        Map<String, SourceSnapshot> snapshots = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            log.warn("source directory does not exist or is not a directory: {}", root);
            return snapshots;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("failed to walk source directory {}: {}", root, e.getMessage());
            return snapshots;
        }

        files.sort(Path::compareTo);
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String content = new String(bytes, StandardCharsets.UTF_8);
                String relative = root.toAbsolutePath().normalize()
                        .relativize(file.toAbsolutePath().normalize())
                        .toString()
                        .replace('\\', '/');
                String hash = Hashing.sha256(content);
                snapshots.put(relative, new SourceSnapshot(
                        relative, content, hash,
                        Files.getLastModifiedTime(file).toMillis(),
                        bytes.length));
            } catch (IOException e) {
                log.warn("failed to read source file {}: {}", file, e.getMessage());
            }
        }
        return snapshots;
    }
}
