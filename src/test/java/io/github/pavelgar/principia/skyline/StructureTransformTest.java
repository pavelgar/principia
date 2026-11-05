package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A segment's saved data turns with a structure placed rotated or mirrored exactly as vanilla moves the structure's
 * cells, whatever the pivot, and decoding it refuses an offset that loading couldn't afford.
 */
class StructureTransformTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** The cell a structure placed mirrored, then rotated about a pivot, moves a cell to: vanilla's own transform. */
	private static BlockPos moved(BlockPos pos, Mirror mirror, Rotation rotation, BlockPos pivot) {
		return StructureTemplate.transform(pos, mirror, rotation, pivot);
	}

	/**
	 * For every heading, mirror and rotation, about pivots on either side: the cell one step along a heading from an
	 * anchor moves to the cell one step along the turned heading from where the anchor moves. So a block's heading
	 * still points at the cell it pointed at.
	 */
	@Test
	void aHeadingTurnsWithTheCellsItPointsAt() {
		for (BlockPos pivot : new BlockPos[]{BlockPos.ZERO, new BlockPos(3, 0, -7), new BlockPos(-12, 5, 9)}) {
			for (Mirror mirror : Mirror.values()) {
				for (Rotation rotation : Rotation.values()) {
					for (Heading heading : Heading.values()) {
						BlockPos anchor = new BlockPos(5, 64, 2);
						Heading turned = heading.transformed(mirror, rotation);
						assertEquals(moved(anchor.offset(heading.step()), mirror, rotation, pivot),
								moved(anchor, mirror, rotation, pivot).offset(turned.step()),
								heading + " " + mirror + " " + rotation + " about " + pivot);
					}
				}
			}
		}
	}

	/** Mirrors and rotations move cells without stretching: headings keep how far apart they are. */
	@Test
	void turningKeepsTheAnglesBetweenHeadings() {
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				for (Heading a : Heading.values()) {
					for (Heading b : Heading.values()) {
						assertEquals(a.stepsTo(b),
								a.transformed(mirror, rotation).stepsTo(b.transformed(mirror, rotation)));
					}
				}
			}
		}
	}

	/** A quarter turn clockwise, seen from above, takes south to west; each mirror flips one axis. */
	@Test
	void theTurnsGoTheWayVanillasDo() {
		assertEquals(Heading.WEST, Heading.SOUTH.transformed(Mirror.NONE, Rotation.CLOCKWISE_90));
		assertEquals(Heading.EAST, Heading.SOUTH.transformed(Mirror.NONE, Rotation.COUNTERCLOCKWISE_90));
		assertEquals(Heading.SOUTH_EAST, Heading.NORTH_EAST.transformed(Mirror.LEFT_RIGHT, Rotation.NONE));
		assertEquals(Heading.NORTH_WEST, Heading.NORTH_EAST.transformed(Mirror.FRONT_BACK, Rotation.NONE));
	}

	/**
	 * Two anchors anywhere, with the partner's offset and heading saved at one of them: after any mirror and rotation
	 * about any pivot, the turned partner is where vanilla moves the other anchor, heading at the cell vanilla moves the
	 * one it headed at to.
	 */
	@Test
	void aPartnerTurnsToWhereItsAnchorMoves() {
		Random random = new Random(26);
		for (int i = 0; i < 2000; i++) {
			BlockPos anchor = new BlockPos(random.nextInt(-100, 100), random.nextInt(-64, 320),
					random.nextInt(-100, 100));
			Vec3i offset = new Vec3i(random.nextInt(-32, 33), random.nextInt(-16, 17), random.nextInt(-32, 33));
			if (offset.equals(Vec3i.ZERO)) {
				continue;
			}
			Heading heading = pick(random, Heading.values());
			Segment.Partner partner = new Segment.Partner(offset,
					new AnchorTangent(heading, random.nextInt(-45, 46)), pick(random, AnchorSide.values()));
			Mirror mirror = pick(random, Mirror.values());
			Rotation rotation = pick(random, Rotation.values());
			BlockPos pivot = new BlockPos(random.nextInt(-20, 20), 0, random.nextInt(-20, 20));

			Segment.Partner turned = partner.transformed(mirror, rotation);
			BlockPos movedAnchor = moved(anchor, mirror, rotation, pivot);
			BlockPos movedPartner = moved(anchor.offset(offset), mirror, rotation, pivot);
			assertEquals(movedPartner, movedAnchor.offset(turned.offset()));
			// The partner's heading points from its cell to the cell one step on, wherever vanilla moved the two.
			Vec3i movedStep = moved(anchor.offset(offset).offset(heading.step()), mirror, rotation, pivot)
					.subtract(movedPartner);
			assertEquals(movedStep, turned.tangent().heading().step());
			assertEquals(partner.tangent().pitch(), turned.tangent().pitch());
			assertEquals(partner.side(), turned.side());
		}
	}

	private static <T> T pick(Random random, T[] among) {
		return among[random.nextInt(among.length)];
	}

	/** No turn at all, every turn four times, and each mirror twice, leave a partner as it was. */
	@Test
	void turningBackRestoresAPartner() {
		Segment.Partner partner = new Segment.Partner(new Vec3i(7, -2, 3), new AnchorTangent(Heading.NORTH_EAST, 12),
				AnchorSide.BACK);
		assertEquals(partner, partner.transformed(Mirror.NONE, Rotation.NONE));
		for (Rotation rotation : Rotation.values()) {
			Segment.Partner turned = partner;
			for (int i = 0; i < 4; i++) {
				turned = turned.transformed(Mirror.NONE, rotation);
			}
			assertEquals(partner, turned, rotation.toString());
		}
		for (Mirror mirror : Mirror.values()) {
			assertEquals(partner, partner.transformed(mirror, Rotation.NONE).transformed(mirror, Rotation.NONE));
		}
	}

	/** A saved partner {@code offset} away, heading west, on the back side. */
	private static CompoundTag savedPartner(int... offset) {
		CompoundTag tangent = new CompoundTag();
		tangent.putString("heading", "west");
		tangent.putFloat("pitch", 0);
		CompoundTag partner = new CompoundTag();
		partner.put("offset", new IntArrayTag(offset));
		partner.put("tangent", tangent);
		partner.putString("side", "back");
		return partner;
	}

	/**
	 * Decoding takes a partner up to {@link Segment.Partner#MAX_SAVED_OFFSET} along any one axis and up to
	 * {@link Segment.Partner#MAX_SAVED_SPREAD} along the other two, either way, and refuses one further, or at the
	 * anchor itself, which loading would otherwise index section by section.
	 */
	@Test
	void decodingRefusesAPartnerTooFarOrAtItsOwnAnchor() {
		int max = Segment.Partner.MAX_SAVED_OFFSET;
		int spread = Segment.Partner.MAX_SAVED_SPREAD;
		for (int[] fine : new int[][]{{max, 0, 0}, {-spread, max, spread}, {spread, -spread, -max}, {1, 0, 0},
				{0, -1, 0}}) {
			assertTrue(Segment.Partner.CODEC.parse(NbtOps.INSTANCE, savedPartner(fine)).isSuccess(),
					Arrays.toString(fine));
		}
		for (int[] refused : new int[][]{{max + 1, 0, 0}, {0, -max - 1, 0}, {0, 0, 30_000_000}, {0, 0, 0},
				{max, spread + 1, 0}, {-spread - 1, 0, spread + 1}, {-max, max, -max}}) {
			assertTrue(Segment.Partner.CODEC.parse(NbtOps.INSTANCE, savedPartner(refused)).isError(),
					Arrays.toString(refused));
		}
	}
}
