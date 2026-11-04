package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.pavelgar.principia.client.skyline.RailRenderer;
import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.Track;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The Sky-Line as a player sees it, for the human to judge: its anchors' cross-beams across a straight and a diagonal
 * heading, and the rails between anchors on track laid with commands. Track laid with commands reaches the client's
 * track.
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyLineClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			// Side by side 5 south of the player, their beams 2 blocks above the ground.
			BlockPos straight = feet.offset(-2, 1, 5);
			BlockPos diagonal = feet.offset(2, 1, 5);
			setAnchor(server, straight, Heading.SOUTH);
			setAnchor(server, diagonal, Heading.SOUTH_EAST);
			server.runCommand("gamemode spectator @a");

			look(context, singleplayer, feet.getX() + 0.5, feet.getY() + 4, feet.getZ() + 1.5, 0, 45);
			assertHeading(context, singleplayer, straight, Heading.SOUTH);
			assertHeading(context, singleplayer, diagonal, Heading.SOUTH_EAST);
			context.takeScreenshot("sky-line-anchors-straight-and-diagonal-from-above");

			// Close, the eye 0.6 above the beams' tops (2 above the player's feet) and 3 north of their middles.
			look(context, singleplayer, feet.getX() + 0.5, feet.getY() + 2 + 0.6 - 1.62, feet.getZ() + 2.5, 0, 12);
			context.takeScreenshot("sky-line-anchors-straight-and-diagonal-close-up");

			// Behind the camera, out of the screenshots.
			layTrackWithCommands(context, singleplayer, feet.offset(-5, 3, -4));

			drawRails(context, singleplayer, feet);
		}
	}

	/**
	 * The rails of a straight segment, a 90° curve and a 45° climb, each from the side and from above; the joint where
	 * a turn runs into a straight; a rail seen from further than block entities are usually drawn; and a rail running
	 * from sunlight into the shade of a roof. All south of the player, clear of each other and of the anchors' shots.
	 */
	private static void drawRails(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos feet) {
		TestServerContext server = singleplayer.getServer();
		server.runCommand("time set noon");
		BlockPos straight = feet.offset(10, 4, 25);
		Segment straightEast = SkyLineSegmentGameTest.segment(new Vec3i(12, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
		BlockPos curve = feet.offset(-30, 4, 25);
		Segment turnSouth = SkyLineSegmentGameTest.segment(new Vec3i(8, 0, 8), Heading.EAST, 0, Heading.SOUTH, 0);
		BlockPos climb = feet.offset(10, 4, 50);
		Segment climbEast = SkyLineSegmentGameTest.segment(new Vec3i(8, 8, 0), Heading.EAST, 45, Heading.EAST, 45);
		BlockPos shaded = feet.offset(-30, 3, 55);
		Segment intoShade = SkyLineSegmentGameTest.segment(new Vec3i(16, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
		// A stone roof 2 blocks above the rail's second half, reaching 8 blocks past it on every other side.
		server.runCommand(fill(shaded.offset(8, 2, -8), shaded.offset(24, 2, 8), "stone"));
		lay(context, server, straight, straightEast);
		lay(context, server, curve, turnSouth);
		lay(context, server, climb, climbEast);
		lay(context, server, shaded, intoShade);
		context.waitTicks(5);
		assertClientHolds(context, singleplayer, straight, straightEast);
		assertClientHolds(context, singleplayer, curve, turnSouth);
		assertClientHolds(context, singleplayer, climb, climbEast);
		assertClientHolds(context, singleplayer, shaded, intoShade);

		// The straight one runs east, 12 long: from 6 south and 2.5 above its middle, and from 10 above it.
		lookFrom(context, singleplayer, Vec3.atCenterOf(straight).add(6, 2.5, 6), 180, 19);
		context.takeScreenshot("sky-line-rails-straight-from-the-side");
		lookFrom(context, singleplayer, Vec3.atCenterOf(straight).add(6, 10, 0), 180, 90);
		// Drawn once, by the west anchor, first in BlockPos order.
		assertDraws(context, singleplayer, straight, 1);
		assertDraws(context, singleplayer, straight.offset(straightEast.offset()), 0);
		context.takeScreenshot("sky-line-rails-straight-from-above");

		// The curve runs east from its west anchor, turning south into its south one, 8 east and 8 south of it.
		lookFrom(context, singleplayer, Vec3.atCenterOf(curve).add(1, 3, 11), -158, 16);
		context.takeScreenshot("sky-line-rails-90-degree-curve-from-the-side");
		lookFrom(context, singleplayer, Vec3.atCenterOf(curve).add(4, 13, 4), 180, 90);
		assertDraws(context, singleplayer, curve, 1);
		context.takeScreenshot("sky-line-rails-90-degree-curve-from-above");

		// The climb rises 8 over 8 east: from the south-east, so its two rails stand apart, and from above.
		lookFrom(context, singleplayer, Vec3.atCenterOf(climb).add(10, 6, 9), 146, 10);
		context.takeScreenshot("sky-line-rails-45-degree-climb-from-the-side");
		lookFrom(context, singleplayer, Vec3.atCenterOf(climb).add(4, 16, 0), 180, 90);
		assertDraws(context, singleplayer, climb, 1);
		context.takeScreenshot("sky-line-rails-45-degree-climb-from-above");

		// The curve runs on south, straight, through its south anchor: the joint, close up from the east and above,
		// the straight to the left and the curve to the right.
		BlockPos joint = curve.offset(turnSouth.offset());
		lay(context, server, joint,
				SkyLineSegmentGameTest.segment(new Vec3i(0, 0, 8), Heading.SOUTH, 0, Heading.SOUTH, 0));
		lookFrom(context, singleplayer, Vec3.atCenterOf(joint).add(3.5, 2, 0), 90, 24);
		// The curve is drawn by its west anchor, the straight by the joint's, north of its other end.
		assertDraws(context, singleplayer, curve, 1);
		assertDraws(context, singleplayer, joint, 1);
		context.takeScreenshot("sky-line-rails-curve-into-straight-joint-close-up");

		// From 80 blocks north, past vanilla's 64 for block entities, with the world drawn that far (8 chunks, its fog
		// starting past 80 blocks) and the view narrowed, as a spyglass would, so a rail 2 px thick shows.
		context.runOnClient(client -> {
			client.options.renderDistance().set(8);
			client.options.fov().set(30);
		});
		Vec3 far = Vec3.atCenterOf(straight).add(6, 3, -80);
		singleplayer.getServer().runCommand("tp @a %s %s %s 0 2".formatted(far.x, far.y - 1.62, far.z));
		// The server sends the chunks within a circle, so those in the corners of the client's square never come:
		// wait for those that do, then for them to render.
		context.waitTicks(100);
		singleplayer.getConnection().waitForChunksRender(false);
		clearChat(context);
		assertDraws(context, singleplayer, straight, 1);
		context.takeScreenshot("sky-line-rails-straight-from-80-blocks");
		context.runOnClient(client -> {
			client.options.renderDistance().set(5);
			client.options.fov().set(70);
		});

		// The rail runs east from the sunlight under the roof, from x + 8 on.
		BlockPos inSun = shaded.offset(2, 0, 1);
		BlockPos inShade = shaded.offset(14, 0, 1);
		int sunLight = context.computeOnClient(client -> LightCoordsUtil
				.sky(LightCoordsUtil.getLightCoords(singleplayer.getConnection().getClientLevel(), inSun)));
		int shadeLight = context.computeOnClient(client -> LightCoordsUtil
				.sky(LightCoordsUtil.getLightCoords(singleplayer.getConnection().getClientLevel(), inShade)));
		if (shadeLight >= sunLight) {
			throw new AssertionError("sky light " + shadeLight + " under the roof, " + sunLight + " in the sun");
		}
		// From under the roof's south edge, between the rail and the roof: the rail runs from x + 3 to x + 14.
		lookFrom(context, singleplayer, Vec3.atCenterOf(shaded).add(8, 1.1, 5), 180, 8);
		assertDraws(context, singleplayer, shaded, 1);
		// The rail takes the light of each cell it runs through, not its anchor's, which is in the sun.
		int railInSun = railLight(context, singleplayer, shaded, inSun);
		int railInShade = railLight(context, singleplayer, shaded, inShade);
		if (railInSun == -1 || railInShade == -1
				|| LightCoordsUtil.sky(railInShade) >= LightCoordsUtil.sky(railInSun)) {
			throw new AssertionError("the rail takes sky light " + LightCoordsUtil.sky(railInShade)
					+ " under the roof and " + LightCoordsUtil.sky(railInSun) + " in the sun");
		}
		context.takeScreenshot("sky-line-rail-from-sunlight-into-shade");
	}

	/**
	 * Lays a chain of segments with commands, as a player can before the rail item, each leaving the far anchor
	 * of the one before. It places the anchors not yet in place, each facing its first segment's tangent, and waits past
	 * the end of the tick they loaded in, when they are checked, since an anchor that loads drops a segment its partner
	 * doesn't hold yet. Then it gives each anchor its copy of its segments. An anchor already in place keeps its other
	 * segment.
	 */
	static void lay(ClientGameTestContext context, TestServerContext server, BlockPos from, Segment... chain) {
		Map<BlockPos, List<Segment>> held = new LinkedHashMap<>();
		BlockPos anchor = from;
		for (Segment segment : chain) {
			BlockPos partner = anchor.offset(segment.offset());
			held.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(segment);
			held.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(segment.reversed());
			anchor = partner;
		}
		held.forEach((pos, segments) -> {
			if (!server.computeOnServer(s -> s.overworld().getBlockState(pos)).is(SkyLine.ANCHOR)) {
				setAnchor(server, pos, segments.getFirst().from().tangent().heading());
			}
		});
		context.waitTick();
		held.forEach((pos, segments) -> segments.forEach(segment -> merge(server, pos, segment)));
	}

	/**
	 * Gives an anchor a segment, with its pitch, which it takes in the same merge. The anchor stores only the far end:
	 * where its partner is, the partner's tangent, and its side there.
	 */
	private static void merge(TestServerContext server, BlockPos anchor, Segment segment) {
		Segment.Partner partner = segment.partner();
		server.runCommand(("data merge block %d %d %d {pitch:%sf,%s:{offset:[I;%d,%d,%d],"
				+ "tangent:{heading:\"%s\",pitch:%sf},side:\"%s\"}}").formatted(anchor.getX(), anchor.getY(),
						anchor.getZ(), segment.from().tangent().pitch(), segment.from().side().getSerializedName(),
						partner.offset().getX(), partner.offset().getY(), partner.offset().getZ(),
						partner.tangent().heading().getSerializedName(), partner.tangent().pitch(),
						partner.side().getSerializedName()));
	}

	private static String fill(BlockPos from, BlockPos to, String block) {
		return "fill %d %d %d %d %d %d %s".formatted(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(),
				to.getZ(), block);
	}

	/** Puts the camera, the eye of the spectating player, at a point, facing by a yaw and a pitch in degrees. */
	private static void lookFrom(ClientGameTestContext context, TestSingleplayerContext singleplayer, Vec3 eye,
			float yaw, float pitch) {
		look(context, singleplayer, eye.x, eye.y - 1.62, eye.z, yaw, pitch);
	}

	/**
	 * Track laid with commands, as a player can before the rail item: both anchors first, then each one's copy of
	 * the segment, as an anchor that loads drops a segment its partner doesn't hold yet. The client's track holds the
	 * segment once the anchors' data reaches it, and drops it when an anchor is removed.
	 */
	private static void layTrackWithCommands(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos west) {
		TestServerContext server = singleplayer.getServer();
		BlockPos east = west.east(10);
		Segment eastward = SkyLineSegmentGameTest.segment(new Vec3i(10, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
		lay(context, server, west, eastward);
		context.waitTicks(5);
		assertClientTrack(context, singleplayer, west, east, List.of(new SegmentIndex.Held(west, eastward)));

		server.runCommand("setblock %d %d %d air".formatted(west.getX(), west.getY(), west.getZ()));
		context.waitTicks(5);
		assertClientTrack(context, singleplayer, west, east, List.of());
	}

	private static void setAnchor(TestServerContext server, BlockPos pos, Heading heading) {
		server.runCommand("setblock %d %d %d principia:sky_line_anchor[heading=%s]".formatted(pos.getX(), pos.getY(),
				pos.getZ(), heading.getSerializedName()));
	}

	/**
	 * Puts the spectating player's feet at a point, facing by a yaw (0 south, 90 west, 180 north, -90 east) and looking
	 * down by a pitch, in degrees.
	 */
	private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, double x, double y,
			double z, float yaw, float pitch) {
		singleplayer.getServer().runCommand("tp @a %s %s %s %s %s".formatted(x, y, z, yaw, pitch));
		context.waitTicks(20); // the teleport reaches the client, and the chunks re-mesh after the block changes
		singleplayer.getConnection().waitForChunksRender();
		clearChat(context);
	}

	private static void assertHeading(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos pos, Heading heading) {
		BlockState seen = context.computeOnClient(client -> singleplayer.getConnection().getClientLevel()
				.getBlockState(pos));
		if (!seen.is(SkyLine.ANCHOR) || seen.getValue(AnchorBlock.HEADING) != heading) {
			throw new AssertionError("the client sees " + seen + " at " + pos + ", not an anchor heading " + heading);
		}
	}

	/**
	 * Asserts how many segments an anchor draws as the camera now sees them: what the client's block entity renderers
	 * extract from it for the frame, past their checks of distance and view.
	 */
	private static void assertDraws(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos anchor, int segments) {
		int drawn = context
				.computeOnClient(client -> extract(client, singleplayer, anchor) instanceof RailRenderer.State rails
						? rails.segments()
						: 0);
		if (drawn != segments) {
			throw new AssertionError("the anchor at " + anchor + " draws " + drawn + " segments, not " + segments);
		}
	}

	/**
	 * The packed light an anchor's rails take in a cell as the camera now sees them, or -1 if no part of them drawn is
	 * in it.
	 */
	private static int railLight(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos anchor,
			BlockPos cell) {
		return context
				.computeOnClient(client -> extract(client, singleplayer, anchor) instanceof RailRenderer.State rails
						? rails.lightAt(cell)
						: -1);
	}

	/**
	 * What the client's block entity renderers extract from an anchor for the frame, past their checks of distance and
	 * view; none if they skip it. On the client thread.
	 */
	private static @Nullable BlockEntityRenderState extract(Minecraft client, TestSingleplayerContext singleplayer,
			BlockPos anchor) {
		BlockEntity blockEntity = singleplayer.getConnection().getClientLevel().getBlockEntity(anchor);
		if (blockEntity == null) {
			throw new AssertionError("the client has no anchor at " + anchor);
		}
		return client.getBlockEntityRenderDispatcher().tryExtractRenderState(blockEntity, 0, null, true);
	}

	/** Clears the chat, whose game mode message would cover the screenshots' lower left. */
	private static void clearChat(ClientGameTestContext context) {
		context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
	}

	/** Asserts the client's track holds a segment, as its anchor first in {@code BlockPos} order stores it. */
	private static void assertClientHolds(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos from, Segment segment) {
		assertClientTrack(context, singleplayer, from, from.offset(segment.offset()),
				List.of(new SegmentIndex.Held(from, segment)));
	}

	/** Asserts the segments the client's track finds in the cells from one corner to another. */
	private static void assertClientTrack(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			BlockPos from, BlockPos to, List<SegmentIndex.Held> expected) {
		List<SegmentIndex.Held> seen = context.computeOnClient(client -> Track
				.of(singleplayer.getConnection().getClientLevel()).index()
				.near(AABB.encapsulatingFullBlocks(from, to)));
		if (!seen.equals(expected)) {
			throw new AssertionError("the client's track holds " + seen + ", not " + expected);
		}
	}
}
