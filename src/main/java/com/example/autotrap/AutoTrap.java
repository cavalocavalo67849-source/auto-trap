package com.example.autotrap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = "autotrap", name = "AutoTrap", version = "1.1",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class AutoTrap {

    // ----- configuracoes -----
    private static final double TRAP_RANGE = 5.0;
    private static final double REACH = 4.5;
    private static final double BUCKET_REACH = 4.5;
    private static final float AIM_TOLERANCE = 2.0F;
    private static final int DELAY_TICKS = 2;
    private static final int MAX_TICKS = 200;
    private static final boolean USE_OBSIDIAN = true;
    // -------------------------

    private static final int FAIL = 0, PLACED = 1, AIMING = 2;
    private static final EnumFacing[] SIDES = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};

    private final KeyBinding toggleKey =
            new KeyBinding("AutoTrap (1 trap)", Keyboard.KEY_G, "AutoTrap");

    private boolean active = false;
    private EntityLivingBase target;
    private int ticksLeft, cooldown, idle;
    private int obsPhase = 0;      // 0 nada, 1 falta agua, 2 falta recolher agua
    private BlockPos obsWater;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) { active = false; return; }

        if (toggleKey.isPressed()) {
            if (active) { finish(mc, "cancelada."); return; }
            if (mc.currentScreen == null) start(mc);
        }

        if (!active || mc.currentScreen != null) return;
        if (--ticksLeft <= 0) { finish(mc, "tempo esgotado."); return; }
        if (target == null || !target.isEntityAlive()
                || mc.thePlayer.getDistanceToEntity(target) > TRAP_RANGE + 2.0) {
            finish(mc, "alvo perdido.");
            return;
        }
        if (cooldown > 0) { cooldown--; return; }
        step(mc);
    }

    private void start(Minecraft mc) {
        EntityLivingBase t = findTarget(mc);
        if (t == null) { say(mc, "nenhum alvo por perto."); return; }
        target = t;
        active = true;
        ticksLeft = MAX_TICKS;
        cooldown = 0;
        idle = 0;
        obsPhase = 0;
        say(mc, "trap em " + t.getName());
    }

    private void finish(Minecraft mc, String msg) {
        active = false;
        target = null;
        obsPhase = 0;
        say(mc, msg);
    }

    private void say(Minecraft mc, String msg) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("[AutoTrap] " + msg));
        }
    }

    // ---------------------------------------------------------------
    // Logica principal
    // ---------------------------------------------------------------
    private void step(Minecraft mc) {
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;

        if (obsPhase != 0) { obsidianStep(mc); return; }

        int blockSlot = findBlockSlot(p);
        BlockPos feet = new BlockPos(
                MathHelper.floor_double(target.posX),
                MathHelper.floor_double(target.posY),
                MathHelper.floor_double(target.posZ));
        boolean pending = false;

        // 1) anel dos pes (obsidian se tiver baldes, senao bloco normal)
        for (EnumFacing dir : SIDES) {
            BlockPos pos = feet.offset(dir);
            if (!isReplaceable(w, pos)) continue;
            pending = true;
            if (tryObsidian(mc, pos, dir)) return;
            if (blockSlot >= 0 && tryPlace(mc, pos, blockSlot) != FAIL) return;
        }

        // 2) anel da cabeca
        for (EnumFacing dir : SIDES) {
            BlockPos pos = feet.up().offset(dir);
            if (!isReplaceable(w, pos)) continue;
            pending = true;
            if (blockSlot >= 0 && tryPlace(mc, pos, blockSlot) != FAIL) return;
        }

        // 3) tampa
        BlockPos cap = feet.up(2);
        if (isReplaceable(w, cap)) {
            pending = true;
            if (blockSlot >= 0) {
                if (tryPlace(mc, cap, blockSlot) != FAIL) return;
                for (EnumFacing dir : SIDES) {
                    if (tryPlace(mc, cap.offset(dir), blockSlot) != FAIL) return;
                }
            }
        }

        if (!pending) finish(mc, "trap completa!");
        else if (++idle > 20) finish(mc, "sem alcance ou sem blocos na hotbar.");
    }

    // ---------------------------------------------------------------
    // Obsidian: lava no anel, agua do lado de fora, recolhe a agua
    // ---------------------------------------------------------------
    private boolean tryObsidian(Minecraft mc, BlockPos pos, EnumFacing dir) {
        if (!USE_OBSIDIAN) return false;
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;

        int lavaSlot = findItemSlot(p, Items.lava_bucket);
        int waterSlot = findItemSlot(p, Items.water_bucket);
        if (lavaSlot < 0 || waterSlot < 0) return false;

        BlockPos ground = pos.down();
        BlockPos q = pos.offset(dir);
        if (!w.getBlockState(ground).getBlock().getMaterial().isSolid()) return false;
        if (!w.isAirBlock(q) || !w.getBlockState(q.down()).getBlock().getMaterial().isSolid()) return false;

        int r = useBucket(mc, lavaSlot, topPoint(ground), ground, EnumFacing.UP, false);
        if (r == 0) return true;   // ainda mirando
        if (r < 0) return false;   // nao da, usa bloco normal
        obsPhase = 1;
        obsWater = q;
        cooldown = DELAY_TICKS;
        return true;
    }

    private void obsidianStep(Minecraft mc) {
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;

        if (obsPhase == 1) {
            int ws = findItemSlot(p, Items.water_bucket);
            if (ws < 0) { obsPhase = 0; return; }
            BlockPos ground = obsWater.down();
            int r = useBucket(mc, ws, topPoint(ground), ground, EnumFacing.UP, false);
            if (r == 0) return;
            if (r < 0) { obsPhase = 0; return; }
            obsPhase = 2;
            cooldown = DELAY_TICKS;
            return;
        }

        if (obsPhase == 2) {
            if (w.getBlockState(obsWater).getBlock().getMaterial() != Material.water) {
                obsPhase = 0;
                return;
            }
            int bs = findItemSlot(p, Items.bucket);
            if (bs < 0) { obsPhase = 0; return; }
            Vec3 aim = new Vec3(obsWater.getX() + 0.5, obsWater.getY() + 0.9, obsWater.getZ() + 0.5);
            int r = useBucket(mc, bs, aim, obsWater, null, true);
            if (r == 0) return;
            obsPhase = 0;          // recolheu, ou nao deu (a agua fica la)
            cooldown = DELAY_TICKS;
        }
    }

    /** 0 = ainda mirando, 1 = usou o balde, -1 = impossivel (longe ou algo no caminho). */
    private int useBucket(Minecraft mc, int slot, Vec3 aim, BlockPos expect,
                          EnumFacing face, boolean liquids) {
        EntityPlayerSP p = mc.thePlayer;
        Vec3 eyes = p.getPositionEyes(1.0F);
        if (eyes.distanceTo(aim) > BUCKET_REACH - 0.1) return -1;
        if (!lookAt(p, eyes, aim)) return 0;

        // confere se o raio realmente acerta o bloco esperado (seguranca contra lava no lugar errado)
        Vec3 look = p.getLook(1.0F);
        Vec3 end = eyes.addVector(look.xCoord * BUCKET_REACH, look.yCoord * BUCKET_REACH,
                look.zCoord * BUCKET_REACH);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, end, liquids, !liquids, false);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(expect)
                || (face != null && mop.sideHit != face)) {
            return -1;
        }

        int prev = p.inventory.currentItem;
        p.inventory.currentItem = slot;
        mc.playerController.sendUseItem(p, mc.theWorld, p.inventory.getStackInSlot(slot));
        p.inventory.currentItem = prev;
        p.swingItem();
        return 1;
    }

    private Vec3 topPoint(BlockPos ground) {
        return new Vec3(ground.getX() + 0.5, ground.getY() + 0.99, ground.getZ() + 0.5);
    }

    // ---------------------------------------------------------------
    // Colocar blocos normais
    // ---------------------------------------------------------------
    private int tryPlace(Minecraft mc, BlockPos pos, int slot) {
        WorldClient w = mc.theWorld;
        EntityPlayerSP p = mc.thePlayer;

        if (!isReplaceable(w, pos)) return FAIL;

        AxisAlignedBB box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        if (p.getEntityBoundingBox().intersectsWith(box)) return FAIL;

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

            if (!lookAt(p, eyes, hit)) return AIMING;

            int prev = p.inventory.currentItem;
            p.inventory.currentItem = slot;
            boolean ok = mc.playerController.onPlayerRightClick(
                    p, w, p.inventory.getStackInSlot(slot), support, face, hit);
            p.inventory.currentItem = prev;
            if (ok) {
                p.swingItem();
                cooldown = DELAY_TICKS;
                return PLACED;
            }
        }
        return FAIL;
    }

    // ---------------------------------------------------------------
    // Utilitarios
    // ---------------------------------------------------------------
    /** Vira a camera para o ponto. Retorna true se ja estava mirando. */
    private boolean lookAt(EntityPlayerSP p, Vec3 eyes, Vec3 aim) {
        double dx = aim.xCoord - eyes.xCoord;
        double dy = aim.yCoord - eyes.yCoord;
        double dz = aim.zCoord - eyes.zCoord;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, hd) * 180.0 / Math.PI);

        float dyaw = MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw);
        float dpitch = pitch - p.rotationPitch;
        if (Math.abs(dyaw) <= AIM_TOLERANCE && Math.abs(dpitch) <= AIM_TOLERANCE) return true;

        p.rotationYaw += dyaw;
        p.rotationPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        return false;
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

    private int findItemSlot(EntityPlayerSP player, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.inventory.getStackInSlot(i);
            if (s != null && s.getItem() == item) return i;
        }
        return -1;
    }

    private boolean isReplaceable(WorldClient w, BlockPos pos) {
        return w.getBlockState(pos).getBlock().isReplaceable(w, pos);
    }
}
