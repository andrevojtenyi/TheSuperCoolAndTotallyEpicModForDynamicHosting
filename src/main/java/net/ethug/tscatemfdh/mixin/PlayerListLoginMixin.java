package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    /** Refuse banned players during login, before they appear in the world, with a generic reason. */
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$refuse(SocketAddress address, NameAndId id, CallbackInfoReturnable<Component> cir) {
        if (SharedSession.INSTANCE.isHosting()) {
            Component denial = SharedSession.INSTANCE.loginDenial(id);
            if (denial != null) cir.setReturnValue(denial);
        }
    }
}
