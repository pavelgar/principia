package io.github.pavelgar.principia.skyline;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;

/**
 * The anchor a player has selected to lay track from. It is the player's, not a stack's: a rail is a rail, so any
 * Sky-Line they hold lays from it. It is saved with the player and sent to their client, which shows what a click would
 * lay from it. It goes when the player dies or changes dimension, once its anchor is gone or full, and once they are
 * more than 64 blocks from it ({@link Placement#keepCurrent}).
 *
 * @param pos the anchor's position
 * @param side the side a segment leaves the anchor by; empty while both are free, for laying to pick one
 */
public record TrackStart(BlockPos pos, Optional<AnchorSide> side) {
	public static final Codec<TrackStart> CODEC = RecordCodecBuilder.create(instance -> instance
			.group(BlockPos.CODEC.fieldOf("pos").forGetter(TrackStart::pos),
					AnchorSide.CODEC.optionalFieldOf("side").forGetter(TrackStart::side))
			.apply(instance, TrackStart::new));
	public static final StreamCodec<ByteBuf, TrackStart> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
			TrackStart::pos, ByteBufCodecs.optional(AnchorSide.STREAM_CODEC), TrackStart::side, TrackStart::new);

	/** The anchor a player has selected, if any; on a client, for its own player. */
	public static @Nullable TrackStart of(Player player) {
		return SkyLineHooks.selection(player);
	}

	/** Selects an anchor for a player, or clears their selection. Server only: the client follows. */
	static void set(Player player, @Nullable TrackStart start) {
		SkyLineHooks.select(player, start);
	}
}
