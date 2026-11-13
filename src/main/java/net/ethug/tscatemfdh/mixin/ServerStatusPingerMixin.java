package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedServerList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
public abstract class ServerStatusPingerMixin {
    /** Shared worlds have no fixed address, so don't ping them; show a label instead. */
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$ping(ServerData data, Runnable onPing, Runnable onDone, EventLoopGroupHolder group, CallbackInfo ci) {
        if (SharedServerList.isShared(data)) {
            ci.cancel();
            // SUCCESSFUL (not INITIAL) so vanilla doesn't reset and re-ping the entry every frame.
            SharedServerList.applyLive(data);
            data.version = Component.literal("");
            data.protocol = net.minecraft.SharedConstants.getCurrentVersion().protocolVersion();
            data.ping = 0L;
            data.setState(ServerData.State.SUCCESSFUL);
        }
    }
}
