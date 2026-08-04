package com.lucasdunn.paradisebuffs.buffs;

import com.lucasdunn.paradisebuffs.BuffItemAuthenticator;
import com.lucasdunn.paradisebuffs.CustomBuff;
import com.lucasdunn.paradisebuffs.ParadiseBuffsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Grants the attacker a configurable Speed effect after enough successful PvP hits. */
public final class SwingersRushBuff implements CustomBuff, Listener {
    private static final String ID = "swingers-rush";
    private static final String ORB_KIND = "ORB";
    private static final String ITEM_KIND = "ITEM";

    private final ParadiseBuffsPlugin plugin;
    private final BuffItemAuthenticator authenticator;
    private final Map<UUID, Integer> hitCounts = new HashMap<UUID, Integer>();
    private final Random random = new Random();

    private boolean enabled;
    private Material applicableMaterial;
    private String applicableItemName;
    private Material orbMaterial;
    private short orbData;
    private int requiredHits;
    private int speedDurationTicks;
    private int speedAmplifier;
    private double procChancePercent;
    private double minimumDamage;
    private boolean resetHitsOnFailedProc;
    private boolean effectAmbient;
    private boolean effectParticles;
    private boolean overwriteExistingEffect;
    private boolean showAppliedMessage;
    private boolean showActivatedMessage;
    private boolean showOrbPlaceMessage;
    private boolean showErrorMessages;
    private String buffName;
    private String orbName;
    private List<String> orbLore;
    private String appliedLore;

    public SwingersRushBuff(ParadiseBuffsPlugin plugin,
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
        hitCounts.clear();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("buffs." + ID);
        if (config == null) {
            enabled = false;
            plugin.getLogger().warning("Missing config section buffs." + ID
                    + "; the buff is disabled.");
            return;
        }

        enabled = config.getBoolean("enabled", true);
        resetHitsOnFailedProc = config.getBoolean("reset-hits-on-failed-proc", true);
        effectAmbient = config.getBoolean("effect-ambient", false);
        effectParticles = config.getBoolean("effect-particles", true);
        overwriteExistingEffect = config.getBoolean("overwrite-existing-effect", true);
        showAppliedMessage = config.getBoolean("messages.applied", true);
        showActivatedMessage = config.getBoolean("messages.activated", true);
        showOrbPlaceMessage = config.getBoolean("messages.orb-place", true);
        showErrorMessages = config.getBoolean("messages.errors", true);

        String materialName = config.getString("applicable-item", "DIAMOND_CHESTPLATE");
        applicableMaterial = Material.matchMaterial(materialName);
        if (applicableMaterial == null || !isArmor(applicableMaterial)) {
            applicableMaterial = Material.DIAMOND_CHESTPLATE;
            plugin.getLogger().warning("Invalid buffs." + ID + ".applicable-item '"
                    + materialName + "'. It must be armor; using DIAMOND_CHESTPLATE.");
        }
        applicableItemName = plugin.color(config.getString(
                "applicable-item-name", "Diamond Chestplate"));

        requiredHits = clamp(config.getInt("required-hits", 3), 1, 100);
        int durationSeconds = clamp(config.getInt("speed-duration-seconds", 5), 1, 300);
        speedDurationTicks = durationSeconds * 20;
        int speedLevel = clamp(config.getInt("speed-level", 3), 1, 10);
        speedAmplifier = speedLevel - 1;
        minimumDamage = config.getDouble("minimum-counted-damage", 0.01D);
        if (!isFinite(minimumDamage) || minimumDamage < 0.0D) {
            minimumDamage = 0.01D;
            plugin.getLogger().warning("buffs." + ID
                    + ".minimum-counted-damage must be finite and non-negative; using 0.01.");
        }
        double configuredChance = config.getDouble("proc-chance-percent", 50.0D);
        if (!isFinite(configuredChance)) {
            configuredChance = 50.0D;
            plugin.getLogger().warning("buffs." + ID
                    + ".proc-chance-percent must be finite; using 50.0.");
        }
        procChancePercent = Math.max(0.0D, Math.min(100.0D, configuredChance));
        if (configuredChance != procChancePercent) {
            plugin.getLogger().warning("buffs." + ID
                    + ".proc-chance-percent must be between 0 and 100; using "
                    + procChancePercent + ".");
        }

        buffName = plugin.color(config.getString("name", "&e&lSWINGERS RUSH"));
        String orbMaterialName = config.getString("orb-material", "FEATHER");
        orbMaterial = Material.matchMaterial(orbMaterialName);
        if (orbMaterial == null || orbMaterial == Material.AIR) {
            orbMaterial = Material.FEATHER;
            plugin.getLogger().warning("Invalid buffs." + ID + ".orb-material '"
                    + orbMaterialName + "'; using FEATHER.");
        }
        orbData = (short) clamp(config.getInt("orb-data", 0), 0, Short.MAX_VALUE);
        orbName = plugin.color(config.getString(
                "orb-name", "&e&lSWINGERS RUSH &7Buff Orb"));
        orbLore = new ArrayList<String>();
        List<String> configuredLore = config.getStringList("orb-lore");
        if (configuredLore.isEmpty()) {
            configuredLore.add("&7> &fAfter %hits% Hits: &e%chance%%% &fSpeed %level% &7for &e%duration%s");
        }
        for (String line : configuredLore) {
            orbLore.add(format(line));
        }
        appliedLore = format(config.getString("applied-lore",
                "&e&lSWINGERS RUSH &7(&e%chance%%% Speed %level% Chance&7)"));
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
        if (!isBuffOrb(orb) || armor == null || armor.getType() == Material.AIR
                || !(event.getWhoClicked() instanceof Player)) {
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
        meta.setLore(organizedAppliedLore(meta,
                authenticator.createMarker(ID, ITEM_KIND, result.getType())));
        result.setItemMeta(meta);
        event.setCurrentItem(result);
        consumeOneOrb(player, orb);
        if (showAppliedMessage) {
            player.sendMessage(plugin.message("prefix") + plugin.message("applied")
                    .replace("%buff%", buffName));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!enabled || !(event.getDamager() instanceof Player)
                || !(event.getEntity() instanceof Player)
                || event.getFinalDamage() < minimumDamage) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        UUID attackerId = attacker.getUniqueId();
        if (!isWearingBuffedArmor(attacker)) {
            hitCounts.remove(attackerId);
            return;
        }

        int hits = hitCounts.containsKey(attackerId) ? hitCounts.get(attackerId) + 1 : 1;
        if (hits < requiredHits) {
            hitCounts.put(attackerId, hits);
            return;
        }

        if (random.nextDouble() * 100.0D >= procChancePercent) {
            hitCounts.put(attackerId, resetHitsOnFailedProc ? 0 : requiredHits);
            return;
        }

        hitCounts.remove(attackerId);
        attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                speedDurationTicks, speedAmplifier, effectAmbient, effectParticles),
                overwriteExistingEffect);
        if (showActivatedMessage) {
            attacker.sendMessage(format(plugin.message("prefix")
                    + plugin.message("rush-speed-activated")));
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hitCounts.remove(event.getPlayer().getUniqueId());
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

    private boolean isWearingBuffedArmor(Player player) {
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() == applicableMaterial && hasBuff(armor)) {
                return true;
            }
        }
        return false;
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

    private boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    private String format(String value) {
        return plugin.color(value
                .replace("%buff%", ChatColor.stripColor(buffName))
                .replace("%hits%", String.valueOf(requiredHits))
                .replace("%duration%", String.valueOf(speedDurationTicks / 20))
                .replace("%level%", String.valueOf(speedAmplifier + 1))
                .replace("%chance%", formatNumber(procChancePercent)));
    }

    private String formatNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value) : String.valueOf(value);
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
