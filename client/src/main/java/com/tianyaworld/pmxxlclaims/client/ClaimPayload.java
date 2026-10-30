package com.tianyaworld.pmxxlclaims.client;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ClaimPayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<ClaimPayload> ID = new CustomPayload.Id<>(Identifier.of("pmxxl", "claims"));

    public static final PacketCodec<PacketByteBuf, ClaimPayload> CODEC = CustomPayload.codecOf(
            (payload, buffer) -> buffer.writeBytes(payload.data()),
            buffer -> {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                return new ClaimPayload(bytes);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
