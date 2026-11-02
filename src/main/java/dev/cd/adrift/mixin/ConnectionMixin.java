package dev.cd.adrift.mixin;

import dev.cd.adrift.events.PacketEvent;
import dev.cd.adrift.events.TickEvent;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts every incoming packet (on the netty thread, before it is queued for the main thread) and a server tick
 * for each ping: Hypixel sends one per server tick, so these count ticks even while the client lags.
 */
@Mixin(value = Connection.class, priority = 500)
public abstract class ConnectionMixin {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"),
            cancellable = true
    )
    private void adrift$onPacket(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundPingPacket ping && ping.getId() != 0) TickEvent.Server.INSTANCE.post();
        if (new PacketEvent.Receive(packet).post()) ci.cancel();
    }
}
