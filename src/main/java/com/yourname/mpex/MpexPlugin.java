package com.yourname.mpex;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class MpexPlugin extends JavaPlugin implements Listener {

    private static final int    SLIDE_TICKS    = 30;   // スライド持続時間（30tick = 1.5秒）
    private static final double SLIDE_SPEED    = 0.9;  // スライド初速（ブロック/tick）
    private static final float  RUN_SPEED      = 0.3f; // デフォルト0.2fの1.5倍
    private static final double JUMP_STRENGTH  = 0.52; // 約1.5ブロックの高さ
    private static final double STEP_HEIGHT    = 1.5;  // ブロック+ハーフブロックを自動登り
    private static final int    BOW_DRAW_TICKS = 5;    // 弓のチャージ時間（5tick = 0.25秒）
    private static final double ARROW_SPEED    = 3.0;  // 矢の速度

    private final Map<UUID, BukkitTask> slidingPlayers = new HashMap<>();
    private final Map<UUID, BukkitTask> bowPlayers     = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getOnlinePlayers().forEach(this::applyAttributes);
        getLogger().info("Mpex が有効になりました！");
    }

    @Override
    public void onDisable() {
        slidingPlayers.values().forEach(BukkitTask::cancel);
        slidingPlayers.clear();
        bowPlayers.values().forEach(BukkitTask::cancel);
        bowPlayers.clear();
        getLogger().info("Mpex が無効になりました");
    }

    // ── 属性設定 ──────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyAttributes(event.getPlayer());
    }

    private void applyAttributes(Player player) {
        player.setWalkSpeed(RUN_SPEED);
        Objects.requireNonNull(player.getAttribute(Attribute.JUMP_STRENGTH))
               .setBaseValue(JUMP_STRENGTH);
        Objects.requireNonNull(player.getAttribute(Attribute.STEP_HEIGHT))
               .setBaseValue(STEP_HEIGHT);
    }

    // ── スライディング ────────────────────────────────────

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) return;
        if (!player.isSprinting()) return;

        float yaw = player.getLocation().getYaw();
        double radians = Math.toRadians(yaw);
        Vector slideDir = new Vector(-Math.sin(radians), 0, Math.cos(radians));

        cancelSlide(player);

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !player.isSneaking() || tick >= SLIDE_TICKS) {
                    cancelSlide(player);
                    cancel();
                    return;
                }
                double progress = 1.0 - (double) tick / SLIDE_TICKS;
                Vector newVelocity = slideDir.clone().multiply(SLIDE_SPEED * progress);
                newVelocity.setY(player.getVelocity().getY());
                player.setVelocity(newVelocity);
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L);

        slidingPlayers.put(player.getUniqueId(), task);
    }

    private void cancelSlide(Player player) {
        BukkitTask task = slidingPlayers.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    // ── 弓の連射 ──────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.BOW) return;

        cancelBow(player);

        BukkitTask task = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.getActiveItem().getType() != Material.BOW) {
                    cancelBow(player);
                    cancel();
                    return;
                }
                tick++;
                if (tick >= BOW_DRAW_TICKS) {
                    tick = 0;
                    fireArrow(player);
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        bowPlayers.put(player.getUniqueId(), task);
    }

    @EventHandler
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (bowPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true); // タスク側で発射するため二重撃ちを防ぐ
        }
    }

    private void fireArrow(Player player) {
        ItemStack bow = player.getInventory().getItemInMainHand();
        if (bow.getType() != Material.BOW) return;

        boolean hasInfinity = bow.getEnchantmentLevel(Enchantment.INFINITY) > 0;
        if (!hasInfinity && !consumeArrow(player)) return;

        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setVelocity(player.getEyeLocation().getDirection().multiply(ARROW_SPEED));
        arrow.setCritical(true);
        arrow.setPickupStatus(hasInfinity ? AbstractArrow.PickupStatus.CREATIVE_ONLY : AbstractArrow.PickupStatus.ALLOWED);
    }

    private boolean consumeArrow(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.ARROW) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    player.getInventory().remove(item);
                }
                return true;
            }
        }
        return false;
    }

    private void cancelBow(Player player) {
        BukkitTask task = bowPlayers.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }
}
