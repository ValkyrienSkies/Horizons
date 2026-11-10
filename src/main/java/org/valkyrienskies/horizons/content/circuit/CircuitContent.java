package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import java.util.List;

public final class CircuitContent {
  private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "horizons");
  private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "horizons");
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "horizons");
  private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, "horizons");
  public static final RegistryObject<Block> BOX = BLOCKS.register("circuit_box", CircuitBoxBlock::new);
  public static final RegistryObject<Item> BOX_ITEM = ITEMS.register("circuit_box", () -> new BlockItem(BOX.get(), new Item.Properties()));
  public static final RegistryObject<Item> RESISTOR = ITEMS.register("circuit_resistor", () -> new Item(new Item.Properties()));
  public static final RegistryObject<Item> CAPACITOR = ITEMS.register("circuit_capacitor", () -> new Item(new Item.Properties()));
  public static final RegistryObject<Item> NMOS = ITEMS.register("circuit_nmos", () -> new Item(new Item.Properties()));
  public static final RegistryObject<Item> AND = ITEMS.register("circuit_and_gate", () -> new Item(new Item.Properties()));
  public static final RegistryObject<Item> OR = ITEMS.register("circuit_or_gate", () -> new Item(new Item.Properties()));
  public static final RegistryObject<Item> SCHEMATIC = ITEMS.register("circuit_schematic", () -> new Item(new Item.Properties().stacksTo(1)));
  public static final RegistryObject<BlockEntityType<CircuitBoxBlockEntity>> ENTITY = ENTITIES.register("circuit_box",
      () -> BlockEntityType.Builder.of(CircuitBoxBlockEntity::new, BOX.get()).build(null));
  public static final RegistryObject<MenuType<CircuitBoxMenu>> MENU = MENUS.register("circuit_box",
      () -> IForgeMenuType.create((id, inventory, buffer) -> new CircuitBoxMenu(id, inventory, buffer.readBlockPos())));

  private CircuitContent() {}

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    bus.addListener(CircuitContent::creativeItems);
  }

  private static void creativeItems(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
      List.of(BOX_ITEM, RESISTOR, CAPACITOR, NMOS, AND, OR).forEach(item -> event.accept(item.get()));
    }
  }
}
