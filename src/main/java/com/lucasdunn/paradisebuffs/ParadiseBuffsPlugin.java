package com.lucasdunn.paradisebuffs;

import com.lucasdunn.paradisebuffs.buffs.ForcersSharkBuff;
import com.lucasdunn.paradisebuffs.buffs.PatcherParadiseBuff;
import com.lucasdunn.paradisebuffs.buffs.ParadiseRushBuff;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ParadiseBuffsPlugin extends JavaPlugin implements Listener {
    private static final int DEFAULT_MAX_GIVE_AMOUNT = 64;
    private static final int HARD_MAX_GIVE_AMOUNT = 36 * 64;

    private final Map<String, CustomBuff> buffs = new LinkedHashMap<String, CustomBuff>();
    private BuffItemAuthenticator authenticator;
    private BuffShop shop;
    private int maxGiveAmount;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        try {
            authenticator = new BuffItemAuthenticator(loadOrCreateSigningKey());
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().severe("Could not load secret.key: " + exception.getMessage());
            getLogger().severe("ParadiseBuffs cannot safely validate items and will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerBuff(new ForcersSharkBuff(this, authenticator));
        registerBuff(new PatcherParadiseBuff(this, authenticator));
        registerBuff(new ParadiseRushBuff(this, authenticator));
        getServer().getPluginManager().registerEvents(this, this);
        shop = new BuffShop(this);
        getServer().getPluginManager().registerEvents(shop, this);
        reloadSettings();
        getLogger().info("ParadiseBuffs enabled with " + enabledBuffCount()
                + " buff(s).");
    }

    private void registerBuff(CustomBuff buff) {
        String id = buff.getId().toLowerCase(Locale.ENGLISH);
        if (buffs.containsKey(id)) {
            throw new IllegalStateException("Duplicate buff ID: " + id);
        }
        buffs.put(id, buff);
        if (buff instanceof org.bukkit.event.Listener) {
            getServer().getPluginManager().registerEvents(
                    (org.bukkit.event.Listener) buff, this);
        }
    }

    public String color(String value) {
        return value == null ? "" : ChatColor.translateAlternateColorCodes('&', value);
    }

    public String message(String key) {
        return color(getConfig().getString("messages." + key, ""));
    }

    public CustomBuff getBuff(String id) {
        return id == null ? null : buffs.get(id.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("shop"))) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(message("prefix") + message("players-only"));
                return true;
            }
            if (!sender.hasPermission("paradisebuffs.shop")) {
                sender.sendMessage(message("prefix") + message("no-permission"));
                return true;
            }
            shop.open((Player) sender);
            return true;
        }

        if (!sender.hasPermission("paradisebuffs.admin")) {
            sender.sendMessage(message("prefix") + message("no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadSettings();
            sender.sendMessage(message("prefix") + message("reloaded"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage((message("prefix") + message("buff-list"))
                    .replace("%buffs%", enabledBuffList()));
            return true;
        }

        if (args.length >= 2 && args.length <= 4 && args[0].equalsIgnoreCase("give")) {
            CustomBuff buff = buffs.get(args[1].toLowerCase(Locale.ENGLISH));
            if (buff == null || !buff.isEnabled()) {
                sender.sendMessage((message("prefix") + message("buff-not-found"))
                        .replace("%buff%", args[1]));
                return true;
            }

            Player target;
            if (args.length >= 3) {
                target = getServer().getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(message("prefix") + message("player-not-found"));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(message("prefix") + message("usage"));
                return true;
            }

            int amount = 1;
            if (args.length == 4) {
                try {
                    amount = Integer.parseInt(args[3]);
                } catch (NumberFormatException ignored) {
                    amount = 0;
                }
                if (amount < 1) {
                    sender.sendMessage(message("prefix") + message("invalid-amount"));
                    return true;
                }
            }
            if (amount > maxGiveAmount) {
                sender.sendMessage((message("prefix") + message("amount-too-large"))
                        .replace("%max%", String.valueOf(maxGiveAmount)));
                return true;
            }

            int delivered = giveOrbs(target, buff, amount);
            Map<String, String> replacements = new HashMap<String, String>();
            replacements.put("%amount%", String.valueOf(delivered));
            replacements.put("%player%", target.getName());
            replacements.put("%buff%", buff.getDisplayName());
            sender.sendMessage(replace(message("prefix") + message("given"), replacements));
            if (sender != target) {
                target.sendMessage(replace(message("prefix") + message("received"), replacements));
            }
            return true;
        }

        sender.sendMessage(message("prefix") + message("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> choices = new ArrayList<String>();
        if (args.length == 1) {
            if (sender.hasPermission("paradisebuffs.shop")) {
                choices.add("shop");
            }
            if (sender.hasPermission("paradisebuffs.admin")) {
                choices.add("give");
                choices.add("list");
                choices.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (CustomBuff buff : buffs.values()) {
                if (buff.isEnabled()) {
                    choices.add(buff.getId());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player player : getServer().getOnlinePlayers()) {
                choices.add(player.getName());
            }
        }
        return matching(choices, args[args.length - 1]);
    }

    private List<String> matching(List<String> choices, String input) {
        String prefix = input.toLowerCase(Locale.ENGLISH);
        List<String> matches = new ArrayList<String>();
        for (String choice : choices) {
            if (choice.toLowerCase(Locale.ENGLISH).startsWith(prefix)) {
                matches.add(choice);
            }
        }
        return matches;
    }

    private void reloadSettings() {
        int configuredMaximum = getConfig().getInt("max-give-amount", DEFAULT_MAX_GIVE_AMOUNT);
        maxGiveAmount = Math.max(1, Math.min(HARD_MAX_GIVE_AMOUNT, configuredMaximum));
        if (configuredMaximum != maxGiveAmount) {
            getLogger().warning("max-give-amount must be between 1 and "
                    + HARD_MAX_GIVE_AMOUNT + "; using " + maxGiveAmount + ".");
        }
        for (CustomBuff buff : buffs.values()) {
            buff.reload();
        }
        if (shop != null) {
            shop.reload();
        }
        for (Player player : getServer().getOnlinePlayers()) {
            hideLegacyMarkers(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        hideLegacyMarkers(event.getPlayer());
    }

    private void hideLegacyMarkers(Player player) {
        if (authenticator == null) {
            return;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            authenticator.hideLegacyMarkers(item);
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            authenticator.hideLegacyMarkers(item);
        }
    }

    private int enabledBuffCount() {
        int count = 0;
        for (CustomBuff buff : buffs.values()) {
            if (buff.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    private String enabledBuffList() {
        List<String> names = new ArrayList<String>();
        for (CustomBuff buff : buffs.values()) {
            if (buff.isEnabled()) {
                names.add(buff.getId());
            }
        }
        return names.isEmpty() ? message("none") : join(names, ", ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    private byte[] loadOrCreateSigningKey() throws IOException {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IOException("Could not create the plugin data folder.");
        }

        Path path = new java.io.File(getDataFolder(), "secret.key").toPath();
        if (Files.exists(path)) {
            String encoded = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < 32) {
                throw new IllegalArgumentException("The existing signing key is too short.");
            }
            return decoded;
        }

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.write(path, Base64.getEncoder().encode(key));
        return key;
    }

    private int giveOrbs(Player player, CustomBuff buff, int amount) {
        int delivered = 0;
        int remaining = amount;
        ItemStack prototype = buff.createOrb(1);
        int maximumStackSize = prototype.getMaxStackSize();
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maximumStackSize);
            ItemStack stack = prototype.clone();
            stack.setAmount(stackSize);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            int rejected = 0;
            for (ItemStack item : overflow.values()) {
                rejected += item.getAmount();
            }
            delivered += stackSize - rejected;
            remaining -= stackSize;
            if (rejected > 0) {
                break;
            }
        }
        if (delivered < amount) {
            player.sendMessage(message("prefix") + message("inventory-full"));
        }
        return delivered;
    }

    private String replace(String input, Map<String, String> replacements) {
        String result = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
