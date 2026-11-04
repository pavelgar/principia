package io.github.pavelgar.principia.client.skyline;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.pavelgar.principia.skyline.SkyHook;
import io.github.pavelgar.principia.skyline.SkyHookEntity;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

/**
 * How a Sky-Hook rider looks: the arm whose Sky-Hook grips the rail raised overhead, the held hook on the rail, the
 * body hanging upright rather than seated.
 */
public final class SkyHookPose {
	/**
	 * How far the raised arm leans in over the head, in radians: the rail is right above the rider's middle, the
	 * shoulder 5 px to the side of it. With {@link #SHRUG}, this puts the hook held along the arm (the model's
	 * third-person display: 0.85 scale, its grip in the fist) on the rail's line, its head 13 px up the model.
	 */
	private static final float LEAN = 0.35F;
	/**
	 * How far the shoulder of the arm the rider hangs from is pulled up, in px: a body hanging from one arm. Without it
	 * the fist ends 0.6 below the rail's top, too far for a hook of a held tool's size to reach.
	 */
	private static final float SHRUG = 2.5F;
	/** In first person, where the fist gripping the hook is: right of the middle, near the top of the view. */
	private static final float GRIP_X = 0.35F;
	private static final float GRIP_Y = 0.3F;
	private static final float GRIP_Z = -0.72F;
	/** In first person, how far the arm leans in from the side it comes up from, in degrees. */
	private static final float TILT = 10;
	/** How far the hook's grip sits below the end of the arm, in the fist. */
	private static final float ITEM_DROP = 0.08F;

	private SkyHookPose() {
	}

	/** Whether an entity hangs from a Sky-Hook. */
	public static boolean hangs(Entity entity) {
		return entity.getVehicle() instanceof SkyHookEntity;
	}

	/** Whether a hand of a rider holds the Sky-Hook that grips the rail: the main hand if both hold one. */
	public static boolean grips(LivingEntity entity, InteractionHand hand) {
		return hangs(entity) && SkyHook.grippingHand(entity) == hand;
	}

	/**
	 * In first person, the frame of the hand that grips the rail: at the top of the view, the arm coming up into it
	 * from below.
	 */
	public static void firstPersonGrip(PoseStack poseStack, float invert) {
		poseStack.translate(invert * GRIP_X, GRIP_Y, GRIP_Z);
		poseStack.rotateDegrees(Axis.ZP, invert * TILT);
	}

	/**
	 * From {@link #firstPersonGrip} to the frame vanilla's arm model part is drawn in, raised: the arm part hangs 12 px
	 * from its shoulder, its hand 6 px to the side of the body's middle, so its hand ends at the grip.
	 */
	public static void firstPersonArm(PoseStack poseStack, float invert) {
		poseStack.translate(invert * 6 / 16F, -12 / 16F, 0);
	}

	/** From {@link #firstPersonGrip} to where the held Sky-Hook is drawn: its grip in the fist. */
	public static void firstPersonItem(PoseStack poseStack) {
		poseStack.translate(0, -ITEM_DROP, 0);
	}

	/** Raises an arm straight overhead, leaning in over the head, its shoulder pulled up. */
	public static void raise(ModelPart arm, HumanoidArm side) {
		arm.xRot = (float) -Math.PI;
		arm.yRot = 0;
		arm.zRot = side == HumanoidArm.RIGHT ? LEAN : -LEAN;
		arm.y -= SHRUG;
	}
}
