package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedUi;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collection;

/** Shows only the entries that belong to the selected tab (Worlds or Servers), pinned worlds first. */
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin {
    @ModifyArg(method = "refreshEntries", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;replaceEntries(Ljava/util/Collection;)V"))
    private Collection<ServerSelectionList.Entry> tscatemfdh$filterTab(Collection<ServerSelectionList.Entry> entries) {
        return SharedUi.filter(entries);
    }
}
