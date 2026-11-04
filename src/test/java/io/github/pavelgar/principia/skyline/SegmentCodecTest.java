package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

class SegmentCodecTest {
	/** {@link Level}, whose dimension keys a selection names, reaches the registries as it loads. */
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}
	/** Every heading and side at least once, at both ends, with offsets and pitches of either sign. */
	private static List<Segment> segments() {
		List<Segment> segments = new ArrayList<>();
		Heading[] headings = Heading.values();
		for (int i = 0; i < headings.length; i++) {
			for (AnchorSide side : AnchorSide.values()) {
				segments.add(new Segment(new Vec3i(-7 + i, 3 - i, 12 * i), end(headings[i], -12.5f + 7 * i, side),
						end(headings[(i + 3) % headings.length], 44.75f - 11 * i,
								side == AnchorSide.FRONT ? AnchorSide.BACK : AnchorSide.FRONT)));
			}
		}
		return segments;
	}

	/** An anchor saves only its partner's end of a segment, and gets the segment back with its own end. */
	@Test
	void aSegmentRoundTripsThroughWhatAnAnchorSaves() {
		for (Segment segment : segments()) {
			Tag saved = Segment.Partner.CODEC.encodeStart(NbtOps.INSTANCE, segment.partner()).getOrThrow();

			Segment.Partner loaded = Segment.Partner.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
			assertEquals(segment, Segment.of(segment.from(), loaded));
		}
	}

	/** What an anchor saves of a segment names the partner anchor's end only: its own is its tangent and the side. */
	@Test
	void anAnchorSavesNothingOfItsOwnEnd() {
		Segment segment = segments().get(0);

		CompoundTag saved = (CompoundTag) Segment.Partner.CODEC.encodeStart(NbtOps.INSTANCE, segment.partner())
				.getOrThrow();

		assertEquals(Set.of("offset", "tangent", "side"), saved.keySet());
	}

	@Test
	void aPitchPast45DegreesIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new AnchorTangent(Heading.EAST, 45.5f));

		CompoundTag tangent = new CompoundTag();
		tangent.putString("heading", "east");
		tangent.putFloat("pitch", 50);
		assertTrue(AnchorTangent.CODEC.parse(NbtOps.INSTANCE, tangent).isError());
	}

	/** A rail point saves, as a hook does where it hangs, as its anchor, the side, the rail and the distance. */
	@Test
	void aRailPointSavesByName() {
		RailPoint point = new RailPoint(new BlockPos(3, 64, -2), AnchorSide.BACK, RailSide.RIGHT, 2.5);

		CompoundTag saved = (CompoundTag) RailPoint.CODEC.encodeStart(NbtOps.INSTANCE, point).getOrThrow();

		assertEquals("back", saved.getStringOr("side", ""));
		assertEquals("right", saved.getStringOr("rail", ""));
		assertEquals(2.5, saved.getDoubleOr("distance", 0));
		assertEquals(point, RailPoint.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
	}

	@Test
	void aRailPointRoundTripsThroughItsStreamCodec() {
		for (RailSide rail : RailSide.values()) {
			RailPoint point = new RailPoint(new BlockPos(-30000, 300, 17), AnchorSide.FRONT, rail, 31.75);
			ByteBuf buffer = Unpooled.buffer();
			RailPoint.STREAM_CODEC.encode(buffer, point);

			assertEquals(point, RailPoint.STREAM_CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes(), "bytes left over");
		}
	}

	/**
	 * A click to lay track to, as a client sends it: the hand, the block and face it hit with the point, 0.5, 0.25 and
	 * 0 into the block (exact as floats), and the heading chosen, none or each in turn.
	 */
	@Test
	void aClickToLayTrackRoundTripsThroughItsStreamCodec() {
		BlockPos pos = new BlockPos(-30000, 300, 17);
		List<Optional<Heading>> choices = new ArrayList<>(List.of(Optional.empty()));
		for (Heading heading : Heading.values()) {
			choices.add(Optional.of(heading));
		}
		for (Optional<Heading> chosen : choices) {
			InteractionHand hand = chosen.isPresent() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			BlockHitResult hit = new BlockHitResult(new Vec3(-29999.5, 300.25, 17), Direction.NORTH, pos, false);
			ByteBuf buffer = Unpooled.buffer();
			LayTrackPayload.STREAM_CODEC.encode(buffer, new LayTrackPayload(hand, hit, chosen));

			LayTrackPayload sent = LayTrackPayload.STREAM_CODEC.decode(buffer);
			assertEquals(hand, sent.hand());
			assertEquals(chosen, sent.chosen());
			assertEquals(pos, sent.hit().getBlockPos());
			assertEquals(Direction.NORTH, sent.hit().getDirection());
			assertEquals(new Vec3(-29999.5, 300.25, 17), sent.hit().getLocation());
			assertEquals(0, buffer.readableBytes(), "bytes left over");
		}
	}

	/** A selection round-trips through what a player saves and sends, with a side and without. */
	@Test
	void aSelectionRoundTripsThroughItsCodecs() {
		for (TrackStart start : List.of(new TrackStart(new BlockPos(5, -40, 9), Optional.of(AnchorSide.BACK)),
				new TrackStart(new BlockPos(-3, 70, 2), Optional.empty()))) {
			Tag saved = TrackStart.CODEC.encodeStart(NbtOps.INSTANCE, start).getOrThrow();
			assertEquals(start, TrackStart.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
			ByteBuf buffer = Unpooled.buffer();
			TrackStart.STREAM_CODEC.encode(buffer, start);
			assertEquals(start, TrackStart.STREAM_CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes(), "bytes left over");
		}
	}

	@Test
	void aSegmentRoundTripsThroughItsStreamCodec() {
		for (Segment segment : segments()) {
			ByteBuf buffer = Unpooled.buffer();
			Segment.STREAM_CODEC.encode(buffer, segment);

			assertEquals(segment, Segment.STREAM_CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes(), "bytes left over");
		}
	}
}
