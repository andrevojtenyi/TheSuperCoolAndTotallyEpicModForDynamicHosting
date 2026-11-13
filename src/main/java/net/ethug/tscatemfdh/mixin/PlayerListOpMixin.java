package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedSession;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListOpMixin {
    @Shadow @Final private ServerOpList ops;

    /** Same rule as {@link MinecraftServerPermissionMixin}: being the host does not make you an operator. */
    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void tscatemfdh$onlyListedOps(NameAndId id, CallbackInfoReturnable<Boolean> cir) {
        if (SharedSession.INSTANCE.isHosting()) cir.setReturnValue(ops.get(id) != null);
    }
}
