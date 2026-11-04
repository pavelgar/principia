package io.github.pavelgar.principia.client.skyline;

import java.util.function.BiPredicate;

import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.RailPoint;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.Ride;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import io.github.pavelgar.principia.skyline.SkyHook;
import io.github.pavelgar.principia.skyline.SkyHookEntity;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.Track;
import io.github.pavelgar.principia.skyline.UseOnRailPayload;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The rails the client's player aims at, found each time vanilla picks its target: the one they would mine, and the one
 * a right-click with the Sky-Hook would go to. A rail aimed at that is nearer than vanilla's block or entity takes the
 * crosshair, as Create's curves do: vanilla's target becomes a miss, so the block or entity behind gets no click and
 * loses its outline, and a right-click does what the held item does in the air.
 *
 * <p>Whatever the player holds, they aim at a rail's own bar; holding a Sky-Line or a Sky-Hook, at a wider box around it
 * ({@link RailAim#HELD_RADIUS}). They mine the nearest rail within their block reach, as they would a block, their own
 * too while riding. The hook goes as far as it reaches for attaching ({@link SkyHook#REACH}), or for a rider's jump
 * ({@link SkyHook#JUMP_REACH}) to any rail but their own line, and nowhere during a flight. A rail beyond vanilla's
 * block reach, which vanilla's pick doesn't look as far as, is aimed at only where the eyes see it past blocks.
 *
 * <p>A Sky-Hook in either hand takes a right-click on its target, as a minecart takes one whatever the main hand holds,
 * unless the main hand holds a Sky-Line: as when building while hanging, the Sky-Line decides.
 */
public final class RailTargeting {
	private static RailAim.@Nullable Hit mineTarget;
	private static RailAim.@Nullable Hit hookTarget;
	/** The hand whose Sky-Hook takes a right-click, if any. */
	private static @Nullable InteractionHand hookHand;

	private RailTargeting() {
	}

	/** The rail the player would mine, within their block reach: it is outlined, as a block. */
	public static RailAim.@Nullable Hit mineTarget() {
		return mineTarget;
	}

	/** The rail a right-click with the Sky-Hook would attach the player to, or jump a rider to: it is marked. */
	public static RailAim.@Nullable Hit hookTarget() {
		return hookTarget;
	}

	/** Called at the end of vanilla's pick, every frame and every tick. */
	public static void afterPick(Minecraft minecraft, float partialTicks) {
		mineTarget = null;
		hookTarget = null;
		hookHand = null;
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		Entity camera = minecraft.getCameraEntity();
		if (player == null || level == null || camera == null || player.isSpectator()) {
			return;
		}
		double radius = SkyHook.holds(player) || player.isHolding(SkyLine.RAIL)
				? RailAim.HELD_RADIUS
				: RailAim.BAR_RADIUS;
		SegmentIndex index = Track.of(level).index();
		HitResult picked = minecraft.hitResult;
		RailAim.Hit mined = aim(camera, partialTicks, picked, index, player.blockInteractionRange(), radius,
				(segment, rail) -> false);
		RailAim.Hit hooked = null;
		InteractionHand hand = hookHand(player);
		if (hand != null) {
			if (!(player.getVehicle() instanceof SkyHookEntity vehicle)) {
				hooked = aim(camera, partialTicks, picked, index, SkyHook.REACH, radius,
						(segment, rail) -> false);
			} else {
				RailPoint riding = vehicle.rail();
				if (riding != null) {
					hooked = aim(camera, partialTicks, picked, index, SkyHook.JUMP_REACH, radius,
							Ride.line(index, riding, SkyHook.JUMP_REACH));
				}
			}
		}
		mineTarget = mined;
		hookTarget = hooked;
		hookHand = hand;
		RailAim.Hit nearest = mined == null || (hooked != null && hooked.reach() < mined.reach()) ? hooked : mined;
		if (nearest != null) {
			minecraft.hitResult = BlockHitResult.miss(nearest.point(),
					Direction.getApproximateNearest(camera.getViewVector(partialTicks)),
					BlockPos.containing(nearest.point()));
			minecraft.crosshairPickEntity = null;
		}
	}

	/**
	 * The nearest rail, of those not skipped, that the look passes within a radius of, as far as a reach: none if
	 * vanilla's target is nearer, or, beyond what vanilla's pick looked at, a block hides it from the eyes.
	 */
	private static RailAim.@Nullable Hit aim(Entity camera, float partialTicks, @Nullable HitResult picked,
			SegmentIndex index, double reach, double radius, BiPredicate<SegmentIndex.Held, RailSide> skipped) {
		Vec3 eye = camera.getEyePosition(partialTicks);
		Vec3 look = camera.getViewVector(partialTicks);
		RailAim.Hit hit = RailAim.aim(index, eye, eye.add(look.scale(reach + radius)), radius, skipped);
		if (hit == null || hit.reach() > reach) {
			return null;
		}
		if (picked != null && picked.getType() != HitResult.Type.MISS) {
			return picked.getLocation().distanceTo(eye) < hit.reach() ? null : hit;
		}
		return camera.pick(hit.reach(), partialTicks, false).getType() == HitResult.Type.MISS ? hit : null;
	}

	/**
	 * The hand whose Sky-Hook takes a right-click: the main hand's, or the off hand's unless a Sky-Line is in the main.
	 */
	private static @Nullable InteractionHand hookHand(Player player) {
		ItemStack main = player.getMainHandItem();
		if (main.is(SkyLine.HOOK)) {
			return InteractionHand.MAIN_HAND;
		}
		return !main.is(SkyLine.RAIL) && player.getOffhandItem().is(SkyLine.HOOK) ? InteractionHand.OFF_HAND : null;
	}

	/**
	 * A use of the item in a hand, on the client: with a hook target, the hand whose Sky-Hook takes the right-click
	 * sends the target to the server, which attaches the player, or jumps them there if they ride, if it agrees. The
	 * main hand's use fails when the hook is in the off hand, so vanilla goes on to it without using the main hand's
	 * item.
	 */
	static InteractionResult use(InteractionHand hand) {
		RailAim.Hit aimed = hookTarget;
		InteractionHand hook = hookHand;
		if (aimed == null || hook == null) {
			return InteractionResult.PASS;
		}
		if (hand != hook) {
			return InteractionResult.FAIL;
		}
		ClientPlayNetworking.send(new UseOnRailPayload(hand, aimed.railPoint()));
		// Consumed here, with no use-item packet: the payload is the use.
		return InteractionResult.CONSUME;
	}
}
