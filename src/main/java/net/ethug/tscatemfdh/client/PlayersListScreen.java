package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-screen player list, used when the window is too narrow for the panel beside the edit-server screen. */
public class PlayersListScreen extends Screen {
    private final Screen back;
    private final SharedConfig.Entry entry;

    public PlayersListScreen(Screen back, SharedConfig.Entry entry) {
        super(Component.literal("Players: " + entry.name));
        this.back = back;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 16), x = (width - w) / 2;
        PlayersUi.build(this::addRenderableWidget, font, entry, x, 28, w, height - 36);
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(x, height - 28, w, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(font, title, width / 2 - font.width(title) / 2, 10, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(back);
    }
}
