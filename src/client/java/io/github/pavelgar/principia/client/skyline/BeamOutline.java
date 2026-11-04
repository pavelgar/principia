package io.github.pavelgar.principia.client.skyline;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pavelgar.principia.skyline.AnchorBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Aiming at any part of an anchor's cross-beam, its middle or an end, outlines the whole beam: the box of its bar as
 * the anchor's model draws it, 34 × 2 × 2 px, turned with the anchor's heading. The parts' shapes, which a player aims
 * at, are axis-aligned boxes within their cells, a staircase across a diagonal heading; vanilla would outline those.
 * Create outlines its diagonal tracks so, a box drawn turned over shapes that are staircases. Drawn as vanilla draws
 * a block's outline, high contrast included. Holding a Sky-Line with no anchor selected, the outline says whether the
 * anchor can be selected to lay track from (white) or not (red), as Create's does.
 */
final class BeamOutline {
	/** Vanilla's high-contrast outline colours: a wide black line, and a cyan one over it. */
	private static final int HIGH_CONTRAST_BACKING = ARGB.black(255);
	private static final int HIGH_CONTRAST = 0xFF57FFE1;
	private static final float HIGH_CONTRAST_BACKING_WIDTH = 7;

	private BeamOutline() {
	}

	/** Draws the whole beam's outline if the block outlined is part of an anchor; returns whether vanilla still should. */
	static boolean beforeBlockOutline(LevelRenderContext context, BlockOutlineRenderState state) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		BlockPos anchor = level == null ? null : AnchorBlock.anchorOfPart(level, state.pos());
		if (level == null || anchor == null) {
			return true;
		}
		Integer start = minecraft.player == null ? null : TrackLaying.startOutline(minecraft.player, anchor);
		Vec3 camera = context.levelState().cameraRenderState.pos;
		Vec3 origin = Vec3.atLowerCornerOf(anchor).subtract(camera);
		List<Vec3> corners = AnchorBlock.beamCorners(level.getBlockState(anchor)).stream().map(origin::add).toList();
		float width = context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		// As LevelRenderer.submitBlockOutline does.
		if (state.highContrast()) {
			submitBox(context, corners, RenderTypes.secondaryBlockOutline(), HIGH_CONTRAST_BACKING,
					HIGH_CONTRAST_BACKING_WIDTH);
		}
		RenderType lines = state.highContrast()
				? RenderTypes.linesDepthBias()
				: context.gameRenderer().useImprovedTransparency()
						? RenderTypes.linesTranslucentNoDepthWrite()
						: RenderTypes.linesTranslucent();
		int color = start != null ? start : state.highContrast() ? HIGH_CONTRAST : ARGB.black(102);
		submitBox(context, corners, lines, color, width);
		return false;
	}

	/**
	 * Draws a box's 12 edges, its corners as {@link AnchorBlock#beamCorners} gives a beam's: between the corners whose
	 * indices differ in one bit.
	 */
	static void submitBox(LevelRenderContext context, List<Vec3> corners, RenderType type, int color, float width) {
		SubmitNodeCollector submits = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();
		submits.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
			for (int i = 0; i < corners.size(); i++) {
				for (int bit = 1; bit < corners.size(); bit <<= 1) {
					if ((i & bit) == 0) {
						RailHighlight.line(pose, buffer, corners.get(i), corners.get(i | bit), width, color);
					}
				}
			}
		});
	}
}
