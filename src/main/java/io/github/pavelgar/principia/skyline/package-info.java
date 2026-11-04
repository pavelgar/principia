/**
 * The Sky-Line, an overhead rail, and the Sky-Hook a player rides it with. Track is two parallel iron rails, 3
 * blocks apart, running in smooth curves through the air between anchors, where an iron cross-beam joins them. Nothing
 * of the track has collision. A player lays track with the Sky-Line item ({@link Placement}) and rides a rail hanging
 * below it from the Sky-Hook ({@link SkyHook}, {@link Ride}).
 *
 * <h2>The model</h2>
 *
 * <ul>
 * <li><b>Geometry.</b> An anchor's tangent ({@link AnchorTangent}) is one of 8 headings, 45° apart, and a pitch within
 * ±45°. A segment's centre line ({@link SegmentCurve}) is a cubic Bézier from one anchor's point to the other's,
 * smooth in direction through every anchor, and its rails ({@link Rail}) run 1 block to either side of it, level with
 * each other. Positions along a curve or a rail are distances along it. Placing checks the shape's limits
 * ({@link SegmentLimit}).
 * <li><b>Storage.</b> An anchor is an {@link AnchorBlock}, its heading in the block state, with an
 * {@link AnchorBlockEntity} holding its pitch and at most one segment per side. Of each segment it stores only the far
 * end ({@link Segment.Partner}): its own end is its tangent and the side, so the two can't disagree. A segment exists
 * only while both of its anchors hold it: an anchor that loads is checked against its partner, and an anchor removed,
 * or turned in place, takes its segments with it.
 * <li><b>Multiblock.</b> An anchor's {@link CrossBeam} reaches 9 px into the cells either side of it, square to its
 * heading (the corner cells for a diagonal one). Each of those cells holds a {@link BeamEndBlock}, part of the anchor:
 * no item or collision, and whatever happens to a part happens to the whole anchor.
 * <li><b>Index.</b> Each level's {@link Track}, on the server and on clients, has a {@link SegmentIndex} of the segments
 * of its loaded anchors by the chunk sections they cross: each once while either anchor is loaded, its curve built
 * once. Riding, aiming and drawing ask it.
 * <li><b>Targets.</b> A client sends the {@link RailPoint} it aims at, and the server checks it in one place,
 * {@link RailTarget}, for attaching, jumping and breaking. Laying is checked by {@link Placement#plan}, which the
 * client also runs for its preview.
 * <li><b>Selection.</b> The anchor a player lays from ({@link TrackStart}) is the player's, not a stack's.
 * <li><b>Fabric-specific APIs</b> go through {@link SkyLineHooks}, and on the client through
 * {@code SkyLineClientHooks}.
 * </ul>
 *
 * <h2>Rules and reasons</h2>
 *
 * <p>The rules every piece keeps are tests named as sentences: {@code SkyLineItemGameTest} for laying,
 * {@code SkyLineBreakingGameTest} for breaking, {@code SkyLineAnchorGameTest} and {@code SkyLineSegmentGameTest} for
 * anchors and segments, {@code SkyHookGameTest} and {@code RideTest} for riding, {@code RailAimTest} for aiming, and
 * the fuzz tests for what holds for every input. A change of mind changes the test. The reason behind a rule sits with
 * the code that keeps it, in its class's javadoc, except this one, which no code keeps:
 *
 * <p><b>Whether a rider can use the track is the player's job,</b> as with minecarts, so placing doesn't check the room
 * below a rail. A block placed under the track later would break any such promise anyway, and low or tangled track has
 * its uses: a dip to the ground is a natural station, and track can be decoration.
 */
package io.github.pavelgar.principia.skyline;
