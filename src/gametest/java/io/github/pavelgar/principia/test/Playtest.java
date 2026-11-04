package io.github.pavelgar.principia.test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import org.jspecify.annotations.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Playtest scenes in a dev run: {@code /playtest} lists them and {@code /playtest <scene>} builds one beside the player
 * (see {@link Scene}). With the system property {@value #PROPERTY} set to a scene's name, as {@code scripts/play.sh
 * <scene>} sets it, the scene is built as the first player joins.
 */
public final class Playtest implements ModInitializer {
	/** The system property {@code scripts/play.sh} sets: empty for the playtest world alone, or a scene's name. */
	static final String PROPERTY = "principia.playtest";
	/** How far around a scene's anchors building it first clears the Sky-Line track a build before left. */
	private static final int MARGIN = 4;

	private static boolean builtOnJoin;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher
				.register(Commands.literal("playtest").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(command -> {
							command.getSource().sendSuccess(Playtest::list, false);
							return 1;
						})
						.then(Commands.argument("scene", StringArgumentType.word())
								.suggests((command, builder) -> SharedSuggestionProvider
										.suggest(Arrays.stream(Scene.values()).map(Scene::id), builder))
								.executes(command -> build(command.getSource(),
										StringArgumentType.getString(command, "scene"))))));
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			String id = System.getProperty(PROPERTY, "");
			if (!id.isEmpty() && !builtOnJoin) {
				builtOnJoin = true;
				ServerPlayer player = listener.getPlayer();
				Scene scene = scene(id);
				server.execute(() -> {
					if (scene == null) {
						player.sendSystemMessage(Component.literal("No scene " + id + ". " + list().getString()));
					} else {
						build(player, scene);
					}
				});
			}
		});
	}

	private static @Nullable Scene scene(String id) {
		return Arrays.stream(Scene.values()).filter(scene -> scene.id().equals(id)).findFirst().orElse(null);
	}

	private static Component list() {
		return Component.literal(Arrays.stream(Scene.values()).map(scene -> scene.id() + ": " + scene.about)
				.collect(Collectors.joining("\n", "Playtest scenes:\n", "")));
	}

	private static int build(CommandSourceStack source, String id) {
		ServerPlayer player = source.getPlayer();
		Scene scene = scene(id);
		if (player == null || scene == null) {
			source.sendFailure(Component.literal(player == null
					? "Only a player can build a scene."
					: "No scene " + id
							+ ". " + list().getString()));
			return 0;
		}
		build(player, scene);
		return 1;
	}

	/**
	 * Builds a scene from a player's feet, first clearing the Sky-Line track around it and the rails that drops, and
	 * puts the player at its start, flying, with Sky-Lines and a Sky-Hook unless they have some.
	 */
	static List<Line> build(ServerPlayer player, Scene scene) {
		ServerLevel level = player.level();
		BlockPos origin = scene.origin(player.blockPosition());
		List<Line> lines = scene.lines(origin);
		clear(level, lines);
		lines.forEach(line -> line.lay(level));
		Vec3 start = Vec3.atBottomCenterOf(origin.offset(scene.startX, scene.startY, scene.startZ));
		player.teleportTo(level, start.x, start.y, start.z, Set.of(), scene.yaw, 0, false);
		player.getAbilities().flying = player.getAbilities().mayfly;
		player.onUpdateAbilities();
		for (ItemStack tool : List.of(new ItemStack(SkyLine.RAIL, 64), new ItemStack(SkyLine.HOOK))) {
			if (!player.getInventory().contains(stack -> stack.is(tool.getItem()))) {
				player.getInventory().add(tool);
			}
		}
		player.sendSystemMessage(Component.literal("Built the " + scene.id() + " scene: " + scene.about + "."));
		return lines;
	}

	/** Removes the Sky-Line blocks a scene's lines would be built among, and the rails they drop. */
	private static void clear(ServerLevel level, List<Line> lines) {
		List<BlockPos> anchors = lines.stream().map(Line::anchors).map(Map::keySet).flatMap(Set::stream)
				.toList();
		BlockPos min = anchors.stream().reduce(anchors.getFirst(), BlockPos::min).offset(-MARGIN, -MARGIN, -MARGIN);
		BlockPos max = anchors.stream().reduce(anchors.getFirst(), BlockPos::max).offset(MARGIN, MARGIN, MARGIN);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(SkyLine.ANCHOR) || state.is(SkyLine.BEAM_END)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		level.getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(min, max).inflate(2))
				.forEach(ItemEntity::discard);
	}

	/** Every anchor a scene built and the segments it should hold, each as seen from it. */
	static Map<BlockPos, List<Segment>> anchors(List<Line> lines) {
		return lines.stream().map(Line::anchors).flatMap(anchors -> anchors.entrySet().stream())
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
