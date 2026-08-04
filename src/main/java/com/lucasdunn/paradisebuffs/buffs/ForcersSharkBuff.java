package com.lucasdunn.paradisebuffs.buffs;

import com.lucasdunn.paradisebuffs.BuffItemAuthenticator;
import com.lucasdunn.paradisebuffs.CustomBuff;
import com.lucasdunn.paradisebuffs.ParadiseBuffsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.List;

public final class ForcersSharkBuff implements CustomBuff, Listener {
    private static final String ID = "forcers-shark";
    private static final String ORB_KIND = "ORB";
    private static final String ITEM_KIND = "ITEM";
    private static final String PROJECTILE_MARKER =
            "com.lucasdunn.paradisebuffs.forcers-shark.bonus";
    private static final double DEFAULT_DAMAGE_PERCENT = 25.0D;
    private static final double MAX_DAMAGE_PERCENT = 1000.0D;

    private final ParadiseBuffsPlugin plugin;
    private final BuffItemAuthenticator authenticator;
    private boolean enabled;
    private Material applicableMaterial;
    private String applicableItemName;
    private Material orbMaterial;
    private short orbData;
    private double damagePercent;
    private boolean meleeEnabled;
    private boolean projectilesEnabled;
    private boolean detectWaterAtFeet;
    private boolean detectWaterAtHead;
    private boolean showAppliedMessage;
    private boolean showErrorMessages;
    private boolean showOrbPlaceMessage;
    private String buffName;
    private String orbName;
    private List<String> orbLore;
    private String appliedLore;

    public ForcersSharkBuff(ParadiseBuffsPlugin plugin,
                            BuffItemAuthenticator authenticator) {
        this.plugin = plugin;
        this.authenticator = authenticator;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return buffName;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void reload() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection(
                "buffs." + ID);
        if (config == null) {
            enabled = false;
            plugin.getLogger().warning("Missing config section buffs." + ID
                    + "; the buff is disabled.");
            return;
        }

        enabled = config.getBoolean("enabled", true);
        meleeEnabled = config.getBoolean("melee-enabled", true);
        projectilesEnabled = config.getBoolean("projectiles-enabled", true);
        detectWaterAtFeet = config.getBoolean("water-detection.feet", true);
        detectWaterAtHead = config.getBoolean("water-detection.head", true);
        showAppliedMessage = config.getBoolean("messages.applied", true);
        showErrorMessages = config.getBoolean("messages.errors", true);
        showOrbPlaceMessage = config.getBoolean("messages.orb-place", true);
        String materialName = config.getString("applicable-item", "DIAMOND_CHESTPLATE");
        applicableMaterial = Material.matchMaterial(materialName);
        if (applicableMaterial == null || !isArmor(applicableMaterial)) {
            applicableMaterial = Material.DIAMOND_CHESTPLATE;
            plugin.getLogger().warning("Invalid buffs." + ID + ".applicable-item '"
                    + materialName + "'. It must be armor; using DIAMOND_CHESTPLATE.");
        }
        applicableItemName = plugin.color(config.getString(
                "applicable-item-name", "Diamond Chestplate"));

        double configuredPercent = config.getDouble("damage-percent", DEFAULT_DAMAGE_PERCENT);
        if (Double.isNaN(configuredPercent) || Double.isInfinite(configuredPercent)) {
            damagePercent = DEFAULT_DAMAGE_PERCENT;
            plugin.getLogger().warning("buffs." + ID + ".damage-percent must be finite; using "
                    + DEFAULT_DAMAGE_PERCENT + ".");
        } else {
            damagePercent = Math.max(0.0D, Math.min(MAX_DAMAGE_PERCENT, configuredPercent));
            if (configuredPercent != damagePercent) {
                plugin.getLogger().warning("buffs." + ID
                        + ".damage-percent must be between 0 and " + MAX_DAMAGE_PERCENT
                        + "; using " + damagePercent + ".");
            }
        }

        buffName = plugin.color(config.getString("name", "&bForcers Shark"));
        String orbMaterialName = config.getString("orb-material", "MAGMA_CREAM");
        orbMaterial = Material.matchMaterial(orbMaterialName);
        if (orbMaterial == null || orbMaterial == Material.AIR) {
            orbMaterial = Material.MAGMA_CREAM;
            plugin.getLogger().warning("Invalid buffs." + ID + ".orb-material '"
                    + orbMaterialName + "'; using MAGMA_CREAM.");
        }
        orbData = (short) Math.max(0, Math.min(Short.MAX_VALUE,
                config.getInt("orb-data", 0)));
        orbName = plugin.color(config.getString(
                "orb-name", "&bForcers Shark Buff Orb"));
        orbLore = new ArrayList<String>();
        List<String> configuredOrbLore = config.getStringList("orb-lore");
        if (configuredOrbLore.isEmpty()) {
            configuredOrbLore.add("&7Deals &b%percent%% &7more damage while in water.");
        }
        for (String line : configuredOrbLore) {
            orbLore.add(format(line));
        }
        appliedLore = format(config.getString(
                "applied-lore", "&bForcers Shark &7(+%percent%% in water)"));
    }

    @Override
    public ItemStack createOrb(int amount) {
        ItemStack orb = new ItemStack(orbMaterial, amount, orbData);
        ItemMeta meta = orb.getItemMeta();
        meta.setDisplayName(orbName);
        List<String> lore = new ArrayList<String>(orbLore);
        authenticator.attachMarker(lore,
                authenticator.createMarker(ID, ORB_KIND, orb.getType()));
        meta.setLore(lore);
        orb.setItemMeta(meta);
        return orb;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOrbApply(InventoryClickEvent event) {
        if (!enabled) {
            return;
        }
        ItemStack orb = event.getCursor();
        ItemStack armor = event.getCurrentItem();
        if (!isBuffOrb(orb) || armor == null || armor.getType() == Material.AIR) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!(event.getClickedInventory() instanceof PlayerInventory)
                || event.getClickedInventory().getHolder() != player) {
            return;
        }

        ClickType click = event.getClick();
        if ((click != ClickType.LEFT && click != ClickType.RIGHT)
                || event.getAction() != InventoryAction.SWAP_WITH_CURSOR) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        if (armor.getType() != applicableMaterial) {
            if (showErrorMessages) {
                player.sendMessage(plugin.message("prefix") + plugin.message("wrong-item")
                        .replace("%item%", applicableItemName));
            }
            return;
        }
        if (hasBuff(armor)) {
            if (showErrorMessages) {
                player.sendMessage(plugin.message("prefix") + plugin.message("already-buffed")
                        .replace("%buff%", buffName));
            }
            return;
        }

        ItemStack result = armor.clone();
        ItemMeta meta = result.getItemMeta();
        List<String> lore = organizedAppliedLore(meta,
                authenticator.createMarker(ID, ITEM_KIND, result.getType()));
        meta.setLore(lore);
        result.setItemMeta(meta);
        event.setCurrentItem(result);
        consumeOneOrb(player, orb);
        if (showAppliedMessage) {
            player.sendMessage(plugin.message("prefix") + plugin.message("applied")
                    .replace("%buff%", buffName));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getDamager() instanceof Arrow) {
            if (projectilesEnabled && hasOwnedProjectileMarker(event.getDamager())) {
                applyBonus(event);
            }
            return;
        }

        Player attacker = event.getDamager() instanceof Player
                ? (Player) event.getDamager() : null;
        if (meleeEnabled && attacker != null && isInWater(attacker)
                && isWearingBuffedArmor(attacker)) {
            applyBonus(event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!enabled || !projectilesEnabled || !(event.getEntity() instanceof Arrow)) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (shooter instanceof Player) {
            Player player = (Player) shooter;
            if (isInWater(player) && isWearingBuffedArmor(player)) {
                event.getEntity().setMetadata(
                        PROJECTILE_MARKER, new FixedMetadataValue(plugin, true));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOrbPlace(BlockPlaceEvent event) {
        if (!enabled || !isBuffOrb(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        if (showOrbPlaceMessage) {
            event.getPlayer().sendMessage(plugin.message("prefix")
                    + plugin.message("orb-cannot-place"));
        }
    }

    private void consumeOneOrb(Player player, ItemStack orb) {
        if (orb.getAmount() <= 1) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
        } else {
            ItemStack remaining = orb.clone();
            remaining.setAmount(orb.getAmount() - 1);
            player.setItemOnCursor(remaining);
        }
    }

    private void applyBonus(EntityDamageByEntityEvent event) {
        event.setDamage(event.getDamage() * (1.0D + damagePercent / 100.0D));
    }

    private boolean hasOwnedProjectileMarker(Entity entity) {
        for (MetadataValue value : entity.getMetadata(PROJECTILE_MARKER)) {
            if (value.getOwningPlugin() == plugin && value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private boolean isInWater(LivingEntity entity) {
        Block feet = entity.getLocation().getBlock();
        Block head = entity.getEyeLocation().getBlock();
        return (detectWaterAtFeet && isWater(feet.getType()))
                || (detectWaterAtHead && isWater(head.getType()));
    }

    private boolean isWater(Material material) {
        return material == Material.WATER || material == Material.STATIONARY_WATER;
    }

    private boolean isWearingBuffedArmor(Player player) {
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() == applicableMaterial && hasBuff(armor)) {
                return true;
            }
        }
        return false;
    }

    private boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    private boolean isBuffOrb(ItemStack item) {
        return item != null && item.getType() == orbMaterial
                && item.getDurability() == orbData
                && authenticator.hasValidMarker(item, ID, ORB_KIND);
    }

    private boolean hasBuff(ItemStack item) {
        return authenticator.hasValidMarker(item, ID, ITEM_KIND);
    }

    private List<String> organizedAppliedLore(ItemMeta meta, String newMarker) {
        List<String> lore = meta.hasLore()
                ? authenticator.normalizeLore(meta.getLore()) : new ArrayList<String>();
        lore.add(appliedLore + newMarker);
        return lore;
    }

    private String format(String value) {
        String percent = damagePercent == Math.rint(damagePercent)
                ? String.valueOf((int) damagePercent) : String.valueOf(damagePercent);
        return plugin.color(value.replace("%percent%", percent)
                .replace("%buff%", ChatColor.stripColor(buffName)));
    }
}
