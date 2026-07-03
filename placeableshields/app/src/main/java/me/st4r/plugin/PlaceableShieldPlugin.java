package me.st4r.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class PlaceableShieldPlugin extends JavaPlugin implements Listener {

    private NamespacedKey shieldKey;
    private NamespacedKey linkedEntityKey;
    private NamespacedKey ownerKey;

    private final Random random = new Random();

    private final Set<UUID> placedShields = new HashSet<>();
    private final Set<UUID> activeProjectiles = new HashSet<>();
    private final Set<UUID> placementDisabledPlayers = new HashSet<>();

    private final Map<ChunkKey, Set<UUID>> shieldChunkIndex = new HashMap<>();
    private final Map<UUID, ChunkKey> shieldToChunk = new HashMap<>();
    private final Map<UUID, Integer> playerShieldCounts = new HashMap<>();

    private BukkitTask projectileTask;
    private BukkitTask cleanupTask;

    // Config values
    private double standYOffset;
    private double hitboxYOffset;
    private float hitboxWidth;
    private float hitboxHeight;
    private double hitboxCenterYFromStand;

    private int arrowDamage;
    private int tridentDamage;
    private int fireballDamage;
    private int explosiveDamage;
    private int otherProjectileDamage;
    private int explosionDamage;

    private int projectileTickInterval;
    private int cleanupIntervalTicks;
    private int chunkSearchRadius;

    private int maxTotalShields;
    private int maxShieldsPerPlayer;

    @Override
    public void onEnable() {
        shieldKey = new NamespacedKey(this, "placed_shield");
        linkedEntityKey = new NamespacedKey(this, "shield_linked_uuid");
        ownerKey = new NamespacedKey(this, "shield_owner_uuid");

        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        loadExistingShields();
        loadExistingProjectiles();

        projectileTask = new BukkitRunnable() {
            @Override
            public void run() {
                scanActiveProjectiles();
            }
        }.runTaskTimer(this, 1L, projectileTickInterval);

        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupInvalidShields();
            }
        }.runTaskTimer(this, cleanupIntervalTicks, cleanupIntervalTicks);

        getLogger().info("----------------------------------");
        getLogger().info("PlaceableShields v1.0.0-BETA Enabled! - Built by dams");
        getLogger().info("Loaded placed shields: " + placedShields.size());
        getLogger().info("'To become a star, you must burn.'");
        getLogger().info("----------------------------------");
    }

    private void loadSettings() {
        standYOffset = getConfig().getDouble("hitbox.stand-y-offset", -0.8);
        hitboxYOffset = getConfig().getDouble("hitbox.hitbox-y-offset", 0.1);
        hitboxWidth = (float) getConfig().getDouble("hitbox.width", 1.8);
        hitboxHeight = (float) getConfig().getDouble("hitbox.height", 1.8);

        hitboxCenterYFromStand = (hitboxYOffset - standYOffset) + (hitboxHeight / 2.0);

        arrowDamage = Math.max(0, getConfig().getInt("damage.projectiles.arrow", 5));
        tridentDamage = Math.max(0, getConfig().getInt("damage.projectiles.trident", 5));
        fireballDamage = Math.max(0, getConfig().getInt("damage.projectiles.fireball", 10));
        explosiveDamage = Math.max(0, getConfig().getInt("damage.projectiles.explosive", 10));
        otherProjectileDamage = Math.max(0, getConfig().getInt("damage.projectiles.other", 2));
        explosionDamage = Math.max(0, getConfig().getInt("damage.explosion", 10));

        projectileTickInterval = Math.max(1, getConfig().getInt("performance.projectile-tick-interval", 1));
        cleanupIntervalTicks = Math.max(20, getConfig().getInt("performance.cleanup-interval-ticks", 1200));
        chunkSearchRadius = Math.max(0, getConfig().getInt("performance.chunk-search-radius", 1));

        maxTotalShields = Math.max(0, getConfig().getInt("limits.max-total-shields", 0));
        maxShieldsPerPlayer = Math.max(0, getConfig().getInt("limits.max-shields-per-player", 0));
    }

    // =======================================================================
    // STARTUP RELOADING
    // =======================================================================

    private void loadExistingShields() {
        placedShields.clear();
        shieldChunkIndex.clear();
        shieldToChunk.clear();
        playerShieldCounts.clear();

        int loaded = 0;
        int removedBroken = 0;

        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (!isShieldEntity(stand)) continue;

                ItemStack item = stand.getEquipment().getItemInMainHand();

                if (item == null || item.getType() != Material.SHIELD) {
                    removeLinkedHitbox(stand);
                    stand.remove();
                    removedBroken++;
                    continue;
                }

                applyArmorStandSettings(stand);
                ensureLinkedHitbox(stand);
                registerShield(stand);

                loaded++;
            }
        }

        cleanupOrphanedHitboxes();

        getLogger().info("Reloaded " + loaded + " placed shields.");
        if (removedBroken > 0) {
            getLogger().warning("Removed " + removedBroken + " invalid placed shield entities.");
        }
    }

    private void loadExistingProjectiles() {
        activeProjectiles.clear();

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Projectile) {
                    activeProjectiles.add(entity.getUniqueId());
                }
            }
        }
    }

    private void cleanupOrphanedHitboxes() {
        int removed = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Interaction hitbox : world.getEntitiesByClass(Interaction.class)) {
                if (!isShieldEntity(hitbox)) continue;

                ArmorStand stand = resolveLinkedArmorStand(hitbox);

                if (stand == null || !placedShields.contains(stand.getUniqueId())) {
                    hitbox.remove();
                    removed++;
                }
            }
        }

        if (removed > 0) {
            getLogger().warning("Removed " + removed + " orphaned shield hitboxes.");
        }
    }

    private void cleanupInvalidShields() {
        Iterator<UUID> iterator = placedShields.iterator();

        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Entity entity = Bukkit.getEntity(uuid);

            if (!(entity instanceof ArmorStand stand) || !stand.isValid() || stand.isDead()) {
                iterator.remove();
                unindexShield(uuid);
                continue;
            }

            ItemStack item = stand.getEquipment().getItemInMainHand();
            if (item == null || item.getType() != Material.SHIELD) {
                iterator.remove();
                unindexShield(uuid);
                removeLinkedHitbox(stand);
                stand.remove();
                continue;
            }

            ensureLinkedHitbox(stand);
            indexShield(stand);
        }
    }

    // =======================================================================
    // PROJECTILE SYSTEM
    // =======================================================================

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        activeProjectiles.add(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        activeProjectiles.remove(event.getEntity().getUniqueId());
    }

    private void scanActiveProjectiles() {
        if (activeProjectiles.isEmpty()) return;

        Iterator<UUID> iterator = activeProjectiles.iterator();

        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            Entity entity = Bukkit.getEntity(uuid);

            if (!(entity instanceof Projectile projectile) || !entity.isValid() || entity.isDead()) {
                iterator.remove();
                continue;
            }

            if (tryBlockProjectile(projectile)) {
                iterator.remove();
            }
        }
    }

    private boolean tryBlockProjectile(Projectile projectile) {
        Location projectileLoc = projectile.getLocation();
        Set<UUID> candidates = getCandidateShields(projectileLoc);

        if (candidates.isEmpty()) return false;

        ArmorStand bestStand = null;
        double bestDistance = Double.MAX_VALUE;

        for (UUID shieldUuid : candidates) {
            Entity entity = Bukkit.getEntity(shieldUuid);

            if (!(entity instanceof ArmorStand stand) || !stand.isValid() || stand.isDead()) {
                placedShields.remove(shieldUuid);
                unindexShield(shieldUuid);
                continue;
            }

            if (!projectileIntersectsShield(projectile, stand)) continue;

            double distance = stand.getLocation().distanceSquared(projectileLoc);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStand = stand;
            }
        }

        if (bestStand == null) return false;

        int damage = getProjectileDamage(projectile);

        projectile.remove();
        damageShield(bestStand, damage);

        return true;
    }

    private Set<UUID> getCandidateShields(Location location) {
        Set<UUID> result = new HashSet<>();

        if (location.getWorld() == null) return result;

        UUID worldId = location.getWorld().getUID();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        for (int x = chunkX - chunkSearchRadius; x <= chunkX + chunkSearchRadius; x++) {
            for (int z = chunkZ - chunkSearchRadius; z <= chunkZ + chunkSearchRadius; z++) {
                Set<UUID> shields = shieldChunkIndex.get(new ChunkKey(worldId, x, z));
                if (shields != null) {
                    result.addAll(shields);
                }
            }
        }

        return result;
    }

    private boolean projectileIntersectsShield(Projectile projectile, ArmorStand stand) {
        Location projectileLoc = projectile.getLocation();
        Location center = getShieldCenter(stand);

        if (projectileLoc.getWorld() == null || center.getWorld() == null) return false;
        if (!projectileLoc.getWorld().equals(center.getWorld())) return false;

        double halfW = hitboxWidth / 2.0;
        double halfH = hitboxHeight / 2.0;

        Vector min = center.toVector().subtract(new Vector(halfW, halfH, halfW));
        Vector max = center.toVector().add(new Vector(halfW, halfH, halfW));

        Vector current = projectileLoc.toVector();
        Vector previous = current.clone().subtract(projectile.getVelocity());

        return segmentIntersectsAabb(previous, current, min, max);
    }

    private boolean segmentIntersectsAabb(Vector start, Vector end, Vector min, Vector max) {
        double tMin = 0.0;
        double tMax = 1.0;

        double[] s = {start.getX(), start.getY(), start.getZ()};
        double[] e = {end.getX(), end.getY(), end.getZ()};
        double[] mn = {min.getX(), min.getY(), min.getZ()};
        double[] mx = {max.getX(), max.getY(), max.getZ()};

        for (int i = 0; i < 3; i++) {
            double direction = e[i] - s[i];

            if (Math.abs(direction) < 1.0E-9) {
                if (s[i] < mn[i] || s[i] > mx[i]) {
                    return false;
                }
            } else {
                double inverse = 1.0 / direction;
                double t1 = (mn[i] - s[i]) * inverse;
                double t2 = (mx[i] - s[i]) * inverse;

                if (t1 > t2) {
                    double temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);

                if (tMin > tMax) {
                    return false;
                }
            }
        }

        return true;
    }

    private int getProjectileDamage(Projectile projectile) {
        if (projectile instanceof Trident) return tridentDamage;
        if (projectile instanceof AbstractArrow) return arrowDamage;
        if (projectile instanceof Fireball) return fireballDamage;
        if (projectile instanceof Explosive) return explosiveDamage;

        return otherProjectileDamage;
    }

    // =======================================================================
    // PLACEMENT
    // =======================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!player.isSneaking()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (placementDisabledPlayers.contains(player.getUniqueId())) return;

        ShieldSource source = getShieldSource(player);
        if (source == ShieldSource.NONE) return;

        Location baseLoc = getPlacementLocation(player, event);
        if (baseLoc == null || baseLoc.getWorld() == null) return;

        if (hasShieldAt(baseLoc)) {
            player.sendMessage(ChatColor.RED + "There is already a shield here!");
            return;
        }

        if (maxTotalShields > 0 && placedShields.size() >= maxTotalShields) {
            player.sendMessage(ChatColor.RED + "The server has reached the maximum number of placed shields.");
            return;
        }

        if (maxShieldsPerPlayer > 0) {
            int current = playerShieldCounts.getOrDefault(player.getUniqueId(), 0);
            if (current >= maxShieldsPerPlayer) {
                player.sendMessage(ChatColor.RED + "You have reached your placed shield limit.");
                return;
            }
        }

        ItemStack itemToPlace = consumeOneShield(player, source);
        if (itemToPlace == null) return;

        event.setCancelled(true);

        spawnPlacedShield(player, baseLoc, itemToPlace);

        player.updateInventory();
        player.playSound(baseLoc, Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
    }

    private ShieldSource getShieldSource(Player player) {
        if (player.getInventory().getItemInMainHand().getType() == Material.SHIELD) {
            return ShieldSource.MAIN_HAND;
        }

        if (player.getInventory().getItemInOffHand().getType() == Material.SHIELD) {
            return ShieldSource.OFF_HAND;
        }

        return ShieldSource.NONE;
    }

    private ItemStack consumeOneShield(Player player, ShieldSource source) {
        ItemStack original;

        if (source == ShieldSource.MAIN_HAND) {
            original = player.getInventory().getItemInMainHand();
        } else if (source == ShieldSource.OFF_HAND) {
            original = player.getInventory().getItemInOffHand();
        } else {
            return null;
        }

        if (original.getType() != Material.SHIELD) return null;

        ItemStack itemToPlace = original.clone();
        itemToPlace.setAmount(1);

        if (original.getAmount() <= 1) {
            if (source == ShieldSource.MAIN_HAND) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            }
        } else {
            original.setAmount(original.getAmount() - 1);
        }

        return itemToPlace;
    }

    private Location getPlacementLocation(Player player, PlayerInteractEvent event) {
        Location baseLoc;

        if (event.getClickedBlock() != null) {
            baseLoc = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        } else {
            baseLoc = player.getLocation().add(player.getLocation().getDirection().multiply(1.5));
            baseLoc.setY(Math.floor(baseLoc.getY()) + 1.0);
        }

        baseLoc.setYaw(player.getLocation().getYaw() - 90f);
        return baseLoc;
    }

    private boolean hasShieldAt(Location baseLoc) {
        for (Entity nearby : baseLoc.getWorld().getNearbyEntities(baseLoc, 0.5, 1.0, 0.5)) {
            if (nearby instanceof ArmorStand && isShieldEntity(nearby)) {
                return true;
            }
        }

        return false;
    }

    private void spawnPlacedShield(Player player, Location baseLoc, ItemStack shieldItem) {
        Location standLoc = baseLoc.clone().add(0, standYOffset, 0);
        Location hitboxLoc = baseLoc.clone().add(0, hitboxYOffset, 0);

        ArmorStand stand = (ArmorStand) standLoc.getWorld().spawnEntity(standLoc, EntityType.ARMOR_STAND);
        applyArmorStandSettings(stand);

        stand.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getEquipment().setItemInMainHand(shieldItem);

        Interaction hitbox = spawnHitbox(hitboxLoc, stand, player.getUniqueId());

        stand.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, hitbox.getUniqueId().toString());
        hitbox.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, stand.getUniqueId().toString());

        registerShield(stand);
    }

    private void applyArmorStandSettings(ArmorStand stand) {
        stand.setPersistent(true);
        stand.setRemoveWhenFarAway(false);
        stand.setInvisible(true);
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setSmall(false);
        stand.setMarker(true);
        stand.setCollidable(false);
        stand.setRightArmPose(new EulerAngle(Math.toRadians(-90), 0, 0));
    }

    private Interaction spawnHitbox(Location location, ArmorStand stand, UUID owner) {
        Interaction hitbox = (Interaction) location.getWorld().spawnEntity(location, EntityType.INTERACTION);

        hitbox.setPersistent(true);
        hitbox.setInteractionWidth(hitboxWidth);
        hitbox.setInteractionHeight(hitboxHeight);
        hitbox.setResponsive(true);

        hitbox.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
        hitbox.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, stand.getUniqueId().toString());

        if (owner != null) {
            hitbox.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        }

        return hitbox;
    }

    private Interaction ensureLinkedHitbox(ArmorStand stand) {
        String uuidStr = stand.getPersistentDataContainer().get(linkedEntityKey, PersistentDataType.STRING);
        Entity linked = getEntityFromString(uuidStr);

        if (linked instanceof Interaction hitbox && hitbox.isValid() && !hitbox.isDead()) {
            hitbox.setPersistent(true);
            hitbox.setInteractionWidth(hitboxWidth);
            hitbox.setInteractionHeight(hitboxHeight);
            hitbox.setResponsive(true);

            hitbox.getPersistentDataContainer().set(shieldKey, PersistentDataType.BYTE, (byte) 1);
            hitbox.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, stand.getUniqueId().toString());

            UUID owner = getOwner(stand);
            if (owner != null) {
                hitbox.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            }

            return hitbox;
        }

        Location baseLoc = stand.getLocation().clone().add(0, -standYOffset, 0);
        Location hitboxLoc = baseLoc.clone().add(0, hitboxYOffset, 0);

        Interaction newHitbox = spawnHitbox(hitboxLoc, stand, getOwner(stand));
        stand.getPersistentDataContainer().set(linkedEntityKey, PersistentDataType.STRING, newHitbox.getUniqueId().toString());

        return newHitbox;
    }

    // =======================================================================
    // COMMAND
    // =======================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("shieldplace")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

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
    // RIGHT-CLICK / LEFT-CLICK
    // =======================================================================

    @EventHandler(ignoreCancelled = true)
    public void onShieldRightClick(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!isShieldEntity(clicked)) return;

        ArmorStand stand = resolveLinkedArmorStand(clicked);
        if (stand == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (player.isSneaking()) {
            giveShieldToPlayer(player, stand);
        }
    }

    @EventHandler
    public void onShieldLeftClick(EntityDamageByEntityEvent event) {
        Entity target = event.getEntity();
        if (!isShieldEntity(target)) return;

        ArmorStand stand = resolveLinkedArmorStand(target);
        if (stand == null) return;

        event.setCancelled(true);

        if (!(event.getDamager() instanceof Player player)) return;

        if (player.isSneaking()) {
            giveShieldToPlayer(player, stand);
        }
    }

    // =======================================================================
    // EXPLOSION DAMAGE
    // =======================================================================

    @EventHandler
    public void onShieldExplosion(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!isShieldEntity(entity)) return;

        ArmorStand stand = resolveLinkedArmorStand(entity);
        if (stand == null) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            event.setCancelled(true);
            damageShield(stand, explosionDamage);
        }
    }

    // =======================================================================
    // BLOCK BREAK
    // =======================================================================

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location above = event.getBlock().getLocation().add(0.5, 1.0, 0.5);

        for (Entity entity : above.getWorld().getNearbyEntities(above, 0.5, 1.0, 0.5)) {
            if (entity instanceof ArmorStand stand && isShieldEntity(entity)) {
                dropShieldToGround(stand);
            }
        }
    }

    // =======================================================================
    // SHIELD REGISTRATION / INDEXING
    // =======================================================================

    private void registerShield(ArmorStand stand) {
        UUID shieldUuid = stand.getUniqueId();

        if (placedShields.add(shieldUuid)) {
            UUID owner = getOwner(stand);
            if (owner != null) {
                playerShieldCounts.put(owner, playerShieldCounts.getOrDefault(owner, 0) + 1);
            }
        }

        indexShield(stand);
    }

    private void indexShield(ArmorStand stand) {
        UUID shieldUuid = stand.getUniqueId();

        unindexShield(shieldUuid);

        Location location = stand.getLocation();
        if (location.getWorld() == null) return;

        ChunkKey key = new ChunkKey(
                location.getWorld().getUID(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );

        shieldChunkIndex.computeIfAbsent(key, ignored -> new HashSet<>()).add(shieldUuid);
        shieldToChunk.put(shieldUuid, key);
    }

    private void unindexShield(UUID shieldUuid) {
        ChunkKey oldKey = shieldToChunk.remove(shieldUuid);
        if (oldKey == null) return;

        Set<UUID> set = shieldChunkIndex.get(oldKey);
        if (set == null) return;

        set.remove(shieldUuid);

        if (set.isEmpty()) {
            shieldChunkIndex.remove(oldKey);
        }
    }

    private void removeShield(ArmorStand stand) {
        UUID shieldUuid = stand.getUniqueId();

        if (placedShields.remove(shieldUuid)) {
            UUID owner = getOwner(stand);
            if (owner != null) {
                int current = playerShieldCounts.getOrDefault(owner, 0);
                if (current <= 1) {
                    playerShieldCounts.remove(owner);
                } else {
                    playerShieldCounts.put(owner, current - 1);
                }
            }
        }

        unindexShield(shieldUuid);
        removeLinkedHitbox(stand);
        stand.remove();
    }

    // =======================================================================
    // HELPERS
    // =======================================================================

    private boolean isShieldEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(shieldKey, PersistentDataType.BYTE);
    }

    private ArmorStand resolveLinkedArmorStand(Entity entity) {
        if (entity instanceof ArmorStand stand) return stand;

        String uuidStr = entity.getPersistentDataContainer().get(linkedEntityKey, PersistentDataType.STRING);
        Entity linked = getEntityFromString(uuidStr);

        return linked instanceof ArmorStand stand ? stand : null;
    }

    private Entity getEntityFromString(String uuidStr) {
        if (uuidStr == null) return null;

        try {
            return Bukkit.getEntity(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UUID getOwner(Entity entity) {
        String uuidStr = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (uuidStr == null) return null;

        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void removeLinkedHitbox(ArmorStand stand) {
        String uuidStr = stand.getPersistentDataContainer().get(linkedEntityKey, PersistentDataType.STRING);
        Entity hitbox = getEntityFromString(uuidStr);

        if (hitbox != null) {
            hitbox.remove();
        }
    }

    private Location getShieldCenter(ArmorStand stand) {
        return stand.getLocation().clone().add(0, hitboxCenterYFromStand, 0);
    }

    private void giveShieldToPlayer(Player player, ArmorStand stand) {
        ItemStack shield = stand.getEquipment().getItemInMainHand();
        if (shield == null || shield.getType() == Material.AIR) return;

        if (player.getInventory().getItemInOffHand().getType() == Material.AIR) {
            player.getInventory().setItemInOffHand(shield);
        } else {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(shield);

            for (ItemStack item : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }

        player.playSound(stand.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
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
        if (amount <= 0) return;

        ItemStack shield = stand.getEquipment().getItemInMainHand();
        if (shield == null || shield.getType() != Material.SHIELD) return;

        if (!(shield.getItemMeta() instanceof Damageable meta)) return;

        int unbreaking = meta.getEnchantLevel(Enchantment.UNBREAKING);
        int finalDamage = 0;

        for (int i = 0; i < amount; i++) {
            if (random.nextInt(unbreaking + 1) == 0) {
                finalDamage++;
            }
        }

        int currentDamage = meta.hasDamage() ? meta.getDamage() : 0;
        int newDamage = currentDamage + finalDamage;

        stand.getWorld().playSound(stand.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);

        if (newDamage >= shield.getType().getMaxDurability()) {
            stand.getWorld().playSound(stand.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            removeShield(stand);
            return;
        }

        meta.setDamage(newDamage);
        shield.setItemMeta(meta);
        stand.getEquipment().setItemInMainHand(shield);
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
        if (projectileTask != null) {
            projectileTask.cancel();
        }

        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        placedShields.clear();
        activeProjectiles.clear();
        shieldChunkIndex.clear();
        shieldToChunk.clear();
        playerShieldCounts.clear();

        getLogger().info("PlaceableShields has been disabled. Placed shields were left in the world for restart persistence.");
    }

    private enum ShieldSource {
        MAIN_HAND,
        OFF_HAND,
        NONE
    }

    private static final class ChunkKey {
        private final UUID worldId;
        private final int x;
        private final int z;

        private ChunkKey(UUID worldId, int x, int z) {
            this.worldId = worldId;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof ChunkKey other)) return false;

            return x == other.x &&
                    z == other.z &&
                    worldId.equals(other.worldId);
        }

        @Override
        public int hashCode() {
            int result = worldId.hashCode();
            result = 31 * result + x;
            result = 31 * result + z;
            return result;
        }
    }
  }
