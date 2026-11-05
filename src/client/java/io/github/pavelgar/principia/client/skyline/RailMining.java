package io.github.pavelgar.principia.client.skyline;

import java.util.Arrays;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import io.github.pavelgar.principia.skyline.BreakRailPayload;
import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.StartMiningRailPayload;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Mining a rail, on the client, as Create mines its curves: holding attack on a rail mines its whole segment, at the
 * speed the anchor block mines at (a vanilla rail's). The client tells the server as it starts ({@link
 * StartMiningRailPayload}), which times it, and once done sends the rail point for the server to
 * break ({@link BreakRailPayload}). Progress holds while the player aims anywhere on the same segment, and starts over
 * on another target or once they let go. The arm swings and the rails crack as when mining a block ({@link
 * #submitCracks}); there is no sound or particles. Creative breaks at once, with vanilla's 5 ticks between breaks.
 *
 * <p>Only the rail outlined, within the player's block reach ({@link RailTargeting#mineTarget}): a rail the Sky-Hook
 * aims at further out is attacked as the air is. Players who may not build, and items that break no blocks (a sword in
 * creative), don't mine rails, as they don't mine blocks.
 */
final class RailMining {
	/** Vanilla's ticks between one block breaking and the next starting, in survival and creative. */
	private static final int DESTROY_DELAY = 5;

	/** Vanilla's stages of cracks a block goes through as it is mined. */
	private static final int CRACK_STAGES = 10;

	private static SegmentIndex.@Nullable Held mining;
	/** The rails of the segment last cracked, built once while it is mined. */
	private static SegmentIndex.@Nullable Held crackedFor;
	private static @Nullable RailMesh cracked;
	private static float progress;
	private static int delay;
	/**
	 * Whether attack was held on a rail this client tick. Counted in client ticks, not game time: the server resets
	 * the client's game time now and then, and doesn't advance it while ticks are frozen.
	 */
	private static boolean attackedThisTick;

	private RailMining() {
	}

	/**
	 * Called every tick attack is clicked or held, before vanilla handles it; returns whether the rail aimed at takes it
	 * from vanilla.
	 */
	static boolean attack(Minecraft client, LocalPlayer player, int clicks) {
		ClientLevel level = client.level;
		RailAim.Hit target = RailTargeting.mineTarget();
		if (level == null || target == null) {
			stop();
			return false;
		}
		attackedThisTick = true;
		if (!target.segment().equals(mining)) {
			mining = target.segment();
			progress = 0;
		}
		BlockPos at = BlockPos.containing(target.point());
		BlockState anchor = SkyLine.ANCHOR.defaultBlockState();
		ItemStack held = player.getMainHandItem();
		if (client.missTime > 0 || player.isUsingItem() || !player.mayBuild()
				|| !held.canDestroyBlock(anchor, level, at, player)) {
			return true;
		}
		if (delay > 0) {
			delay--;
			return true;
		}
		player.swing(InteractionHand.MAIN_HAND, held.getAttackAnimation(), false);
		player.connection.send(ServerboundPunchPacket.INSTANCE);
		if (progress == 0) {
			// As vanilla's client tells the server it starts mining a block: the server times the break from here.
			ClientPlayNetworking.send(new StartMiningRailPayload(target.railPoint()));
		}
		progress += player.hasInfiniteMaterials() ? 1 : anchor.getDestroyProgress(player, level, at);
		if (progress >= 1) {
			ClientPlayNetworking.send(new BreakRailPayload(target.railPoint()));
			progress = 0;
			delay = DESTROY_DELAY;
		}
		return true;
	}

	/** Called as each client tick ends: progress starts over once attack isn't held on a rail for a tick. */
	static void tickEnded(Minecraft client) {
		if (!attackedThisTick) {
			stop();
		}
		attackedThisTick = false;
	}

	/**
	 * Draws the cracks of mining over the rails of the segment being mined, as vanilla draws them over a block mined: its
	 * stage of ten textures, laid over the rails' faces by vanilla's decal projection, which tiles it a block at a time.
	 */
	static void submitCracks(LevelRenderContext context) {
		SegmentIndex.Held held = mining;
		if (held == null || progress <= 0) {
			return;
		}
		RailMesh mesh = cracked;
		if (mesh == null || !held.equals(crackedFor)) {
			mesh = new RailMesh(held.anchor(), held.curve());
			cracked = mesh;
			crackedFor = held;
		}
		RailMesh drawn = mesh;
		int stage = Math.min(CRACK_STAGES - 1, (int) (progress * CRACK_STAGES));
		int[] light = new int[drawn.cells().size()];
		Arrays.fill(light, LightCoordsUtil.FULL_BRIGHT);
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(drawn.origin().getX() - camera.x, drawn.origin().getY() - camera.y,
				drawn.origin().getZ() - camera.z);
		context.submitNodeCollector().submitCustomGeometry(poseStack, ModelBakery.DESTROY_TYPES.get(stage),
				(pose, buffer) -> drawn.emit(pose, new SheetedDecalTextureGenerator(buffer, pose, 1), light));
		poseStack.popPose();
	}

	private static void stop() {
		mining = null;
		progress = 0;
	}
}
