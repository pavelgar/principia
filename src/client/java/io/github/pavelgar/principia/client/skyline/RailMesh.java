package io.github.pavelgar.principia.client.skyline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pavelgar.principia.skyline.Rail;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.SegmentCurve;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The bars of a segment's two rails as the renderer draws them, built once from the curve the segment index built for
 * the segment. Each rail is a bar 2 px square around its line, its top 1 px above it, with horizontal and vertical
 * sides, as the anchors' cross-beams have it. The bar is drawn as straight pieces: cut where
 * the texture repeats, every block along the rail, and each block cut into as few equal pieces as keep every one
 * within a quarter pixel of the rail's line, so no facet shows.
 *
 * <p>The texture is 8 texels wide and 16 tall, one texel to a pixel: a 2-texel column for each face (top, right,
 * bottom and left, seen along the rail from its start), and one block of rail down its height.
 *
 * <p>Each part of a rail takes the light of the cell it is in: each end of a piece takes the light of the cell its
 * point of the rail's line is in, and the light blends along the piece. Light changes from frame to frame, so it is
 * passed in on each {@link #emit}, one value per cell of {@link #cells()}.
 */
final class RailMesh {
	/** How far a piece may stray from the rail's line: a quarter pixel. */
	private static final double MAX_STRAY = 1 / 64.0;
	/** Points of the rail along a piece measured against it for straying. */
	private static final int STRAY_SAMPLES = 8;
	/** Pieces to a block at most: far more than the tightest turn within the limits needs. */
	private static final int MAX_PIECES = 64;
	/** A rail this little longer than a whole number of blocks doesn't start another block. */
	private static final double ROUNDING = 1e-6;
	private static final float HALF_BAR = (float) Rail.HALF_BAR;
	/** The floats a ring, the cross-section where two pieces meet, takes: point, right and top's normal. */
	private static final int RING = 8;

	private final BlockPos origin;
	private final AABB bounds;
	private final List<BlockPos> cells;
	/** Each ring's point relative to {@link #origin}, its right as x and z, and its top's normal. */
	private final float[] rings;
	/** The index in {@link #cells} of the cell each ring's point is in. */
	private final int[] ringCells;
	/** The ring each piece starts at; it ends at the next. */
	private final int[] pieceStarts;
	/** The texture's v at each piece's start and end. */
	private final float[] pieceVs;

	/**
	 * @param origin the anchor the curve starts at, which the mesh's points are relative to
	 * @param curve the segment's curve, from {@code origin}
	 */
	RailMesh(BlockPos origin, SegmentCurve curve) {
		this.origin = origin.immutable();
		this.bounds = curve.railBounds();
		Map<BlockPos, Integer> cellIndices = new LinkedHashMap<>();
		FloatArrayList rings = new FloatArrayList();
		IntArrayList ringCells = new IntArrayList();
		IntArrayList pieceStarts = new IntArrayList();
		FloatArrayList pieceVs = new FloatArrayList();
		for (RailSide side : RailSide.values()) {
			Rail rail = curve.rail(side);
			double length = rail.length();
			int blocks = Math.max(1, (int) Math.ceil(length - ROUNDING));
			addRing(rail, 0, rings, ringCells, cellIndices);
			for (int block = 0; block < blocks; block++) {
				double from = block;
				double to = block == blocks - 1 ? length : block + 1;
				int pieces = piecesFor(rail, from, to);
				for (int i = 0; i < pieces; i++) {
					double end = cut(from, to, pieces, i + 1);
					pieceStarts.add(ringCells.size() - 1);
					pieceVs.add((float) (cut(from, to, pieces, i) - block));
					pieceVs.add((float) (end - block));
					addRing(rail, end, rings, ringCells, cellIndices);
				}
			}
		}
		this.cells = List.copyOf(cellIndices.keySet());
		this.rings = rings.toFloatArray();
		this.ringCells = ringCells.toIntArray();
		this.pieceStarts = pieceStarts.toIntArray();
		this.pieceVs = pieceVs.toFloatArray();
	}

	/** The anchor the mesh's points are relative to. */
	BlockPos origin() {
		return origin;
	}

	/** A box around both rails: the segment index's. */
	AABB bounds() {
		return bounds;
	}

	/** The cells the rails' parts are in, each once, for the light {@link #emit} takes. */
	List<BlockPos> cells() {
		return cells;
	}

	/**
	 * Emits the rails' faces as quads, for a render type with the entity vertex format.
	 *
	 * @param pose the pose of {@link #origin()}
	 * @param light the packed light of each cell of {@link #cells()}, in its order
	 */
	void emit(PoseStack.Pose pose, VertexConsumer buffer, int[] light) {
		Emitter emitter = new Emitter(pose, buffer, light);
		for (int piece = 0; piece < pieceStarts.length; piece++) {
			int a = pieceStarts[piece];
			int b = a + 1;
			float va = pieceVs[2 * piece];
			float vb = pieceVs[2 * piece + 1];
			for (Face face : Face.values()) {
				emitter.vertex(a, face, face.first, face.u0, va);
				emitter.vertex(a, face, face.second, face.u1, va);
				emitter.vertex(b, face, face.second, face.u1, vb);
				emitter.vertex(b, face, face.first, face.u0, vb);
			}
		}
	}

	/** Emits the vertices of one call to {@link #emit}, with its pose, buffer and light. */
	private final class Emitter {
		private final PoseStack.Pose pose;
		private final Matrix4f matrix;
		private final VertexConsumer buffer;
		private final int[] light;
		private final Vector3f position = new Vector3f();
		private final Vector3f normal = new Vector3f();

		Emitter(PoseStack.Pose pose, VertexConsumer buffer, int[] light) {
			this.pose = pose;
			this.matrix = pose.pose();
			this.buffer = buffer;
			this.light = light;
		}

		void vertex(int ring, Face face, Corner corner, float u, float v) {
			int at = ring * RING;
			float across = corner.across * HALF_BAR;
			float up = corner.up * HALF_BAR;
			matrix.transformPosition(rings[at] + rings[at + 3] * across, rings[at + 1] + up,
					rings[at + 2] + rings[at + 4] * across, position);
			face.normal(rings, at, pose, normal);
			buffer.addVertex(position.x, position.y, position.z, -1, u, v, OverlayTexture.NO_OVERLAY,
					light[ringCells[ring]], normal.x, normal.y, normal.z);
		}
	}

	/** A corner of the bar's cross-section, as signs of its offsets to the right and up. */
	private enum Corner {
		TOP_LEFT(-1, 1),
		TOP_RIGHT(1, 1),
		BOTTOM_RIGHT(1, -1),
		BOTTOM_LEFT(-1, -1);

		final int across;
		final int up;

		Corner(int across, int up) {
			this.across = across;
			this.up = up;
		}
	}

	/**
	 * A face of the bar: its corners at each end, in order round the bar, so that with the start's two followed by the
	 * end's in reverse it winds anticlockwise seen from outside; and its column of the texture.
	 */
	private enum Face {
		TOP(Corner.TOP_LEFT, Corner.TOP_RIGHT, 0),
		RIGHT(Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT, 1),
		BOTTOM(Corner.BOTTOM_RIGHT, Corner.BOTTOM_LEFT, 2),
		LEFT(Corner.BOTTOM_LEFT, Corner.TOP_LEFT, 3);

		final Corner first;
		final Corner second;
		final float u0;
		final float u1;

		Face(Corner first, Corner second, int column) {
			this.first = first;
			this.second = second;
			this.u0 = column / 4f;
			this.u1 = (column + 1) / 4f;
		}

		/** The face's normal at a ring, transformed by the pose. */
		void normal(float[] rings, int at, PoseStack.Pose pose, Vector3f destination) {
			switch (this) {
				case TOP -> pose.transformNormal(rings[at + 5], rings[at + 6], rings[at + 7], destination);
				case BOTTOM -> pose.transformNormal(-rings[at + 5], -rings[at + 6], -rings[at + 7], destination);
				case RIGHT -> pose.transformNormal(rings[at + 3], 0, rings[at + 4], destination);
				case LEFT -> pose.transformNormal(-rings[at + 3], 0, -rings[at + 4], destination);
			}
		}
	}

	private void addRing(Rail rail, double distance, FloatArrayList rings, IntArrayList ringCells,
			Map<BlockPos, Integer> cellIndices) {
		Vec3 point = rail.point(distance);
		Vec3 right = rail.side(distance);
		// The top faces up from the rail's direction, square to its sides: right × direction.
		Vec3 top = right.cross(rail.direction(distance)).normalize();
		rings.add((float) (point.x - origin.getX()));
		rings.add((float) (point.y - origin.getY()));
		rings.add((float) (point.z - origin.getZ()));
		rings.add((float) right.x);
		rings.add((float) right.z);
		rings.add((float) top.x);
		rings.add((float) top.y);
		rings.add((float) top.z);
		ringCells.add(cellIndices.computeIfAbsent(BlockPos.containing(point), cell -> cellIndices.size()).intValue());
	}

	/** The fewest equal pieces a stretch of rail can be cut into that each stay within {@link #MAX_STRAY} of it. */
	private static int piecesFor(Rail rail, double from, double to) {
		for (int pieces = 1; pieces < MAX_PIECES; pieces++) {
			if (straightEnough(rail, from, to, pieces)) {
				return pieces;
			}
		}
		return MAX_PIECES;
	}

	private static boolean straightEnough(Rail rail, double from, double to, int pieces) {
		for (int i = 0; i < pieces; i++) {
			double start = cut(from, to, pieces, i);
			double end = cut(from, to, pieces, i + 1);
			Vec3 a = rail.point(start);
			Vec3 b = rail.point(end);
			for (int j = 1; j < STRAY_SAMPLES; j++) {
				if (distanceToChord(rail.point(start + (end - start) * j / STRAY_SAMPLES), a, b) > MAX_STRAY) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Where a stretch of rail is cut into equal pieces: the start of the i-th, from 0, or the stretch's end, exactly, at
	 * i = pieces.
	 */
	private static double cut(double from, double to, int pieces, int i) {
		return i == pieces ? to : from + (to - from) * i / pieces;
	}

	/** The distance from a point to the straight line from a to b, between them. */
	private static double distanceToChord(Vec3 point, Vec3 a, Vec3 b) {
		Vec3 chord = b.subtract(a);
		double lengthSqr = chord.lengthSqr();
		double along = lengthSqr == 0 ? 0 : Math.clamp(point.subtract(a).dot(chord) / lengthSqr, 0, 1);
		return point.distanceTo(a.add(chord.scale(along)));
	}
}
