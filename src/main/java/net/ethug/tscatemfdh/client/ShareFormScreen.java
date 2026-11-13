package net.ethug.tscatemfdh.client;

import net.ethug.tscatemfdh.SharedConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Step 2 of sharing: pick world, set a passcode, enter Google credentials, share. */
public class ShareFormScreen extends Screen {
    private final Screen back;
    private EditBox nameBox, passBox, confirmBox, idBox, secretBox;
    private Button shareButton;
    private String hint = "";

    public ShareFormScreen(Screen back) {
        super(Component.literal("Share a world"));
        this.back = back;
    }

    @Override
    protected void init() {
        int cx = width / 2, w = 240, x = cx - w / 2;
        boolean tight = height < 300;
        int row = tight ? 22 : 24, y = 28;

        addRenderableWidget(Button.builder(Component.literal(ShareDraft.worldLabel.isEmpty()
                ? "Choose or create a world..." : "World: " + ShareDraft.worldLabel + "  (change)"),
                b -> minecraft.setScreen(new WorldPickerScreen(back))).bounds(x, y, w, 20).build());
        y += row + (tight ? 10 : 14);

        nameBox = box(x, y, w, "Name friends will see", ShareDraft.displayName);
        y += row + (tight ? 12 : 16);

        passBox = box(x, y, w, "Passcode (optional, any length)", ShareDraft.passcode);
        y += row;
        confirmBox = box(x, y, w, "Type the passcode again", ShareDraft.confirm);
        y += row + (tight ? 12 : 16);

        idBox = box(x, y, w, "Google OAuth client id", SharedConfig.get().oauthClientId);
        idBox.setMaxLength(256);
        y += row;
        secretBox = box(x, y, w, "Google OAuth client secret", SharedConfig.get().oauthClientSecret);
        secretBox.setMaxLength(256);
        y += row + 4;

        shareButton = addRenderableWidget(Button.builder(Component.literal(ShareDraft.hasPendingNewWorld()
                ? "Create world and share" : "Share world"), b -> submit())
                .bounds(x, y, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(cx + 2, y, w / 2 - 2, 20).build());
        updateState();
    }

    private EditBox box(int x, int y, int w, String hintText, String value) {
        EditBox b = addRenderableWidget(new EditBox(font, x, y, w, 20, Component.literal(hintText)));
        b.setMaxLength(128);
        b.setHint(Component.literal(hintText));
        b.setValue(value == null ? "" : value);
        b.setResponder(s -> updateState());
        return b;
    }

    private void save() {
        ShareDraft.displayName = nameBox.getValue();
        ShareDraft.passcode = passBox.getValue();
        ShareDraft.confirm = confirmBox.getValue();
    }

    private void updateState() {
        if (shareButton == null) return;
        boolean worldOk = ShareDraft.worldFolder != null || ShareDraft.hasPendingNewWorld();
        String p = passBox.getValue();
        if (!worldOk) hint = "Choose or create a world first.";
        else if (nameBox.getValue().isBlank()) hint = "Give the world a name.";
        else if (!p.equals(confirmBox.getValue())) hint = "Passcodes don't match.";
        else if (idBox.getValue().isBlank() || secretBox.getValue().isBlank()) hint = "Enter your Google client id and secret.";
        else hint = "";
        shareButton.active = hint.isEmpty();
    }

    private void submit() {
        save();
        Runnable done = () -> minecraft.execute(() -> {
            ShareDraft.reset();
            minecraft.setScreen(new SharedWorldsScreen(back));
        });
        if (ShareDraft.hasPendingNewWorld()) {
            SharedSession.INSTANCE.createAndShareNew(nameBox.getValue(), passBox.getValue(),
                    idBox.getValue(), secretBox.getValue(), done);
        } else {
            SharedSession.INSTANCE.shareExisting(nameBox.getValue(), ShareDraft.worldFolder, passBox.getValue(),
                    idBox.getValue(), secretBox.getValue(), done);
            minecraft.setScreen(new SharedWorldsScreen(back));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        int cx = width / 2;
        g.text(font, title, cx - font.width(title) / 2, 10, 0xFFFFFFFF);
        g.text(font, "Leave empty for no passcode. Only people joining for the first time need it.",
                cx - 120, passBox.getY() - 11, 0xFFAAAAAA, false);
        g.text(font, "Create a Desktop-app OAuth client in Google Cloud (see README).",
                cx - 120, idBox.getY() - 11, 0xFFAAAAAA, false);
        if (!hint.isEmpty()) g.text(font, hint, cx - font.width(hint) / 2, height - 14, 0xFFFF7777);
        String st = SharedSession.INSTANCE.status;
        if (hint.isEmpty() && !st.isEmpty()) g.text(font, st, cx - font.width(st) / 2, height - 14, 0xFFFFFF55);
    }

    @Override
    public void onClose() {
        save();
        minecraft.setScreen(back instanceof SharedWorldsScreen ? back : new SharedWorldsScreen(back));
    }
}
