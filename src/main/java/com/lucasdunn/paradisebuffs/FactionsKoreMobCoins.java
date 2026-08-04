package com.lucasdunn.paradisebuffs;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fail-closed bridge to FactionsKore's MobCoins through its documented
 * PlaceholderAPI expansion and administrative take command.
 */
public final class FactionsKoreMobCoins {
    private static final Pattern INTEGER = Pattern.compile("-?\\d[\\d,]*");

    private final ParadiseBuffsPlugin plugin;
    private Method placeholderMethod;
    private String balancePlaceholder;
    private String takeCommand;
    private String factionsKorePluginName;
    private boolean enabled;

    public FactionsKoreMobCoins(ParadiseBuffsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        placeholderMethod = null;
        ConfigurationSection config = plugin.getConfig().getConfigurationSection(
                "integrations.factionskore-mobcoins");
        if (config == null) {
            enabled = false;
            plugin.getLogger().severe("Missing integrations.factionskore-mobcoins; "
                    + "MobCoin purchases are disabled.");
            return;
        }

        enabled = config.getBoolean("enabled", true);
        factionsKorePluginName = config.getString("plugin-name", "FactionsKore");
        balancePlaceholder = config.getString(
                "balance-placeholder", "%kore_mobcoins_balance%");
        takeCommand = config.getString(
                "take-command", "coins take %player% %amount%");
        if (!enabled) {
            return;
        }
        if (balancePlaceholder == null || balancePlaceholder.trim().isEmpty()
                || takeCommand == null || !takeCommand.contains("%player%")
                || !takeCommand.contains("%amount%")) {
            enabled = false;
            plugin.getLogger().severe("Invalid FactionsKore MobCoin integration config. "
                    + "A balance placeholder and take-command containing %player% and "
                    + "%amount% are required.");
            return;
        }

        Plugin factionsKore = plugin.getServer().getPluginManager()
                .getPlugin(factionsKorePluginName);
        Plugin placeholderApi = plugin.getServer().getPluginManager()
                .getPlugin("PlaceholderAPI");
        if (factionsKore == null || !factionsKore.isEnabled()) {
            enabled = false;
            plugin.getLogger().severe("FactionsKore plugin '" + factionsKorePluginName
                    + "' is missing or disabled; MobCoin purchases fail closed.");
            return;
        }
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            enabled = false;
            plugin.getLogger().severe("PlaceholderAPI is missing or disabled; "
                    + "FactionsKore MobCoin purchases fail closed.");
            return;
        }

        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                    placeholderApi.getClass().getClassLoader());
            for (Method method : api.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("setPlaceholders")
                        && Modifier.isStatic(method.getModifiers())
                        && parameters.length == 2
                        && parameters[0].isAssignableFrom(Player.class)
                        && parameters[1] == String.class
                        && method.getReturnType() == String.class) {
                    placeholderMethod = method;
                    break;
                }
            }
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().severe("Could not load PlaceholderAPI: "
                    + exception.getMessage());
        }
        if (placeholderMethod == null) {
            enabled = false;
            plugin.getLogger().severe("No compatible PlaceholderAPI method was found; "
                    + "MobCoin purchases fail closed.");
            return;
        }
        plugin.getLogger().info("FactionsKore MobCoin shop bridge is ready.");
    }

    public boolean isAvailable() {
        return enabled && placeholderMethod != null;
    }

    public long getBalance(Player player) {
        if (!isAvailable()) {
            return -1L;
        }
        try {
            String value = (String) placeholderMethod.invoke(
                    null, player, balancePlaceholder);
            if (value == null || value.equals(balancePlaceholder)) {
                return -1L;
            }
            value = ChatColor.stripColor(value).trim();
            Matcher matcher = INTEGER.matcher(value);
            if (!matcher.find()) {
                return -1L;
            }
            return Long.parseLong(matcher.group().replace(",", ""));
        } catch (ReflectiveOperationException | NumberFormatException exception) {
            plugin.getLogger().warning("Could not read MobCoin balance for "
                    + player.getName() + ": " + exception.getMessage());
            return -1L;
        }
    }

    public boolean withdraw(Player player, int amount, long balanceBefore) {
        if (!isAvailable() || amount < 0 || balanceBefore < amount) {
            return false;
        }
        if (amount == 0) {
            return true;
        }
        String command = takeCommand
                .replace("%player%", player.getName())
                .replace("%amount%", String.valueOf(amount));
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        boolean dispatched = plugin.getServer().dispatchCommand(
                plugin.getServer().getConsoleSender(), command);
        if (!dispatched) {
            plugin.getLogger().severe("FactionsKore rejected MobCoin take command: "
                    + command);
            return false;
        }

        long balanceAfter = getBalance(player);
        if (balanceAfter < 0L || balanceAfter > balanceBefore - amount) {
            plugin.getLogger().severe("MobCoin withdrawal could not be verified for "
                    + player.getName() + " (before=" + balanceBefore + ", after="
                    + balanceAfter + ", price=" + amount + "). No item was granted.");
            return false;
        }
        if (balanceAfter < balanceBefore - amount) {
            plugin.getLogger().warning("FactionsKore removed more MobCoins than expected from "
                    + player.getName() + " (before=" + balanceBefore + ", after="
                    + balanceAfter + ", price=" + amount + ").");
        }
        return true;
    }
}
