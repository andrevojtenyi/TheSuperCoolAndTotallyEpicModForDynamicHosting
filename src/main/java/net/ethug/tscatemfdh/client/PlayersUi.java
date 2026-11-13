package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Builds the Players / Blacklist UI (tabs, whitelist toggle, ban box, scrollable list) into any rectangle, so it can sit
 * on the right of the edit-server screen or fill its own screen on narrow windows. Everyone can see the list; only the
 * owner can change anything.
 */
public final class PlayersUi {
    private PlayersUi() {}

    public static void build(Consumer<AbstractWidget> add, Font font, SharedConfig.Entry e, int px, int top, int pw, int bottom) {
        boolean ro = !e.owner;
        PlayersPanel.State st = PlayersPanel.draft(e.folderId());
        int listY = top + (ro ? 0 : 74);
        boolean compact = pw < 170;
        PlayersPanel panel = new PlayersPanel(px, listY, pw, Math.max(40, bottom - listY), e, ro, compact);
        EditBox banBox = new EditBox(font, px, top + 24, pw, 20, Component.literal("Ban username"));
        banBox.setHint(Component.literal("Ban a player..."));
        banBox.setMaxLength(32);
        Button banBtn = Button.builder(Component.literal("Ban Player"), b -> {
            panel.banByName(banBox.getValue());
            banBox.setValue("");
        }).bounds(px, top + 48, pw, 20).build();
        Button wlBtn = Button.builder(Component.literal(""), b -> {
            if (st.access != null && !ro) {
                st.access.whitelistEnabled = !st.access.whitelistEnabled;
                st.dirty = true;
                b.setMessage(Component.literal("Whitelist: " + (st.access.whitelistEnabled ? "ON" : "OFF")));
            }
        }).bounds(px, top, pw, 20).build();
        wlBtn.visible = false; // only the owner can whitelist, and only once the access list has loaded
        banBox.visible = !ro;
        banBtn.visible = !ro;
        Runnable refresh = () -> {
            if (st.access != null && !ro) {
                wlBtn.setMessage(Component.literal("Whitelist: " + (st.access.whitelistEnabled ? "ON" : "OFF")));
                wlBtn.visible = true;
            }
        };
        add.accept(wlBtn);
        add.accept(banBox);
        add.accept(banBtn);
        add.accept(panel);
        panel.setState(st);
        refresh.run();
        if (st.access == null) {
            SharedSession.INSTANCE.loadPlayers(e, (a, k) -> Minecraft.getInstance().execute(() -> {
                if (st.access == null) {
                    st.access = a;
                    st.known = k;
                }
                refresh.run();
            }));
        }
    }
}
