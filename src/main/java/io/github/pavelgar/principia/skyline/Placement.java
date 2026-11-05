package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import io.github.pavelgar.principia.content.Protection;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Laying track with the Sky-Line item, much as Create lays its track: a click on a block face places a lone anchor and
 * selects it, a click on an anchor selects it, and with one selected, a click on a block lays a segment from it to
 * there, the selection moving on to the segment's far end.
 *
 * <p>Laying is checked by {@link #plan}, which the client runs to show what a click would lay and the server runs to
 * lay it: a second copy would let the two disagree.
 */
public final class Placement {
	/** Create's red for track that can't be placed: the reasons' colour, and the client's for the ghost. */
	public static final int CANT_COLOR = 0xEA5C2B;
	/**
	 * How far a player may go from the anchor they selected before it is left behind and cleared: twice what a segment
	 * from it can reach, its longest and block reach beyond, so the selection doesn't go while they look for a spot.
	 */
	static final double SELECTION_REACH = 64;
	/** Points along a segment its sharpest bend is looked for at, to pick the smoothest of headings. */
	private static final int BEND_SAMPLES = 64;

	private Placement() {
	}

	/** What a click on a block would lay from the selected anchor, or why it can't lay anything. */
	public sealed interface Plan {
		/**
		 * Nothing, and nothing to say: the block is part of the selected anchor, or would put a new anchor in its cell;
		 * or the selected anchor has gone, or filled up, this tick.
		 */
		record Nothing() implements Plan {
		}

		/** No segment at all, for a reason the player is told: the target is an anchor with no free side. */
		record Refused(Component why) implements Plan {
		}

		/**
		 * A segment from the selected anchor, which the click lays if nothing stands in its way.
		 *
		 * @param start the selected anchor's position
		 * @param segment the segment, seen from the selected anchor
		 * @param toNewAnchor whether a new anchor goes at the far end
		 * @param cost the rails laying it takes
		 * @param why why the click can't lay it, if it can't
		 */
		record Laying(BlockPos start, Segment segment, boolean toNewAnchor, int cost,
				@Nullable Component why) implements Plan {
			/** The far end's position. */
			public BlockPos end() {
				return start.offset(segment.offset());
			}
		}
	}

	/**
	 * What a click on a block would lay from the selected anchor, by a player holding the Sky-Line in a hand. A click on
	 * any part of another anchor means that anchor's free side; on any other block, a new anchor in the cell a block
	 * would be placed in, heading the way the track arrives there ({@link #arcHeading}), the segment on its back. If
	 * that new anchor has no room or its segment breaks a limit, another heading that works takes its place ({@link
	 * #pickHeading}). Where the player faces doesn't count, so each spot has one answer. Nothing is laid, with nothing said, toward the selected anchor itself or its own cell, nor from an
	 * anchor gone or filled up this tick. The reasons a segment can't be laid come in this order, the first with no
	 * segment to show: the target is an anchor with no free side; the player may not build at either end, or a new
	 * anchor has no room; the shape breaks a limit; the player has too few rails.
	 *
	 * <p>On a client, the level and the player are the client's: it can't see spawn protection, which the server then
	 * refuses with its own reason.
	 */
	public static Plan plan(Player player, InteractionHand hand, TrackStart start, BlockHitResult hit) {
		return plan(player, hand, start, hit, null);
	}

	/**
	 * What a click on a block would lay, as {@link #plan(Player, InteractionHand, TrackStart, BlockHitResult)} does, but
	 * with a new anchor at the far end heading a way the player chose, if one is given ({@link #headingsThatWork}).
	 */
	public static Plan plan(Player player, InteractionHand hand, TrackStart start, BlockHitResult hit,
			@Nullable Heading chosen) {
		Level level = player.level();
		// Gone, or full: the selection follows its anchor as the tick ends.
		if (!(level.getBlockEntity(start.pos()) instanceof AnchorBlockEntity from)) {
			return new Plan.Nothing();
		}
		BlockPos anchorPos = AnchorBlock.anchorOfPart(level, hit.getBlockPos());
		AnchorBlockEntity to = anchorPos == null
				? null
				: level.getBlockEntity(anchorPos) instanceof AnchorBlockEntity anchor ? anchor : null;
		BlockPos end;
		Optional<AnchorSide> endSide = Optional.empty();
		if (to != null) {
			end = to.getBlockPos();
			if (end.equals(start.pos())) {
				return new Plan.Nothing();
			}
			List<AnchorSide> free = freeSides(to);
			if (free.isEmpty()) {
				return refused(SkyLine.NO_FREE_SIDE);
			}
			// With both sides free, rule A picks one.
			endSide = free.size() == 1 ? Optional.of(free.getFirst()) : Optional.empty();
		} else {
			end = new BlockPlaceContext(level, player, hand, player.getItemInHand(hand), hit).getClickedPos();
			if (end.equals(start.pos())) {
				return new Plan.Nothing();
			}
		}
		// A plain Vec3i: a BlockPos offset would be stored and compared as one.
		Vec3i offset = new Vec3i(end.getX() - start.pos().getX(), end.getY() - start.pos().getY(),
				end.getZ() - start.pos().getZ());
		Segment.End leaving = end(from.tangent(), !from.segments().isEmpty(), start.side(), offset);
		// With both sides free at selecting, the side the segment leaves by may have been taken this tick.
		if (from.segment(leaving.side()) != null) {
			return new Plan.Nothing();
		}
		if (to != null) {
			return laying(level, player, start.pos(), new Segment(offset, leaving,
					end(to.tangent(), !to.segments().isEmpty(), endSide, offset.multiply(-1))), false);
		}
		if (chosen != null) {
			return toNewAnchor(level, player, start.pos(), offset, leaving, chosen);
		}
		Map<Heading, Plan.Laying> plans = new EnumMap<>(Heading.class);
		Function<Heading, Plan.Laying> toward = heading -> plans.computeIfAbsent(heading,
				turned -> toNewAnchor(level, player, start.pos(), offset, leaving, turned));
		Heading picked = pickHeading(arcHeading(leaving.leavingHeading(), offset),
				heading -> fits(level, player, toward.apply(heading)),
				heading -> maxBend(toward.apply(heading).segment()));
		return toward.apply(picked);
	}

	/**
	 * The heading a new anchor takes unless the player chooses: the arc's ({@link #arcHeading}) if it works, and
	 * otherwise, of the other headings that work, the one whose segment bends least sharply; with none working, the
	 * arc's, which then says why not. A spot where any heading works is thus one a plain click lays to.
	 *
	 * @param works whether a heading works: the anchor has room, and its segment keeps the limits
	 * @param sharpestBend how sharply a heading's segment bends at its sharpest
	 */
	static Heading pickHeading(Heading arc, Predicate<Heading> works, ToDoubleFunction<Heading> sharpestBend) {
		if (works.test(arc)) {
			return arc;
		}
		Heading best = null;
		for (Heading heading : Heading.values()) {
			if (heading != arc && works.test(heading)
					&& (best == null || sharpestBend.applyAsDouble(heading) < sharpestBend.applyAsDouble(best))) {
				best = heading;
			}
		}
		return best != null ? best : arc;
	}

	/**
	 * The headings a new anchor at the block aimed at could face, the player choosing, and work: it has room, and its
	 * segment keeps the limits. They come clockwise from the one it faces unless the player chooses, which comes first
	 * if it works. Empty if the click would lay to no new anchor.
	 */
	public static List<Heading> headingsThatWork(Player player, InteractionHand hand, TrackStart start,
			BlockHitResult hit) {
		if (!(plan(player, hand, start, hit) instanceof Plan.Laying unchosen) || !unchosen.toNewAnchor()) {
			return List.of();
		}
		Heading first = unchosen.segment().to().tangent().heading();
		List<Heading> work = new ArrayList<>();
		for (int turn = 0; turn < Heading.values().length; turn++) {
			Heading heading = first.turned(turn);
			if (plan(player, hand, start, hit, heading) instanceof Plan.Laying laying
					&& fits(player.level(), player, laying)) {
				work.add(heading);
			}
		}
		return work;
	}

	/** A segment to a new anchor heading a way, arriving on its back, as a plan. */
	private static Plan.Laying toNewAnchor(Level level, Player player, BlockPos start, Vec3i offset,
			Segment.End leaving, Heading heading) {
		Vec3i back = offset.multiply(-1);
		Segment segment = new Segment(offset, leaving,
				new Segment.End(new AnchorTangent(heading, pitchToward(heading, back)), AnchorSide.BACK));
		return laying(level, player, start, segment, true);
	}

	private static Plan.Laying laying(Level level, Player player, BlockPos start, Segment segment,
			boolean toNewAnchor) {
		int cost = RailDrops.cost(segment, toNewAnchor);
		BlockPos end = start.offset(segment.offset());
		return new Plan.Laying(start, segment, toNewAnchor, cost, why(level, player, start, end,
				toNewAnchor ? segment.to().tangent().heading() : null, segment, cost));
	}

	/** Whether a new anchor's heading works for its segment: the anchor has room, and the shape keeps the limits. */
	private static boolean fits(Level level, Player player, Plan.Laying laying) {
		return noRoom(level, player, laying.end(), laying.segment().to().tangent().heading()) == null
				&& laying.segment().brokenLimits().isEmpty();
	}

	/** The sharpest bend of a segment's centre line: its greatest curvature, sampled. */
	private static double maxBend(Segment segment) {
		SegmentCurve curve = segment.curve(BlockPos.ZERO);
		double max = 0;
		for (int i = 0; i <= BEND_SAMPLES; i++) {
			double t = (double) i / BEND_SAMPLES;
			Vec3 velocity = curve.velocity(t);
			double speed = velocity.length();
			if (speed > 0) {
				max = Math.max(max, velocity.cross(curve.acceleration(t)).length() / (speed * speed * speed));
			}
		}
		return max;
	}

	/**
	 * Whether a player hangs from a rail, where they don't lay track: from below a rail, block reach finds no spot a
	 * segment could be laid to.
	 */
	public static boolean hanging(Player player) {
		return player.getVehicle() instanceof SkyHookEntity;
	}

	/**
	 * The heading a new anchor faces: the way the track runs as it arrives there, along the circle that leaves the start
	 * along its track and passes through the new anchor, seen from above and snapped to 45°. That is the start's
	 * direction mirrored in the chord. Straight on gives straight on; off to the side, a bend that ends turned twice as
	 * far as the chord is off. With the new anchor straight above or below, straight on.
	 *
	 * @param leaving the heading the segment leaves the start in
	 * @param toEnd the new anchor's position minus the start's
	 */
	static Heading arcHeading(Heading leaving, Vec3i toEnd) {
		double length = Math.sqrt((double) toEnd.getX() * toEnd.getX() + (double) toEnd.getZ() * toEnd.getZ());
		if (length == 0) {
			return leaving;
		}
		double chordX = toEnd.getX() / length;
		double chordZ = toEnd.getZ() / length;
		double along = leaving.x() * chordX + leaving.z() * chordZ;
		double arrivingX = 2 * along * chordX - leaving.x();
		double arrivingZ = 2 * along * chordZ - leaving.z();
		// An entity's yaw: south is 0°, west 90°.
		return Heading.fromYaw((float) Math.toDegrees(Math.atan2(-arrivingX, arrivingZ)));
	}

	/**
	 * Why a segment can't be laid, past what makes it no segment at all: the player may not build at an end, a new
	 * anchor has no room, its shape breaks a limit, or the player has too few rails.
	 *
	 * @param newHeading the heading of a new anchor at the far end; null if one is there
	 */
	private static @Nullable Component why(Level level, Player player, BlockPos start, BlockPos end,
			@Nullable Heading newHeading, Segment segment, int cost) {
		String noRoom = newHeading != null
				? noRoom(level, player, end, newHeading)
				: mayBuild(level, player, end) ? null : SkyLine.MAY_NOT_BUILD;
		if (noRoom == null && !mayBuild(level, player, start)) {
			noRoom = SkyLine.MAY_NOT_BUILD;
		}
		if (noRoom != null) {
			return reason(noRoom);
		}
		Optional<SegmentLimit> broken = segment.brokenLimits().stream().findFirst();
		if (broken.isPresent()) {
			return why(broken.get());
		}
		int have = player.getInventory().countItem(SkyLine.RAIL);
		return !player.hasInfiniteMaterials() && have < cost ? reason(SkyLine.TOO_FEW_RAILS) : null;
	}

	/**
	 * A player clicked a block to lay track to, as their client sends it ({@link LayTrackPayload}). The hand must hold a
	 * Sky-Line with an anchor selected, the player mustn't be a spectator, and the block must be within their reach as
	 * vanilla checks a use on a block, nor hang from a rail. The segment the click {@link #plan plans} is then laid, paid for from the
	 * player's Sky-Lines, the held stack first, and the selection moves on to its far end's free side, clearing once
	 * none is left. If it can't be laid, the player is told why, and nothing is laid or taken.
	 *
	 * @param chosen the heading the player chose for a new anchor at the far end, if they did
	 */
	public static void lay(ServerPlayer player, InteractionHand hand, BlockHitResult hit, Optional<Heading> chosen) {
		ServerLevel level = player.level();
		ItemStack held = player.getItemInHand(hand);
		TrackStart start = TrackStart.of(player);
		if (!held.is(SkyLine.RAIL) || start == null || player.isSpectator() || hanging(player)
				|| !player.isWithinBlockInteractionRange(hit.getBlockPos(), 1)) {
			return;
		}
		Plan plan = plan(player, hand, start, hit, chosen.orElse(null));
		if (plan instanceof Plan.Refused(Component why)) {
			player.sendOverlayMessage(why);
			return;
		}
		if (!(plan instanceof Plan.Laying laying)) {
			return;
		}
		if (laying.why() != null) {
			player.sendOverlayMessage(laying.why());
			return;
		}
		Segment segment = laying.segment();
		BlockPos end = laying.end();
		if (laying.toNewAnchor()) {
			place(level, end, segment.to().tangent().heading());
		}
		alignIfAlone(level, start.pos(), segment.from().tangent());
		alignIfAlone(level, end, segment.to().tangent());
		if (!(level.getBlockEntity(start.pos()) instanceof AnchorBlockEntity from)
				|| !(level.getBlockEntity(end) instanceof AnchorBlockEntity to)) {
			throw new IllegalStateException("no anchor at an end of " + segment + " from " + start.pos()
					+ ", where the plan found or placed them this tick");
		}
		from.connect(segment);
		AnchorSide next = segment.to().side().opposite();
		TrackStart.set(player, to.segment(next) == null ? new TrackStart(end, Optional.of(next)) : null);
		// The client swung as it sent the click; the others see it now.
		player.swing(hand, held.getInteractAnimation(), false);
		take(player, held, laying.cost());
		playPlaceSound(level, end);
	}

	/**
	 * A click on a block face with nothing selected: a lone anchor goes in the cell a block would be placed in, heading
	 * the way the player faces, snapped to 45°, level, and is selected, so track can be laid from it at once. It costs
	 * one Sky-Line. If it can't go there, says why on the action bar.
	 */
	static void placeAlone(ServerPlayer player, UseOnContext context) {
		ServerLevel level = player.level();
		BlockPos pos = new BlockPlaceContext(context).getClickedPos();
		Heading heading = Heading.fromYaw(player.getYRot());
		String noRoom = noRoom(level, player, pos, heading);
		if (noRoom != null) {
			player.sendOverlayMessage(reason(noRoom));
			return;
		}
		place(level, pos, heading);
		TrackStart.set(player, new TrackStart(pos, Optional.empty()));
		take(player, context.getItemInHand(), 1);
		playPlaceSound(level, pos);
	}

	/**
	 * A click on an anchor with nothing selected: selects it, with its free side if only one is. If it has none, or the
	 * player may not build there, says so on the action bar.
	 */
	static void select(ServerPlayer player, AnchorBlockEntity anchor) {
		ServerLevel level = player.level();
		BlockPos pos = anchor.getBlockPos();
		List<AnchorSide> free = freeSides(anchor);
		if (!mayBuild(level, player, pos)) {
			player.sendOverlayMessage(reason(SkyLine.MAY_NOT_BUILD));
		} else if (free.isEmpty()) {
			player.sendOverlayMessage(reason(SkyLine.NO_FREE_SIDE));
		} else {
			// With both sides free, laying picks one.
			Optional<AnchorSide> side = free.size() == 1 ? Optional.of(free.getFirst()) : Optional.empty();
			TrackStart.set(player, new TrackStart(pos, side));
		}
	}

	/**
	 * Keeps a player's selection true to its anchor, as each server tick ends: it goes once the anchor is gone or both
	 * its sides are taken, and keeps to its free side once only one is. Laying from it then never meets a taken side
	 * but within the tick. It goes too once the player is more than {@link #SELECTION_REACH} from the anchor, left
	 * behind. An anchor whose chunk isn't loaded is otherwise left as it was.
	 */
	static void keepCurrent(ServerPlayer player) {
		TrackStart start = TrackStart.of(player);
		if (start != null && start.pos().distToCenterSqr(player.position()) > SELECTION_REACH * SELECTION_REACH) {
			TrackStart.set(player, null);
			return;
		}
		if (start == null || !player.level().isLoaded(start.pos())) {
			return;
		}
		List<AnchorSide> free = player.level().getBlockEntity(start.pos()) instanceof AnchorBlockEntity anchor
				? freeSides(anchor)
				: List.of();
		TrackStart now = free.isEmpty()
				? null
				: new TrackStart(start.pos(), free.size() == 1 ? Optional.of(free.getFirst()) : Optional.empty());
		if (!start.equals(now)) {
			TrackStart.set(player, now);
		}
	}

	/**
	 * How a segment leaves one of its anchors, with the other at an offset from it. An anchor that holds a segment
	 * leaves along its tangent: tilting it would reshape the segments it holds. One that holds none, a lone anchor or a
	 * new one, takes the pitch of the chord to the other. The side is the one given, or by rule A if none is.
	 *
	 * @param tangent the anchor's tangent; only its heading counts for an anchor that holds no segment
	 * @param holdsSegment whether the anchor holds a segment
	 * @param side the side the segment leaves by, if only one is free
	 * @param toOther the other anchor's position minus this one's
	 */
	static Segment.End end(AnchorTangent tangent, boolean holdsSegment, Optional<AnchorSide> side, Vec3i toOther) {
		Heading heading = tangent.heading();
		AnchorTangent leaving = holdsSegment ? tangent : new AnchorTangent(heading, pitchToward(heading, toOther));
		return new Segment.End(leaving, side.orElseGet(() -> sideToward(heading, toOther)));
	}

	/**
	 * The side of an anchor with both sides free a segment leaves it on (rule A): the one whose direction points more
	 * toward the other anchor, the front on a tie. The anchor's heading only fixes its line, not which way along it the
	 * player faced, so a player who turns round to face the start while placing the end still gets an end whose free
	 * side points on.
	 *
	 * @param toOther the other anchor's position minus this one's
	 */
	static AnchorSide sideToward(Heading heading, Vec3i toOther) {
		return across(heading, toOther) < 0 ? AnchorSide.BACK : AnchorSide.FRONT;
	}

	/**
	 * The pitch of an anchor holding no segment: the slope of the chord to the other anchor, seen along the anchor's
	 * heading, so it climbs along its heading toward a higher anchor ahead, held within ±45°. Both anchors' points are
	 * as deep in their cells, so the chord is the offset between the cells.
	 *
	 * @param toOther the other anchor's position minus this one's
	 */
	static float pitchToward(Heading heading, Vec3i toOther) {
		double across = across(heading, toOther);
		double pitch = across == 0
				? Math.signum(toOther.getY()) * 90
				: Math.toDegrees(Math.atan(toOther.getY() / across));
		// Adding 0 turns -0, from a level chord seen backwards, into 0: tangents compare their pitches exactly.
		return (float) Mth.clamp(pitch, -AnchorTangent.MAX_PITCH, AnchorTangent.MAX_PITCH) + 0F;
	}

	/** How far an offset reaches along a heading, horizontally. */
	private static double across(Heading heading, Vec3i offset) {
		return heading.x() * offset.getX() + heading.z() * offset.getZ();
	}

	/** Puts a new anchor in place, level, holding water if its cell does. */
	private static void place(ServerLevel level, BlockPos pos, Heading heading) {
		boolean water = level.getFluidState(pos).is(Fluids.WATER);
		level.setBlock(pos, SkyLine.ANCHOR.defaultBlockState().setValue(AnchorBlock.HEADING, heading)
				.setValue(AnchorBlock.WATERLOGGED, water), Block.UPDATE_ALL);
	}

	/** Tilts an anchor that holds no segment to the pitch a segment leaves it at. */
	private static void alignIfAlone(ServerLevel level, BlockPos pos, AnchorTangent tangent) {
		if (level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor && anchor.segments().isEmpty()) {
			anchor.setPitch(tangent.pitch());
		}
	}

	private static void playPlaceSound(ServerLevel level, BlockPos pos) {
		SoundType sound = SkyLine.ANCHOR.defaultBlockState().getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2,
				sound.getPitch() * 0.8F);
	}

	/** The sides of an anchor that hold no segment. */
	private static List<AnchorSide> freeSides(AnchorBlockEntity anchor) {
		return Arrays.stream(AnchorSide.values()).filter(side -> anchor.segment(side) == null).toList();
	}

	/**
	 * Why a new anchor can't go in a cell, heading a way, if it can't: its cell and the cells its beam reaches into must
	 * be air or replaceable, inside the world, and the player must be allowed to build in them.
	 */
	private static @Nullable String noRoom(Level level, Player player, BlockPos pos, Heading heading) {
		List<BlockPos> cells = new ArrayList<>(List.of(pos));
		CrossBeam.ends(heading).forEach(offset -> cells.add(pos.offset(offset)));
		for (BlockPos cell : cells) {
			if (level.isOutsideBuildHeight(cell) || !level.getBlockState(cell).canBeReplaced()) {
				return SkyLine.NO_ROOM_FOR_ANCHOR;
			}
			if (!mayBuild(level, player, cell)) {
				return SkyLine.MAY_NOT_BUILD;
			}
		}
		return null;
	}

	/**
	 * Whether a player may build in a cell: not in adventure mode, under spawn protection, or in another's claim, the
	 * last two of which only the server sees ({@link Protection#mayPlace}).
	 */
	private static boolean mayBuild(Level level, Player player, BlockPos cell) {
		return Protection.mayPlace(level, player, cell);
	}

	/**
	 * The action-bar text for a limit a segment breaks: its length or slope, with the limit's value, or one reason for
	 * every way it turns too sharply, which the ghost shows. A segment from an anchor to itself was planned as nothing.
	 */
	private static Component why(SegmentLimit limit) {
		return switch (limit) {
			case SAME_CELL -> throw new IllegalStateException("a segment from an anchor to its own cell");
			case TOO_LONG -> reason(SkyLine.TOO_LONG, (int) SegmentLimit.MAX_LENGTH);
			case TOO_STEEP -> reason(SkyLine.TOO_STEEP, (int) SegmentLimit.MAX_SLOPE_DEGREES);
			case TURNS_TOO_FAR, TURN_TOO_TIGHT, BEND_TOO_SHARP -> reason(SkyLine.TURN_TOO_SHARP);
		};
	}

	/**
	 * Takes Sky-Lines from the held stack first, then from the rest of the inventory; nothing from a player with
	 * infinite materials (creative).
	 */
	private static void take(Player player, ItemStack held, int count) {
		if (player.hasInfiniteMaterials()) {
			return;
		}
		Inventory inventory = player.getInventory();
		int left = count;
		int fromHeld = Math.min(left, held.getCount());
		held.shrink(fromHeld);
		left -= fromHeld;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack != held && stack.is(SkyLine.RAIL)) {
				int taken = Math.min(left, stack.getCount());
				stack.shrink(taken);
				left -= taken;
			}
		}
	}

	private static Plan refused(String why) {
		return new Plan.Refused(reason(why));
	}

	/**
	 * Why a click can't lay, place or select, for the action bar, in Create's red for what can't be placed: the server's
	 * reasons and the client's preview say it alike, so one never flashes another colour before the other.
	 */
	static Component reason(String key, Object... args) {
		return Component.translatable(key, args).withColor(CANT_COLOR);
	}
}
