package net.ethug.tscatemfdh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Local-only settings: known shared worlds and the owner's Google OAuth client. */
public final class SharedConfig {
    public static final class Entry {
        public String name;
        public String invite;
        public String passcode; // owner only (shown/copyable); friends keep only the key
        public boolean filed;   // owner: this world's Drive folder has been moved into the shared worlds folder
        public String key;      // base64 master key; lets existing members keep playing when the passcode changes
        public boolean owner;
        public String localBackupName; // original saves/ folder (owner only)
        public String description;     // shown under the world's name in the server list (set by the owner, synced via Drive)
        public boolean backup;         // also keep a local singleplayer copy up to date on every autosave
        public String backupName;      // saves/ folder used for that copy
        public boolean backupMigrated;
        public boolean pinned;         // shown at the top of the Worlds tab
        public boolean hidden;         // the owner banned this player: the world is not shown to them at all

        public String folderId() {
            try {
                return net.ethug.tscatemfdh.core.InviteCode.decode(invite).folderId();
            } catch (Exception e) {
                return invite;
            }
        }
    }

    public String oauthClientId = "";
    public String oauthClientSecret = "";
    /** Seconds between uploads of the running world to Drive (at least 30). Lower = less lost if the host drops. */
    public int autosaveSeconds = 600;
    public int configVersion = 2;
    public List<Entry> worlds = new ArrayList<>();
    /** Sessions whose progress is only on this computer (upload failed or the game ended mid-save). Survives restarts. */
    public List<Pending> pending = new ArrayList<>();

    public static final class Pending {
        public String folderId;
        public String worldName;
        public String dir;           // folder in saves/ holding the unsaved world
        public String baseModified;  // Drive's version time this progress was built on
        public String holderUuid;
        public long created;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SharedConfig instance;

    public Entry findByFolder(String folderId) {
        for (Entry e : worlds) if (e.folderId().equals(folderId)) return e;
        return null;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("tscatemfdh.json");
    }

    public static synchronized SharedConfig get() {
        if (instance == null) {
            try {
                instance = Files.exists(file()) ? GSON.fromJson(Files.readString(file()), SharedConfig.class) : new SharedConfig();
            } catch (Exception e) {
                instance = new SharedConfig();
            }
            if (instance.worlds == null) instance.worlds = new ArrayList<>();
            if (instance.pending == null) instance.pending = new ArrayList<>();
            for (Entry e : instance.worlds) { // owners always had a local backup; keep that as the default
                if (!e.backupMigrated) {
                    e.backupMigrated = true;
                    if (e.owner && e.localBackupName != null) {
                        e.backup = true;
                        e.backupName = e.localBackupName;
                    }
                }
            }
            if (instance.configVersion < 2) { // old default (120s) was too aggressive for big worlds
                instance.autosaveSeconds = 600;
                instance.configVersion = 2;
                save();
            }
        }
        return instance;
    }

    public static synchronized void save() {
        try {
            Files.writeString(file(), GSON.toJson(get()));
        } catch (IOException e) {
            tscatemfdh.LOGGER.error("Could not save config", e);
        }
    }
}
