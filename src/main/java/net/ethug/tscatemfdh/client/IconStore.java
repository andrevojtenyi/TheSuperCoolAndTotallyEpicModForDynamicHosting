package net.ethug.tscatemfdh.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local cache of each shared world's icon.png (the world's own icon, same as a singleplayer world's). */
public final class IconStore {
    private IconStore() {}

    private static Path png(String folderId) {
        return FabricLoader.getInstance().getConfigDir().resolve("tscatemfdh-icons")
                .resolve(folderId.replaceAll("[^A-Za-z0-9_-]", "_") + ".png");
    }

    private static Path stampFile(String folderId) {
        return png(folderId).resolveSibling(png(folderId).getFileName() + ".stamp");
    }

    public static byte[] read(String folderId) {
        try {
            Path f = png(folderId);
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String stamp(String folderId) {
        try {
            Path f = stampFile(folderId);
            return Files.exists(f) ? Files.readString(f) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public static void write(String folderId, byte[] data, String stamp) throws IOException {
        Path f = png(folderId);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
        Files.writeString(stampFile(folderId), stamp);
    }
}
