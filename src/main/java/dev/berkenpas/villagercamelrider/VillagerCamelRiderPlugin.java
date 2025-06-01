package dev.berkenpas.vcr;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class VillagerCamelRiderPlugin extends JavaPlugin implements Listener {

    private NamespacedKey PDC_VILLAGER_MOUNTED_KEY;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        PDC_VILLAGER_MOUNTED_KEY = new NamespacedKey(this, "villager_camel_rider");
        getLogger().info("VillagerCamelRider enabled.");
    }

    // 1. Mount villager on camel: as long as camel is leashed to player (no lead in hand needed)
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();

        // Find nearest camel leashed to the player within 6 blocks
        Camel nearestCamel = null;
        double closest = 6.0;
        for (Entity ent : villager.getNearbyEntities(6, 6, 6)) {
            if (!(ent instanceof Camel camel)) continue;
            if (camel.isLeashed() && camel.getLeashHolder() instanceof Player leashHolder
                && leashHolder.getUniqueId().equals(player.getUniqueId())) {
                double dist = camel.getLocation().distance(villager.getLocation());
                if (dist < closest) {
                    closest = dist;
                    nearestCamel = camel;
                }
            }
        }
        if (nearestCamel == null) {
            return;
        }

        // Is there room? (camel can have max 2 passengers)
        if (nearestCamel.getPassengers().size() >= 2) {
            return;
        }

        // If camel already has a villager, block (only allow 1 villager at a time)
        for (Entity passenger : nearestCamel.getPassengers()) {
            if (passenger instanceof Villager) {
                return;
            }
        }

        // Mount the villager!
        if (villager.getVehicle() != null) {
            return;
        }

        // Actually add as passenger
        nearestCamel.addPassenger(villager);

        // Tag villager as "mounted by plugin" to control behavior
        villager.getPersistentDataContainer().set(PDC_VILLAGER_MOUNTED_KEY, PersistentDataType.BYTE, (byte) 1);

        // Unleash villager if leashed
        if (villager.isLeashed()) villager.setLeashHolder(null);

        // (Do NOT consume or remove any leads!)

        player.playSound(player, Sound.ENTITY_CAMEL_SADDLE, 1, 1);
    }

    // 2. Dismount villager: right-click camel (not sneaking) with stick in hand
    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Camel camel)) return;
        Player player = event.getPlayer();

        // Only if camel has plugin-villager passenger
        Villager villagerPassenger = null;
        for (Entity passenger : camel.getPassengers()) {
            if (passenger instanceof Villager villager
                    && villager.getPersistentDataContainer().has(PDC_VILLAGER_MOUNTED_KEY)) {
                villagerPassenger = villager;
                break;
            }
        }
        if (villagerPassenger == null) return; // No villager, vanilla behavior

        // Only if player is NOT sneaking AND holding a stick
        boolean hasStick =
                player.getInventory().getItemInMainHand().getType() == Material.STICK ||
                player.getInventory().getItemInOffHand().getType() == Material.STICK;
        if (!player.isSneaking() && hasStick) {
            // Do NOT cancel event, allow player to mount camel as usual
            camel.removePassenger(villagerPassenger);
            villagerPassenger.getPersistentDataContainer().remove(PDC_VILLAGER_MOUNTED_KEY);
            villagerPassenger.teleport(camel.getLocation().add(1, 0, 0));
            player.playSound(player, Sound.ENTITY_VILLAGER_CELEBRATE, 1, 1);
        }
        // Else: Vanilla camel inventory/mounting occurs as normal
    }

    // 3. Dismount via camel death
    @EventHandler
    public void onCamelDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Camel camel)) return;

        // Release villager passenger, remove plugin marker
        for (Entity passenger : List.copyOf(camel.getPassengers())) {
            if (passenger instanceof Villager villager
                && villager.getPersistentDataContainer().has(PDC_VILLAGER_MOUNTED_KEY)) {
                camel.removePassenger(villager);
                villager.getPersistentDataContainer().remove(PDC_VILLAGER_MOUNTED_KEY);
                villager.teleport(camel.getLocation().add(1, 0, 0));
            }
        }
    }

    // 4. Prevent villager from dismounting on their own
    @EventHandler
    public void onVillagerDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!villager.getPersistentDataContainer().has(PDC_VILLAGER_MOUNTED_KEY)) return;
        // Only allow if the vehicle is dead (e.g. camel killed), or plugin initiates
        if (!(event.getDismounted() instanceof Camel camel) || !camel.isDead()) {
            event.setCancelled(true);
        }
    }

    // 5. Extra: nothing needed on player quit
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // No cleanup needed.
    }

    // Not used, but here for future extensibility
    private static class CamelHolder implements InventoryHolder {
        final Camel camel;
        CamelHolder(Camel camel) { this.camel = camel; }
        @NotNull @Override
        public Inventory getInventory() { return null; }
    }
}
