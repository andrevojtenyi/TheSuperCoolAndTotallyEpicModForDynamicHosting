package net.ethug.tscatemfdh.client;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * level.dat edits. The integrated server loads the "singleplayer owner" from playerdata/&lt;singleplayer_uuid&gt;.dat
 * no matter who is hosting, so every host must be written in as the owner first or they inherit someone else's
 * position/identity (and the real owner then collides with that UUID when joining).
 */
public final class WorldIdentity {
    private WorldIdentity() {}

    public static void setSingleplayerUuid(Path worldDir, UUID uuid, boolean onlyIfAbsent) throws IOException {
        Path f = worldDir.resolve("level.dat");
        if (!Files.exists(f)) return;
        CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompoundOrEmpty("Data");
        if (onlyIfAbsent && data.contains("singleplayer_uuid")) return;
        data.putIntArray("singleplayer_uuid", UUIDUtil.uuidToIntArray(uuid));
        root.put("Data", data);
        NbtIo.writeCompressed(root, f);
    }

    /**
     * Earlier versions let a host load someone else's player file, which left the wrong "UUID" inside
     * playerdata/&lt;uuid&gt;.dat. A host entity carrying another player's UUID makes that player's login kick the host
     * ("logged in from another location"), so make every file's inner UUID match its file name.
     */
    public static int repairPlayerData(Path worldDir) {
        int fixed = 0;
        Path dir = worldDir.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id());
        if (!Files.isDirectory(dir)) return 0;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".dat")) continue;
                try {
                    UUID id = UUID.fromString(n.substring(0, n.length() - 4));
                    CompoundTag tag = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                    int[] want = UUIDUtil.uuidToIntArray(id);
                    var have = tag.getIntArray("UUID");
                    if (have.isEmpty() || !java.util.Arrays.equals(have.get(), want)) {
                        tag.putIntArray("UUID", want);
                        NbtIo.writeCompressed(tag, f);
                        fixed++;
                    }
                } catch (Exception ignored) {
                    // not a player file we understand; leave it alone
                }
            }
        } catch (IOException ignored) {
        }
        return fixed;
    }

    public static void setLevelName(Path worldDir, String name) throws IOException {
        Path f = worldDir.resolve("level.dat");
        if (!Files.exists(f)) return;
        CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompoundOrEmpty("Data");
        data.putString("LevelName", name);
        root.put("Data", data);
        NbtIo.writeCompressed(root, f);
    }
}
