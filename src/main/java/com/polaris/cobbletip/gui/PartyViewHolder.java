package com.polaris.cobbletip.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class PartyViewHolder implements InventoryHolder {
    private final UUID targetPlayerId;

    public PartyViewHolder(UUID targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public UUID targetPlayerId() {
        return targetPlayerId;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}

