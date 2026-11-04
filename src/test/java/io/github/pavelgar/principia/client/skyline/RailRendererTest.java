package io.github.pavelgar.principia.client.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.AnchorTangent;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class RailRendererTest {
	private static final BlockPos WEST_ANCHOR = new BlockPos(100, 64, 200);
	/** 30 east from {@link #WEST_ANCHOR}, level: its rails run at z = 200.5 ± 1 from x = 100.5 to 130.5. */
	private static final Segment EASTWARD = new Segment(new Vec3i(30, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
			end(Heading.EAST, 0, AnchorSide.BACK));
	private static final BlockPos EAST_ANCHOR = WEST_ANCHOR.offset(EASTWARD.offset());

	@Test
	void aSegmentWhoseRailsAreInViewIsDrawnAndOneWhoseRailsArentIsnt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		RailRenderer renderer = new RailRenderer();
		Vec3 westOfIt = new Vec3(90, 65, 200.5);

		assertEquals(1, renderer.inView(index, WEST_ANCHOR, frustum(westOfIt, new Vec3(1, 0, 0))).size());
		assertEquals(List.of(), renderer.inView(index, WEST_ANCHOR, frustum(westOfIt, new Vec3(-1, 0, 0))));
	}

	/**
	 * Looking south at the rails' east end from 8 blocks north of them, the view reaches 35° to either side: from x =
	 * 128 it takes in nothing west of x = 112 within 16 blocks south, and so none of the anchor's chunk section, x = 96
	 * to 112, z = 192 to 208. The rails, still in view, are drawn.
	 */
	@Test
	void aSegmentWhoseRailsAreInViewIsDrawnThoughItsAnchorsSectionIsnt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		Frustum lookingSouth = frustum(new Vec3(128, 65, 192), new Vec3(0, 0, 1));
		assertFalse(lookingSouth.isVisible(new AABB(96, 64, 192, 112, 80, 208)), "the anchor's section is out of view");

		assertEquals(1, new RailRenderer().inView(index, WEST_ANCHOR, lookingSouth).size());
	}

	@Test
	void onlyTheAnchorThatDrawsASegmentDrawsIt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));
		Frustum lookingEast = frustum(new Vec3(90, 65, 200.5), new Vec3(1, 0, 0));

		RailRenderer renderer = new RailRenderer();
		assertEquals(1, renderer.inView(index, WEST_ANCHOR, lookingEast).size());
		assertEquals(List.of(), renderer.inView(index, EAST_ANCHOR, lookingEast));
	}

	@Test
	void eachSegmentsMeshIsBuiltOnceAndDrawnFrameAfterFrame() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		RailRenderer renderer = new RailRenderer();
		Frustum lookingEast = frustum(new Vec3(90, 65, 200.5), new Vec3(1, 0, 0));

		RailMesh mesh = renderer.inView(index, WEST_ANCHOR, lookingEast).getFirst();

		assertSame(mesh, renderer.inView(index, WEST_ANCHOR, lookingEast).getFirst());
		assertEquals(WEST_ANCHOR, mesh.origin());
	}

	/**
	 * At a render distance of 5 chunks, 80 blocks, an anchor draws its rails out to 112 blocks, measured horizontally
	 * from the middle of its cell, (0.5, 64.5, 0.5), as the world itself is drawn.
	 */
	@Test
	void anAnchorDrawsOutToTheRenderDistanceAnd32BlocksMore() {
		int viewDistance = RailRenderer.viewDistance(5);
		BlockPos anchor = new BlockPos(0, 64, 0);

		assertEquals(112, viewDistance);
		assertTrue(RailRenderer.inRange(anchor, new Vec3(0.5 + 111.9, 64.5, 0.5), viewDistance));
		assertFalse(RailRenderer.inRange(anchor, new Vec3(0.5 + 112.1, 64.5, 0.5), viewDistance));
		// 79 blocks along each axis is 111.7 blocks away, 80 is 113.1.
		assertTrue(RailRenderer.inRange(anchor, new Vec3(0.5 - 79, 64.5, 0.5 + 79), viewDistance));
		assertFalse(RailRenderer.inRange(anchor, new Vec3(0.5 - 80, 64.5, 0.5 + 80), viewDistance));
		// Far above, but within reach horizontally.
		assertTrue(RailRenderer.inRange(anchor, new Vec3(0.5 + 100, 64.5 + 300, 0.5), viewDistance));
	}

	/** A view from a camera along a direction, 70° across each way, as a square window shows it. */
	private static Frustum frustum(Vec3 camera, Vec3 looking) {
		Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) looking.x, (float) looking.y, (float) looking.z, 0, 1,
				0);
		Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 1, 0.05f, 1000);
		Frustum frustum = new Frustum(view, projection);
		frustum.prepare(camera.x, camera.y, camera.z);
		return frustum;
	}

	private static Segment.End end(Heading heading, float pitch, AnchorSide side) {
		return new Segment.End(new AnchorTangent(heading, pitch), side);
	}
}
