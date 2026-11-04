package io.github.pavelgar.principia.client.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the face marks around a click on a block face are: the clicks they stand for. The cell each places in is
 * {@code BlockPlaceContext}'s, as {@code Placement.plan} finds it, which {@code SkyLineItemGameTest} checks for these
 * faces.
 */
class FaceMarksTest {
	/**
	 * A click on the top of (0, 64, 0) is marked at the 5 × 5 blocks around it at y = 64, each clicked on its top, at
	 * its middle.
	 */
	@Test
	void aTopFaceIsMarkedAtTheTopsOfTheBlocksAroundIt() {
		List<BlockHitResult> clicks = FaceMarks.clicks(click(new BlockPos(0, 64, 0), Direction.UP));
		Set<BlockPos> clicked = new HashSet<>();
		for (BlockHitResult click : clicks) {
			BlockPos pos = click.getBlockPos();
			assertEquals(Direction.UP, click.getDirection());
			assertEquals(new Vec3(pos.getX() + 0.5, 65, pos.getZ() + 0.5), click.getLocation());
			clicked.add(pos);
		}
		assertEquals(25, clicks.size());
		assertEquals(blocks(-2, 2, 64, 64, -2, 2), clicked);
	}

	/**
	 * A click on the north face of (10, 70, -5) is marked at the 5 × 5 blocks around it in the wall, x from 8 to 12 and y
	 * from 68 to 72 at z = -5, each clicked on its north face, at its middle.
	 */
	@Test
	void aNorthFaceIsMarkedAtTheNorthFacesOfTheBlocksAroundItInTheWall() {
		List<BlockHitResult> clicks = FaceMarks.clicks(click(new BlockPos(10, 70, -5), Direction.NORTH));
		Set<BlockPos> clicked = new HashSet<>();
		for (BlockHitResult click : clicks) {
			BlockPos pos = click.getBlockPos();
			assertEquals(Direction.NORTH, click.getDirection());
			assertEquals(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, -5), click.getLocation());
			clicked.add(pos);
		}
		assertEquals(25, clicks.size());
		assertEquals(blocks(8, 12, 68, 72, -5, -5), clicked);
	}

	/**
	 * A click on the bottom of (3, 0, 3) is marked at the 5 × 5 blocks around it at y = 0, each clicked on its bottom, at
	 * its middle.
	 */
	@Test
	void aBottomFaceIsMarkedAtTheBottomsOfTheBlocksAroundIt() {
		List<BlockHitResult> clicks = FaceMarks.clicks(click(new BlockPos(3, 0, 3), Direction.DOWN));
		Set<BlockPos> clicked = new HashSet<>();
		for (BlockHitResult click : clicks) {
			BlockPos pos = click.getBlockPos();
			assertEquals(Direction.DOWN, click.getDirection());
			assertEquals(new Vec3(pos.getX() + 0.5, 0, pos.getZ() + 0.5), click.getLocation());
			clicked.add(pos);
		}
		assertEquals(25, clicks.size());
		assertEquals(blocks(1, 5, 0, 0, 1, 5), clicked);
	}

	/** A click on a face of a block, off its middle. */
	private static BlockHitResult click(BlockPos pos, Direction face) {
		return new BlockHitResult(Vec3.atCenterOf(pos).add(0.2, 0.1, -0.3), face, pos, false);
	}

	private static Set<BlockPos> blocks(int x1, int x2, int y1, int y2, int z1, int z2) {
		Set<BlockPos> blocks = new HashSet<>();
		BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2).forEach(pos -> blocks.add(pos.immutable()));
		assertEquals(25, blocks.size());
		return blocks;
	}
}
