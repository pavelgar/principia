package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The iron beam across an anchor that joins its two rails, as its model draws it: level, 2 px square, square to the
 * anchor's heading, from the outer side of one rail's bar to the other's, with its top flush with the top of the
 * anchor's cell. It reaches 9 px past the cell on both sides, into the cells of its ends. It has no collision, as the
 * rails have none: its only shape is the one a player aims at, 4 px thick, in each cell its piece.
 *
 * <p>A shape is made of axis-aligned boxes, so across a diagonal heading the beam's is a staircase of boxes, each
 * sticking out at most 1 px past the bar sideways, as a rail's boxes do. What aiming at the beam outlines is its bar
 * itself, one box turned with the heading ({@link #corners}).
 */
final class CrossBeam {
	private static final double PX = 1 / 16.0;
	/** From the cell's centre to the outer side of a rail's bar: the rail's offset and half its bar. */
	private static final double HALF_LENGTH = 1 + Rail.HALF_BAR;
	/** How far a box may stick out past the beam sideways. */
	private static final double OVERSHOOT = PX;
	/** How thick the shape aimed at is, across the beam and down from its top. */
	private static final double AIM_THICKNESS = 4 * PX;

	private CrossBeam() {
	}

	/**
	 * The offsets from the anchor of the two cells the beam reaches into, each holding one of its ends: square to the
	 * heading on either side, the corner cells for a diagonal heading.
	 */
	static List<Vec3i> ends(Heading heading) {
		return List.of(heading.turned(2).step(), heading.turned(-2).step());
	}

	/**
	 * The middle of the beam, the part inside the anchor's cell, thickened to 4 px: what a player aims at to break the
	 * anchor. A starting value, to be tuned by feel. Built anew on each call: the block caches it.
	 */
	static VoxelShape outline(Heading heading) {
		return Shapes.join(bar(heading, AIM_THICKNESS / 2, AIM_THICKNESS), Shapes.block(), BooleanOp.AND);
	}

	/**
	 * The piece of the beam, thickened as {@link #outline} is, in the cell of one of its ends: the cell past the
	 * anchor's one way along it.
	 *
	 * @param toAnchor the heading from the end's cell to the anchor's
	 */
	static VoxelShape endOutline(Heading toAnchor) {
		// The beam lies square to the anchor's heading, along the line from the end to the anchor.
		VoxelShape beam = bar(toAnchor.turned(2), AIM_THICKNESS / 2, AIM_THICKNESS);
		return Shapes.join(beam.move(toAnchor.step()), Shapes.block(), BooleanOp.AND).optimize();
	}

	/**
	 * The corners of the beam's bar as the anchor's models draw it, from the anchor's cell: 34 × 2 × 2 px, square to
	 * the heading, its top flush with the cell's top. Corner i lies toward the beam's far end if bit 0 of i is set,
	 * along the heading if bit 1 is, and at the top if bit 2 is, so corners whose indices differ in one bit share an
	 * edge.
	 */
	static List<Vec3> corners(Heading heading) {
		double half = Rail.HALF_BAR;
		List<Vec3> corners = new ArrayList<>(8);
		for (int i = 0; i < 8; i++) {
			double along = (i & 1) == 0 ? -HALF_LENGTH : HALF_LENGTH;
			double across = (i & 2) == 0 ? -half : half;
			double y = (i & 4) == 0 ? 1 - 2 * half : 1;
			// Along the beam: square to the heading, (-z, x); across it: the heading.
			corners.add(new Vec3(0.5 - heading.z() * along + heading.x() * across, y,
					0.5 + heading.x() * along + heading.z() * across));
		}
		return corners;
	}

	/**
	 * A level bar along the beam's line, its top flush with the cell's top.
	 *
	 * @param halfWidth half its width across the beam
	 * @param height how far it reaches down from the top
	 */
	private static VoxelShape bar(Heading heading, double halfWidth, double height) {
		// Along the beam: square to the heading, (-z, x).
		double alongX = -heading.z();
		double alongZ = heading.x();
		// A piece of horizontal length L at 45° to the axes sticks out L / 2 past the bar sideways, one along an axis
		// nothing: |sin 2β| L / 2.
		double askew = Math.abs(2 * alongX * alongZ);
		int pieces = askew < 1e-9 ? 1 : (int) Math.ceil(2 * HALF_LENGTH * askew / (2 * OVERSHOOT));
		// Half the bar's cross-section, as it reaches along x and z.
		double halfAcrossX = halfWidth * Math.abs(alongZ);
		double halfAcrossZ = halfWidth * Math.abs(alongX);
		VoxelShape shape = Shapes.empty();
		for (int i = 0; i < pieces; i++) {
			double from = -HALF_LENGTH + 2 * HALF_LENGTH * i / pieces;
			double to = -HALF_LENGTH + 2 * HALF_LENGTH * (i + 1) / pieces;
			double x1 = 0.5 + alongX * from;
			double x2 = 0.5 + alongX * to;
			double z1 = 0.5 + alongZ * from;
			double z2 = 0.5 + alongZ * to;
			shape = Shapes.or(shape,
					Shapes.box(Math.min(x1, x2) - halfAcrossX, 1 - height, Math.min(z1, z2) - halfAcrossZ,
							Math.max(x1, x2) + halfAcrossX, 1, Math.max(z1, z2) + halfAcrossZ));
		}
		return shape.optimize();
	}
}
