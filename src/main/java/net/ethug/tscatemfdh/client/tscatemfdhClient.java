package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

public class tscatemfdhClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SharedSession.register();
        SharedSession.registerAccess();
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("tscatemfdh", "autosave"), (g, delta) -> {
                    if (!SharedSession.INSTANCE.isAutosaving()) return;
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    String t = "Autosaving...";
                    int x = mc.getWindow().getGuiScaledWidth() - mc.font.width(t) - 4;
                    int y = mc.getWindow().getGuiScaledHeight() - mc.font.lineHeight - 3;
                    g.text(mc.font, t, x, y, 0xFFC8C8C8, true);
                });
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            if (screen instanceof JoinMultiplayerScreen) {
                SharedServerList.sync();
                SharedSession.INSTANCE.refreshIcons();
                SharedSession.INSTANCE.organizeDrive();
                SharedSession.INSTANCE.refreshDescriptions();
                SharedSession.INSTANCE.refreshAccessState();
                SharedSession.INSTANCE.recoverLeftovers();
                SharedSession.INSTANCE.refreshOnline();
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof JoinMultiplayerScreen)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, dt) -> {
                if (SharedUi.ESSENTIAL || SharedUi.tab != SharedUi.WORLDS) return;
                for (var e : SharedConfig.get().worlds) if (!e.hidden) return;
                String msg = "No shared worlds yet. Use Add World to add or share one.";
                g.centeredText(client.font, msg, s.width / 2, s.height / 2, 0xFFA0A0A0);
            });
        });
        // Results of things done from the menus (added, copied, failed...) pop up as vanilla toasts instead of text on the screen.
        int[] refreshTicks = {0};
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { // keep "who is on" fresh while the multiplayer screen is open
            if (mc.screen instanceof JoinMultiplayerScreen && ++refreshTicks[0] % 400 == 0) SharedSession.INSTANCE.refreshOnline();
        });
        String[] lastStatus = {""};
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            String st = SharedSession.INSTANCE.status;
            if (st.equals(lastStatus[0])) return;
            lastStatus[0] = st;
            if (st.isEmpty()) return;
            var screen = mc.screen;
            if (screen instanceof JoinMultiplayerScreen || screen instanceof SharedWorldsScreen
                    || screen instanceof ManageServerScreen || screen instanceof ShareFormScreen) {
                mc.getToastManager().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("Shared Worlds"), Component.literal(st)));
            }
        });
    }
}
