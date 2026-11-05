package io.github.pavelgar.principia.content;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A block entity whose saved data holds offsets or directions, which turn with it when a structure places it rotated
 * or mirrored (a structure block, {@code /place}, world generation). Vanilla turns a structure's block states but
 * loads its block entities' data unchanged; {@code StructureTemplateMixin} has each block entity that implements this
 * turn its data first. A block's own {@code rotate} and {@code mirror} see only its state, and from a state alone a
 * mirror can look like a rotation.
 */
public interface TurnsWithStructures {
	/**
	 * Turns this block entity's saved data, in place, as a structure placed mirrored, then rotated, turns its block:
	 * the same as {@code StructureTemplate.transform} with these two. The data is a copy of the structure's, about to
	 * load into this block entity, and the turn is never none at all. Data that doesn't decode is left for loading to
	 * report.
	 */
	void turnSavedData(CompoundTag saved, Mirror mirror, Rotation rotation);

	/**
	 * The data a block entity a structure placed mirrored, then rotated, loads: its saved data turned, if it turns with
	 * structures, in a copy (a structure processor may hand on the structure's own); otherwise the saved data itself.
	 */
	static CompoundTag dataToLoad(BlockEntity entity, CompoundTag saved, Mirror mirror, Rotation rotation) {
		if (!(entity instanceof TurnsWithStructures turning) || (mirror == Mirror.NONE && rotation == Rotation.NONE)) {
			return saved;
		}
		CompoundTag turned = saved.copy();
		turning.turnSavedData(turned, mirror, rotation);
		return turned;
	}
}
