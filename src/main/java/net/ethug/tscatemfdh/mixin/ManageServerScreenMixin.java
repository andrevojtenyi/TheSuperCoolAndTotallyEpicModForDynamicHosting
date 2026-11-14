package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.SharedConfig;
import net.ethug.tscatemfdh.client.SharedServerList;
import net.ethug.tscatemfdh.client.SharedSession;
import net.ethug.tscatemfdh.client.SharedWorldsScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Minecraft;
import net.ethug.tscatemfdh.client.PlayersListScreen;
import net.ethug.tscatemfdh.client.PlayersPanel;
import net.ethug.tscatemfdh.client.PlayersUi;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Edit-server screen for "[Shared]" entries: fixed address, Done always usable, passcode field. */
@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {
    @Shadow private Button addButton;
    @Shadow private EditBox ipEdit;
    @Shadow private EditBox nameEdit;
    @Shadow @Final private ServerData serverData;
    @Unique private EditBox tscatemfdh$pass;
    @Unique private EditBox tscatemfdh$desc;

    protected ManageServerScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private SharedConfig.Entry tscatemfdh$entry() {
        return SharedServerList.isShared(serverData)
                ? SharedConfig.get().findByFolder(SharedServerList.folderId(serverData)) : null;
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void tscatemfdh$init(CallbackInfo ci) {
        SharedConfig.Entry e = tscatemfdh$entry();
        if (e == null) return;
        ipEdit.setEditable(false);
        // Lay everything out from the address box down, so rows can't land on top of each other on short windows.
        int x = ipEdit.getX(), w = ipEdit.getWidth(), y = ipEdit.getY() + 24;
        Button cancel = null;
        for (GuiEventListener c : children()) {
            if (c instanceof CycleButton<?> cb) { // resource pack option is meaningless here
                cb.visible = false;
                cb.active = false;
            } else if (c instanceof Button b && b != addButton
                    && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                    && "gui.cancel".equals(tc.getKey())) {
                cancel = b;
            }
        }
        ipEdit.visible = false; // the internal id isn't useful on screen; its slot (under the name) is used instead
        ipEdit.active = false;
        y = ipEdit.getY();
        if (e.owner) {
            tscatemfdh$desc = new EditBox(font, x, y, w, 20, Component.literal("Description"));
            tscatemfdh$desc.setMaxLength(120);
            tscatemfdh$desc.setHint(Component.literal("No description"));
            tscatemfdh$desc.setValue(e.description == null ? "" : e.description);
            tscatemfdh$desc.setTooltip(Tooltip.create(Component.literal("Shown under the world's name for everyone. Leave empty for none.")));
            addRenderableWidget(tscatemfdh$desc);
            y += 24;
            tscatemfdh$pass = new EditBox(font, x, y, w - 46, 20, Component.literal("Passcode"));
            tscatemfdh$pass.setMaxLength(128);
            tscatemfdh$pass.setHint(Component.literal("Passcode (empty = none)"));
            tscatemfdh$pass.setValue(e.passcode == null ? "" : e.passcode);
            tscatemfdh$pass.setTooltip(Tooltip.create(Component.literal(
                    "Only new connections need this. People already added keep playing.")));
            addRenderableWidget(tscatemfdh$pass);
            addRenderableWidget(Button.builder(Component.literal("Copy"),
                            b -> Minecraft.getInstance().keyboardHandler.setClipboard(tscatemfdh$pass.getValue()))
                    .tooltip(Tooltip.create(Component.literal("Copy the passcode to share")))
                    .bounds(x + w - 42, y, 42, 20).build());
            y += 24;
        }
        // Everyone sees the player list; it sits beside this screen when there's room, otherwise it opens full-screen.
        int px = width / 2 + 104, pw = width - px - 4;
        boolean inline = pw >= 90; // narrower than this the list can't be read, so fall back to a button
        Button copySp = Button.builder(Component.literal("Copy to singleplayer"),
                        b -> SharedWorldsScreen.confirmCopy(e.folderId()))
                .tooltip(Tooltip.create(Component.literal("Make a separate singleplayer copy of the latest saved version")))
                .build();
        boolean longLabel = !e.owner;
        Button backup = Button.builder(Component.literal(backupLabel(e, !longLabel)), b -> {
                    SharedSession.INSTANCE.toggleBackup(e);
                    b.setMessage(Component.literal(backupLabel(e, !longLabel)));
                })
                .tooltip(Tooltip.create(Component.literal("Keep a singleplayer copy of this world up to date on every autosave"
                        + " (and when someone else hosts), saved as \"" + (e.backupName != null ? e.backupName : e.name + " (shared backup)") + "\"")))
                .build();
        int rowH = 20, pitch = 22;
        if (e.owner) { // invite + backup share a row; the long "Copy to singleplayer" label gets a full-width row
            Button invite = Button.builder(Component.literal("Copy invite code"),
                            b -> Minecraft.getInstance().keyboardHandler.setClipboard(e.invite))
                    .tooltip(Tooltip.create(Component.literal("Copy the invite code to share")))
                    .bounds(x, y, 98, rowH).build();
            backup.setRectangle(98, rowH, x + 102, y);
            addRenderableWidget(invite);
            addRenderableWidget(backup);
            y += pitch;
            copySp.setRectangle(w, rowH, x, y);
            addRenderableWidget(copySp);
            y += pitch;
            addRenderableWidget(Button.builder(Component.literal("Refresh Google sign-in"),
                            b -> SharedSession.INSTANCE.refreshGoogleSignIn())
                    .tooltip(Tooltip.create(Component.literal("Use this when saving or joining says \"Google sign-in expired\". "
                            + "It changes the invite code, so send the new one to your friends.")))
                    .bounds(x, y, w, rowH).build());
            y += pitch;
        } else {
            copySp.setRectangle(w, rowH, x, y);
            addRenderableWidget(copySp);
            y += pitch;
            backup.setRectangle(w, rowH, x, y);
            addRenderableWidget(backup);
            y += pitch;
        }
        if (!inline) {
            addRenderableWidget(Button.builder(Component.literal("Players"),
                            b -> Minecraft.getInstance().setScreen(new PlayersListScreen(Minecraft.getInstance().screen, e)))
                    .bounds(x, y, w, rowH).build());
            y += pitch;
        }
        // Done and Cancel share a row, and drop below our rows if those would reach them.
        int doneY = Math.max(addButton.getY(), y + 4);
        addButton.setRectangle(98, 20, x, doneY);
        if (cancel != null) cancel.setRectangle(98, 20, x + 102, doneY);
        if (inline) PlayersUi.build(this::addRenderableWidget, font, e, px, 36, pw, height - 12);
    }

    /** The label above the (now hidden) address box becomes the description's label. */
    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            ordinal = 1), index = 1)
    private Component tscatemfdh$label(Component original) {
        SharedConfig.Entry e = tscatemfdh$entry();
        if (e == null) return original;
        return e.owner ? Component.literal("Description") : Component.empty();
    }

    @Unique
    private static String backupLabel(SharedConfig.Entry e, boolean small) {
        return (small ? "Backup: " : "Backup on autosave: ") + (e.backup ? "ON" : "OFF");
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void tscatemfdh$discard(CallbackInfo ci) {
        SharedConfig.Entry e = tscatemfdh$entry();
        if (e != null) PlayersPanel.clear(e.folderId());
    }

    @Inject(method = "updateAddButtonStatus", at = @At("RETURN"))
    private void tscatemfdh$done(CallbackInfo ci) {
        if (SharedServerList.isShared(serverData)) addButton.active = !nameEdit.getValue().isEmpty();
    }

    @Inject(method = "onAdd", at = @At("HEAD"))
    private void tscatemfdh$save(CallbackInfo ci) {
        SharedConfig.Entry e = tscatemfdh$entry();
        if (e != null) ipEdit.setValue(serverData.ip); // vanilla writes the box back; keep the internal id intact
        if (e != null) {
            PlayersPanel.State st = PlayersPanel.draft(e.folderId());
            if (e.owner && st.dirty && st.access != null) SharedSession.INSTANCE.savePlayers(e, st.access);
            PlayersPanel.clear(e.folderId());
        }
        if (e != null && tscatemfdh$pass != null) SharedSession.INSTANCE.setPasscode(e, tscatemfdh$pass.getValue());
        if (e != null && e.owner && tscatemfdh$desc != null
                && !tscatemfdh$desc.getValue().strip().equals(e.description == null ? "" : e.description)) {
            SharedSession.INSTANCE.setDescription(e, tscatemfdh$desc.getValue());
        }
    }
}
