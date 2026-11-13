package net.ethug.tscatemfdh.core;

import java.io.IOException;
import java.nio.file.*;

/**
 * Keeps a normal singleplayer copy of the shared world in saves/ up to date. Only files that changed are copied (the old
 * version copied the whole world every autosave, which is a lot of disk activity on a big world while people are playing).
 * A hidden marker file beside the folder records that a sync is in progress, so a crash mid-way is detectable.
 */
public final class LocalBackup {
    private LocalBackup() {}

    public static void write(Path worldDir, Path savesDir, String name) throws IOException {
        Path dest = savesDir.resolve(name);
        Path marker = savesDir.resolve("." + name + ".backup-incomplete");
        Files.writeString(marker, "A backup sync was in progress. If this file is still here, the copy in \"" + name + "\" may be half updated.");
        WorldSync.syncMirror(worldDir, dest);
        Files.deleteIfExists(marker);
    }
}
