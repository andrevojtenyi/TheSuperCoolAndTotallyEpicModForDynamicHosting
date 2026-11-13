package net.ethug.tscatemfdh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Minimal file store the incremental world storage runs on (Google Drive in the game, a folder in tests). */
public interface Remote {
    record File(String id, String name, Instant created, Instant modified) {}

    List<File> list() throws IOException;

    File create(String name, Path content) throws IOException;

    File update(String id, Path content) throws IOException;

    void download(String id, Path dest) throws IOException;

    void delete(String id) throws IOException;

    default byte[] read(String id) throws IOException {
        Path t = Files.createTempFile("tsc-read", ".bin");
        try {
            download(id, t);
            return Files.readAllBytes(t);
        } finally {
            Temp.delete(t);
        }
    }
}
