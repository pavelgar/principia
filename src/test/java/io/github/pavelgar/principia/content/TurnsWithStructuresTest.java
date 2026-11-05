package io.github.pavelgar.principia.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * What a block entity a structure places loads: its saved data turned, in a copy, if it {@link TurnsWithStructures};
 * otherwise the saved data itself, whatever its id says.
 */
class TurnsWithStructuresTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A block entity that turns with structures, noting each turn it is asked for. */
	private static final class Turning extends BlockEntity implements TurnsWithStructures {
		final List<String> turns = new ArrayList<>();

		Turning() {
			super(BlockEntityTypes.CHEST, BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		}

		@Override
		public void turnSavedData(CompoundTag saved, Mirror mirror, Rotation rotation) {
			turns.add(mirror + " " + rotation);
			saved.putString("turned", mirror + " " + rotation);
		}
	}

	private static CompoundTag saved(String id) {
		CompoundTag saved = new CompoundTag();
		saved.putString("id", id);
		saved.putString("turned", "never");
		return saved;
	}

	/**
	 * A chest's data loads as the structure saved it, the same tag, in every mirror and rotation: even data claiming
	 * to be an anchor's, which only the block entity, not the id, could turn.
	 */
	@Test
	void aBlockEntityThatDoesntTurnLoadsItsDataUntouched() {
		ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				CompoundTag saved = saved("principia:sky_line_anchor");
				CompoundTag before = saved.copy();
				assertSame(saved, TurnsWithStructures.dataToLoad(chest, saved, mirror, rotation));
				assertEquals(before, saved);
			}
		}
	}

	/**
	 * A block entity that turns with structures turns a copy of its data, whatever the id saved with it, for every
	 * mirror and rotation but none at all, and the structure keeps its own data as saved.
	 */
	@Test
	void aBlockEntityThatTurnsTurnsACopyOfItsData() {
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				Turning entity = new Turning();
				CompoundTag saved = saved("minecraft:chest");
				CompoundTag loaded = TurnsWithStructures.dataToLoad(entity, saved, mirror, rotation);
				assertEquals(saved("minecraft:chest"), saved, "the structure's data");
				if (mirror == Mirror.NONE && rotation == Rotation.NONE) {
					assertSame(saved, loaded);
					assertEquals(List.of(), entity.turns);
				} else {
					assertNotSame(saved, loaded);
					assertEquals(List.of(mirror + " " + rotation), entity.turns);
					assertEquals(mirror + " " + rotation, loaded.getStringOr("turned", ""));
				}
			}
		}
	}
}
