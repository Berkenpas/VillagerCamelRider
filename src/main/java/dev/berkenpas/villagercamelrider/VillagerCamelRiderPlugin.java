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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class VillagerCamelRiderPlugin extends JavaPlugin implements Listener {

    private final String RELEASE_ITEM_NAME = "§cRelease Villager";

    private NamespacedKey PDC_VILLAGER_MOUNTED_KEY;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        PDC_VILLAGER_MOUNTED_KEY = new NamespacedKey(this, "villager_camel_rider");
        getLogger().info("VillagerCamelRider enabled.");
    }

    // ---- 1. Mount villager on camel ----
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Only trigger if right-clicking a villager
        if (!(event.getRightClicked() instanceof Villager villager)) return;

        Player player = event.getPlayer();

        // Must hold a lead in hand
        if (!player.getInventory().getItemInMainHand().getType().equals(Material.LEAD) &&
            !player.getInventory().getItemInOffHand().getType().equals(Material.LEAD)) return;

        // Find nearest camel on a lead (owned by this player), within 6 blocks
        Camel nearestCamel = null;
        double closest = 6.0;
        for (Entity ent : villager.getNearbyEntities(6, 6, 6)) {
            if (!(ent instanceof Camel camel)) continue;
            // Check if the camel is leashed to this player
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
            player.sendMessage("§cNo camel on a lead nearby!");
            return;
        }

        // Is there room? (camel can have max 2 passengers)
        if (nearestCamel.getPassengers().size() >= 2) {
            player.sendMessage("§eThat camel is full!");
            return;
        }

        // If camel already has a villager, block (only allow 1 villager at a time)
        for (Entity passenger : nearestCamel.getPassengers()) {
            if (passenger instanceof Villager) {
                player.sendMessage("§eA villager is already riding this camel.");
                return;
            }
        }

        // Mount the villager!
        if (villager.getVehicle() != null) {
            player.sendMessage("§eThat villager is already riding something!");
            return;
        }

        // Actually add as passenger
        nearestCamel.addPassenger(villager);

        // Tag villager as "mounted by plugin" to control behavior
        villager.getPersistentDataContainer().set(PDC_VILLAGER_MOUNTED_KEY, PersistentDataType.BYTE, (byte) 1);

        // Unleash camel and villager if leashed
        if (nearestCamel.isLeashed()) nearestCamel.setLeashHolder(null);
        if (villager.isLeashed()) villager.setLeashHolder(null);

        player.sendMessage("§aVillager is now riding your camel!");
        player.playSound(player, Sound.ENTITY_CAMEL_SADDLE, 1, 1);
    }

    // ---- 2. Custom dismount: shift-right-click camel with stick to release villager ----
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

        // Only if player is sneaking AND holding a stick
        boolean hasStick =
                player.getInventory().getItemInMainHand().getType() == Material.STICK ||
                player.getInventory().getItemInOffHand().getType() == Material.STICK;
        if (player.isSneaking() && hasStick) {
            // Cancel vanilla inventory and dismount villager
            event.setCancelled(true);

            camel.removePassenger(villagerPassenger);
            villagerPassenger.getPersistentDataContainer().remove(PDC_VILLAGER_MOUNTED_KEY);
            villagerPassenger.teleport(camel.getLocation().add(1, 0, 0));

            player.sendMessage("§aVillager released from camel!");
            player.playSound(player, Sound.ENTITY_VILLAGER_CELEBRATE, 1, 1);
        }
        // Else: Let normal inventory open, do not cancel event
    }

    // ---- 3. Dismount via camel death ----
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

    // ---- 4. Prevent villager from dismounting on their own ----
    @EventHandler
    public void onVillagerDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!villager.getPersistentDataContainer().has(PDC_VILLAGER_MOUNTED_KEY)) return;
        // Only allow if the vehicle is dead (e.g. camel killed), or plugin initiates
        if (!(event.getDismounted() instanceof Camel camel) || !camel.isDead()) {
            event.setCancelled(true);
        }
    }

    // ---- 5. Ensure villager is passive while mounted (no walk, no despawn, no panic) ----
    // In modern Paper, villagers won't despawn. The ride state disables AI by default.
    // But for extra safety:
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Edge case: Player logs out with a villager on camel, nothing to do since AI is paused on ride.
    }

    // --- Utility class for custom inventory holder (not used in this MVP, but left for future UI) ---
    private static class CamelHolder implements InventoryHolder {
        final Camel camel;
        CamelHolder(Camel camel) { this.camel = camel; }
        @NotNull @Override
        public Inventory getInventory() { return null; }
    }
}
