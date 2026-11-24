package com.example.autotrap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "autotrap", name = "AutoTrap", version = "1.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoTrap {

    // ----- configuracoes -----
    private static final double TRAP_RANGE = 5.0;  // distancia maxima do alvo
    private static final double REACH = 4.5;       // alcance de colocacao de bloco
    private static final int DELAY_TICKS = 2;      // ticks entre cada bloco (20 ticks = 1s)
    // -------------------------

    private final KeyBinding toggleKey =
            new KeyBinding("Ligar/Desligar AutoTrap", Keyboard.KEY_G, "AutoTrap");
    private boolean enabled = false;
    private int cooldown = 0;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();

        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        "[AutoTrap] " + (enabled ? "LIGADO" : "DESLIGADO")));
            }
        }

        if (!enabled || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (cooldown > 0) { cooldown--; return; }

        EntityLivingBase target = findTarget(mc);
        if (target == null) return;

        int slot = findBlockSlot(mc.thePlayer);
        if (slot < 0) return;

        BlockPos feet = new BlockPos(
                MathHelper.floor_double(target.posX),
                MathHelper.floor_double(target.posY),
                MathHelper.floor_double(target.posZ));

        BlockPos[] ring0 = ring(feet);
        BlockPos[] ring1 = ring(feet.up());
        for (BlockPos p : ring0) if (tryPlace(mc, p, slot)) { cooldown = DELAY_TICKS; return; }
        for (BlockPos p : ring1) if (tryPlace(mc, p, slot)) { cooldown = DELAY_TICKS; return; }

        BlockPos cap = feet.up(2);
        if (isReplaceable(mc.theWorld, cap)) {
            if (tryPlace(mc, cap, slot)) { cooldown = DELAY_TICKS; return; }
            for (BlockPos p : ring(feet.up(2))) {
                if (tryPlace(mc, p, slot)) { cooldown = DELAY_TICKS; return; }
            }
        }
    }

    private BlockPos[] ring(BlockPos base) {
        return new BlockPos[]{base.north(), base.south(), base.east(), base.west()};
    }

    private EntityLivingBase findTarget(Minecraft mc) {
        EntityLivingBase best = null;
        double bestDist = TRAP_RANGE;
        for (Object o : mc.theWorld.loadedEntityList) {
            Entity e = (Entity) o;
            if (!(e instanceof EntityLivingBase) || !e.isEntityAlive()) continue;
            boolean isPlayer = e instanceof EntityPlayer && e != mc.thePlayer
                    && !((EntityPlayer) e).isSpectator();
            if (!(e instanceof IMob) && !isPlayer) continue;
            double d = mc.thePlayer.getDistanceToEntity(e);
            if (d <= bestDist) {
                bestDist = d;
                best = (EntityLivingBase) e;
            }
        }
        return best;
    }

    private int findBlockSlot(EntityPlayerSP player) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.inventory.getStackInSlot(i);
            if (s == null || !(s.getItem() instanceof ItemBlock)) continue;
            Block b = ((ItemBlock) s.getItem()).getBlock();
            if (b instanceof BlockFalling || b instanceof ITileEntityProvider) continue;
            return i;
        }
        return -1;
    }

    private boolean isReplaceable(WorldClient w, BlockPos pos) {
        return w.getBlockState(pos).getBlock().isReplaceable(w, pos);
    }

    private boolean tryPlace(Minecraft mc, BlockPos pos, int slot) {
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;

        if (!isReplaceable(w, pos)) return false;

        AxisAlignedBB box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        if (p.getEntityBoundingBox().intersectsWith(box)) return false;

        Vec3 eyes = p.getPositionEyes(1.0F);

        for (EnumFacing side : EnumFacing.values()) {
            BlockPos support = pos.offset(side);
            Block sb = w.getBlockState(support).getBlock();
            if (!sb.getMaterial().isSolid()) continue;

            EnumFacing face = side.getOpposite();
            Vec3 hit = new Vec3(
                    support.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                    support.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                    support.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
            if (eyes.distanceTo(hit) > REACH) continue;

            int prev = p.inventory.currentItem;
            p.inventory.currentItem = slot;
            boolean ok = mc.playerController.onPlayerRightClick(
                    p, w, p.inventory.getStackInSlot(slot), support, face, hit);
            if (ok) p.swingItem();
            p.inventory.currentItem = prev;
            if (ok) return true;
        }
        return false;
    }
}
