package net.ethug.tscatemfdh.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The "Connecting to server..." style screen for a shared world: shows what the join is doing right now and a Cancel
 * button. Once the join has ended without getting into the world it stays up with the reason and a Back button.
 */
public class SharedConnectScreen extends Screen {
    private final Screen back;
    private final String worldName;
    private Button action, refresh;

    public SharedConnectScreen(Screen back, String worldName) {
        super(Component.literal("Connecting"));
        this.back = back;
        this.worldName = worldName;
    }

    @Override
    protected void init() {
        action = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> leave())
                .bounds(width / 2 - 100, height / 4 + 120 + 12, 200, 20).build());
        refresh = addRenderableWidget(Button.builder(Component.literal("Refresh Google Sign-in"),
                        b -> SharedSession.INSTANCE.refreshGoogleSignIn())
                .bounds(width / 2 - 100, height / 4 + 120 + 12 + 24, 200, 20).build());
        refreshButton();
    }

    private void refreshButton() {
        if (action == null) return;
        if (refresh != null) refresh.visible = SharedSession.EXPIRED.equals(SharedSession.INSTANCE.status); // only when that's the problem
        SharedSession s = SharedSession.INSTANCE;
        action.setMessage(s.isActive() ? CommonComponents.GUI_CANCEL : CommonComponents.GUI_BACK);
        action.active = !(s.isActive() && s.isHosting()); // the world is already opening; too late to back out here
    }

    private void leave() {
        SharedSession s = SharedSession.INSTANCE;
        if (s.isActive() && !s.isHosting()) s.cancel();
        s.status = "";
        s.connectScreenClosed(this);
        minecraft.setScreen(back);
    }

    @Override
    public void tick() {
        super.tick();
        refreshButton();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        SharedSession s = SharedSession.INSTANCE;
        boolean running = s.isActive();
        int cx = width / 2, y = height / 2 - 50;
        g.centeredText(font, Component.literal(worldName), cx, y - 16, 0xFFFFFFFF);
        String st = s.status.isEmpty() ? "Connecting..." : s.status;
        int shown = 0;
        for (var line : font.split(Component.literal(st), Math.min(width - 40, 320))) {
            g.centeredText(font, line, cx, y + shown * (font.lineHeight + 2), running ? 0xFFFFFFFF : 0xFFFF7777);
            shown++;
        }
        if (running) {
            g.centeredText(font, LoadingDotsText.get(System.currentTimeMillis()), cx, y + shown * (font.lineHeight + 2), 0xFF808080);
            int hy = y + (shown + 3) * (font.lineHeight + 2);
            for (var line : font.split(Component.literal(
                    "The first player in starts the world for everyone, which can take a minute."), Math.min(width - 40, 320))) {
                g.centeredText(font, line, cx, hy, 0xFF808080);
                hy += font.lineHeight + 2;
            }
        }
    }

    @Override
    public void onClose() {
        leave();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
