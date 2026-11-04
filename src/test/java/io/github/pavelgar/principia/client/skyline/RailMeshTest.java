package io.github.pavelgar.principia.client.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.AnchorTangent;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Rail;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SegmentCurve;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

class RailMeshTest {
	private static final double PX = 1 / 16.0;
	private static final double EPSILON = 1e-4;
	private static final BlockPos WEST_ANCHOR = new BlockPos(100, 64, 200);
	/**
	 * 10 east from {@link #WEST_ANCHOR}, level. Its rails run 1 block either side of the centre line, at z = 200.5 ± 1,
	 * from x = 100.5 to 110.5, along a line 1 px below the top of the anchors' cell row: y = 64.9375.
	 */
	private static final Segment EASTWARD = new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
			end(Heading.EAST, 0, AnchorSide.BACK));

	/**
	 * Each rail's bar is 2 px square around its line, so its top is at 64.9375 + 1/16 = 65 (flush with the cell row) and
	 * its bottom at 64.875; its sides 1 px either side of the line, at z = 201.5 ± 1/16 for the south rail and 199.5 ±
	 * 1/16 for the north one. The texture repeats every block, so the 10 blocks are 10 pieces.
	 */
	@Test
	void aStraightRailAlongAnAxisIsABarTwoPixelsSquareOnePiecePerBlock() {
		List<Quad> quads = emit(WEST_ANCHOR, EASTWARD.curve(WEST_ANCHOR), cell -> LightCoordsUtil.FULL_BRIGHT);

		assertEquals(80, quads.size(), "2 rails of 10 pieces, each with 4 faces");
		for (double railZ : new double[]{201.5, 199.5}) {
			List<Quad> rail = quads.stream().filter(quad -> Math.abs(quad.centre().z - railZ) < 0.5).toList();
			assertFaces(rail, new Vec3(0, 1, 0), 65, Double.NaN, railZ);
			assertFaces(rail, new Vec3(0, -1, 0), 64.875, Double.NaN, railZ);
			assertFaces(rail, new Vec3(0, 0, 1), Double.NaN, railZ + PX, railZ);
			assertFaces(rail, new Vec3(0, 0, -1), Double.NaN, railZ - PX, railZ);
		}
	}

	/**
	 * On the tightest turn the limits allow, a quarter turn with anchors 4 apart on each axis, the right rail runs on a
	 * radius of 3 and the left on 5. A straight piece of length L across an arc of radius r strays from it by r (1 -
	 * cos(L / 2r)) at its middle. Each block is cut into as few equal pieces as stay within 1/64 ≈ 0.0156 of it:
	 * <ul>
	 * <li>on radius 3, a whole block strays 0.042, so it takes 2 pieces, which stray 0.010; the rail, 2π · 3 / 4 ≈
	 * 4.712 long, ends in 0.712 of a block, which strays 0.021 whole, so takes 2 as well: 4 · 2 + 2 = 10 pieces;
	 * <li>on radius 5, a block strays 0.025 and half a block 0.006: 2 to a block; the rail is 2π · 5 / 4 ≈ 7.854 long,
	 * its last 0.854 of a block strays 0.018, so takes 2 too: 7 · 2 + 2 = 16 pieces.
	 * </ul>
	 */
	@Test
	void onTheTightestTurnNoPieceStraysMoreThanAQuarterPixelFromTheRailsLine() {
		SegmentCurve turn = new Segment(new Vec3i(4, 0, 4), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);

		List<Quad> quads = emit(BlockPos.ZERO, turn, cell -> LightCoordsUtil.FULL_BRIGHT);

		for (RailSide side : RailSide.values()) {
			List<Vec3> line = line(turn.rail(side));
			// The tops of the rail's pieces: each level, its middle 1 px above the middle of the piece's line.
			List<Quad> tops = quads.stream().filter(quad -> quad.normal().y > 0.99)
					.filter(quad -> distance(quad.centre(), line) < 0.5).toList();
			assertEquals(side == RailSide.RIGHT ? 10 : 16, tops.size(), side + " rail's pieces");
			for (Quad top : tops) {
				Vec3 middle = top.centre().subtract(0, PX, 0);
				assertTrue(distance(middle, line) <= 1 / 64.0,
						side + " rail: the piece around " + middle + " strays " + distance(middle, line));
			}
		}
	}

	/**
	 * Climbing east at 45°, the top of the bar faces up and back, (-1, 1, 0) / √2, and its sides face north and south.
	 * Its top is 1 px above the line, straight up, as a level rail's is: the line climbs from y = 0.9375 at x = 0.5, the
	 * anchor point, so the top is at y = x + 0.5 above x.
	 */
	@Test
	void onAClimbEachFaceFacesTheWayItsSurfaceDoes() {
		SegmentCurve climb = new Segment(new Vec3i(8, 8, 0), end(Heading.EAST, 45, AnchorSide.FRONT),
				end(Heading.EAST, 45, AnchorSide.BACK)).curve(BlockPos.ZERO);

		List<Quad> quads = emit(BlockPos.ZERO, climb, cell -> LightCoordsUtil.FULL_BRIGHT);

		Vec3 upAndBack = new Vec3(-1, 1, 0).normalize();
		List<Quad> tops = quads.stream().filter(quad -> quad.normal().distanceTo(upAndBack) < EPSILON).toList();
		assertEquals(quads.size() / 4, tops.size(), "one top per piece");
		for (Quad top : tops) {
			for (Vertex vertex : top.vertices()) {
				assertEquals(vertex.position().x + 0.5, vertex.position().y, EPSILON, () -> "top at " + vertex);
			}
		}
		assertEquals(quads.size() / 4,
				quads.stream().filter(quad -> quad.normal().distanceTo(new Vec3(0, 0, 1)) < EPSILON).count());
		assertEquals(quads.size() / 4,
				quads.stream().filter(quad -> quad.normal().distanceTo(new Vec3(0, 0, -1)) < EPSILON).count());
	}

	/**
	 * Climbing north-east in a straight line, 3 up over 5 east and 5 north, each rail runs along t = (5, 3, -5) / √59,
	 * √59 ≈ 7.681 long, and its bar's sides face south-east and north-west, ±s = ±(1, 0, 1) / √2. Its top faces s × t =
	 * (-3, 10, 3) / √118, square to both, and its bottom the other way. Each corner of the bar is 1 px to a side of the
	 * rail's line, along s, and 1 px above or below it, straight up or down: a vertex d from the start of the line is α t
	 * + β s + γ (0, 1, 0), with |β| = |γ| = 1/16. Along the rail the texture runs one texel to a pixel: each piece's
	 * stretch of it is as long as the piece, and the last piece, 0.681 long, stops 0.681 down it.
	 */
	@Test
	void onAClimbingDiagonalEachFaceIsWhereTheBarsSurfaceIsAndFacesOut() {
		float pitch = (float) Math.toDegrees(Math.atan2(3, 5 * Math.sqrt(2)));
		SegmentCurve climb = new Segment(new Vec3i(5, 3, -5), end(Heading.NORTH_EAST, pitch, AnchorSide.FRONT),
				end(Heading.NORTH_EAST, pitch, AnchorSide.BACK)).curve(BlockPos.ZERO);

		List<Quad> quads = emit(BlockPos.ZERO, climb, cell -> LightCoordsUtil.FULL_BRIGHT);

		Vec3 along = new Vec3(5, 3, -5).normalize();
		Vec3 right = new Vec3(1, 0, 1).normalize();
		Vec3 top = new Vec3(-3, 10, 3).normalize();
		// Each rail starts a block to its side of the anchor point, (0.5, 0.9375, 0.5).
		for (Vec3 start : List.of(new Vec3(0.5, 0.9375, 0.5).add(right), new Vec3(0.5, 0.9375, 0.5).subtract(right))) {
			List<Quad> rail = quads.stream()
					.filter(quad -> Math.abs(quad.centre().subtract(start).dot(right)) < 0.5).toList();
			assertEquals(32, rail.size(), "8 pieces of 4 faces");
			double lastPiece = 0;
			for (Quad quad : rail) {
				assertTrue(quad.facesOut(), () -> quad + " faces in");
				Vec3 normal = quad.normal();
				for (Vertex vertex : quad.vertices()) {
					Vec3 d = vertex.position().subtract(start);
					double beta = d.dot(right);
					Vec3 rest = d.subtract(right.scale(beta));
					double alpha = alongLine(d, along, right);
					double gamma = rest.y - alpha * along.y;
					assertEquals(alpha * along.z, rest.z, EPSILON, () -> "off the bar's surface: " + vertex);
					assertEquals(PX, Math.abs(beta), EPSILON, () -> "to the side at " + vertex);
					assertEquals(PX, Math.abs(gamma), EPSILON, () -> "up or down at " + vertex);
					if (normal.distanceTo(top) < EPSILON) {
						assertEquals(PX, gamma, EPSILON, () -> "a top below the line at " + vertex);
					} else if (normal.distanceTo(top.reverse()) < EPSILON) {
						assertEquals(-PX, gamma, EPSILON, () -> "a bottom above the line at " + vertex);
					} else if (normal.distanceTo(right) < EPSILON) {
						assertEquals(PX, beta, EPSILON, () -> "a right side to the left at " + vertex);
					} else {
						assertEquals(right.reverse().x, normal.x, EPSILON, () -> "facing " + normal);
						assertEquals(right.reverse().z, normal.z, EPSILON, () -> "facing " + normal);
						assertEquals(-PX, beta, EPSILON, () -> "a left side to the right at " + vertex);
					}
				}
				double[] alphas = quad.vertices().stream()
						.mapToDouble(vertex -> alongLine(vertex.position().subtract(start), along, right)).sorted()
						.toArray();
				double[] vs = quad.vertices().stream().mapToDouble(Vertex::v).sorted().toArray();
				assertEquals(alphas[3] - alphas[0], vs[3] - vs[0], EPSILON, () -> "texture stretched on " + quad);
				if (alphas[3] > Math.sqrt(59) - 0.5) {
					lastPiece = vs[3];
				}
			}
			assertEquals(Math.sqrt(59) - 7, lastPiece, EPSILON, "the last piece's end down the texture");
		}
	}

	/**
	 * The texture is 8 texels wide and 16 tall: a 2-texel column for each face, top, right, bottom and left seen along
	 * the rail, a quarter of its width each, and one block of rail down its height. Each piece maps within one
	 * repeat.
	 */
	@Test
	void eachFaceTakesItsOwnColumnOfTheTextureRepeatingEveryBlock() {
		List<Quad> quads = emit(WEST_ANCHOR, EASTWARD.curve(WEST_ANCHOR), cell -> LightCoordsUtil.FULL_BRIGHT);

		// Along the south rail, heading east, right is south.
		List<Quad> south = quads.stream().filter(quad -> quad.centre().z > 200.5).toList();
		assertColumn(south, new Vec3(0, 1, 0), 0, 0.25);
		assertColumn(south, new Vec3(0, 0, 1), 0.25, 0.5);
		assertColumn(south, new Vec3(0, -1, 0), 0.5, 0.75);
		assertColumn(south, new Vec3(0, 0, -1), 0.75, 1);
		// Each piece, from x = 100.5 + k to 101.5 + k, runs down the texture once: v = 0 at its west end, 1 at its east.
		for (Quad quad : quads) {
			double west = quad.vertices().stream().mapToDouble(vertex -> vertex.position().x).min().orElseThrow();
			for (Vertex vertex : quad.vertices()) {
				assertEquals(vertex.position().x - west, vertex.v(), EPSILON, () -> "v at " + vertex);
			}
		}
	}

	@Test
	void theTextureRepeatsAtTheBlockBordersAlongACurvedRail() {
		SegmentCurve turn = new Segment(new Vec3i(4, 0, 4), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);

		List<Quad> quads = emit(BlockPos.ZERO, turn, cell -> LightCoordsUtil.FULL_BRIGHT);

		for (Quad quad : quads) {
			for (Vertex vertex : quad.vertices()) {
				assertTrue(vertex.v() >= 0 && vertex.v() <= 1, () -> "v outside one repeat at " + vertex);
			}
		}
	}

	/**
	 * Each part of a rail takes the light of the cell it is in. The pieces' ends lie on the rails' lines at x = 100.5,
	 * 101.5, ..., 110.5, y = 64.9375 and z = 199.5 and 201.5: in cells x = 100 to 110 of rows z = 199 and 201, at
	 * y = 64. Lit dark from x = 105 on, the vertices west of x = 105 stay bright and those east of it go dark.
	 */
	@Test
	void eachPartOfARailTakesTheLightOfTheCellItIsIn() {
		int bright = LightCoordsUtil.pack(0, 15);
		int dark = LightCoordsUtil.pack(2, 4);
		RailMesh mesh = new RailMesh(WEST_ANCHOR, EASTWARD.curve(WEST_ANCHOR));

		Set<BlockPos> expected = new HashSet<>();
		for (int x = 100; x <= 110; x++) {
			expected.add(new BlockPos(x, 64, 199));
			expected.add(new BlockPos(x, 64, 201));
		}
		assertEquals(expected, Set.copyOf(mesh.cells()));
		assertEquals(expected.size(), mesh.cells().size(), "each cell once");

		List<Quad> quads = emit(mesh, cell -> cell.getX() >= 105 ? dark : bright);
		for (Quad quad : quads) {
			for (Vertex vertex : quad.vertices()) {
				assertEquals(vertex.position().x < 105 ? bright : dark, vertex.light(), () -> "light at " + vertex);
			}
		}
	}

	/**
	 * How far along a straight line, of direction t, a point d from its start is: its α in d = α t + β s + γ (0, 1, 0), s
	 * the horizontal unit vector square to the line. The bar's corners are offset straight up and down, which isn't
	 * square to a climbing line, so this isn't d · t.
	 */
	private static double alongLine(Vec3 d, Vec3 along, Vec3 side) {
		return d.subtract(side.scale(d.dot(side))).x / along.x;
	}

	/** Asserts the faces of one rail facing one way: one per block from x = 100.5 to 110.5, at a height or z. */
	private static void assertFaces(List<Quad> rail, Vec3 facing, double y, double z, double railZ) {
		List<Quad> faces = rail.stream().filter(quad -> quad.normal().distanceTo(facing) < EPSILON)
				.sorted(Comparator.comparingDouble(quad -> quad.centre().x)).toList();
		assertEquals(10, faces.size(), "faces facing " + facing);
		for (int k = 0; k < faces.size(); k++) {
			Quad face = faces.get(k);
			assertTrue(face.facesOut(), () -> face + " faces in");
			for (Vertex vertex : face.vertices()) {
				assertTrue(Math.abs(vertex.position().x - (100.5 + k)) < EPSILON
						|| Math.abs(vertex.position().x - (101.5 + k)) < EPSILON, () -> "x of " + vertex);
				if (!Double.isNaN(y)) {
					assertEquals(y, vertex.position().y, EPSILON, () -> "y of " + vertex);
					assertTrue(Math.abs(Math.abs(vertex.position().z - railZ) - PX) < EPSILON, () -> "z of " + vertex);
				} else {
					assertEquals(z, vertex.position().z, EPSILON, () -> "z of " + vertex);
					assertTrue(Math.abs(vertex.position().y - 65) < EPSILON
							|| Math.abs(vertex.position().y - 64.875) < EPSILON, () -> "y of " + vertex);
				}
			}
		}
	}

	private static void assertColumn(List<Quad> rail, Vec3 facing, double from, double to) {
		List<Quad> faces = rail.stream().filter(quad -> quad.normal().distanceTo(facing) < EPSILON).toList();
		assertEquals(10, faces.size());
		for (Quad face : faces) {
			double[] us = face.vertices().stream().mapToDouble(Vertex::u).sorted().toArray();
			assertEquals(from, us[0], EPSILON, () -> "u of " + face);
			assertEquals(to, us[3], EPSILON, () -> "u of " + face);
		}
	}

	/** The rail's line, sampled every 1/512 block. */
	private static List<Vec3> line(Rail rail) {
		List<Vec3> line = new ArrayList<>();
		for (double d = 0; d < rail.length(); d += 1 / 512.0) {
			line.add(rail.point(d));
		}
		line.add(rail.point(rail.length()));
		return line;
	}

	/** The distance from a point to a line sampled finely, as the distance to the nearest of its samples. */
	private static double distance(Vec3 point, List<Vec3> line) {
		return line.stream().mapToDouble(point::distanceTo).min().orElseThrow();
	}

	private static Segment.End end(Heading heading, float pitch, AnchorSide side) {
		return new Segment.End(new AnchorTangent(heading, pitch), side);
	}

	private static List<Quad> emit(BlockPos origin, SegmentCurve curve, LightAt light) {
		return emit(new RailMesh(origin, curve), light);
	}

	/** The mesh's quads in the level's coordinates, each part lit by its cell. */
	private static List<Quad> emit(RailMesh mesh, LightAt light) {
		int[] lights = mesh.cells().stream().mapToInt(light::at).toArray();
		PoseStack poseStack = new PoseStack();
		BlockPos origin = mesh.origin();
		poseStack.translate(origin.getX(), origin.getY(), origin.getZ());
		Recorder recorder = new Recorder();
		mesh.emit(poseStack.last(), recorder, lights);
		assertEquals(0, recorder.vertices.size() % 4, "whole quads");
		List<Quad> quads = new ArrayList<>();
		for (int i = 0; i < recorder.vertices.size(); i += 4) {
			quads.add(new Quad(recorder.vertices.subList(i, i + 4).stream().map(Vertex.Builder::build).toList()));
		}
		return quads;
	}

	@FunctionalInterface
	private interface LightAt {
		int at(BlockPos cell);
	}

	private record Vertex(Vec3 position, float u, float v, int light, Vec3 normal) {
		static final class Builder {
			Vec3 position = Vec3.ZERO;
			float u = Float.NaN;
			float v = Float.NaN;
			int light = -1;
			int overlay = -1;
			Vec3 normal = Vec3.ZERO;
			boolean hasNormal;

			Vertex build() {
				assertTrue(!Float.isNaN(u) && light != -1 && overlay != -1 && hasNormal,
						() -> "a vertex at " + position + " lacks its uv, light, overlay or normal");
				return new Vertex(position, u, v, light, normal);
			}
		}
	}

	private record Quad(List<Vertex> vertices) {
		Vec3 centre() {
			Vec3 sum = Vec3.ZERO;
			for (Vertex vertex : vertices) {
				sum = sum.add(vertex.position());
			}
			return sum.scale(0.25);
		}

		/** The normal of its vertices, the same for each on a straight rail; their average on a curved one. */
		Vec3 normal() {
			Vec3 sum = Vec3.ZERO;
			for (Vertex vertex : vertices) {
				sum = sum.add(vertex.normal());
			}
			return sum.normalize();
		}

		/** Whether its vertices wind anticlockwise seen from the side its normal points to, its front. */
		boolean facesOut() {
			Vec3 a = vertices.get(1).position().subtract(vertices.get(0).position());
			Vec3 b = vertices.get(2).position().subtract(vertices.get(0).position());
			return a.cross(b).dot(normal()) > 0;
		}
	}

	/** Records the vertices a mesh emits, as a buffer would take them. */
	private static final class Recorder implements VertexConsumer {
		final List<Vertex.Builder> vertices = new ArrayList<>();

		private Vertex.Builder last() {
			return vertices.getLast();
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			Vertex.Builder vertex = new Vertex.Builder();
			vertex.position = new Vec3(x, y, z);
			vertices.add(vertex);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			last().u = u;
			last().v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			last().overlay = u | v << 16;
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			last().light = u | v << 16;
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			last().normal = new Vec3(x, y, z);
			last().hasNormal = true;
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
