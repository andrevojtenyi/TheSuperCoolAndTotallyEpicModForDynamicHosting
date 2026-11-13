package net.ethug.tscatemfdh.core;

import com.google.gson.Gson;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner-managed whitelist/bans (access.json) and host-reported player list (players.json). Two files so the owner
 * and a host never overwrite each other's edits. Both are encrypted with the world's master key.
 */
public final class AccessList {
    public static final String ACCESS = "access.json", PLAYERS = "players.json";
    private static final Gson GSON = new Gson();

    public static final class Person {
        public String uuid;
        public String name;
        public long lastSeen;

        public Person() {}

        public Person(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    public static final class Access {
        public String ownerUuid = "";
        public boolean whitelistEnabled;
        public List<Person> whitelist = new ArrayList<>();
        public List<Person> banned = new ArrayList<>();
        /** Permission levels the owner has set (uuid -> {@code PermissionLevel} name). Applied by the host when they change. */
        public java.util.Map<String, String> perms = new java.util.HashMap<>();

        /** Bans match by UUID, or by name (case-insensitive) for entries the owner typed in. */
        public boolean isBanned(String uuid, String name) {
            return banned.stream().anyMatch(p ->
                    (p.uuid != null && !p.uuid.isEmpty() && p.uuid.equals(uuid))
                            || (name != null && p.name != null && p.name.equalsIgnoreCase(name)));
        }

        public boolean isWhitelisted(String uuid) {
            return whitelist.stream().anyMatch(p -> uuid.equals(p.uuid));
        }

        public boolean allows(String uuid, String name, String hostUuid) {
            if (uuid.equals(ownerUuid) || uuid.equals(hostUuid)) return true;
            if (isBanned(uuid, name)) return false;
            return !whitelistEnabled || isWhitelisted(uuid);
        }
    }

    public static final class Known {
        public List<Person> players = new ArrayList<>();
        public List<String> online = new ArrayList<>();
        /** Operators as the host currently has them (uuid -> {@code PermissionLevel} name). */
        public java.util.Map<String, String> perms = new java.util.HashMap<>();

        public void touch(String uuid, String name) {
            for (Person p : players) {
                if (uuid.equals(p.uuid)) {
                    p.name = name;
                    p.lastSeen = System.currentTimeMillis();
                    return;
                }
            }
            Person p = new Person(uuid, name);
            p.lastSeen = System.currentTimeMillis();
            players.add(p);
        }
    }

    private AccessList() {}

    public static Access loadAccess(WorldSync s, SecretKey k) throws Exception {
        String j = s.readJson(ACCESS, k);
        Access a = j == null ? new Access() : GSON.fromJson(j, Access.class);
        return a == null ? new Access() : a;
    }

    public static void saveAccess(WorldSync s, SecretKey k, Access a) throws Exception {
        s.writeJson(ACCESS, k, GSON.toJson(a));
    }

    public static Known loadKnown(WorldSync s, SecretKey k) throws Exception {
        String j = s.readJson(PLAYERS, k);
        Known n = j == null ? new Known() : GSON.fromJson(j, Known.class);
        return n == null ? new Known() : n;
    }

    public static void saveKnown(WorldSync s, SecretKey k, Known n) throws Exception {
        s.writeJson(PLAYERS, k, GSON.toJson(n));
    }
}
