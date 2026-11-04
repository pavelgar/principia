package io.github.pavelgar.principia.client.skyline;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pavelgar.principia.skyline.Rail;
import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.RailSide;
import org.joml.Vector3f;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * The rails a player aims at ({@link RailTargeting}), drawn as vanilla draws its block outline. The rail they would
 * mine, within their block reach, whatever they hold: the outline of its segment, which mining breaks whole, thin dark
 * lines along the four long edges of both rails' 2 × 2 px bars, closed at each end. Where a right-click with the
 * Sky-Hook would attach them or land a rider's jump, as far as it reaches: a mark, a light collar round the bar, 8 × 8
 * px across and 4 px along it. An outline means the rail can be mined, as for a block, so the hook's grip has a
 * look of its own.
 */
final class RailHighlight {
	/** Vanilla's block outline colour: black, 40 % opaque. */
	private static final int COLOR = ARGB.black(102);
	/** The mark's colour: white, 80 % opaque, to tell it from the outline. */
	private static final int MARK_COLOR = ARGB.white(204);
	/** Half the bar's width and height, 1 px. */
	private static final double HALF_BAR = 1 / 16.0;
	/** Half the mark's width and height, 4 px. */
	private static final double HALF_MARK = 4 / 16.0;
	/** Half the mark's length along the rail, 2 px. */
	private static final double HALF_MARK_LENGTH = 2 / 16.0;
	/** The longest straight piece of an edge: on the tightest turn it strays from the rail by well under a pixel. */
	private static final double STEP = 0.25;
	/** A box's corners round the rail, as steps to its right and up from the rail's line, in order round it. */
	private static final int[][] CORNERS = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

	private RailHighlight() {
	}

	static void submit(LevelRenderContext context) {
		RailAim.Hit mined = RailTargeting.mineTarget();
		RailAim.Hit hooked = RailTargeting.hookTarget();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		float width = context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		if (mined != null) {
			for (RailSide side : RailSide.values()) {
				Rail rail = mined.segment().curve().rail(side);
				int pieces = Math.max(1, (int) Math.ceil(rail.length() / STEP));
				double[] distances = new double[pieces + 1];
				for (int i = 0; i <= pieces; i++) {
					distances[i] = rail.length() * i / pieces;
				}
				submit(context, corners(rail, distances, HALF_BAR, camera), width, COLOR);
			}
		}
		if (hooked != null) {
			Rail rail = hooked.segment().curve().rail(hooked.rail());
			double[] distances = {Math.max(0, hooked.distance() - HALF_MARK_LENGTH),
					Math.min(rail.length(), hooked.distance() + HALF_MARK_LENGTH)};
			submit(context, corners(rail, distances, HALF_MARK, camera), width, MARK_COLOR);
		}
	}

	private static void submit(LevelRenderContext context, Vec3[][] corners, float width, int color) {
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.linesTranslucent(),
				(pose, buffer) -> box(pose, buffer, corners, width, color));
	}

	/** The lines along a box's four long edges, and round its ends. */
	private static void box(PoseStack.Pose pose, VertexConsumer buffer, Vec3[][] corners, float width, int color) {
		int last = corners.length - 1;
		for (int corner = 0; corner < CORNERS.length; corner++) {
			for (int i = 0; i < last; i++) {
				line(pose, buffer, corners[i][corner], corners[i + 1][corner], width, color);
			}
			int next = (corner + 1) % CORNERS.length;
			line(pose, buffer, corners[0][corner], corners[0][next], width, color);
			line(pose, buffer, corners[last][corner], corners[last][next], width, color);
		}
	}

	/** The corners of a box round a rail, so far out from its line, at points along it, relative to the camera. */
	private static Vec3[][] corners(Rail rail, double[] distances, double half, Vec3 camera) {
		Vec3[][] corners = new Vec3[distances.length][];
		for (int i = 0; i < distances.length; i++) {
			Vec3 point = rail.point(distances[i]).subtract(camera);
			Vec3 direction = rail.direction(distances[i]);
			Vec3 right = new Vec3(-direction.z, 0, direction.x).normalize();
			corners[i] = new Vec3[CORNERS.length];
			for (int corner = 0; corner < CORNERS.length; corner++) {
				corners[i][corner] = point.add(right.scale(CORNERS[corner][0] * half))
						.add(0, CORNERS[corner][1] * half, 0);
			}
		}
		return corners;
	}

	static void line(PoseStack.Pose pose, VertexConsumer buffer, Vec3 from, Vec3 to, float width, int color) {
		Vector3f normal = to.subtract(from).toVector3f().normalize();
		buffer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z).setColor(color).setNormal(pose, normal)
				.setLineWidth(width);
		buffer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z).setColor(color).setNormal(pose, normal)
				.setLineWidth(width);
	}
}
