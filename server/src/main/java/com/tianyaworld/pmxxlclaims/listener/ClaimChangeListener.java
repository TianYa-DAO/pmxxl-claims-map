package com.tianyaworld.pmxxlclaims.listener;

import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceAreaDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceCreationEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.tianyaworld.pmxxlclaims.publish.ClaimPublisher;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ClaimChangeListener implements Listener {

    private final ClaimPublisher publisher;

    public ClaimChangeListener(ClaimPublisher publisher) {
        this.publisher = publisher;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreation(ResidenceCreationEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDelete(ResidenceDeleteEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSizeChange(ResidenceSizeChangeEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRename(ResidenceRenameEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOwnerChange(ResidenceOwnerChangeEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAreaAdd(ResidenceAreaAddEvent event) {
        publisher.markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAreaDelete(ResidenceAreaDeleteEvent event) {
        publisher.markDirty();
    }
}
