package net.ethug.tscatemfdh.mixin;

import net.ethug.tscatemfdh.client.SharedSession;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * e4mc announces the tunnel address by adding a message straight to the chat window (no server packet), so the only
 * place to read it from is here.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;"
            + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"))
    private void tscatemfdh$chat(Component msg, MessageSignature sig, GuiMessageSource src, GuiMessageTag tag, CallbackInfo ci) {
        SharedSession.INSTANCE.onChatMessage(msg);
    }
}
