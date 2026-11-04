package io.github.pavelgar.principia.client.skyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.SegmentCurve;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import io.github.pavelgar.principia.skyline.Track;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the rails of the Sky-Line's segments, each from one of its anchors: the first in {@code BlockPos} order that
 * the client's track holds it from, so it is drawn while either anchor is loaded.
 *
 * <p>A rail runs far from the anchor drawing it, out of the anchor's chunk section, so the renderer draws off screen,
 * as the beacon's beam does, and culls each segment itself: it is drawn while the box around its rails is in view and
 * its anchor is within the render distance and 32 blocks more, so track across the skyline doesn't pop in.
 */
public final class RailRenderer implements BlockEntityRenderer<AnchorBlockEntity, RailRenderer.State> {
	/** How far past the render distance an anchor still draws its rails, in blocks. */
	private static final int PAST_RENDER_DISTANCE = 32;
	private static final Identifier TEXTURE = Principia.id("textures/entity/sky_line_rail.png");

	/**
	 * Each segment's mesh, by the curve the track's index built for the segment, which it keeps while the segment is in
	 * it and then drops. A mesh holds nothing of its curve, so the entry goes with it.
	 */
	private final Map<SegmentCurve, RailMesh> meshes = new WeakHashMap<>();

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(AnchorBlockEntity anchor, State state, float partialTicks, Vec3 cameraPosition,
			ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(anchor, state, partialTicks, cameraPosition, breakProgress);
		Level level = anchor.getLevel();
		if (level == null) {
			return;
		}
		Frustum frustum = Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
		for (RailMesh mesh : inView(Track.of(level).index(), anchor.getBlockPos(), frustum)) {
			// Each part of a rail is lit by its own cell, not the anchor's. The state's light is the anchor's.
			List<BlockPos> cells = mesh.cells();
			int[] light = new int[cells.size()];
			for (int i = 0; i < light.length; i++) {
				light[i] = LightCoordsUtil.getLightCoords(level, cells.get(i));
			}
			state.drawn.add(new Drawn(mesh, light));
		}
	}

	/** The meshes of the segments an anchor draws whose rails are in view. */
	List<RailMesh> inView(SegmentIndex index, BlockPos anchor, Frustum frustum) {
		List<SegmentIndex.Held> drawn = index.drawnFrom(anchor);
		if (drawn.isEmpty()) {
			return List.of(); // most anchors: each segment has one that draws it
		}
		List<RailMesh> inView = new ArrayList<>(drawn.size());
		for (SegmentIndex.Held held : drawn) {
			RailMesh mesh = meshes.computeIfAbsent(held.curve(), curve -> new RailMesh(held.anchor(), curve));
			if (frustum.isVisible(mesh.bounds())) {
				inView.add(mesh);
			}
		}
		return inView;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
			CameraRenderState camera) {
		for (Drawn drawn : state.drawn) {
			BlockPos origin = drawn.mesh.origin();
			poseStack.pushPose();
			poseStack.translate(origin.getX() - state.blockPos.getX(), origin.getY() - state.blockPos.getY(),
					origin.getZ() - state.blockPos.getZ());
			submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(TEXTURE),
					(pose, buffer) -> drawn.mesh.emit(pose, buffer, drawn.light));
			poseStack.popPose();
		}
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	/** Read on each frame, as the render distance can change while playing. */
	@Override
	public int getViewDistance() {
		return viewDistance(Minecraft.getInstance().options.getEffectiveRenderDistance());
	}

	@Override
	public boolean shouldRender(AnchorBlockEntity anchor, Vec3 cameraPosition) {
		return inRange(anchor.getBlockPos(), cameraPosition, getViewDistance());
	}

	/** How far an anchor draws its rails, in blocks, at a render distance in chunks. */
	static int viewDistance(int renderDistance) {
		return renderDistance * 16 + PAST_RENDER_DISTANCE;
	}

	/** Whether an anchor is close enough to the camera to draw its rails: horizontally, as the world is drawn. */
	static boolean inRange(BlockPos anchor, Vec3 camera, int viewDistance) {
		return Vec3.atCenterOf(anchor).multiply(1, 0, 1).closerThan(camera.multiply(1, 0, 1), viewDistance);
	}

	/** The segments an anchor draws this frame, each with the light of its cells. */
	public static final class State extends BlockEntityRenderState {
		final List<Drawn> drawn = new ArrayList<>(2);

		/** How many segments the anchor draws this frame. */
		public int segments() {
			return drawn.size();
		}

		/** The packed light the anchor's rails take in a cell this frame, or -1 if no part of them is in it. */
		public int lightAt(BlockPos cell) {
			for (Drawn segment : drawn) {
				int index = segment.mesh.cells().indexOf(cell);
				if (index >= 0) {
					return segment.light[index];
				}
			}
			return -1;
		}
	}

	/** A segment's mesh with the packed light of each of its cells. */
	static final class Drawn {
		final RailMesh mesh;
		final int[] light;

		Drawn(RailMesh mesh, int[] light) {
			this.mesh = mesh;
			this.light = light;
		}
	}
}
