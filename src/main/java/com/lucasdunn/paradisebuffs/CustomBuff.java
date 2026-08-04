package com.lucasdunn.paradisebuffs;

import org.bukkit.inventory.ItemStack;

public interface CustomBuff {
    String getId();

    String getDisplayName();

    boolean isEnabled();

    void reload();

    ItemStack createOrb(int amount);
}
