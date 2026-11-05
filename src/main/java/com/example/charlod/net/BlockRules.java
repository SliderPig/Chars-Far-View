package com.example.charlod.net;

import com.example.charlod.core.BlockTable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

// What kind of surface a block makes. Shared by your PC and a server.
public final class BlockRules {
    private static final int GLOW_LEVEL = 12;

    private BlockRules() {}

    public static int flagsOf(BlockState state) {
        if (state.isAir()) return BlockTable.AIR;
        FluidState fluid = state.getFluidState();
        boolean glow = state.getLightEmission() >= GLOW_LEVEL;
        if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) return BlockTable.WATER;
        RenderShape shape = state.getRenderShape();
        if (!fluid.isEmpty() && shape == RenderShape.INVISIBLE) return glow || fluid.is(FluidTags.LAVA) ? BlockTable.GLOW : 0;
        if (shape == RenderShape.INVISIBLE) return BlockTable.AIR;
        if (state.is(Blocks.SNOW)) return BlockTable.SNOW_LAYER;
        boolean thin;
        try {
            thin = !state.canOcclude() && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (Throwable t) {
            thin = false;
        }
        if (thin) return BlockTable.THIN;
        int flags = glow ? BlockTable.GLOW : 0;
        if (state.is(BlockTags.LEAVES)) flags |= BlockTable.FOLIAGE;
        return flags;
    }
}
