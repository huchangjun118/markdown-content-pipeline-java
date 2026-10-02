package com.github.huchangjun118.markdowncontentpipelinejava.watch;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceFile;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceChange;
import com.github.huchangjun118.markdowncontentpipelinejava.build.ProductHasher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Scans the source directory and detects changes against a previous snapshot.
 *
 * <p>Scans are deterministic: files are always processed in sorted relative-path
 * order, so build results never depend on filesystem iteration order.
 */
@Component
public class ChangeScanner {

    private final PipelineProperties properties;

    public ChangeScanner(PipelineProperties properties) {
        this.properties = properties;
    }

    public List<SourceFile> scan() throws IOException {
        Path root = Path.of(properties.getSourceDir());
        if (!Files.exists(root)) {
            return List.of();
        }
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String content = Files.readString(p);
                files.add(new SourceFile(rel, sha256(content), content));
            }
        }
        return List.copyOf(files);
    }

    public Map<String, String> snapshot(List<SourceFile> files) {
        Map<String, String> snapshot = new TreeMap<>();
        for (SourceFile f : files) {
            snapshot.put(f.relativePath(), f.sha256());
        }
        return snapshot;
    }

    /**
     * Detects added, modified and deleted files between two snapshots.
     * Rename is reported as delete + add, which correctly invalidates the
     * old path's product and builds a new one.
     */
    public List<SourceChange> detectChanges(Map<String, String> previous, Map<String, String> current) {
        List<SourceChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String oldHash = previous.get(e.getKey());
            if (oldHash == null) {
                changes.add(new SourceChange(SourceChange.Type.ADDED, e.getKey(),
                        DocId.fromPath(e.getKey()), e.getValue()));
            } else if (!oldHash.equals(e.getValue())) {
                changes.add(new SourceChange(SourceChange.Type.MODIFIED, e.getKey(),
                        DocId.fromPath(e.getKey()), e.getValue()));
            }
        }
        for (String path : previous.keySet()) {
            if (!current.containsKey(path)) {
                changes.add(new SourceChange(SourceChange.Type.DELETED, path,
                        DocId.fromPath(path), null));
            }
        }
        return List.copyOf(changes);
    }

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
