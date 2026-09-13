/*
 * XLTournaments Plugin
 * Copyright (c) 2026 Zithium Studios. All rights reserved.
 */

package net.zithium.tournaments.utility;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-objective anti-farm protection based on cooldowns rather than movement.
 *
 * <p>Instead of requiring the player to move or rotate (which produces bad UX —
 * a player standing still while fishing/clicking would be blocked), each
 * objective applies a targeted cooldown that prevents the specific farming
 * pattern while leaving normal play untouched:</p>
 * <ul>
 *   <li>Block break / place — the same block position can't be counted again
 *       within a short window (blocks regenerating-block farms such as snow
 *       under a snow golem or cobblestone generators).</li>
 * </ul>
 *
 * <p>Fishing and mob kills are intentionally not gated: a legit player can
 * catch or kill faster than any reasonable cooldown, so an interval has no
 * correlation with AFK and only causes false positives.</p>
 */
public final class AntiFarmTracker implements Listener {

    private static final AntiFarmTracker INSTANCE = new AntiFarmTracker();

    private static final int MAX_ENTRIES_PER_PLAYER = 256;

    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    private boolean enabled;
    private long blockBreakCooldownMillis;
    private long blockPlaceCooldownMillis;
    private boolean initialized;

    private AntiFarmTracker() {
    }

    public static AntiFarmTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Registers this tracker as a listener and loads its configuration.
     * Safe to call multiple times; the listener is only registered once.
     */
    public void initialize(JavaPlugin plugin) {
        loadConfig(plugin);
        if (!initialized) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            initialized = true;
        }
    }

    /**
     * Reloads the configuration values from the plugin's config file.
     */
    public void loadConfig(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("anti_farm.enabled", true);
        blockBreakCooldownMillis = config.getLong("anti_farm.block_break_same_position_seconds", 10) * 1000L;
        blockPlaceCooldownMillis = config.getLong("anti_farm.block_place_same_position_seconds", 10) * 1000L;
    }

    /**
     * Returns {@code false} when the given block position was broken too recently to count.
     */
    public boolean allowBlockBreak(Player player, Block block) {
        return !isOnCooldown(player, blockKey(block), blockBreakCooldownMillis);
    }

    /**
     * Returns {@code false} when the given block position was placed too recently to count.
     */
    public boolean allowBlockPlace(Player player, Block block) {
        return !isOnCooldown(player, blockKey(block), blockPlaceCooldownMillis);
    }

    private boolean isOnCooldown(Player player, String key, long cooldownMillis) {
        if (!enabled || cooldownMillis <= 0) return false;

        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());

        Long last = playerCooldowns.get(key);
        if (last != null && now - last < cooldownMillis) {
            return true;
        }

        if (playerCooldowns.size() >= MAX_ENTRIES_PER_PLAYER && !playerCooldowns.containsKey(key)) {
            playerCooldowns.clear();
        }
        playerCooldowns.put(key, now);
        return false;
    }

    private static String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }
}
