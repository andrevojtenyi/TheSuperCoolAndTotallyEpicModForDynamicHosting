package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.LayoutUtil;
import net.ethug.tscatemfdh.client.SharedServerList;
import net.ethug.tscatemfdh.client.SharedSession;
import net.ethug.tscatemfdh.client.SharedWorldsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.ethug.tscatemfdh.client.SharedUi;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    @Shadow
    protected ServerSelectionList serverSelectionList;
    @Shadow
    @Final
    private HeaderAndFooterLayout layout;
    @Shadow
    public abstract net.minecraft.client.multiplayer.ServerList getServers();
    @Shadow
    protected abstract void onSelectedChange();
    @Shadow
    private Button selectButton;

    @Unique private Button tscatemfdh$direct, tscatemfdh$add, tscatemfdh$pin, tscatemfdh$addWorld;
    @Unique private Button tscatemfdh$tabWorlds, tscatemfdh$tabServers;
    @Unique private java.util.List<Button> tscatemfdh$topRow;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    /**
     * Worlds / Servers tabs under the title, and on the Worlds tab the Direct Connection and Add Server slots turn into
     * Pin World and Add World. Everything else stays vanilla.
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void tscatemfdh$addTabs(CallbackInfo ci) {
        LinearLayout row = LayoutUtil.rowWithButton(layout, "selectServer.select");
        tscatemfdh$direct = tscatemfdh$add = null;
        if (row != null) {
            row.visitChildren(c -> {
                if (!(c instanceof Button b) || !(b.getMessage().getContents() instanceof TranslatableContents tc)) return;
                if ("selectServer.direct".equals(tc.getKey())) tscatemfdh$direct = b;
                else if ("selectServer.add".equals(tc.getKey())) tscatemfdh$add = b;
            });
        }
        tscatemfdh$pin = addRenderableWidget(Button.builder(Component.literal("Pin World"), b -> tscatemfdh$togglePin())
                .bounds(0, 0, 100, 20).build());
        tscatemfdh$addWorld = addRenderableWidget(Button.builder(Component.literal("Add World"),
                b -> minecraft.setScreen(new SharedWorldsScreen(this))).bounds(0, 0, 100, 20).build());
        if (SharedUi.ESSENTIAL) { // leave the header and tabs to Essential; our two buttons just join the top row
            if (row == null) return;
            tscatemfdh$topRow = new java.util.ArrayList<>();
            row.visitChildren(c -> {
                if (c instanceof Button b) tscatemfdh$topRow.add(b);
            });
            row.addChild(tscatemfdh$pin);
            row.addChild(tscatemfdh$addWorld);
            tscatemfdh$topRow.add(tscatemfdh$pin);
            tscatemfdh$topRow.add(tscatemfdh$addWorld);
            repositionElements();
            serverSelectionList.updateOnlineServers(getServers());
            return;
        }
        tscatemfdh$tabWorlds = addRenderableWidget(Button.builder(Component.literal("Worlds"), b -> tscatemfdh$setTab(SharedUi.WORLDS))
                .bounds(0, 0, 150, 20).build());
        tscatemfdh$tabServers = addRenderableWidget(Button.builder(Component.literal("Servers"), b -> tscatemfdh$setTab(SharedUi.SERVERS))
                .bounds(0, 0, 150, 20).build());
        layout.setHeaderHeight(61);
        repositionElements();
        serverSelectionList.updateOnlineServers(getServers());
        tscatemfdh$syncTab();
    }

    @Inject(method = "repositionElements", at = @At("RETURN"))
    private void tscatemfdh$refit(CallbackInfo ci) {
        tscatemfdh$place();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void tscatemfdh$tick(CallbackInfo ci) {
        tscatemfdh$syncPin();
    }

    @Unique
    private void tscatemfdh$place() {
        if (tscatemfdh$tabWorlds == null) {
            java.util.List<Button> row = tscatemfdh$topRow; // Essential layout: five buttons in one row, shrunk to fit
            if (row == null || row.isEmpty()) return;
            int w = Math.max(60, Math.min(100, (width - 8 - 4 * (row.size() - 1)) / row.size()));
            for (Button b : row) b.setWidth(w);
            layout.arrangeElements();
            return;
        }
        int tabW = Math.min(150, (width - 12) / 2), x = width / 2 - tabW - 2, y = layout.getHeaderHeight() - 24;
        tscatemfdh$tabWorlds.setRectangle(tabW, 20, x, y);
        tscatemfdh$tabServers.setRectangle(tabW, 20, x + tabW + 4, y);
        if (tscatemfdh$direct != null) tscatemfdh$pin.setRectangle(tscatemfdh$direct.getWidth(), 20, tscatemfdh$direct.getX(), tscatemfdh$direct.getY());
        if (tscatemfdh$add != null) tscatemfdh$addWorld.setRectangle(tscatemfdh$add.getWidth(), 20, tscatemfdh$add.getX(), tscatemfdh$add.getY());
    }

    @Unique
    private void tscatemfdh$setTab(int tab) {
        if (SharedUi.tab == tab) return;
        SharedUi.tab = tab;
        serverSelectionList.setSelected(null);
        serverSelectionList.updateOnlineServers(getServers());
        onSelectedChange();
        tscatemfdh$syncTab();
    }

    @Unique
    private void tscatemfdh$syncTab() {
        boolean worlds = SharedUi.tab == SharedUi.WORLDS;
        tscatemfdh$tabWorlds.active = !worlds;
        tscatemfdh$tabServers.active = worlds;
        if (tscatemfdh$direct != null) tscatemfdh$direct.visible = !worlds;
        if (tscatemfdh$add != null) tscatemfdh$add.visible = !worlds;
        tscatemfdh$pin.visible = worlds && tscatemfdh$direct != null;
        tscatemfdh$addWorld.visible = worlds && tscatemfdh$add != null;
        selectButton.setMessage(worlds ? Component.literal("Join World") : Component.translatable("selectServer.select"));
        tscatemfdh$place();
        tscatemfdh$syncPin();
    }

    @Unique
    private ServerData tscatemfdh$selectedWorld() {
        return serverSelectionList.getSelected() instanceof ServerSelectionList.OnlineServerEntry e
                && SharedServerList.isShared(e.getServerData()) ? e.getServerData() : null;
    }

    @Unique
    private void tscatemfdh$syncPin() {
        if (tscatemfdh$pin == null) return;
        ServerData d = tscatemfdh$selectedWorld();
        tscatemfdh$pin.active = d != null;
        tscatemfdh$pin.setMessage(Component.literal(d != null && SharedServerList.isPinned(d) ? "Unpin World" : "Pin World"));
    }

    @Unique
    private void tscatemfdh$togglePin() {
        ServerData d = tscatemfdh$selectedWorld();
        if (d == null) return;
        SharedServerList.setPinned(getServers(), d, !SharedServerList.isPinned(d));
        serverSelectionList.updateOnlineServers(getServers());
        onSelectedChange();
        tscatemfdh$syncPin();
    }

    @Inject(method = "join", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$join(ServerData data, CallbackInfo ci) {
        if (SharedServerList.isShared(data)) {
            ci.cancel();
            SharedSession.INSTANCE.joinByFolder(SharedServerList.folderId(data));
        }
    }

    @Inject(method = "deleteCallback", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$delete(boolean confirmed, CallbackInfo ci) {
        if (!confirmed) return;
        if (serverSelectionList.getSelected() instanceof ServerSelectionList.OnlineServerEntry e
                && SharedServerList.isShared(e.getServerData())) {
            ci.cancel();
            SharedWorldsScreen.confirmDelete(SharedServerList.folderId(e.getServerData()));
        }
    }
}
