package io.github.pavelgar.principia.client.skyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.LayTrackPayload;
import io.github.pavelgar.principia.skyline.Placement;
import io.github.pavelgar.principia.skyline.Rail;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.SegmentCurve;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.TrackStart;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Laying track on the client, as Create previews its track. While the player holds a Sky-Line with an anchor
 * selected, every tick it works out what a click on the block they aim at would lay ({@link Placement#plan}, the
 * server's own checks) and shows it: a ghost of the segment, green if the click lays it and red and dashed if not, why
 * not on the action bar, the rails it takes in a slot above the hotbar, and marks on the faces around a new
 * anchor's spot ({@link FaceMarks}). The selected anchor's beam stays outlined. A click then sends that block for the
 * server to lay ({@link LayTrackPayload}). With nothing selected, an anchor aimed at is outlined white if it can be
 * selected and red if not.
 */
public final class TrackLaying {
	/** Create's colours for track that can be placed, and can't. */
	static final int CAN = 0xFF95CD41;
	static final int CANT = ARGB.opaque(Placement.CANT_COLOR);
	/** How opaque the outline of a new anchor's beam is along another heading that works. */
	private static final float OTHER_HEADING_OPACITY = 0.4F;
	/** How far the ghost's colour moves toward its target each tick: an exponential chaser, as Create's. */
	private static final float CHASE = 0.25F;
	/** The length of each piece of a ghost's line: a dash, or a gap between two, when it is dashed. */
	private static final double STEP = 0.25;
	/** A slot on its own, as vanilla frames the off hand's beside the hotbar: 22 × 22 px, at the left of its 29 × 24. */
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("hud/hotbar_offhand_left");
	/** How far the cost slot's bottom sits above the screen's: clear of the hotbar and the experience level. */
	private static final int SLOT_BOTTOM = 36;

	private static @Nullable Preview preview;
	/** The heading the player chose for a new anchor, and where: it holds only there. */
	private static @Nullable Heading chosen;
	private static @Nullable Spot chosenAt;
	/** Whether the action bar was last told why a click can't lay. */
	private static boolean saidWhy;
	/** How far the ghost's colour has come from red (0) to green (1), this tick and the one before. */
	private static float green;
	private static float greenBefore;

	private TrackLaying() {
	}

	/**
	 * What a click would lay from the selected anchor, worked out this tick.
	 *
	 * @param target the block aimed at, if the player aims at one
	 */
	private record Preview(InteractionHand hand, TrackStart start, @Nullable BlockHitResult target,
			@Nullable Heading chosen, Placement.Plan plan, List<Heading> work) {
	}

	/** Where a heading was chosen: the block aimed at, its face, and the selection. */
	private record Spot(BlockPos pos, Direction face, TrackStart start) {
	}

	/** What a click would lay from the selected anchor, as the preview shows it this tick; null with none selected. */
	public static Placement.@Nullable Plan plan() {
		Preview shown = preview;
		return shown == null ? null : shown.plan();
	}

	/** The hand whose Sky-Line lays track: the main hand if both hold one, as vanilla tries it first. */
	static @Nullable InteractionHand hand(Player player) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).is(SkyLine.RAIL)) {
				return hand;
			}
		}
		return null;
	}

	/** Called as each client tick ends, once vanilla has picked what the player aims at. */
	static void tick(Minecraft minecraft) {
		boolean ghostBefore = preview != null && preview.plan() instanceof Placement.Plan.Laying;
		preview = preview(minecraft);
		if (preview == null) {
			FaceMarks.clear();
			if (saidWhy) {
				// The selection or the Sky-Line has gone: so has the reason given for it.
				minecraft.gui.hud.setOverlayMessage(Component.empty(), false);
				saidWhy = false;
			}
			return;
		}
		Component why = why(preview.plan());
		if (why != null || saidWhy) {
			// Once the reason is gone, so is what was said of it.
			minecraft.gui.hud.setOverlayMessage(why != null ? why : Component.empty(), false);
		}
		saidWhy = why != null;
		if (preview.plan() instanceof Placement.Plan.Laying laying) {
			float target = laying.why() == null ? 1 : 0;
			greenBefore = ghostBefore ? green : target;
			green = ghostBefore ? green + (target - green) * CHASE : target;
		}
		LocalPlayer player = minecraft.player;
		if (player != null) {
			FaceMarks.update(player, preview.hand(), preview.start(), preview.target(), preview.plan());
		}
	}

	private static @Nullable Preview preview(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator() || Placement.hanging(player)) {
			return null;
		}
		InteractionHand hand = hand(player);
		TrackStart start = hand == null ? null : TrackStart.of(player);
		if (hand == null || start == null) {
			return null;
		}
		// A rail aimed at, or an entity, has taken vanilla's target: there is nothing to lay to.
		BlockHitResult target = minecraft.hitResult instanceof BlockHitResult block
				&& block.getType() == HitResult.Type.BLOCK ? block : null;
		if (target == null || !new Spot(target.getBlockPos(), target.getDirection(), start).equals(chosenAt)) {
			chosen = null;
			chosenAt = null;
		}
		Placement.Plan plan = target == null
				? new Placement.Plan.Nothing()
				: Placement.plan(player, hand, start, target, chosen);
		List<Heading> work = target != null && plan instanceof Placement.Plan.Laying laying && laying.toNewAnchor()
				? Placement.headingsThatWork(player, hand, start, target)
				: List.of();
		return new Preview(hand, start, target, chosen, plan, work);
	}

	/** Why a click can't lay or select, for the action bar; null when the preview says it all. */
	private static @Nullable Component why(Placement.Plan plan) {
		return switch (plan) {
			case Placement.Plan.Nothing() -> null;
			case Placement.Plan.Refused refused -> refused.why();
			case Placement.Plan.Laying laying -> laying.why();
		};
	}

	/**
	 * A use of the item in a hand on a block, on the client, before vanilla's: with an anchor selected and the player
	 * not sneaking, the click sends what the preview shows, and vanilla sends nothing of its own, nor tries the other
	 * hand. The arm swings if the click lays something, as vanilla's does when it predicts a block is placed. Placing,
	 * selecting and a sneak-use stay on vanilla's path.
	 */
	static InteractionResult use(Player player, InteractionHand hand, BlockHitResult hit) {
		ItemStack held = player.getItemInHand(hand);
		TrackStart start = TrackStart.of(player);
		if (!held.is(SkyLine.RAIL) || start == null || player.isSecondaryUseActive() || Placement.hanging(player)) {
			return InteractionResult.PASS;
		}
		// The preview was worked out as the last tick ended, and the view may have turned since, onto another block:
		// the click lays what the player saw. Before the selection's first preview, what it hits now.
		Preview shown = preview;
		if (shown == null || shown.hand() != hand || !shown.start().equals(start)) {
			shown = new Preview(hand, start, hit, null, Placement.plan(player, hand, start, hit), List.of());
		}
		BlockHitResult target = shown.target();
		if (target != null) {
			if (shown.plan() instanceof Placement.Plan.Laying laying && laying.why() == null) {
				player.swing(hand, held.getInteractAnimation(), false);
			}
			ClientPlayNetworking.send(new LayTrackPayload(hand, target, Optional.ofNullable(shown.chosen())));
		}
		// Fabric API sends vanilla's use-item-on packet for a result that consumes the action; FAIL sends nothing.
		return InteractionResult.FAIL;
	}

	/**
	 * The mouse wheel turned in game, before it moves the hotbar's selection: while the player sneaks and aims at a new
	 * anchor's spot, it steps the anchor's heading through those that work there instead, up the wheel against the
	 * clock, down with it, and does nothing where only one does. The choice holds while they aim at that spot. Returns
	 * whether it took the turn.
	 */
	public static boolean scroll(double wheel) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Preview shown = preview;
		BlockHitResult target = shown == null ? null : shown.target();
		if (player == null || !player.isShiftKeyDown() || shown == null || target == null
				|| !(shown.plan() instanceof Placement.Plan.Laying laying) || !laying.toNewAnchor()) {
			return false;
		}
		List<Heading> work = shown.work();
		if (work.size() > 1) {
			int step = wheel > 0 ? -1 : 1;
			int at = work.indexOf(laying.segment().to().tangent().heading());
			// From a heading that doesn't work, the first step down is the first that does, up the last.
			int from = at >= 0 ? at : step > 0 ? -1 : 0;
			chosen = work.get(Math.floorMod(from + step, work.size()));
			chosenAt = new Spot(target.getBlockPos(), target.getDirection(), shown.start());
		}
		return true;
	}

	/**
	 * The colour of an anchor's outline, aimed at holding a Sky-Line with nothing selected: white if it has a free side
	 * to lay from, red if not. Null otherwise, for vanilla's colour.
	 */
	static @Nullable Integer startOutline(Player player, BlockPos anchor) {
		InteractionHand hand = player.isSpectator() || Placement.hanging(player) ? null : hand(player);
		if (hand == null || TrackStart.of(player) != null) {
			return null;
		}
		return player.level().getBlockEntity(anchor) instanceof AnchorBlockEntity entity
				&& entity.segments().size() < 2 ? ARGB.white(1F) : CANT;
	}

	/**
	 * Draws the outline of the selected anchor's beam, white, and the ghost of the segment a click would lay: a line
	 * along each of its rails, and the outline of a new anchor's beam at its far end, in a colour easing between red and
	 * green; dashed if the click can't lay it. A new anchor's beam is outlined faintly too along each other heading that
	 * works there, which the wheel steps to.
	 */
	static void submit(LevelRenderContext context) {
		Preview shown = preview;
		if (shown == null) {
			return;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		float width = context.gameRenderer().gameRenderState().windowRenderState.appropriateLineWidth;
		ClientLevel level = Minecraft.getInstance().level;
		BlockState selected = level == null ? null : level.getBlockState(shown.start().pos());
		if (selected != null && selected.is(SkyLine.ANCHOR)) {
			Vec3 origin = Vec3.atLowerCornerOf(shown.start().pos()).subtract(camera);
			BeamOutline.submitBox(context, AnchorBlock.beamCorners(selected).stream().map(origin::add).toList(),
					RenderTypes.linesTranslucent(), ARGB.white(1F), width);
		}
		if (!(shown.plan() instanceof Placement.Plan.Laying laying)) {
			return;
		}
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		int color = ARGB.srgbLerp(Mth.lerp(partialTick, greenBefore, green), CANT, CAN);
		SegmentCurve curve = laying.segment().curve(laying.start());
		List<Vec3[]> lines = new ArrayList<>();
		for (RailSide side : RailSide.values()) {
			Rail rail = curve.rail(side);
			int pieces = Math.max(1, (int) Math.ceil(rail.length() / STEP));
			for (int i = 0; i < pieces; i += laying.why() == null ? 1 : 2) {
				lines.add(new Vec3[]{rail.point(rail.length() * i / pieces).subtract(camera),
						rail.point(rail.length() * (i + 1) / pieces).subtract(camera)});
			}
		}
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.linesTranslucent(),
				(pose, buffer) -> lines.forEach(line -> RailHighlight.line(pose, buffer, line[0], line[1], width,
						color)));
		if (laying.toNewAnchor()) {
			Heading own = laying.segment().to().tangent().heading();
			BeamOutline.submitBox(context, beamCorners(laying.end(), own, camera), RenderTypes.linesTranslucent(),
					color,
					width);
			// Faint, the beam the anchor would have along each other heading that works, which the wheel steps to.
			int faint = ARGB.multiplyAlpha(CAN, OTHER_HEADING_OPACITY);
			for (Heading other : shown.work()) {
				if (other != own) {
					BeamOutline.submitBox(context, beamCorners(laying.end(), other, camera),
							RenderTypes.linesTranslucent(), faint, width);
				}
			}
		}
		FaceMarks.submit(context, camera);
	}

	/** The corners of the beam of a new anchor in a cell, heading a way, relative to the camera. */
	private static List<Vec3> beamCorners(BlockPos pos, Heading heading, Vec3 camera) {
		Vec3 origin = Vec3.atLowerCornerOf(pos).subtract(camera);
		return AnchorBlock.beamCorners(SkyLine.ANCHOR.defaultBlockState().setValue(AnchorBlock.HEADING, heading))
				.stream().map(origin::add).toList();
	}

	/**
	 * Draws, in survival, the rails a click would take, while a ghost shows: a slot centred above the hotbar with a
	 * Sky-Line and their number, white if the player has as many, gold if not. The most a segment takes is 33, a stack.
	 */
	static void extractCost(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Preview shown = preview;
		if (player == null || player.hasInfiniteMaterials() || shown == null
				|| !(shown.plan() instanceof Placement.Plan.Laying laying)) {
			return;
		}
		int x = graphics.guiWidth() / 2 - 11;
		int y = graphics.guiHeight() - SLOT_BOTTOM - 22;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y - 1, 29, 24);
		graphics.item(new ItemStack(SkyLine.RAIL), x + 3, y + 3);
		String count = String.valueOf(laying.cost());
		boolean enough = player.getInventory().countItem(SkyLine.RAIL) >= laying.cost();
		int color = enough ? ARGB.white(1F) : ARGB.opaque(TextColor.GOLD.getValue());
		// Where vanilla draws a stack's count over its slot.
		graphics.text(minecraft.font, count, x + 3 + 19 - 2 - minecraft.font.width(count), y + 3 + 9, color, true);
	}
}
