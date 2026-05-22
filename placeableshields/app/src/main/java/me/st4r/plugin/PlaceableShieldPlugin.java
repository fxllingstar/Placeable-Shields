package me.st4r.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class PlaceableShieldPlugin extends JavaPlugin implements Listener {

    private NamespacedKey shieldKey;
    private NamespacedKey linkedEntityKey;
    private final Random random = new Random();


    private final Set<UUID> placedShields = new HashSet<>();
    private final Set<UUID> placementDisabledPlayers = new HashSet<>();

    // -----------------------------------------------------------------------
    // TUNING CONSTANTS
    // -----------------------------------------------------------------------
    private static final double STAND_Y_OFFSET  = -0.8;  // ArmorStand feet relative to baseLoc
    private static final double HITBOX_Y_OFFSET =  0.1;  // Interaction entity bottom relative to baseLoc
    private static final float  HITBOX_WIDTH    =  1.8f;
    private static final float  HITBOX_HEIGHT   =  1.8f;

    // Y of the hitbox CENTER relative to the ArmorStand's feet:
    // (HITBOX_Y_OFFSET - STAND_Y_OFFSET) + HITBOX_HEIGHT/2
    // = (0.1 - (-0.8)) + 0.9 = 1.8
    private static final double HITBOX_CENTER_Y_FROM_STAND = 1.8;
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        shieldKey = new NamespacedKey(this, "placed_shield");
        linkedEntityKey = new NamespacedKey(this, "shield_linked_uuid");
        getServer().getPluginManager().registerEvents(this, this);

        // Every tick: scan for projectiles passing through any placed shield volume
        new BukkitRunnable() {
            @Override
            public void run() {
                scanProjectiles();
            }
        }.runTaskTimer(this, 0L, 1L);

        getLogger().info("----------------------------------");
        getLogger().info("PlaceableShields v1.0.0-BETA Enabled! - Built by dams");
        getLogger().info("'To become a star, you must burn.'");
        getLogger().info("----------------------------------");
    }

    
    private void scanProjectiles() {
      
        for (UUID uuid : new HashSet<>(placedShields)) {
            Entity e = Bukkit.getEntity(uuid);

            // Entity is gone (server restart, chunk unloaded, etc.) — clean up
            if (!(e instanceof ArmorStand)) {
                placedShields.remove(uuid);
                continue;
            }

            ArmorStand stand = (ArmorStand) e;

            // Center of the hitbox volume in world space
            Location center = stand.getLocation().clone()
                                   .add(0, HITBOX_CENTER_Y_FROM_STAND, 0);

            double halfW = HITBOX_WIDTH  / 2.0;
            double halfH = HITBOX_HEIGHT / 2.0;

            for (Entity nearby : center.getWorld().getNearbyEntities(center, halfW, halfH, halfW)) {
                if (!(nearby instanceof Projectile)) continue;

                // Determine damage by type
                int damage;
                if (nearby instanceof AbstractArrow || nearby instanceof Trident) {
                    damage = 5;
                } else if (nearby instanceof Fireball || nearby instanceof Explosive) {
                    damage = 10;
                } else {
                    damage = 2;
                }

                nearby.remove();
                damageShield(stand, damage);
                break; // one projectile per shield per tick is enough
            }
        }
    }

    // =======================================================================
    // PLACEMENT
    // =======================================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (!player.isSneaking()) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (placementDisabledPlayers.contains(player.getUniqueId())) return;

        ItemStack itemToPlace = null;
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand  = player.getInventory().getItemInOffHand();

        if (mainHand.getType() == Material.SHIELD) {
            itemToPlace = mainHand.clone();
            if (mainHand.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                mainHand.setAmount(mainHand.getAmount() - 1);
            }
        } else if (offHand.getType() == Material.SHIELD) {
            itemToPlace = offHand.clone();
            if (offHand.getAmount() <= 1) {
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            } else {
                offHand.setAmount(offHand.getAmount() - 1);
            }
        }

        if (itemToPlace == null) return;
        itemToPlace.setAmount(1);
        e.setCancelled(true);

        Location baseLoc;
        if (e.getClickedBlock() != null) {
            baseLoc = e.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            baseLoc = player.getLocation().add(player.getLocation().getDirection().multiply(1.5));
            baseLoc.setY(Math.floor(baseLoc.getY()) + 1.0);
        }
        baseLoc.setYaw(player.getLocation().getYaw() - 90f);

        // Collision check
        for (Entity nearby : baseLoc.getWorld().getNearbyEntities(baseLoc, 0.5, 1.0, 0.5)) {
            if (nearby instanceof ArmorStand
                    && nearby.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE)) {
                player.sendMessage(org.bukkit.ChatColor.RED + "There is already a shield here!");
                return;
            }
        }

        Location standLoc  = baseLoc.clone().add(0, STAND_Y_OFFSET,  0);
        Location hitboxLoc = baseLoc.clone().add(0, HITBOX_Y_OFFSET, 0);

        // --- ArmorStand (visual only) ---
        ArmorStand stand = (ArmorStand) standLoc.getWorld().spawnEntity(standLoc, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setSmall(false);
        stand.setMarker(true);
        stand.setCollidable(false);
        stand.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        stand.setRightArmPose(new EulerAngle(Math.toRadians(-90), 0, 0));
        stand.getEquipment().setItemInMainHand(itemToPlace);

        // --- Interaction entity (left/right click detection only) ---
        Interaction hitbox = (Interaction) hitboxLoc.getWorld().spawnEntity(hitboxLoc, EntityType.INTERACTION);
        hitbox.setInteractionWidth(HITBOX_WIDTH);
        hitbox.setInteractionHeight(HITBOX_HEIGHT);
        hitbox.setResponsive(true);

        hitbox.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        hitbox.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, stand.getUniqueId().toString());
        stand.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, hitbox.getUniqueId().toString());

        // Register in the projectile scanner
        placedShields.add(stand.getUniqueId());

        player.updateInventory();
        player.playSound(baseLoc, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("shieldplace")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
            boolean disabledNow = togglePlacement(player);
            sendToggleMessage(player, disabledNow);
            return true;
        }

        if (args[0].equalsIgnoreCase("off")) {
            placementDisabledPlayers.add(player.getUniqueId());
            sendToggleMessage(player, true);
            return true;
        }

        if (args[0].equalsIgnoreCase("on")) {
            placementDisabledPlayers.remove(player.getUniqueId());
            sendToggleMessage(player, false);
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            boolean disabled = placementDisabledPlayers.contains(player.getUniqueId());
            player.sendMessage(disabled
                    ? ChatColor.YELLOW + "Shield placement is currently disabled for you."
                    : ChatColor.GREEN + "Shield placement is currently enabled for you.");
            return true;
        }

        player.sendMessage(ChatColor.RED + "Usage: /shieldplace <on|off|toggle|status>");
        return true;
    }

    // =======================================================================
    // RIGHT-CLICK (Interaction entity)
    // =======================================================================

    @EventHandler
    public void onShieldRightClick(PlayerInteractAtEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (!clicked.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE)) return;

        ArmorStand stand = resolveLinkedArmorStand(clicked);
        if (stand == null) return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        if (p.isSneaking()) {
            giveShieldToPlayer(p, stand);
        }
    }

    // =======================================================================
    // LEFT-CLICK / MELEE (Interaction entity)
    // =======================================================================

    @EventHandler
    public void onShieldLeftClick(EntityDamageByEntityEvent e) {
        Entity target = e.getEntity();
        if (!target.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE)) return;

        ArmorStand stand = resolveLinkedArmorStand(target);
        if (stand == null) return;

        e.setCancelled(true);

        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();

        if (p.isSneaking()) {
            giveShieldToPlayer(p, stand);
        }
    }

    // =======================================================================
    // EXPLOSION DAMAGE
    // =======================================================================

    @EventHandler
    public void onShieldExplosion(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        if (!entity.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE)) return;

        ArmorStand stand = resolveLinkedArmorStand(entity);
        if (stand == null) return;

        if (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
            e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            e.setCancelled(true);
            damageShield(stand, 10);
        }
    }

    // =======================================================================
    // BLOCK BREAK
    // =======================================================================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Location above = e.getBlock().getLocation().add(0.5, 1.0, 0.5);

        for (Entity entity : above.getWorld().getNearbyEntities(above, 0.5, 1.0, 0.5)) {
            if (entity instanceof ArmorStand
                    && entity.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE)) {
                dropShieldToGround((ArmorStand) entity);
            }
        }
    }

    // =======================================================================
    // HELPERS
    // =======================================================================

    private ArmorStand resolveLinkedArmorStand(Entity entity) {
        if (entity instanceof ArmorStand) return (ArmorStand) entity;

        String uuidStr = entity.getPersistentDataContainer()
                               .get(linkedEntityKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;

        Entity linked = Bukkit.getEntity(UUID.fromString(uuidStr));
        return (linked instanceof ArmorStand) ? (ArmorStand) linked : null;
    }

    private void removeLinkedHitbox(ArmorStand stand) {
        String uuidStr = stand.getPersistentDataContainer()
                              .get(linkedEntityKey, PersistentDataType.STRING);
        if (uuidStr == null) return;

        Entity hitbox = Bukkit.getEntity(UUID.fromString(uuidStr));
        if (hitbox != null) hitbox.remove();
    }

    private void removeShield(ArmorStand stand) {
        placedShields.remove(stand.getUniqueId());
        removeLinkedHitbox(stand);
        stand.remove();
    }

    private void giveShieldToPlayer(Player p, ArmorStand stand) {
        ItemStack shield = stand.getEquipment().getItemInMainHand();
        if (shield == null || shield.getType() == Material.AIR) return;

        if (p.getInventory().getItemInOffHand().getType() == Material.AIR) {
            p.getInventory().setItemInOffHand(shield);
        } else {
            java.util.Map<Integer, ItemStack> leftovers = p.getInventory().addItem(shield);
            for (ItemStack item : leftovers.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), item);
            }
        }

        p.playSound(stand.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        removeShield(stand);
    }

    private void dropShieldToGround(ArmorStand stand) {
        ItemStack shield = stand.getEquipment().getItemInMainHand();
        if (shield != null && shield.getType() != Material.AIR) {
            stand.getWorld().dropItemNaturally(stand.getLocation(), shield);
            stand.getWorld().playSound(stand.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        }
        removeShield(stand);
    }

    private void damageShield(ArmorStand stand, int amount) {
        ItemStack shield = stand.getEquipment().getItemInMainHand();
        if (shield == null || shield.getType() != Material.SHIELD) return;

        Damageable meta       = (Damageable) shield.getItemMeta();
        int        unbreaking = meta.getEnchantLevel(Enchantment.UNBREAKING);
        int        finalDmg   = 0;

        for (int i = 0; i < amount; i++) {
            if (random.nextInt(unbreaking + 1) == 0) finalDmg++;
        }

        int newDamage = (meta.hasDamage() ? meta.getDamage() : 0) + finalDmg;

        if (newDamage >= shield.getType().getMaxDurability()) {
            stand.getWorld().playSound(stand.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            removeShield(stand);
        } else {
            meta.setDamage(newDamage);
            shield.setItemMeta(meta);
            stand.getEquipment().setItemInMainHand(shield);

            if (finalDmg > 0) {
                stand.getWorld().playSound(stand.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
            }
        }
    }

    private boolean togglePlacement(Player player) {
        UUID uuid = player.getUniqueId();
        if (placementDisabledPlayers.contains(uuid)) {
            placementDisabledPlayers.remove(uuid);
            return false;
        }

        placementDisabledPlayers.add(uuid);
        return true;
    }

    private void sendToggleMessage(Player player, boolean disabled) {
        if (disabled) {
            player.sendMessage(ChatColor.YELLOW + "Shield placement disabled. You can still use shields normally.");
        } else {
            player.sendMessage(ChatColor.GREEN + "Shield placement enabled.");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("PlaceableShields has been disabled. CYA!!!");
    }
}
