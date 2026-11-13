package net.ethug.tscatemfdh.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deleting temp files must never fail an operation. On Windows a file that was just uploaded or read can still be held open
 * for a moment by the HTTP client, and deleting it throws "being used by another process": that used to turn a finished upload
 * into a failed save. Retries briefly, then leaves it for the JVM to remove on exit.
 */
public final class Temp {
    private Temp() {}

    public static void delete(Path p) {
        if (p == null) return;
        for (int i = 0; i < 8; i++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(150L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        p.toFile().deleteOnExit();
    }
}
