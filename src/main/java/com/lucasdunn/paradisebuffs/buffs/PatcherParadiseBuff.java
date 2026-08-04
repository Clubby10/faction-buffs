package com.lucasdunn.paradisebuffs.buffs;

import com.lucasdunn.paradisebuffs.BuffItemAuthenticator;
import com.lucasdunn.paradisebuffs.CustomBuff;
import com.lucasdunn.paradisebuffs.ParadiseBuffsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

public final class PatcherParadiseBuff implements CustomBuff, Listener {
    private static final String ID = "patcher-paradise";
    private static final String ORB_KIND = "ORB";
    private static final String ITEM_KIND = "ITEM";

    private final ParadiseBuffsPlugin plugin;
    private final BuffItemAuthenticator authenticator;
    private final Map<UUID, PatchActivity> activity =
            new HashMap<UUID, PatchActivity>();
    private final Random random = new Random();

    private boolean enabled;
    private Material applicableMaterial;
    private String applicableItemName;
    private Material orbMaterial;
    private short orbData;
    private int requiredHits;
    private long activityWindowMillis;
    private int resistanceDurationTicks;
    private int resistanceAmplifier;
    private PotionEffectType effectType;
    private double procChancePercent;
    private double minimumDamage;
    private boolean refreshActivityWindow;
    private boolean resetHitsOnFailedProc;
    private boolean resetWindowOnSuccess;
    private boolean effectAmbient;
    private boolean effectParticles;
    private boolean overwriteExistingEffect;
    private boolean detectReplacedWater;
    private boolean detectBlockAgainstWater;
    private boolean detectPlayerFeetWater;
    private boolean detectPlayerHeadWater;
    private boolean detectAdjacentWater;
    private boolean showArmedMessage;
    private boolean showAppliedMessage;
    private boolean showActivatedMessage;
    private boolean showOrbPlaceMessage;
    private boolean showErrorMessages;
    private String buffName;
    private String orbName;
    private List<String> orbLore;
    private String appliedLore;

    public PatcherParadiseBuff(ParadiseBuffsPlugin plugin,
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
        activity.clear();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection(
                "buffs." + ID);
        if (config == null) {
            enabled = false;
            plugin.getLogger().warning("Missing config section buffs." + ID
                    + "; the buff is disabled.");
            return;
        }

        enabled = config.getBoolean("enabled", true);
        refreshActivityWindow = config.getBoolean("refresh-activity-window", true);
        resetHitsOnFailedProc = config.getBoolean("reset-hits-on-failed-proc", true);
        resetWindowOnSuccess = config.getBoolean("reset-window-on-success", true);
        effectAmbient = config.getBoolean("effect-ambient", false);
        effectParticles = config.getBoolean("effect-particles", true);
        overwriteExistingEffect = config.getBoolean("overwrite-existing-effect", true);
        detectReplacedWater = config.getBoolean("water-detection.replaced-block", true);
        detectBlockAgainstWater = config.getBoolean("water-detection.block-against", true);
        detectPlayerFeetWater = config.getBoolean("water-detection.player-feet", true);
        detectPlayerHeadWater = config.getBoolean("water-detection.player-head", true);
        detectAdjacentWater = config.getBoolean("water-detection.adjacent-blocks", true);
        String materialName = config.getString(
                "applicable-item", "DIAMOND_CHESTPLATE");
        applicableMaterial = Material.matchMaterial(materialName);
        if (applicableMaterial == null || !isArmor(applicableMaterial)) {
            applicableMaterial = Material.DIAMOND_CHESTPLATE;
            plugin.getLogger().warning("Invalid buffs." + ID + ".applicable-item '"
                    + materialName + "'. It must be armor; using DIAMOND_CHESTPLATE.");
        }
        applicableItemName = plugin.color(config.getString(
                "applicable-item-name", "Diamond Chestplate"));

        requiredHits = clamp(config.getInt("required-hits", 3), 1, 100);
        int windowSeconds = clamp(config.getInt("activity-window-seconds", 10),
                1, 300);
        activityWindowMillis = windowSeconds * 1000L;
        String durationPath = config.isSet("regeneration-duration-seconds")
                ? "regeneration-duration-seconds" : "resistance-duration-seconds";
        String levelPath = config.isSet("regeneration-level")
                ? "regeneration-level" : "resistance-level";
        if (durationPath.startsWith("regeneration") || levelPath.startsWith("regeneration")) {
            plugin.getLogger().warning("Legacy Patcher Paradise regeneration settings are "
                    + "being used for the configured effect; rename them to resistance-duration-seconds "
                    + "and resistance-level.");
        }
        int durationSeconds = clamp(config.getInt(durationPath, 5), 1, 300);
        resistanceDurationTicks = durationSeconds * 20;
        int resistanceLevel = clamp(config.getInt(levelPath, 2), 1, 10);
        resistanceAmplifier = resistanceLevel - 1;
        String effectName = config.getString("effect-type", "DAMAGE_RESISTANCE");
        effectType = PotionEffectType.getByName(effectName.toUpperCase(java.util.Locale.ENGLISH));
        if (effectType == null) {
            effectType = PotionEffectType.DAMAGE_RESISTANCE;
            plugin.getLogger().warning("Invalid buffs." + ID + ".effect-type '"
                    + effectName + "'; using DAMAGE_RESISTANCE.");
        }
        minimumDamage = config.getDouble("minimum-counted-damage", 0.01D);
        if (Double.isNaN(minimumDamage) || Double.isInfinite(minimumDamage)
                || minimumDamage < 0.0D) {
            minimumDamage = 0.01D;
            plugin.getLogger().warning("buffs." + ID
                    + ".minimum-counted-damage must be finite and non-negative; using 0.01.");
        }
        double configuredChance = config.getDouble("proc-chance-percent", 50.0D);
        if (Double.isNaN(configuredChance) || Double.isInfinite(configuredChance)) {
            procChancePercent = 50.0D;
            plugin.getLogger().warning("buffs." + ID
                    + ".proc-chance-percent must be finite; using 50.0.");
        } else {
            procChancePercent = Math.max(0.0D, Math.min(100.0D, configuredChance));
            if (configuredChance != procChancePercent) {
                plugin.getLogger().warning("buffs." + ID
                        + ".proc-chance-percent must be between 0 and 100; using "
                        + procChancePercent + ".");
            }
        }
        showArmedMessage = config.isSet("show-armed-message")
                ? config.getBoolean("show-armed-message")
                : config.getBoolean("show-enabled-message", true);
        showAppliedMessage = config.getBoolean("show-applied-message", true);
        showActivatedMessage = config.getBoolean("show-activated-message", true);
        showOrbPlaceMessage = config.getBoolean("show-orb-place-message", true);
        showErrorMessages = config.getBoolean("show-error-messages", true);

        buffName = plugin.color(config.getString("name", "&dPatcher Paradise"));
        String orbMaterialName = config.getString("orb-material", "BRICK");
        orbMaterial = Material.matchMaterial(orbMaterialName);
        if (orbMaterial == null || orbMaterial == Material.AIR) {
            orbMaterial = Material.BRICK;
            plugin.getLogger().warning("Invalid buffs." + ID + ".orb-material '"
                    + orbMaterialName + "'; using BRICK.");
        }
        orbData = (short) clamp(config.getInt("orb-data", 0),
                0, Short.MAX_VALUE);
        orbName = plugin.color(config.getString(
                "orb-name", "&dPatcher Paradise Buff Orb"));
        orbLore = new ArrayList<String>();
        List<String> configuredLore = config.getStringList("orb-lore");
        if (configuredLore.isEmpty()) {
            configuredLore.add("&7> &fAfter %hits% Hits: &e%chance%%% &b%effect% %level% &7for &b%duration%s");
        }
        for (String line : configuredLore) {
            orbLore.add(format(line));
        }
        appliedLore = format(config.getString("applied-lore",
                "&dPatcher Paradise &7(%hits% hits for Resistance %level%)"));
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled || !isWearingBuffedArmor(event.getPlayer())) {
            return;
        }
        if (!isWaterPlacement(event)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        PatchActivity current = activity.get(playerId);
        long now = System.currentTimeMillis();
        boolean newlyArmed = current == null || current.expiresAt < now;
        if (!newlyArmed && !refreshActivityWindow) {
            return;
        }
        int hits = newlyArmed ? 0 : current.hits;
        activity.put(playerId, new PatchActivity(
                now + activityWindowMillis, hits));
        if (showArmedMessage && newlyArmed) {
            event.getPlayer().sendMessage(format(plugin.message("prefix")
                    + plugin.message("patcher-enabled")));
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!enabled || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (event.getFinalDamage() < minimumDamage) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PatchActivity current = activity.get(playerId);
        if (current == null) {
            return;
        }
        if (current.expiresAt < System.currentTimeMillis()
                || !isWearingBuffedArmor(player)) {
            activity.remove(playerId);
            return;
        }

        current.hits++;
        if (current.hits < requiredHits) {
            return;
        }

        if (random.nextDouble() * 100.0D >= procChancePercent) {
            if (resetHitsOnFailedProc) {
                current.hits = 0;
            }
            return;
        }

        if (resetWindowOnSuccess) {
            activity.remove(playerId);
        } else {
            current.hits = 0;
        }
        player.addPotionEffect(new PotionEffect(effectType,
                resistanceDurationTicks, resistanceAmplifier,
                effectAmbient, effectParticles), overwriteExistingEffect);
        if (showActivatedMessage) {
            player.sendMessage(format(plugin.message("prefix")
                    + plugin.message("patcher-resistance-activated")));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activity.remove(event.getPlayer().getUniqueId());
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

    private boolean isWaterPlacement(BlockPlaceEvent event) {
        if ((detectReplacedWater && isWater(event.getBlockReplacedState().getType()))
                || (detectBlockAgainstWater && event.getBlockAgainst() != null
                && isWater(event.getBlockAgainst().getType()))) {
            return true;
        }
        Player player = event.getPlayer();
        if ((detectPlayerFeetWater && isWater(player.getLocation().getBlock().getType()))
                || (detectPlayerHeadWater
                && isWater(player.getEyeLocation().getBlock().getType()))) {
            return true;
        }
        if (!detectAdjacentWater) {
            return false;
        }
        Block placed = event.getBlockPlaced();
        BlockFace[] faces = {
                BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        };
        for (BlockFace face : faces) {
            if (isWater(placed.getRelative(face).getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean isWater(Material material) {
        return material == Material.WATER || material == Material.STATIONARY_WATER;
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
        int durationSeconds = resistanceDurationTicks / 20;
        int windowSeconds = (int) (activityWindowMillis / 1000L);
        return plugin.color(value
                .replace("%buff%", ChatColor.stripColor(buffName))
                .replace("%hits%", String.valueOf(requiredHits))
                .replace("%window%", String.valueOf(windowSeconds))
                .replace("%duration%", String.valueOf(durationSeconds))
                .replace("%level%", String.valueOf(resistanceAmplifier + 1))
                .replace("%effect%", effectType.getName().replace('_', ' '))
                .replace("%chance%", formatNumber(procChancePercent)));
    }

    private String formatNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((int) value) : String.valueOf(value);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class PatchActivity {
        private final long expiresAt;
        private int hits;

        private PatchActivity(long expiresAt, int hits) {
            this.expiresAt = expiresAt;
            this.hits = hits;
        }
    }
}
