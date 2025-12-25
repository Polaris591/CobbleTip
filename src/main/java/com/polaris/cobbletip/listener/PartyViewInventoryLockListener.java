package com.polaris.cobbletip.listener;

import com.polaris.cobbletip.gui.PartyViewHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

public final class PartyViewInventoryLockListener implements Listener {

    private static boolean isPartyView(InventoryClickEvent e) {
        return e != null
                && e.getView() != null
                && e.getView().getTopInventory() != null
                && e.getView().getTopInventory().getHolder() instanceof PartyViewHolder;
    }

    private static boolean isPartyView(InventoryDragEvent e) {
        return e != null
                && e.getView() != null
                && e.getView().getTopInventory() != null
                && e.getView().getTopInventory().getHolder() instanceof PartyViewHolder;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!isPartyView(e)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!isPartyView(e)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (e == null || e.getDestination() == null) return;
        if (!(e.getDestination().getHolder() instanceof PartyViewHolder)) return;
        e.setCancelled(true);
    }
}

