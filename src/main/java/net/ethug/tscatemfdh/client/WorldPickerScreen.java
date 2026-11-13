package net.ethug.tscatemfdh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pick a singleplayer world to share. Uses vanilla's own world list (icons, names, dates, search) so it looks the
 * same as the singleplayer menu; only the buttons at the bottom differ.
 */
public class WorldPickerScreen extends Screen {
    private final Screen back;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);
    private WorldSelectionList list;
    private EditBox searchBox;
    private Button selectButton;
    private LevelSummary selected;

    public WorldPickerScreen(Screen back) {
        super(Component.translatable("selectWorld.title"));
        this.back = back;
    }

    @Override
    protected void init() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        searchBox = header.addChild(new EditBox(font, width / 2 - 100, 22, 200, 20, searchBox,
                Component.translatable("selectWorld.search")));
        searchBox.setResponder(text -> {
            if (list != null) list.updateFilter(text);
        });
        searchBox.setHint(Component.translatable("gui.selectWorld.search"));

        list = layout.addToContents(new WorldSelectionList.Builder(minecraft, this)
                .width(width)
                .height(layout.getContentHeight())
                .filter(searchBox.getValue())
                .oldList(list)
                .onEntrySelect(this::onSelect)
                .onEntryInteract(entry -> choose(entry.getLevelSummary()))
                .build());

        GridLayout grid = layout.addToFooter(new GridLayout().columnSpacing(8).rowSpacing(4));
        grid.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper rows = grid.createRowHelper(2);
        selectButton = rows.addChild(Button.builder(Component.literal("Select world"), b -> choose(selected)).build());
        rows.addChild(Button.builder(Component.literal("Create new world"), b -> createNew()).build());
        rows.addChild(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).build(), 2);

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        onSelect(null);
    }

    @Override
    protected void repositionElements() {
        if (list != null) list.updateSize(width, layout);
        layout.arrangeElements();
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(searchBox);
    }

    private void onSelect(LevelSummary summary) {
        selected = summary;
        if (selectButton != null) {
            selectButton.active = summary != null && !summary.isLocked() && !summary.isDisabled()
                    && !summary.requiresManualConversion() && !summary.getLevelId().startsWith("tscatemfdh-");
        }
    }

    private void choose(LevelSummary s) {
        if (s == null || s.isLocked() || s.isDisabled() || s.requiresManualConversion()
                || s.getLevelId().startsWith("tscatemfdh-")) return;
        ShareDraft.clearPending();
        ShareDraft.worldFolder = s.getLevelId();
        ShareDraft.worldLabel = s.getLevelName();
        if (ShareDraft.displayName.isBlank()) ShareDraft.displayName = s.getLevelName();
        minecraft.setScreen(new ShareFormScreen(back));
    }

    @Override
    public void removed() {
        if (list != null) list.children().forEach(WorldSelectionList.Entry::close);
    }

    /**
     * Opens vanilla world creation, but when "Create New World" is pressed we pause (the world isn't created yet),
     * stash everything, and go back to the share form. The world is created when the form is submitted.
     */
    private void createNew() {
        Minecraft mc = Minecraft.getInstance();
        CreateWorldScreen.openFresh(mc, () -> mc.setScreen(new WorldPickerScreen(back)), (screen, registries, data, rules, packDir) -> {
            try {
                ShareDraft.clearPending();
                ShareDraft.pendingScreen = screen;
                ShareDraft.pendingRegistries = registries;
                ShareDraft.pendingData = data;
                ShareDraft.pendingRules = rules;
                ShareDraft.pendingPackDir = packDir;
                if (packDir != null && Files.exists(packDir)) { // vanilla deletes this right after we return
                    Path backup = Files.createTempDirectory("tsc-packs");
                    copy(packDir, backup);
                    ShareDraft.pendingPackBackup = backup;
                }
                ShareDraft.worldFolder = null;
                ShareDraft.worldLabel = data.data().getLevelName() + " (new world)";
                ShareDraft.displayName = data.data().getLevelName();
            } catch (Exception e) {
                SharedSession.INSTANCE.status = "Could not prepare new world: " + e.getMessage();
                return false;
            }
            mc.execute(() -> mc.setScreen(new ShareFormScreen(back)));
            return true;
        });
    }

    private static void copy(Path src, Path dst) throws java.io.IOException {
        try (var w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(new ShareFormScreen(back));
    }
}
