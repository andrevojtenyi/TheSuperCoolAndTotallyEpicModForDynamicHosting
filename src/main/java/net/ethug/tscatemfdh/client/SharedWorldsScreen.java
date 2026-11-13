package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Add a friend's invite, share one of your worlds, or refresh Google sign-in. Laid out like the vanilla menus. */
public class SharedWorldsScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private EditBox inviteBox, passBox;

    public SharedWorldsScreen(Screen parent) {
        super(Component.literal("Add a Shared World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(4));
        body.defaultCellSetting().alignHorizontallyCenter();
        int w = 200;
        inviteBox = body.addChild(new EditBox(font, w, 20, Component.literal("Invite code")));
        inviteBox.setMaxLength(8192);
        inviteBox.setHint(Component.literal("Invite code"));
        passBox = body.addChild(new EditBox(font, w, 20, Component.literal("Passcode")));
        passBox.setHint(Component.literal("Passcode (leave empty if none)"));
        body.addChild(Button.builder(Component.literal("Add Shared World"),
                b -> SharedSession.INSTANCE.addInvite(inviteBox.getValue(), passBox.getValue())).width(w).build());
        body.addChild(SpacerElement.height(8));
        body.addChild(Button.builder(Component.literal("Share a World..."),
                b -> minecraft.setScreen(new ShareFormScreen(parent))).width(w).build());
        body.addChild(Button.builder(Component.literal("Refresh Google Sign-in"),
                        b -> SharedSession.INSTANCE.refreshGoogleSignIn())
                .tooltip(Tooltip.create(Component.literal(
                        "Use this when a world says \"Google sign-in expired\". Sign in with the Google account whose Drive holds the world.")))
                .width(w).build());
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(w).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    /** Used by the multiplayer screen's "Copy to singleplayer" button. */
    public static void confirmCopy(String folderId) {
        Minecraft mc = Minecraft.getInstance();
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId);
        if (e == null) return;
        Screen back = mc.screen;
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) SharedSession.INSTANCE.copyToSingleplayer(e);
            mc.setScreen(back);
        }, Component.literal("Copy to singleplayer?"),
                Component.literal("Makes a new singleplayer world from the latest saved version of \"" + e.name
                        + "\". It's a separate backup: changes don't sync back.")));
    }

    /** Used by this screen and by the multiplayer list's Delete button. */
    public static void confirmDelete(String folderId) {
        Minecraft mc = Minecraft.getInstance();
        SharedConfig.Entry e = SharedConfig.get().findByFolder(folderId);
        if (e == null) return;
        Component title = Component.literal(e.owner ? "Delete shared world for everyone?" : "Remove shared world?");
        Component msg = Component.literal(e.owner
                ? "\"" + e.name + "\" will be permanently deleted from Google Drive. Nobody will be able to play it again. "
                + "Your local backup in saves/ is not touched. This cannot be undone."
                : "\"" + e.name + "\" will disappear from your list. The world itself is not affected and you can re-add it with the invite.");
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) SharedSession.INSTANCE.delete(e);
            mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }, title, msg));
    }

    @Override
    public void onClose() {
        // The multiplayer list only reads servers.dat when it opens, so give back a fresh one to show new/removed worlds.
        if (parent instanceof JoinMultiplayerScreen) minecraft.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        else minecraft.setScreen(parent);
    }
}
