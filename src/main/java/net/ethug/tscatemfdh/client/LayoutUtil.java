package net.ethug.tscatemfdh.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Helpers for finding vanilla buttons inside a screen's layout tree. */
public final class LayoutUtil {
    private LayoutUtil() {}

    /** The linear layout that directly holds a button whose label is the given translation key, or null. */
    public static LinearLayout rowWithButton(LayoutElement el, String translationKey) {
        if (!(el instanceof Layout layout)) return null;
        boolean[] direct = new boolean[1];
        LinearLayout[] nested = new LinearLayout[1];
        layout.visitChildren(c -> {
            if (c instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents tc
                    && translationKey.equals(tc.getKey())) {
                direct[0] = true;
            } else if (nested[0] == null) {
                nested[0] = rowWithButton(c, translationKey);
            }
        });
        return direct[0] && layout instanceof LinearLayout ll ? ll : nested[0];
    }
}
