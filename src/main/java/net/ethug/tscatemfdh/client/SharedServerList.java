package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

/** Mirrors shared worlds into the vanilla multiplayer list as "tsc-shared:&lt;folderId&gt;" entries. */
public final class SharedServerList {
    public static final String PREFIX = "tsc-shared:";

    private SharedServerList() {}

    public static boolean isShared(ServerData d) {
        return d != null && d.ip != null && d.ip.startsWith(PREFIX);
    }

    public static String folderId(ServerData d) {
        return d.ip.substring(PREFIX.length());
    }

    public static boolean isPinned(ServerData d) {
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId(d));
        return e != null && e.pinned;
    }

    /** The name shown in the list: just the world's name, with a star when pinned. */
    public static String displayName(SharedConfig.Entry e) {
        return (e.pinned ? "\u2605 " : "") + e.name;
    }

    public static void setPinned(ServerList list, ServerData d, boolean pinned) {
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId(d));
        if (e == null) return;
        e.pinned = pinned;
        SharedConfig.save();
        d.name = displayName(e);
        list.save();
    }

    /** Fills in the "who is on" details (status text, tooltip list, second line of the description) from what we last heard. */
    public static void applyLive(ServerData d) {
        String id = folderId(d);
        java.util.List<String> names = SharedSession.onlineOf(id);
        String desc = SharedSession.descriptionOf(id);
        net.minecraft.network.chat.MutableComponent motd = Component.literal(desc);
        if (!names.isEmpty()) {
            if (!desc.isEmpty()) motd.append("\n");
            motd.append(Component.literal("Online: " + String.join(", ", names)).withStyle(ChatFormatting.GREEN));
        }
        d.motd = motd;
        d.status = names.isEmpty() ? Component.literal("Shared")
                : Component.literal(names.size() + " online").withStyle(ChatFormatting.GREEN);
        d.playerList = names.stream().map(n -> (Component) Component.literal(n)).toList();
    }

    /** Call before the multiplayer screen loads servers.dat. */
    public static void sync() {
        Minecraft mc = Minecraft.getInstance();
        ServerList list = new ServerList(mc);
        list.load();
        Set<String> wanted = new HashSet<>();
        for (SharedConfig.Entry e : SharedConfig.get().worlds) if (!e.hidden) wanted.add(PREFIX + e.folderId());
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            ServerData d = list.get(i);
            if (isShared(d) && !wanted.contains(d.ip)) {
                list.remove(d);
                changed = true;
            }
        }
        for (SharedConfig.Entry e : SharedConfig.get().worlds) {
            if (e.hidden) continue;
            String ip = PREFIX + e.folderId();
            ServerData existing = list.get(ip);
            if (existing == null) {
                list.add(new ServerData(displayName(e), ip, ServerData.Type.OTHER), false);
                changed = true;
            } else if (existing.name.equals("[Shared] " + e.name) || existing.name.equals(e.name) || existing.name.equals("\u2605 " + e.name)) {
                if (!existing.name.equals(displayName(e))) { // keep custom names the player chose themselves
                    existing.name = displayName(e);
                    changed = true;
                }
            }
        }
        for (SharedConfig.Entry e : SharedConfig.get().worlds) {
            if (e.hidden) continue;
            ServerData d = list.get(PREFIX + e.folderId());
            byte[] png = d == null ? null : IconStore.read(e.folderId());
            if (png != null) {
                d.setIconBytes(png);
                changed = true;
            }
        }
        if (changed) list.save();
    }
}
