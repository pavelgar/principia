package io.github.pavelgar.principia.skyline;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.SectionPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The invisible hook a Sky-Hook rider hangs from: it sits on top of a rail, carries the player as its passenger, and
 * follows the rail by its {@link Ride}. The server moves it, as vanilla moves minecarts, for every rider alike: it has
 * no controlling passenger, so clients only interpolate it. It ignores collision and is placed on its path directly;
 * blocks in the rider's way are checked against the rider's body, which hangs {@link #HANG} below the rail's top.
 *
 * <p>A rider who right-clicks another rail jumps to it: the hook carries them there in a {@link Flight}, and they land
 * on it to ride on by its {@link Ride}, which it holds from the take-off.
 *
 * <p>Its ride is saved with it, so a player who logs out riding comes back hanging at the same point, at rest: the
 * point the flight was going to, if they were in one.
 */
public final class SkyHookEntity extends Entity {
	/** How far below the rail's top a rider's feet hang, for a player of normal size. */
	static final double HANG = 2.5;
	/** How much food a block boosted costs, in exhaustion: vanilla sprinting's. */
	private static final float BOOST_EXHAUSTION = 0.1F;
	/**
	 * How many ticks after a crash its thump and hurt come, so they come as the rider sees the stop: the server sends
	 * where entities moved on the tick after, while a sound or a hurt goes out at once, and a client shows a position a
	 * tick after it gets it. Measured in {@code SkyHookJumpClientGameTest}.
	 */
	private static final int CRASH_DELAY = 2;
	/** Whether the rider is boosting, as the server decides it, for their client to widen their view. */
	private static final EntityDataAccessor<Boolean> BOOSTING = SynchedEntityData.defineId(SkyHookEntity.class,
			EntityDataSerializers.BOOLEAN);
	/** Sends an optional rail point, as vanilla sends an optional block position. {@code SkyLineHooks} registers it. */
	static final EntityDataSerializer<Optional<RailPoint>> OPTIONAL_RAIL_POINT = EntityDataSerializer
			.forValueType(RailPoint.STREAM_CODEC.apply(ByteBufCodecs::optional));
	/**
	 * Where the hook rides, for clients, which have no {@link Ride}: so a rider's client knows their own line and where
	 * a jump would start. Empty while it has none to ride: in a flight, or waiting to resume.
	 */
	private static final EntityDataAccessor<Optional<RailPoint>> RAIL = SynchedEntityData
			.defineId(SkyHookEntity.class, OPTIONAL_RAIL_POINT);

	/** What the hook rides, or in a flight the ride it lands on. */
	private @Nullable Ride ride;
	/** The flight of a jump the hook is carrying its rider on, if any. */
	private @Nullable Flight flight;
	/** How many ticks of the flight the hook has flown. */
	private int flown;
	/** The damage of a crash whose thump and hurt are still to come, and in how many ticks. */
	private float crashDamage;
	private int crashIn;
	/** Where a loaded hook's ride was saved, until its segment is in the track to resume it on. */
	private @Nullable RailPoint saved;
	/** The part of a centimetre ridden that the rider's distance stat hasn't counted yet, between -0.5 and 0.5. */
	private double uncounted;

	public SkyHookEntity(EntityType<? extends SkyHookEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
	}

	/** A hook on a rail where a ride begins. */
	SkyHookEntity(Level level, Ride ride) {
		this(SkyLine.HOOK_ENTITY, level);
		this.ride = ride;
		setPos(top(ride.position()));
		setOldPosAndRot();
		entityData.set(RAIL, Optional.of(ride.point()));
	}

	/**
	 * A player's body hanging from a rail: in the way of a point of a rail's line where it would be in a block. Other
	 * entities, rails and other riders never are in the way.
	 */
	private static Ride.Body body(Level level, Player player) {
		return railPoint -> {
			Vec3 feet = top(railPoint).subtract(0, HANG * player.getScale(), 0);
			AABB body = player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
			return !level.noBlockCollision(player, body);
		};
	}

	/** The top of a rail's bar above a point of its line: where the hook sits. */
	private static Vec3 top(Vec3 railPoint) {
		return railPoint.add(0, Rail.HALF_BAR, 0);
	}

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel level) {
			ride(level);
		}
	}

	private void ride(ServerLevel level) {
		if (crashIn > 0 && --crashIn == 0 && getFirstPassenger() instanceof Player hurt) {
			crash(level, hurt, crashDamage);
		}
		// Shift lets go here, before vanilla's own dismount in the rider's tick would, so it carries the speed too.
		if (!(getFirstPassenger() instanceof Player rider) || !SkyHook.holds(rider) || rider.isShiftKeyDown()) {
			letGo();
			return;
		}
		SegmentIndex index = Track.of(level).index();
		Ride current = ride != null ? ride : resumed(level, index);
		if (current == null) {
			return;
		}
		if (!current.onTrack(index)) {
			letGo();
			return;
		}
		Flight jump = flight;
		if (jump != null) {
			fly(index, rider, jump, current);
			return;
		}
		Ride.Controls controls = rider instanceof ServerPlayer player
				? new Ride.Controls(player.getLastClientInput(), player.getLastClientMoveIntent(), canSprint(player))
				: new Ride.Controls(Input.EMPTY, Vec3.ZERO, false);
		Ride.Motion motion = current.tick(index, controls, body(level, rider));
		moveTo(current.position());
		entityData.set(RAIL, Optional.of(current.point()));
		entityData.set(BOOSTING, motion.boosting());
		if (motion.boosting()) {
			rider.causeFoodExhaustion(BOOST_EXHAUSTION * (float) motion.moved());
		}
		count(rider, motion.moved());
		float damage = Ride.impactDamage(motion.lost());
		if (damage > 0) {
			crashDamage = damage;
			crashIn = CRASH_DELAY;
		}
	}

	/**
	 * Adds a move along the rail to the rider's {@link SkyLine#DISTANCE} stat, rounded to the centimetre as vanilla
	 * rounds a minecart's move. What rounding leaves carries to the next tick, so a ride adds the distance it covered,
	 * not one rounded tick by tick. A jump's flight, off the rail, isn't counted.
	 */
	private void count(Player rider, double moved) {
		double centimetres = uncounted + moved * 100;
		int counted = (int) Math.round(centimetres);
		uncounted = centimetres - counted;
		if (counted > 0) {
			rider.awardStat(SkyLine.DISTANCE, counted);
		}
	}

	/**
	 * A tick of a flight: the hook moves on along its path, and at its end lands on the ride it holds, at the take-off
	 * velocity's part along the rail. From then its velocity is the ride's, not the flight's last move, so the rider's
	 * client, which plays the riding loop by it, hears no blip of the flight's speed. The new ride's law runs from the
	 * next tick.
	 */
	private void fly(SegmentIndex index, Player rider, Flight jump, Ride landing) {
		flown++;
		moveTo(jump.at(flown));
		if (flown < jump.ticks()) {
			return;
		}
		flight = null;
		landing.land(index, jump.takeOff());
		setDeltaMovement(landing.velocity());
		entityData.set(RAIL, Optional.of(landing.point()));
		grip(rider);
	}

	/** Puts the hook on a point of a rail's line, its velocity the move there. */
	private void moveTo(Vec3 railPoint) {
		Vec3 before = position();
		setPos(top(railPoint));
		setDeltaMovement(position().subtract(before));
	}

	/** The hook grips a rail, its rider attached or landed: the chain sound, and the rider's fall ends. */
	void grip(Player rider) {
		rider.resetFallDistance();
		level().playSound(null, getX(), getY(), getZ(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS);
	}

	/**
	 * The rider used the Sky-Hook on a rail point, as their client sends it: a jump there. Past the checks every rail
	 * use has ({@link RailTarget}), within {@link SkyHook#JUMP_REACH}, it is refused while the hook has no ride to jump
	 * from, in a flight or waiting to resume, and to the rider's own line ({@link Ride#line}). Blocks don't refuse it,
	 * nor stop the flight, even where the body would be in them: as between minecarts, where a player can aim they can
	 * jump.
	 */
	void jump(ServerPlayer rider, RailPoint target) {
		Ride from = ride;
		if (RailTarget.check(rider, target, SkyHook.JUMP_REACH) == null || from == null || flight != null) {
			return;
		}
		SegmentIndex index = Track.of(rider.level()).index();
		Ride landing = Ride.attach(index, target);
		if (landing == null || landing.isOn(Ride.line(index, from.point(), SkyHook.JUMP_REACH))) {
			return;
		}
		ride = landing;
		flight = new Flight(from.position(), landing.position(), from.velocity());
		flown = 0;
		entityData.set(RAIL, Optional.empty());
		entityData.set(BOOSTING, false);
	}

	/**
	 * Whether a player has the food to sprint, as vanilla's {@code Player.hasEnoughFoodToDoExhaustiveManoeuvres}, which
	 * isn't public, has it.
	 */
	private static boolean canSprint(Player player) {
		return player.getFoodData().hasEnoughFood() || player.getAbilities().mayfly;
	}

	/** Whether the rider is boosting, as the server last decided it. */
	public boolean boosting() {
		return entityData.get(BOOSTING);
	}

	/** Where the hook rides, as the server last sent it: null while it has no ride, in a flight or waiting to resume. */
	public @Nullable RailPoint rail() {
		return entityData.get(RAIL).orElse(null);
	}

	/**
	 * Hurts a rider who stopped at speed as an elytra crash does, with its sound, which their own client doesn't play:
	 * it doesn't know of the crash. It comes {@link #CRASH_DELAY} ticks after the stop, or at once if they let go first.
	 */
	private static void crash(ServerLevel level, Player rider, float damage) {
		if (damage <= 0) {
			return;
		}
		LivingEntity.Fallsounds sounds = rider.getFallSounds();
		level.playSound(null, rider.getX(), rider.getY(), rider.getZ(),
				(int) damage > 4 ? sounds.big() : sounds.small(),
				rider.getSoundSource(), 1, 1);
		rider.hurtServer(level, level.damageSources().flyIntoWall(), damage);
	}

	/**
	 * The saved ride, resumed once its segment is in the track. Until its anchor's chunk has loaded the segment isn't
	 * gone, only not loaded yet; once it has, an anchor that doesn't hold it means it is gone, and the rider lets go.
	 */
	private @Nullable Ride resumed(ServerLevel level, SegmentIndex index) {
		RailPoint point = saved;
		if (point == null) {
			letGo();
			return null;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(point.anchor().getX()),
				SectionPos.blockToSectionCoord(point.anchor().getZ()));
		if (chunk == null) {
			return null;
		}
		// Asking for the anchor makes its block entity if its chunk hasn't yet, which puts it in the track.
		if (!(chunk.getBlockEntity(point.anchor()) instanceof AnchorBlockEntity anchor)
				|| anchor.segment(point.side()) == null) {
			letGo();
			return null;
		}
		ride = Ride.resume(index, point);
		if (ride != null) {
			saved = null;
		}
		return ride;
	}

	/**
	 * Drops the rider where they hang, flying on at the hook's velocity, its ride's or in a flight its last move, and
	 * removes the hook, which never stays without one. The velocity is set after the dismount, whose teleport keeps the
	 * motion the client last reported, and sent to the rider's client too, as knockback is: a player's motion is their
	 * client's. It reaches the client after the dismount, which the server sends at once, so riding doesn't zero it.
	 */
	private void letGo() {
		if (crashIn > 0 && level() instanceof ServerLevel level && getFirstPassenger() instanceof Player hurt) {
			crash(level, hurt, crashDamage);
		}
		crashIn = 0;
		Flight jump = flight;
		Vec3 velocity = jump != null ? jump.velocity(flown) : ride != null ? ride.velocity() : Vec3.ZERO;
		List<Entity> riders = getPassengers();
		ejectPassengers();
		for (Entity rider : riders) {
			rider.setDeltaMovement(velocity);
			rider.syncVelocity = true;
		}
		discard();
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		double passengerScale = passenger instanceof LivingEntity living ? living.getScale() : 1;
		// The passenger stands at this point less their own vehicle attachment, 0.6 up for a player.
		return new Vec3(0, passenger.getVehicleAttachmentPoint(this).y - HANG * passengerScale, 0);
	}

	/** A rider who lets go falls from where they hang, not from on top of the hook. */
	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		return passenger.position();
	}

	/**
	 * Clients show the hook a tick behind the server, as vanilla's newer minecarts do, rather than smoothing over 3
	 * ticks as boats do, which kept the rider's view 2 ticks further behind: they saw themselves still moving after a
	 * crash.
	 */
	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return LinearInterpolationHandler.create(this, 1);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder entityData) {
		entityData.define(BOOSTING, false);
		entityData.define(RAIL, Optional.empty());
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		RailPoint point = ride != null ? ride.point() : saved;
		output.storeNullable("rail", RailPoint.CODEC, point);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		ride = null;
		saved = input.read("rail", RailPoint.CODEC).orElse(null);
	}
}
