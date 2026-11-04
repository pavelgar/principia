package io.github.pavelgar.principia.skyline;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * The distance along a curve at evenly spaced values of its parameter t in [0, 1], for finding the parameter at a
 * distance. Sampling a curve by distance instead of by its parameter spaces its samples evenly, however unevenly the
 * parameter runs along it.
 */
final class ArcLengthTable {
	/**
	 * Enough that interpolating linearly between entries is off by well under a thousandth of a block. 64 were not: on
	 * an S-bend 23 long, where the parameter runs unevenly, a point an eighth of a block along was 0.12608 along.
	 */
	private static final int INTERVALS = 128;

	// Five-point Gauss-Legendre quadrature on [-1, 1]: exact for polynomials up to degree 9.
	private static final double[] NODES = {-0.9061798459386640, -0.5384693101056831, 0, 0.5384693101056831,
			0.9061798459386640};
	private static final double[] WEIGHTS = {0.2369268850561891, 0.4786286704993665, 0.5688888888888889,
			0.4786286704993665, 0.2369268850561891};

	/** {@code distances[i]} is the distance from t = 0 to t = i / INTERVALS. */
	private final double[] distances = new double[INTERVALS + 1];

	/** @param speed the length of the curve's derivative at t */
	ArcLengthTable(DoubleUnaryOperator speed) {
		double half = 0.5 / INTERVALS;
		for (int i = 0; i < INTERVALS; i++) {
			double middle = (i + 0.5) / INTERVALS;
			double length = 0;
			for (int n = 0; n < NODES.length; n++) {
				length += WEIGHTS[n] * speed.applyAsDouble(middle + half * NODES[n]);
			}
			distances[i + 1] = distances[i] + length * half;
		}
	}

	double length() {
		return distances[INTERVALS];
	}

	/** The parameter t at a distance along the curve, clamped to the curve's ends. */
	double parameterAt(double distance) {
		if (!(distance > 0)) {
			return 0;
		}
		if (distance >= length()) {
			return 1;
		}
		int found = Arrays.binarySearch(distances, distance);
		if (found >= 0) {
			return (double) found / INTERVALS;
		}
		int i = -found - 2; // distances[i] < distance < distances[i + 1]
		double fraction = (distance - distances[i]) / (distances[i + 1] - distances[i]);
		return (i + fraction) / INTERVALS;
	}
}
