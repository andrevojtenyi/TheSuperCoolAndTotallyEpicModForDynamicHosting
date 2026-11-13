package net.ethug.tscatemfdh.client;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Which tab (Worlds or Servers) of the multiplayer screen is showing, and what each tab lists. */
public final class SharedUi {
    public static final int WORLDS = 0, SERVERS = 1;
    public static int tab = WORLDS;
    /** Essential adds its own Favorites/Friends/Discover tabs to this screen, so we leave the tabs and header alone. */
    public static final boolean ESSENTIAL = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("essential");

    private SharedUi() {}

    private static boolean isSharedEntry(ServerSelectionList.Entry e) {
        return e instanceof ServerSelectionList.OnlineServerEntry o && SharedServerList.isShared(o.getServerData());
    }

    /** Worlds with someone in them first, then pinned worlds, then the rest. Stable, so everything else keeps its order. */
    private static int rank(ServerSelectionList.Entry e) {
        if (!isSharedEntry(e)) return 3;
        var d = ((ServerSelectionList.OnlineServerEntry) e).getServerData();
        boolean busy = !SharedSession.onlineOf(SharedServerList.folderId(d)).isEmpty();
        boolean pinned = SharedServerList.isPinned(d);
        return busy ? (pinned ? 0 : 1) : pinned ? 2 : 3;
    }

    /** Worlds tab: shared worlds only. Servers tab: everything else (including LAN games). Essential: one mixed list. */
    public static List<ServerSelectionList.Entry> filter(Collection<ServerSelectionList.Entry> all) {
        List<ServerSelectionList.Entry> out = new ArrayList<>();
        for (ServerSelectionList.Entry e : all) {
            if (ESSENTIAL || isSharedEntry(e) == (tab == WORLDS)) out.add(e);
        }
        out.sort(java.util.Comparator.comparingInt(SharedUi::rank));
        return out;
    }
}
