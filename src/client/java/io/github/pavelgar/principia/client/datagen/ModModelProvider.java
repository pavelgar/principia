package io.github.pavelgar.principia.client.datagen;

import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;

import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;

/** Blockstates, block models and item models. Add every new block and item here, or strict datagen validation fails. */
public class ModModelProvider extends FabricModelProvider {
	public ModModelProvider(FabricPackOutput output) {
		super(output);
	}

	@Override
	public void generateBlockStateModels(BlockModelGenerators blockModelGenerators) {
		skyLineAnchor(blockModelGenerators);
		// The anchor's model draws the whole beam, ends included: an end's model has only the particles it breaks into.
		blockModelGenerators.blockStateOutput.accept(MultiVariantGenerator.dispatch(SkyLine.BEAM_END,
				BlockModelGenerators.plainVariant(Principia.id("block/sky_line_beam_end"))));
	}

	@Override
	public void generateItemModels(ItemModelGenerators itemModelGenerators) {
		itemModelGenerators.generateFlatItem(SkyLine.RAIL, ModelTemplates.FLAT_ITEM);
		// A 3D model, hand-written (in resources), with each hook its own elements so it can spin.
		itemModelGenerators.itemModelOutput.accept(SkyLine.HOOK,
				ItemModelUtils.plainModel(Principia.id("item/sky_hook")));
	}

	/**
	 * The cross-beam lies across the heading. Its two hand-written models (in {@code resources}) run along x and, with
	 * the element turned 45°, from south-west to north-east; a quarter turn of each covers the other headings.
	 */
	private static void skyLineAnchor(BlockModelGenerators generators) {
		MultiVariant straight = BlockModelGenerators.plainVariant(Principia.id("block/sky_line_anchor"));
		MultiVariant diagonal = BlockModelGenerators.plainVariant(Principia.id("block/sky_line_anchor_diagonal"));
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(SkyLine.ANCHOR)
				.with(PropertyDispatch.initial(AnchorBlock.HEADING).generate(heading -> switch (heading) {
					case SOUTH, NORTH -> straight;
					case EAST, WEST -> straight.with(BlockModelGenerators.Y_ROT_90);
					case SOUTH_EAST, NORTH_WEST -> diagonal;
					case SOUTH_WEST, NORTH_EAST -> diagonal.with(BlockModelGenerators.Y_ROT_90);
				})));
	}
}
