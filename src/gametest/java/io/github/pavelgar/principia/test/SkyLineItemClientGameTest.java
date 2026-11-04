package io.github.pavelgar.principia.test;

import java.util.Objects;

import io.github.pavelgar.principia.client.skyline.RailTargeting;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.Track;
import io.github.pavelgar.principia.skyline.TrackStart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Aiming at Sky-Line track as a player does, in survival: a rail takes the crosshair whatever the player holds and
 * outlines its whole segment, an end of an anchor's beam is aimed at as the anchor's part, and holding attack on a
 * rail mines its segment. The screenshots are for the human to judge the outlines.
 *
 * <p>The track runs east, 3 blocks south of the player and 3 up, from an anchor 6 west of them to one 6 east: its north
 * rail's line at z = 2.5 and y = 3.9375 from the player's feet, (0.5, 0, 0.5) from their cell's corner. The west
 * anchor's beam reaches north into the cell at (-6, 3, 2), 9 px past its own cell: from z = 2.4375 to 3, its top at
 * y = 4 and its outline 4 px deep.
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyLineItemClientGameTest implements FabricClientGameTest {
	/** The eye height of a standing player. */
	private static final double EYE = 1.62;
	private static final double RAIL_Y = 3.9375;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			BlockPos west = feet.offset(-6, 3, 3);
			Segment straight = SkyLineSegmentGameTest.segment(new Vec3i(12, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
			SkyLineClientGameTest.lay(context, server, west, straight, straight);
			server.runCommand("gamemode survival @a");

			// Up at the north rail straight ahead, 2 south and 2.3175 up from the eyes, the hand empty.
			look(context, singleplayer, Vec3.atBottomCenterOf(feet), 0, pitchTo(2, RAIL_Y - EYE));
			assertTargetsARail(context);
			context.takeScreenshot("sky-line-segment-outlined-aiming-at-a-rail-with-an-empty-hand");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_line 16");
			context.waitTicks(2);
			assertTargetsARail(context);
			context.takeScreenshot("sky-line-segment-outlined-aiming-at-a-rail-holding-a-sky-line");
			// A click on the ground 2 ahead places a lone anchor there and selects it, and its beam stays outlined.
			look(context, singleplayer, Vec3.atBottomCenterOf(feet), 0, pitchTo(2, -EYE));
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			look(context, singleplayer, Vec3.atBottomCenterOf(feet), 0, pitchTo(2, 1 - 1 / 16.0 - EYE));
			boolean glints = context.computeOnClient(client -> client.player != null
					&& TrackStart.of(client.player) != null);
			if (!glints) {
				throw new AssertionError("no anchor is selected");
			}
			context.takeScreenshot("sky-line-selected-anchor-outlined");
			server.runCommand("setblock %d %d %d air".formatted(feet.getX(), feet.getY(), feet.getZ() + 2));

			// A stone wall just south of the north rail, west of the player: a right-click with a block in hand on the
			// rail in front of it places nothing on the wall.
			server.runCommand("fill %d %d %d %d %d %d minecraft:stone".formatted(feet.getX() - 5, feet.getY(),
					feet.getZ() + 3, feet.getX() - 2, feet.getY() + 5, feet.getZ() + 3));
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:cobblestone 8");
			look(context, singleplayer, Vec3.atBottomCenterOf(feet).add(-4, 0, 0), 0, pitchTo(2, RAIL_Y - EYE));
			assertTargetsARail(context);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(5);
			int placed = server.computeOnServer(s -> {
				int count = 0;
				for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-5, 0, 2), feet.offset(-2, 5, 2))) {
					count += s.overworld().getBlockState(pos).is(Blocks.COBBLESTONE) ? 1 : 0;
				}
				return count;
			});
			if (placed != 0) {
				throw new AssertionError(placed + " cobblestone placed on the wall behind the rail");
			}
			server.runCommand("item replace entity @a weapon.mainhand with air");

			// Up from below at the west anchor's north end of its beam, 0.2 west of the wall: from 2.3 south of the
			// eyes, 2.28 up, past the north rail's line (which the ray passes 0.27 below), into the end's cell.
			BlockPos end = west.north();
			Vec3 onTheEnd = Vec3.atLowerCornerOf(end).add(0.5, 0.9, 0.8);
			Vec3 standing = new Vec3(onTheEnd.x, feet.getY(), feet.getZ() + 0.5);
			look(context, singleplayer, standing, 0, pitchTo(onTheEnd.z - standing.z, onTheEnd.y - standing.y - EYE));
			assertAimsAtBlock(context, end);
			clearHud(context);
			context.takeScreenshot("sky-line-cross-beam-outlined-aiming-at-its-end");

			// A diagonal anchor north of the player, alone, heading south-east: its beam runs from north-east to
			// south-west through its cell's corners. Up from the south at its north-east end, 0.12 into that cell
			// from the corner it shares with the anchor's.
			BlockPos diagonal = feet.offset(-3, 2, -4);
			server.runCommand("setblock %d %d %d principia:sky_line_anchor[heading=south_east]"
					.formatted(diagonal.getX(), diagonal.getY(), diagonal.getZ()));
			BlockPos corner = diagonal.offset(1, 0, -1);
			Vec3 onTheCorner = Vec3.atLowerCornerOf(corner).add(0.12, 0.92, 0.88);
			Vec3 below = new Vec3(onTheCorner.x, feet.getY(), feet.getZ() - 2.5);
			look(context, singleplayer, below, 180, pitchTo(below.z - onTheCorner.z, onTheCorner.y - below.y - EYE));
			assertAimsAtBlock(context, corner);
			clearHud(context);
			context.takeScreenshot("sky-line-diagonal-cross-beam-outlined-aiming-at-its-end");

			// Holding attack on the north rail, empty-handed, mines its whole segment in the time a rail takes by hand:
			// 1 / (30 · 0.7 hardness) of the way a tick, so 21 ticks. Still there after 15, gone 15 later.
			BlockPos first = west.east(10);
			look(context, singleplayer, Vec3.atBottomCenterOf(feet), 0, pitchTo(2, RAIL_Y - EYE));
			assertTargetsARail(context);
			context.getInput().holdKey(options -> options.keyAttack);
			context.waitTicks(15);
			assertSegments(context, singleplayer, west, first, 1);
			// 15 of 21 ticks in: the rails show the eighth of vanilla's ten stages of cracks.
			context.takeScreenshot("sky-line-rails-cracking-while-mined");
			context.waitTicks(15);
			context.getInput().releaseKey(options -> options.keyAttack);
			assertSegments(context, singleplayer, west, first, 0);

			// Under /tick freeze the client's game time stands still, but its player still ticks, and mines: the second
			// segment, from under its north rail 12 east of the player.
			BlockPos second = west.east(14);
			server.runCommand("tick freeze");
			look(context, singleplayer, Vec3.atBottomCenterOf(feet).add(12, 0, 0), 0, pitchTo(2, RAIL_Y - EYE));
			assertTargetsARail(context);
			assertSegments(context, singleplayer, second, second.east(9), 1);
			context.getInput().holdKey(options -> options.keyAttack);
			context.waitTicks(30);
			context.getInput().releaseKey(options -> options.keyAttack);
			assertSegments(context, singleplayer, second, second.east(9), 0);
			server.runCommand("tick unfreeze");
		}
	}

	/**
	 * Asserts how many segments the server's track and the client's hold in the cells from one corner to another: the
	 * first segment's rails run to x = 12.5 + 17/16 blocks east of its west anchor, the second's from 11.5 - 17/16.
	 */
	private static void assertSegments(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos from, BlockPos to, int expected) {
		AABB along = AABB.encapsulatingFullBlocks(from, to);
		int server = singleplayer.getServer()
				.computeOnServer(s -> Track.of(s.overworld()).index().near(along).size());
		int client = context
				.computeOnClient(
						c -> Track.of(singleplayer.getConnection().getClientLevel()).index().near(along).size());
		if (server != expected || client != expected) {
			throw new AssertionError("the server holds " + server + " segments and the client " + client + ", not "
					+ expected);
		}
	}

	/** The pitch, in degrees, of looking at a point so far ahead and so far up. */
	private static float pitchTo(double ahead, double up) {
		return (float) -Math.toDegrees(Math.atan2(up, ahead));
	}

	/** Puts the player's feet at a point, facing a yaw and a pitch. */
	private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 feet,
			float yaw, float pitch) {
		singleplayer.getServer().runCommand("tp @a %s %s %s %s %s".formatted(feet.x, feet.y, feet.z, yaw, pitch));
		context.waitTicks(10); // the teleport reaches the client, and the chunks re-mesh after block changes
		singleplayer.getConnection().waitForChunksRender();
	}

	/** Clears the chat and the toasts, which picking up the cobblestone fills, from the screenshots. */
	private static void clearHud(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.gui.hud.getChat().clearMessages(false);
			client.gui.toastManager().clear();
		});
	}

	/** Asserts the player aims at a rail, which has taken the crosshair from whatever is behind it. */
	private static void assertTargetsARail(ClientGameTestContext context) {
		RailAim.Hit target = context.computeOnClient(client -> RailTargeting.mineTarget());
		HitResult.Type vanilla = context.computeOnClient(client -> client.hitResult == null
				? HitResult.Type.MISS
				: client.hitResult.getType());
		if (target == null || vanilla != HitResult.Type.MISS) {
			throw new AssertionError("the player aims at " + target + ", vanilla at " + vanilla);
		}
	}

	/** Asserts vanilla's target is a block, an end of a beam, and no rail took it. */
	private static void assertAimsAtBlock(ClientGameTestContext context, BlockPos pos) {
		HitResult hit = context.computeOnClient(client -> client.hitResult);
		BlockPos aimed = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
				? block.getBlockPos()
				: null;
		boolean isEnd = context.computeOnClient(client -> client.level != null
				&& client.level.getBlockState(pos).is(SkyLine.BEAM_END));
		if (!Objects.equals(aimed, pos) || !isEnd) {
			throw new AssertionError("the player aims at " + aimed + ", not the end of a beam at " + pos);
		}
	}
}
