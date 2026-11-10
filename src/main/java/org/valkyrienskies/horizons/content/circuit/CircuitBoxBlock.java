package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.valkyrienskies.horizons.content.blocks.ElectricalBlock;
import org.valkyrienskies.horizons.content.items.DebugCableItem;
import org.valkyrienskies.horizons.content.items.pda.PDAItem;

public final class CircuitBoxBlock extends ElectricalBlock {
  private static final VoxelShape SHAPE = Shapes.or(Block.box(2, 0, 10, 14, 16, 16), Block.box(0.5, 4, 8, 2.5, 13, 15.25));

  public CircuitBoxBlock() { super(Properties.of().strength(2).noOcclusion()); }

  @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
    return new CircuitBoxBlockEntity(pos, state);
  }

  @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    return rotate(SHAPE, state);
  }

  @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
    if (player.getItemInHand(hand).getItem() instanceof DebugCableItem || player.getItemInHand(hand).getItem() instanceof PDAItem) return InteractionResult.PASS;
    if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof CircuitBoxBlockEntity box) {
      box.syncDevice();
      NetworkHooks.openScreen(server, box, pos);
    }
    return InteractionResult.sidedSuccess(level.isClientSide);
  }
}
