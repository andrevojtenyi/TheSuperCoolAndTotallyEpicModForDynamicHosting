package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.ethug.tscatemfdh.core.AccessList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scrollable player / blacklist list shown on the right of the edit-server screen. tab 0 = players, 1 = blacklist. */
public class PlayersPanel extends AbstractWidget {
    public static final class State {
        public AccessList.Access access;
        public AccessList.Known known;
        public boolean dirty;
    }

    private static final Map<String, State> DRAFTS = new HashMap<>();
    private static final int ROW = 18;

    public static State draft(String folderId) {
        return DRAFTS.computeIfAbsent(folderId, k -> new State());
    }

    public static void clear(String folderId) {
        DRAFTS.remove(folderId);
    }

    private record Chip(String text, Runnable action) {}

    private record Row(String uuid, String label, int color, List<Chip> chips, Runnable onClick, int dot) {
        Row(String uuid, String label, int color, List<Chip> chips) {
            this(uuid, label, color, chips, null, 0);
        }

        Row(String uuid, String label, int color, List<Chip> chips, Runnable onClick) {
            this(uuid, label, color, chips, onClick, 0);
        }
    }

    private static boolean banExpanded;

    /** Permission levels in the order the chip cycles through them. */
    private static final String[] LEVELS = {"ALL", "MODERATORS", "GAMEMASTERS", "ADMINS", "OWNERS"};
    private static final String[] LEVEL_NAMES = {"No permissions", "Moderator", "Gamemaster", "Admin", "Owner"};
    private static final String[] LEVEL_SHORT = {"None", "Mod", "GM", "Admin", "Owner"};

    private static int levelIndex(String level) {
        for (int i = 0; i < LEVELS.length; i++) if (LEVELS[i].equals(level)) return i;
        return 0;
    }

    /** What the owner has set, else what the host reports, else none. */
    private String levelOf(String uuid) {
        if (state != null && state.access != null && state.access.perms.containsKey(uuid)) return state.access.perms.get(uuid);
        if (state != null && state.known != null && state.known.perms.containsKey(uuid)) return state.known.perms.get(uuid);
        return "ALL";
    }

    private final SharedConfig.Entry entry;
    private final boolean readOnly;
    private final boolean compact;
    private State state;
    private double scroll;

    public PlayersPanel(int x, int y, int w, int h, SharedConfig.Entry entry, boolean readOnly) {
        this(x, y, w, h, entry, readOnly, w < 170);
    }

    public PlayersPanel(int x, int y, int w, int h, SharedConfig.Entry entry, boolean readOnly, boolean compact) {
        super(x, y, w, h, Component.literal("Players"));
        this.entry = entry;
        this.readOnly = readOnly;
        this.compact = compact;
    }

    /** Short button labels when the panel is narrow (W = whitelist, B = ban, UB = unban). */
    private String lbl(String t) {
        if (!compact) return t;
        return switch (t) {
            case "Allow" -> "W";
            case "Unlist" -> "-W";
            case "Ban" -> "B";
            case "Unban" -> "UB";
            default -> t;
        };
    }

    public void setState(State s) {
        this.state = s;
    }

    public void banByName(String name) {
        name = name.trim();
        if (name.isEmpty() || state == null || state.access == null || readOnly) return;
        for (AccessList.Person p : state.access.banned) {
            if (name.equalsIgnoreCase(p.name)) return;
        }
        String uuid = "";
        if (state.known != null) {
            for (AccessList.Person p : state.known.players) {
                if (name.equalsIgnoreCase(p.name)) {
                    uuid = p.uuid;
                    name = p.name;
                }
            }
        }
        state.access.banned.add(new AccessList.Person(uuid, name));
        state.dirty = true;
    }

    /** 0 = in the world (green dot), 1 = offline. */
    private int presence(String uuid) {
        return state != null && state.known != null && state.known.online.contains(uuid) ? 0 : 1;
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (state == null || state.access == null) return out;
        AccessList.Access a = state.access;
        Map<String, AccessList.Person> all = new LinkedHashMap<>();
        if (state.known != null) for (AccessList.Person p : state.known.players) all.put(p.uuid, p);
        for (AccessList.Person p : a.whitelist) all.putIfAbsent(p.uuid, p);
        List<AccessList.Person> sorted = new ArrayList<>(all.values());
        sorted.sort(java.util.Comparator
                .comparingInt((AccessList.Person p) -> presence(p.uuid))
                .thenComparing(p -> p.name == null ? "" : p.name, String.CASE_INSENSITIVE_ORDER));
        for (AccessList.Person p : sorted) {
            boolean owner = p.uuid.equals(a.ownerUuid);
            int presence = presence(p.uuid);
            boolean online = presence == 0;
            boolean wl = a.isWhitelisted(p.uuid), banned = a.isBanned(p.uuid, p.name);
            List<Chip> chips = new ArrayList<>();
            int lvl = owner ? 4 : levelIndex(levelOf(p.uuid));
            if (!owner && !readOnly) {
                chips.add(new Chip(compact ? String.valueOf(lvl) : LEVEL_SHORT[lvl], () -> { // cycle: none -> mod -> gm -> admin -> owner
                    a.perms.put(p.uuid, LEVELS[(lvl + 1) % LEVELS.length]);
                    state.dirty = true;
                }));
                chips.add(new Chip(lbl(wl ? "Unlist" : "Allow"), () -> {
                    if (wl) a.whitelist.removeIf(q -> p.uuid.equals(q.uuid));
                    else a.whitelist.add(new AccessList.Person(p.uuid, p.name));
                    state.dirty = true;
                }));
                chips.add(new Chip(lbl(banned ? "Unban" : "Ban"), () -> {
                    if (banned) a.banned.removeIf(q -> p.uuid.equals(q.uuid) || p.name.equalsIgnoreCase(q.name));
                    else a.banned.add(new AccessList.Person(p.uuid, p.name));
                    state.dirty = true;
                }));
            }
            String perm = !owner && lvl > 0 ? " (" + LEVEL_SHORT[lvl] + ")" : "";
            String label = compact ? p.name + (owner ? " *" : "") + (readOnly || lvl == 0 ? "" : "")
                    : p.name + (owner ? " (owner)" : perm)
                    + (wl && a.whitelistEnabled ? " [allowed]" : "");
            out.add(new Row(p.uuid, label, banned ? 0xFFFF8080 : online ? 0xFFFFFFFF : 0xFFAAAAAA, chips, null,
                    presence == 0 ? 0xFF3CD25A : 0));
        }
        out.add(new Row(null, (banExpanded ? "v " : "> ") + "Blacklist (" + a.banned.size() + ")", 0xFFFFC0C0,
                List.of(), () -> banExpanded = !banExpanded));
        if (banExpanded) {
            for (AccessList.Person p : new ArrayList<>(a.banned)) {
                List<Chip> chips = new ArrayList<>();
                if (!readOnly) chips.add(new Chip(lbl("Unban"), () -> {
                    a.banned.remove(p);
                    state.dirty = true;
                }));
                out.add(new Row(p.uuid, p.name, 0xFFFF8080, chips));
            }
            if (a.banned.isEmpty()) out.add(new Row(null, "Nobody is banned.", 0xFFAAAAAA, List.of()));
        }
        return out;
    }

    private int maxScroll(int rowCount) {
        return Math.max(0, rowCount * ROW + 4 - getHeight());
    }

    /** x positions of chips, laid out from the right edge. */
    private int[] chipXs(Row row, Font f) {
        int[] xs = new int[row.chips().size()];
        int right = getX() + getWidth() - 4;
        for (int i = row.chips().size() - 1; i >= 0; i--) {
            int w = f.width(row.chips().get(i).text()) + 8;
            right -= w;
            xs[i] = right;
            right -= 3;
        }
        return xs;
    }

    /** 16x16 head drawn as 2x2-unit pixels (horizontal runs merged); gray box while the skin loads. */
    private void drawHead(GuiGraphicsExtractor g, String uuid, int x, int y) {
        int[] px = HeadCache.get(uuid);
        if (px == null) {
            g.fill(x, y, x + 16, y + 16, 0xFF555555);
            return;
        }
        for (int row = 0; row < 8; row++) {
            int col = 0;
            while (col < 8) {
                int c = px[row * 8 + col], end = col + 1;
                while (end < 8 && px[row * 8 + end] == c) end++;
                g.fill(x + col * 2, y + row * 2, x + end * 2, y + row * 2 + 2, c);
                col = end;
            }
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Font f = Minecraft.getInstance().font;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x88000000);
        List<Row> rows = rows();
        if (state == null || state.access == null) {
            g.text(f, "Loading...", getX() + 6, getY() + 6, 0xFFAAAAAA, false);
            return;
        }
        scroll = Math.min(Math.max(0, scroll), maxScroll(rows.size()));
        g.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int ry = getY() + 2 + i * ROW - (int) scroll;
            if (ry + ROW < getY() || ry > getY() + getHeight()) continue;
            int[] xs = chipXs(r, f);
            if (r.onClick() != null) g.fill(getX() + 2, ry + 1, getX() + getWidth() - 2, ry + ROW - 1,
                    mx >= getX() && mx < getX() + getWidth() && my >= ry && my < ry + ROW - 1 ? 0xFF505050 : 0xFF333333);
            if (r.uuid() != null) drawHead(g, r.uuid(), getX() + 3, ry + 1);
            if (r.dot() != 0) {
                int dx = getX() + 3 + 11, dy = ry + 1 + 11;
                g.fill(dx - 1, dy, dx + 6, dy + 5, 0xFF000000);
                g.fill(dx, dy - 1, dx + 5, dy + 6, 0xFF000000);
                g.fill(dx, dy, dx + 5, dy + 5, r.dot());
            }
            int textX = getX() + 3 + (r.uuid() != null ? 16 + 4 : 3);
            int textMax = (xs.length > 0 ? xs[0] : getX() + getWidth()) - textX - 4;
            String label = f.plainSubstrByWidth(r.label(), Math.max(10, textMax));
            g.text(f, label, textX, ry + 5, r.color(), false);
            for (int c = 0; c < xs.length; c++) {
                String t = r.chips().get(c).text();
                int cw = f.width(t) + 8;
                boolean hover = mx >= xs[c] && mx < xs[c] + cw && my >= ry && my < ry + ROW - 1;
                g.fill(xs[c], ry + 2, xs[c] + cw, ry + ROW - 2, hover ? 0xFF606060 : 0xFF404040);
                g.text(f, t, xs[c] + 4, ry + 5, 0xFFFFFFFF, false);
            }
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean dbl) {
        if (!isMouseOver(ev.x(), ev.y())) return false;
        Font f = Minecraft.getInstance().font;
        List<Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            int ry = getY() + 2 + i * ROW - (int) scroll;
            if (ev.y() < ry || ev.y() >= ry + ROW - 1) continue;
            if (rows.get(i).onClick() != null) {
                rows.get(i).onClick().run();
                return true;
            }
            int[] xs = chipXs(rows.get(i), f);
            for (int c = 0; c < xs.length; c++) {
                int cw = f.width(rows.get(i).chips().get(c).text()) + 8;
                if (ev.x() >= xs[c] && ev.x() < xs[c] + cw) {
                    rows.get(i).chips().get(c).action().run();
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my)) return false;
        scroll = Math.min(Math.max(0, scroll - sy * ROW), maxScroll(rows().size()));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
