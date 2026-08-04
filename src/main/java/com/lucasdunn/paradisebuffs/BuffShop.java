package com.lucasdunn.paradisebuffs;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BuffShop implements Listener {
    private final ParadiseBuffsPlugin plugin;
    private final FactionsKoreMobCoins mobCoins;
    private final Map<Integer, ShopOffer> offers = new LinkedHashMap<Integer, ShopOffer>();
    private boolean enabled;
    private int size;
    private String title;
    private ItemStack decoration;
    private List<Integer> decorationSlots = new ArrayList<Integer>();
    private CurrencyMode currencyMode;
    private String levelCurrencyName;
    private String pointCurrencyName;
    private String mobCoinCurrencyName;
    private boolean closeAfterPurchase;
    private int maxOfferAmount;
    private Sound purchaseSound;
    private float soundVolume;
    private float soundPitch;

    public BuffShop(ParadiseBuffsPlugin plugin) {
        this.plugin = plugin;
        this.mobCoins = new FactionsKoreMobCoins(plugin);
    }

    public void reload() {
        closeOpenShops();
        offers.clear();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("shop");
        if (config == null) {
            enabled = false;
            plugin.getLogger().warning("Missing shop config section; the buff shop is disabled.");
            return;
        }

        enabled = config.getBoolean("enabled", true);
        int rows = Math.max(1, Math.min(6, config.getInt("rows", 3)));
        size = rows * 9;
        title = plugin.color(config.getString("title", "&8Paradise Buffs"));
        if (title.length() > 32) {
            title = title.substring(0, 32);
            plugin.getLogger().warning("shop.title was longer than 32 characters and was shortened.");
        }

        String configuredCurrency = config.getString("currency", "MOBCOINS");
        try {
            currencyMode = CurrencyMode.valueOf(configuredCurrency.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException exception) {
            currencyMode = CurrencyMode.MOBCOINS;
            plugin.getLogger().warning("shop.currency must be MOBCOINS, LEVELS, or POINTS; "
                    + "using MOBCOINS.");
        }
        levelCurrencyName = plugin.color(config.getString("level-currency-name", "XP levels"));
        pointCurrencyName = plugin.color(config.getString("point-currency-name", "XP points"));
        mobCoinCurrencyName = plugin.color(config.getString(
                "mobcoin-currency-name", "MobCoins"));
        closeAfterPurchase = config.getBoolean("close-after-purchase", false);
        maxOfferAmount = Math.max(1, Math.min(36 * 64,
                config.getInt("max-offer-amount", 64)));
        loadSound(config);
        loadDecoration(config.getConfigurationSection("decoration"));
        loadOffers(config.getConfigurationSection("offers"));
        mobCoins.reload();
        if (currencyMode == CurrencyMode.MOBCOINS && !mobCoins.isAvailable()) {
            plugin.getLogger().severe("The /pb shop uses MobCoins, but its FactionsKore "
                    + "bridge is unavailable. Purchases will fail closed.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void open(Player player) {
        if (!enabled) {
            player.sendMessage(plugin.message("prefix") + plugin.message("shop-disabled"));
            return;
        }

        Inventory inventory = plugin.getServer().createInventory(new ShopHolder(), size, title);
        if (decoration != null) {
            for (Integer slot : decorationSlots) {
                if (isValidSlot(slot)) {
                    inventory.setItem(slot, decoration.clone());
                }
            }
        }
        for (Map.Entry<Integer, ShopOffer> entry : offers.entrySet()) {
            inventory.setItem(entry.getKey(), createDisplayItem(entry.getValue()));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= size) {
            return;
        }

        ShopOffer offer = offers.get(event.getRawSlot());
        if (offer != null) {
            purchase((Player) event.getWhoClicked(), offer);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder)) {
            return;
        }
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void purchase(Player player, ShopOffer offer) {
        CustomBuff buff = plugin.getBuff(offer.buffId);
        if (buff == null || !buff.isEnabled()) {
            player.sendMessage(plugin.message("prefix") + plugin.message("shop-unavailable")
                    .replace("%buff%", offer.buffId));
            return;
        }
        long balance = availableCurrency(player);
        if (balance < 0L) {
            player.sendMessage(plugin.message("prefix")
                    + plugin.message("mobcoins-unavailable"));
            return;
        }
        if (balance < offer.price) {
            player.sendMessage(placeholders(
                    plugin.message("prefix") + plugin.message("not-enough-currency"), offer, buff));
            return;
        }
        ItemStack prototype = buff.createOrb(1);
        if (!hasInventorySpace(player, prototype, offer.amount)) {
            player.sendMessage(plugin.message("prefix") + plugin.message("inventory-full"));
            return;
        }

        if (!removeCurrency(player, offer.price, balance)) {
            player.sendMessage(plugin.message("prefix")
                    + plugin.message("mobcoins-transaction-failed"));
            return;
        }
        giveOrbs(player, prototype, offer.amount);
        player.sendMessage(placeholders(
                plugin.message("prefix") + plugin.message("purchased"), offer, buff));
        playPurchaseSound(player);
        if (closeAfterPurchase) {
            player.closeInventory();
        }
    }

    private void loadDecoration(ConfigurationSection config) {
        decoration = null;
        decorationSlots = new ArrayList<Integer>();
        if (config == null || !config.getBoolean("enabled", true)) {
            return;
        }

        Material material = Material.matchMaterial(
                config.getString("material", "STAINED_GLASS_PANE"));
        if (material == null || material == Material.AIR) {
            plugin.getLogger().warning("Invalid shop.decoration.material; decoration is disabled.");
            return;
        }
        int data = Math.max(0, Math.min(Short.MAX_VALUE, config.getInt("data", 11)));
        decoration = new ItemStack(material, 1, (short) data);
        ItemMeta meta = decoration.getItemMeta();
        meta.setDisplayName(plugin.color(config.getString("name", " ")));
        List<String> lore = color(config.getStringList("lore"));
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        decoration.setItemMeta(meta);
        decorationSlots = config.getIntegerList("slots");
    }

    private void loadOffers(ConfigurationSection config) {
        if (config == null) {
            plugin.getLogger().warning("Missing shop.offers section; the shop has no offers.");
            return;
        }

        for (String key : config.getKeys(false)) {
            ConfigurationSection entry = config.getConfigurationSection(key);
            if (entry == null || !entry.getBoolean("enabled", true)) {
                continue;
            }
            int slot = entry.getInt("slot", -1);
            if (!isValidSlot(slot)) {
                plugin.getLogger().warning("Shop offer '" + key + "' has an invalid slot: " + slot);
                continue;
            }
            String buffId = entry.getString("buff", "").toLowerCase(Locale.ENGLISH);
            int amount = Math.max(1, Math.min(maxOfferAmount,
                    entry.getInt("amount", 1)));
            int price = Math.max(0, entry.getInt("price", 0));
            String name = entry.getString("name", "%buff%");
            List<String> lore = entry.getStringList("lore");
            if (offers.containsKey(slot)) {
                plugin.getLogger().warning("Multiple shop offers use slot " + slot
                        + "; '" + key + "' replaces the earlier offer.");
            }
            offers.put(slot, new ShopOffer(buffId, amount, price, name, lore));
        }
    }

    private ItemStack createDisplayItem(ShopOffer offer) {
        CustomBuff buff = plugin.getBuff(offer.buffId);
        ItemStack item = buff != null && buff.isEnabled()
                ? buff.createOrb(1) : new ItemStack(Material.MAGMA_CREAM);
        ItemMeta meta = item.getItemMeta();
        String buffName = buff == null ? offer.buffId : buff.getDisplayName();
        meta.setDisplayName(plugin.color(placeholders(offer.name, offer, buffName)));
        List<String> lore = new ArrayList<String>();
        for (String line : offer.lore) {
            lore.add(plugin.color(placeholders(line, offer, buffName)));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String placeholders(String text, ShopOffer offer, CustomBuff buff) {
        return placeholders(text, offer, buff.getDisplayName());
    }

    private String placeholders(String text, ShopOffer offer, String buffName) {
        return text.replace("%buff%", buffName)
                .replace("%buff-id%", offer.buffId)
                .replace("%amount%", String.valueOf(offer.amount))
                .replace("%price%", String.valueOf(offer.price))
                .replace("%currency%", currencyName());
    }

    private long availableCurrency(Player player) {
        if (currencyMode == CurrencyMode.MOBCOINS) {
            return mobCoins.getBalance(player);
        }
        return currencyMode == CurrencyMode.LEVELS
                ? player.getLevel() : totalExperiencePoints(player);
    }

    private boolean removeCurrency(Player player, int amount, long balanceBefore) {
        if (currencyMode == CurrencyMode.MOBCOINS) {
            return mobCoins.withdraw(player, amount, balanceBefore);
        }
        if (currencyMode == CurrencyMode.LEVELS) {
            player.setLevel(player.getLevel() - amount);
            return true;
        }

        int remaining = totalExperiencePoints(player) - amount;
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0F);
        if (remaining > 0) {
            player.giveExp(remaining);
        }
        return true;
    }

    private int totalExperiencePoints(Player player) {
        int level = player.getLevel();
        double completedLevels;
        if (level <= 16) {
            completedLevels = (double) level * level + 6.0D * level;
        } else if (level <= 31) {
            completedLevels = 2.5D * level * level - 40.5D * level + 360.0D;
        } else {
            completedLevels = 4.5D * level * level - 162.5D * level + 2220.0D;
        }

        double nextLevelCost;
        if (level <= 15) {
            nextLevelCost = 2.0D * level + 7.0D;
        } else if (level <= 30) {
            nextLevelCost = 5.0D * level - 38.0D;
        } else {
            nextLevelCost = 9.0D * level - 158.0D;
        }
        double total = completedLevels + Math.round(player.getExp() * nextLevelCost);
        if (Double.isNaN(total) || total <= 0.0D) {
            return 0;
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private boolean hasInventorySpace(Player player, ItemStack prototype, int amount) {
        int capacity = 0;
        int maximumStackSize = prototype.getMaxStackSize();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                capacity += maximumStackSize;
            } else if (item.isSimilar(prototype)) {
                capacity += Math.max(0, maximumStackSize - item.getAmount());
            }
            if (capacity >= amount) {
                return true;
            }
        }
        return false;
    }

    private void giveOrbs(Player player, ItemStack prototype, int amount) {
        int remaining = amount;
        int maximumStackSize = prototype.getMaxStackSize();
        while (remaining > 0) {
            ItemStack stack = prototype.clone();
            int stackSize = Math.min(remaining, maximumStackSize);
            stack.setAmount(stackSize);
            player.getInventory().addItem(stack);
            remaining -= stackSize;
        }
    }

    private void closeOpenShops() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory topInventory = player.getOpenInventory().getTopInventory();
            if (topInventory != null && topInventory.getHolder() instanceof ShopHolder) {
                player.closeInventory();
            }
        }
    }

    private void loadSound(ConfigurationSection config) {
        purchaseSound = null;
        if (!config.getBoolean("purchase-sound.enabled", true)) {
            return;
        }
        String name = config.getString("purchase-sound.name", "LEVEL_UP");
        try {
            purchaseSound = Sound.valueOf(name.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid shop.purchase-sound.name '" + name
                    + "'; purchase sound is disabled.");
        }
        soundVolume = finiteNonNegative(config.getDouble(
                "purchase-sound.volume", 1.0D), 1.0F,
                "shop.purchase-sound.volume");
        soundPitch = finiteNonNegative(config.getDouble(
                "purchase-sound.pitch", 1.0D), 1.0F,
                "shop.purchase-sound.pitch");
    }

    private void playPurchaseSound(Player player) {
        if (purchaseSound != null) {
            player.playSound(player.getLocation(), purchaseSound, soundVolume, soundPitch);
        }
    }

    private String currencyName() {
        if (currencyMode == CurrencyMode.MOBCOINS) {
            return mobCoinCurrencyName;
        }
        return currencyMode == CurrencyMode.LEVELS ? levelCurrencyName : pointCurrencyName;
    }

    private float finiteNonNegative(double value, float fallback, String path) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0D
                || value > Float.MAX_VALUE) {
            plugin.getLogger().warning(path + " must be a finite, non-negative number; using "
                    + fallback + ".");
            return fallback;
        }
        return (float) value;
    }

    private List<String> color(List<String> lines) {
        List<String> result = new ArrayList<String>();
        for (String line : lines) {
            result.add(plugin.color(line));
        }
        return result;
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < size;
    }

    private enum CurrencyMode {
        MOBCOINS,
        LEVELS,
        POINTS
    }

    private static final class ShopOffer {
        private final String buffId;
        private final int amount;
        private final int price;
        private final String name;
        private final List<String> lore;

        private ShopOffer(String buffId, int amount, int price,
                          String name, List<String> lore) {
            this.buffId = buffId;
            this.amount = amount;
            this.price = price;
            this.name = name;
            this.lore = new ArrayList<String>(lore);
        }
    }

    private static final class ShopHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
