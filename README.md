PlaceableShields

PlaceableShields is a Paper plugin that allows players to place shields into the world as physical defensive barriers.

Instead of shields only being held by players, this plugin lets players deploy them as temporary cover. Placed shields can block projectiles, take durability damage, break naturally, and be picked back up by the player.

Built for servers that want a more tactical combat system without adding complicated custom items or heavy mechanics.

---

Features

- Place normal Minecraft shields into the world
- Shields act as physical defensive barriers
- Blocks incoming projectiles
- Damages shield durability when hit
- Supports Unbreaking enchantment durability reduction
- Shields can be picked back up
- Shields break when durability runs out
- Breaking the block below a shield drops the shield
- Works with shields from either the main hand or offhand
- Per-player placement toggle command
- Uses ArmorStand visuals with an Interaction entity hitbox
- No configuration required

---

How It Works

Players can place a shield by sneaking and right-clicking while holding a shield.

The shield is removed from the player's inventory and placed into the world as a visible shield model. Once placed, it can block projectiles that pass through its hitbox.

Placed shields are not invincible. They take durability damage depending on what hits them.

---

Controls

Place a Shield

Hold a shield in either your main hand or offhand, then:

Sneak + Right Click

If you right-click a block, the shield is placed on top of that block.

If you right-click the air, the shield is placed slightly in front of you.

---

Pick Up a Shield

To retrieve a placed shield:

Sneak + Right Click the shield

or

Sneak + Left Click the shield

The shield will be returned to your offhand if it is empty. Otherwise, it will be added to your inventory. If your inventory is full, the shield will be dropped on the ground.

---

Commands

"/shieldplace"

Toggles whether you can place shields.

/shieldplace

Same as:

/shieldplace toggle

---

"/shieldplace on"

Enables shield placement for yourself.

/shieldplace on

---

"/shieldplace off"

Disables shield placement for yourself.

/shieldplace off

This allows you to keep using shields normally without accidentally placing them.

---

"/shieldplace status"

Shows whether shield placement is currently enabled or disabled for you.

/shieldplace status

---

Projectile Blocking

Placed shields scan for nearby projectiles every tick. If a projectile enters the shield's hitbox, the projectile is removed and the shield takes durability damage.

Current projectile damage values:

Projectile Type| Durability Damage
Arrows| 5
Tridents| 5
Fireballs| 10
Explosive projectiles| 10
Other projectiles| 2

Only one projectile can damage a shield per tick.

---

Explosion Damage

Placed shields can also take damage from explosions.

Explosion Type| Durability Damage
Block explosion| 10
Entity explosion| 10

The explosion damage to the shield entity itself is cancelled, then durability damage is manually applied to the shield item.

---

Durability and Unbreaking

Placed shields use the durability of the actual shield item.

If a shield has the Unbreaking enchantment, the plugin reduces durability damage using a simple chance-based system.

For each point of incoming damage, the plugin rolls against the Unbreaking level. Higher Unbreaking levels make the shield last longer.

When the shield's durability reaches its maximum damage value, the placed shield breaks and is removed from the world.

---

Block Breaking Behavior

If the block below a placed shield is broken, the shield is dropped naturally onto the ground.

This prevents shields from floating in the air after their supporting block is removed.

---

Installation

1. Build the plugin ".jar".
2. Place the ".jar" file into your server's "plugins" folder.
3. Restart the server.
4. Make sure the command is registered in "plugin.yml".


commands:
  shieldplace:
    description: Toggle placeable shield placement.
    usage: /shieldplace <on|off|toggle|status>

---

Requirements

- Paper server
- Modern Minecraft version with Interaction entity support
- Java version compatible with your Paper version (21)
- Paper 1.21.11

---

Technical Details

Main Class

me.st4r.plugin.PlaceableShieldPlugin

The plugin extends:

JavaPlugin

and implements:

Listener

This means the plugin handles both lifecycle methods and Bukkit events in the same class.

---

Entity Structure

Each placed shield is made from two linked entities:

Entity| Purpose
"ArmorStand"| Visual shield display
"Interaction"| Clickable hitbox

The "ArmorStand" holds the shield item visually. It is invisible, has no gravity, has no base plate, and uses a custom arm pose to display the shield.

The "Interaction" entity is used as the actual clickable area for detecting player interaction.

---

Persistent Data

The plugin uses "PersistentDataContainer" to mark and link shield entities.

Used keys:

placed_shield
shield_linked_uuid

The "placed_shield" key marks an entity as part of the shield system.

The "shield_linked_uuid" key links the ArmorStand and Interaction entity together.

This allows the plugin to resolve the visual shield from the clicked hitbox entity.

---

Runtime Shield Tracking

Placed shields are tracked in memory using:

Set<UUID> placedShields

This set is used by the projectile scanner.

When a shield is removed, picked up, broken, or invalidated, its UUID is removed from the set.

Important: this runtime set is not currently rebuilt on server restart. Existing shield entities may still exist in the world because of their PersistentDataContainer data, but projectile scanning depends on the in-memory set. If full restart persistence is desired, the plugin should scan loaded worlds on startup and re-register existing shield ArmorStands.

---

Placement Toggle Tracking

Players who disable shield placement are tracked in memory using:

Set<UUID> placementDisabledPlayers

This setting is not saved to disk. It resets when the server restarts.

---

Hitbox Tuning

The shield hitbox is controlled by constants near the top of the class:

private static final double STAND_Y_OFFSET  = -0.8;
private static final double HITBOX_Y_OFFSET =  0.1;
private static final float  HITBOX_WIDTH    =  1.8f;
private static final float  HITBOX_HEIGHT   =  1.8f;
private static final double HITBOX_CENTER_Y_FROM_STAND = 1.8;

These values control the visual position of the ArmorStand and the collision volume used for projectile scanning.

The hitbox is intentionally larger than the shield item model so that projectiles are easier and more consistent to block.

---

Projectile Scanner

A repeating "BukkitRunnable" runs every tick:

.runTaskTimer(this, 0L, 1L);

For every registered placed shield, the plugin checks nearby entities inside the shield hitbox. If a projectile is found, it is removed and the shield is damaged.

This approach is simple and reliable, but performance scales with the number of placed shields. For small to medium server usage, this should be fine. For very large servers, this could be optimized with chunk-based tracking, spatial partitioning, or projectile movement checks instead of scanning every shield every tick.

---

Shield Removal

A shield can be removed in several ways:

- Player picks it up
- Shield durability reaches its limit
- Supporting block is broken
- Linked entity becomes invalid
- Shield item is missing or invalid

When a shield is removed, the plugin also removes the linked Interaction hitbox to prevent invisible leftover hitboxes.

---

Current Limitations

- Projectile detection is scan-based rather than event-based
- No ownership system
- No limit on how many shields a player can place

---

Possible Future Improvements

- Add "config.yml" for damage values and hitbox size
- Add permissions
- Add per-player placement limits
- Add world blacklist/whitelist
- Add WorldGuard support
- Add restart-safe shield reloading
- Add owner tracking
- Add shield despawn timers
- Add admin cleanup command
- Add particle effects when projectiles are blocked
- Add sound customization
- Add shield health display or action bar feedback

---

Credits

Created by dams.

«To become a star, you must burn.»