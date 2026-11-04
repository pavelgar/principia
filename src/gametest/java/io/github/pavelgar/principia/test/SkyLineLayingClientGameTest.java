package io.github.pavelgar.principia.test;

import java.util.Objects;

import io.github.pavelgar.principia.client.skyline.RailTargeting;
import io.github.pavelgar.principia.client.skyline.TrackLaying;
import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Placement;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.TrackStart;
import io.github.pavelgar.principia.test.mixin.HudAccessor;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Laying Sky-Line track as a player does, in survival on the flat test world, and what the client shows on the way:
 * the outline of an anchor to select, the ghost of the segment a click would lay with the cost above the hotbar and
 * the marks on the faces around a new anchor's spot, and the action bar. A click where the ghost is green lays the very
 * segment it showed, on the server. The screenshots are for the human to judge.
 *
 * <p>Everything is placed from the player's first spot, F: the ground's top is at F's y. The track runs south from a
 * lone anchor at F + (0, 0, 2).
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyLineLayingClientGameTest implements FabricClientGameTest {
	/** The eye height of a standing player. */
	private static final double EYE = 1.62;
	/** How far below the top of its cell an anchor's beam and rails run. */
	private static final double BEAM = 1 / 16.0;
	private static final float SOUTH = 0;
	private static final float EAST = -90;
	private static final float WEST = 90;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			server.runCommand("gamemode survival @a");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_line 64");
			// Earlier tests in the same client can leave something on the action bar, such as how to dismount.
			context.runOnClient(client -> client.gui.hud.setOverlayMessage(Component.empty(), false));
			BlockPos first = feet.south(2);

			// A click on the ground 2 ahead places a lone anchor heading south, and selects it.
			look(context, singleplayer, feet, SOUTH, pitchTo(2, -EYE));
			use(context);
			assertAnchor(server, first, Heading.SOUTH);
			assertSelected(context, true);
			look(context, singleplayer, feet, SOUTH, pitchTo(2, 1 - BEAM - EYE));
			// Still aiming at it: the selected anchor itself is nothing to lay to.
			if (!(context.computeOnClient(client -> TrackLaying.plan()) instanceof Placement.Plan.Nothing)) {
				throw new AssertionError("aiming at the selected anchor shows " + context.computeOnClient(
						client -> TrackLaying.plan()));
			}
			assertActionBarSaysNothing(context);

			// Standing just past it, aiming at the ground 4 ahead: a straight 5 south to a new anchor, green, costing 6.
			BlockPos second = feet.south(7);
			look(context, singleplayer, feet.south(3), SOUTH, pitchTo(4, -EYE));
			Placement.Plan.Laying toTheGround = assertGhost(context, first, second, true);
			assertCost(toTheGround, 6);
			assertActionBarSaysNothing(context);
			context.takeScreenshot("sky-line-green-ghost-to-the-ground-with-its-cost-and-face-marks");
			use(context);
			assertLaid(server, toTheGround);
			assertHeld(context, 64 - 1 - 6);

			// From the new anchor, now selected, facing east at the ground 3 east and 1 south of it: a turn too sharp. Red
			// and dashed, and a click lays nothing.
			BlockPos tight = feet.offset(3, 0, 8);
			look(context, singleplayer, feet.south(8), EAST, pitchTo(3, -EYE));
			Placement.Plan.Laying tooTight = assertGhost(context, second, tight, false);
			Component turnTooTight = Component.translatable(SkyLine.TURN_TOO_SHARP);
			assertActionBar(context, turnTooTight);
			context.takeScreenshot("sky-line-red-dashed-ghost-turning-too-tight");
			use(context);
			assertNoAnchor(server, tight);
			assertHeld(context, 57);
			if (!Objects.equals(tooTight.why(), turnTooTight.copy().withColor(0xEA5C2B))) {
				throw new AssertionError("the ghost is red for " + tooTight.why() + ", not " + turnTooTight);
			}

			// With 2 Sky-Lines, a straight 4 on south to a new anchor costs 5: the cost shows in gold.
			setHeldCount(server, 2);
			look(context, singleplayer, feet.south(8), SOUTH, pitchTo(3, -EYE));
			Placement.Plan.Laying tooFew = assertGhost(context, second, feet.south(11), false);
			assertCost(tooFew, 5);
			assertActionBar(context, Component.translatable(SkyLine.TOO_FEW_RAILS));
			context.takeScreenshot("sky-line-cost-in-gold-with-too-few-sky-lines");
			setHeldCount(server, 57);

			// A lone anchor 3 south: aimed at, the ghost runs to it, with no new anchor, and costs only its length.
			BlockPos third = feet.south(10);
			server.runCommand("setblock %d %d %d principia:sky_line_anchor[heading=south]".formatted(third.getX(),
					third.getY(), third.getZ()));
			look(context, singleplayer, feet.south(8), SOUTH, pitchTo(2, 1 - BEAM - EYE));
			Placement.Plan.Laying toAnAnchor = assertGhost(context, second, third, true);
			assertCost(toAnAnchor, 3);
			assertActionBarSaysNothing(context);
			context.takeScreenshot("sky-line-green-ghost-to-an-existing-anchor");
			use(context);
			assertLaid(server, toAnAnchor);
			assertHeld(context, 57 - 3);

			// A wall 6 south of the last anchor: aiming at it shows marks on its face around the spot aimed at.
			server.runCommand("fill %d %d %d %d %d %d minecraft:stone".formatted(feet.getX() - 3, feet.getY() - 1,
					feet.getZ() + 16, feet.getX() + 3, feet.getY() + 4, feet.getZ() + 16));
			look(context, singleplayer, feet.south(12), SOUTH, pitchTo(3.5, 1.5 - EYE));
			context.takeScreenshot("sky-line-ghost-to-a-wall-with-face-marks");

			// Aiming at a rail of the last segment from beside it: the rail takes the crosshair, there is no ghost, and a
			// click lays nothing.
			look(context, singleplayer, feet.offset(3, 0, 9), WEST, pitchTo(2, 1 - BEAM - EYE));
			if (context.computeOnClient(client -> RailTargeting.mineTarget()) == null) {
				throw new AssertionError("the player doesn't aim at the rail");
			}
			if (!(context.computeOnClient(client -> TrackLaying.plan()) instanceof Placement.Plan.Nothing)) {
				throw new AssertionError("aiming at a rail shows " + context.computeOnClient(client -> TrackLaying
						.plan()));
			}
			assertActionBarSaysNothing(context);
			context.takeScreenshot("sky-line-no-ghost-aiming-at-a-rail");
			use(context);
			assertHeld(context, 54);
			assertSelected(context, true);

			// Aiming at the ground 3 east and 1 south of the last anchor, a turn too sharp, its reason on the action bar. A
			// sneak-use clears the selection, and the reason goes with the preview. The anchor in the middle, holding a
			// segment on both sides, is then outlined red: it can't be selected.
			look(context, singleplayer, feet.south(11), EAST, pitchTo(3, -EYE));
			assertActionBar(context, Component.translatable(SkyLine.TURN_TOO_SHARP));
			context.getInput().holdKey(options -> options.keyShift);
			context.waitTicks(2); // the server hears the player sneak before their click
			use(context);
			context.getInput().releaseKey(options -> options.keyShift);
			assertSelected(context, false);
			assertActionBarSaysNothing(context);
			look(context, singleplayer, feet.south(5), SOUTH, pitchTo(2, 1 - BEAM - EYE));
			context.takeScreenshot("sky-line-start-outlined-red-on-an-anchor-with-no-free-side");

			// A lone anchor heading east, with nothing selected: aimed at, it is outlined white, as one that can be
			// selected. Selected, aiming at the ground 4 east of it, the ghost runs straight to a new anchor there. Turned
			// onto the ground a block nearer in the tick of the click, the click still lays what the ghost showed.
			BlockPos lone = feet.offset(8, 0, -4);
			server.runCommand("setblock %d %d %d principia:sky_line_anchor[heading=east]".formatted(lone.getX(),
					lone.getY(), lone.getZ()));
			look(context, singleplayer, lone.west(2), EAST, pitchTo(2, 1 - BEAM - EYE));
			context.takeScreenshot("sky-line-start-outlined-white-on-an-anchor-with-a-free-side");
			use(context);
			assertSelected(context, true);
			// The eyes 3 back from the ground's middle along a yaw of -112°, which looks along (0.92718, -0.37461).
			Vec3 ground = Vec3.atBottomCenterOf(lone.east(4));
			lookFrom(context, singleplayer, ground.add(-3 * 0.92718, 0, 3 * 0.37461), -112, pitchTo(3, -EYE));
			Placement.Plan.Laying straight = assertGhost(context, lone, lone.east(4), true);
			context.getInput().lookAt(-112, pitchTo(2, -EYE));
			use(context);
			assertLaid(server, straight);

			// From that straight's far end, now selected, the ground 8 east and 2 south: a new anchor there heads
			// south-east by itself. Sneaking, a turn of the wheel up steps it back to the heading before among those that
			// work, east, without moving the hotbar's selection; the click, standing again, lays it. The ground is aimed
			// at 2.3 ahead of the feet, 1.8 crouching, both within the target's cell.
			BlockPos from = lone.east(4);
			BlockPos bend = from.offset(8, 0, 2);
			look(context, singleplayer, bend.west(2), EAST, pitchTo(2.3, -EYE));
			assertHeads(assertGhost(context, from, bend, true), Heading.SOUTH_EAST);
			context.takeScreenshot("sky-line-new-anchor-south-east-with-east-shown-faint");
			int slot = context.computeOnClient(client -> client.player == null
					? -1
					: client.player.getInventory().getSelectedSlot());
			context.getInput().holdKey(options -> options.keyShift);
			context.waitTicks(2);
			context.getInput().scroll(1);
			context.waitTicks(2);
			Placement.Plan.Laying turned = assertGhost(context, from, bend, true);
			assertHeads(turned, Heading.EAST);
			if (context.computeOnClient(client -> client.player == null
					? -1
					: client.player.getInventory().getSelectedSlot()) != slot) {
				throw new AssertionError("the wheel moved the hotbar's selection");
			}
			context.takeScreenshot("sky-line-new-anchor-turned-east-with-the-wheel");
			context.getInput().releaseKey(options -> options.keyShift);
			context.waitTicks(2);
			use(context);
			assertLaid(server, turned);
		}
	}

	/** Presses use, as a right-click, and waits for the server to have it and the client to hear back. */
	private static void use(ClientGameTestContext context) {
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(5);
	}

	/**
	 * Asserts the client shows a ghost from one anchor to another, green or red, and returns what it shows.
	 *
	 * @param lays whether the ghost is green: a click lays it
	 */
	private static Placement.Plan.Laying assertGhost(ClientGameTestContext context, BlockPos start, BlockPos end,
			boolean lays) {
		Placement.Plan plan = context.computeOnClient(client -> TrackLaying.plan());
		if (!(plan instanceof Placement.Plan.Laying laying) || !laying.start().equals(start)
				|| !laying.end().equals(end) || (laying.why() == null) != lays) {
			throw new AssertionError("the client shows " + plan + ", not a " + (lays ? "green" : "red")
					+ " ghost from " + start + " to " + end);
		}
		return laying;
	}

	private static void assertHeads(Placement.Plan.Laying laying, Heading heading) {
		if (laying.segment().to().tangent().heading() != heading) {
			throw new AssertionError("the new anchor heads " + laying.segment().to().tangent().heading() + ", not "
					+ heading);
		}
	}

	private static void assertCost(Placement.Plan.Laying laying, int cost) {
		if (laying.cost() != cost) {
			throw new AssertionError("the ghost costs " + laying.cost() + ", not " + cost);
		}
	}

	/** Asserts the server's anchors at both ends hold the very segment the client showed, each from its end. */
	private static void assertLaid(TestServerContext server, Placement.Plan.Laying shown) {
		Segment segment = shown.segment();
		Segment atStart = server
				.computeOnServer(s -> s.overworld().getBlockEntity(shown.start()) instanceof AnchorBlockEntity anchor
						? anchor.segment(segment.from().side())
						: null);
		Segment atEnd = server
				.computeOnServer(s -> s.overworld().getBlockEntity(shown.end()) instanceof AnchorBlockEntity anchor
						? anchor.segment(segment.to().side())
						: null);
		if (!segment.equals(atStart) || !segment.reversed().equals(atEnd)) {
			throw new AssertionError(
					"the client showed " + segment + " from " + shown.start() + ", and the server laid "
							+ atStart + " there and " + atEnd + " at " + shown.end());
		}
	}

	private static void assertAnchor(TestServerContext server, BlockPos pos, Heading heading) {
		Heading there = server.computeOnServer(s -> s.overworld().getBlockState(pos).is(SkyLine.ANCHOR)
				? s.overworld().getBlockState(pos).getValue(AnchorBlock.HEADING)
				: null);
		if (there != heading) {
			throw new AssertionError("the anchor at " + pos + " heads " + there + ", not " + heading);
		}
	}

	private static void assertNoAnchor(TestServerContext server, BlockPos pos) {
		if (server.computeOnServer(s -> s.overworld().getBlockState(pos).is(SkyLine.ANCHOR))) {
			throw new AssertionError("an anchor is at " + pos);
		}
	}

	private static void assertSelected(ClientGameTestContext context, boolean selected) {
		boolean held = context.computeOnClient(client -> client.player != null
				&& TrackStart.of(client.player) != null);
		if (held != selected) {
			throw new AssertionError(selected ? "no anchor is selected" : "an anchor is still selected");
		}
	}

	private static void assertHeld(ClientGameTestContext context, int count) {
		int held = context.computeOnClient(client -> client.player == null
				? -1
				: client.player.getMainHandItem().getCount());
		if (held != count) {
			throw new AssertionError("the player holds " + held + " Sky-Lines, not " + count);
		}
	}

	/** Asserts what the action bar says, in the client's language. */
	private static void assertActionBar(ClientGameTestContext context, Component expected) {
		@Nullable
		String shown = context.computeOnClient(client -> {
			Component message = ((HudAccessor) client.gui.hud).principia$overlayMessage();
			return message == null ? null : message.getString();
		});
		String wanted = context.computeOnClient(client -> expected.getString());
		if (!wanted.equals(shown)) {
			throw new AssertionError("the action bar says \"" + shown + "\", not \"" + wanted + "\"");
		}
	}

	/** Asserts the action bar says nothing: it hasn't been told anything, or has been emptied. */
	private static void assertActionBarSaysNothing(ClientGameTestContext context) {
		String shown = context.computeOnClient(client -> {
			Component message = ((HudAccessor) client.gui.hud).principia$overlayMessage();
			return message == null ? "" : message.getString();
		});
		if (!shown.isEmpty()) {
			throw new AssertionError("the action bar says \"" + shown + "\"");
		}
	}

	/** Sets how many Sky-Lines the player holds. */
	private static void setHeldCount(TestServerContext server, int count) {
		server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().getItemInHand(InteractionHand.MAIN_HAND)
				.setCount(count));
	}

	/** The pitch, in degrees, of looking at a point so far ahead and so far up. */
	private static float pitchTo(double ahead, double up) {
		return (float) -Math.toDegrees(Math.atan2(up, ahead));
	}

	/** Puts the player's feet in the middle of a cell, facing a yaw and a pitch. */
	private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos feet,
			float yaw, float pitch) {
		lookFrom(context, singleplayer, Vec3.atBottomCenterOf(feet), yaw, pitch);
	}

	/** Puts the player's feet at a point, facing a yaw and a pitch. */
	private static void lookFrom(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 at,
			float yaw, float pitch) {
		singleplayer.getServer().runCommand("tp @a %s %s %s %s %s".formatted(at.x, at.y, at.z, yaw, pitch));
		context.waitTicks(10); // the teleport reaches the client, and the chunks re-mesh after block changes
		singleplayer.getConnection().waitForChunksRender();
	}
}
