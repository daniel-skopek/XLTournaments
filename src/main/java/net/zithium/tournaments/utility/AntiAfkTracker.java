/*
 * XLTournaments Plugin
 * Copyright (c) 2026 Zithium Studios. All rights reserved.
 */

package net.zithium.tournaments.utility;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects players who keep farming an objective while they are effectively AFK
 * (auto-fishing, auto-eating, auto-crafting, kill-aura grinders, "held mouse
 * button" loops, ...).
 *
 * <p>The design intentionally trades a little detection power for a very low
 * false-positive rate, because a wrongly flagged active player is far worse
 * than a farmed point. A score is only suppressed when the player is
 * <b>calm</b> <em>and</em> the action looks automated:</p>
 *
 * <ul>
 *   <li><b>Calm</b> — the player has neither changed block on the X/Z plane
 *       nor rotated the camera for {@code calm_window_seconds}. Vertical
 *       bobbing while standing in water is deliberately ignored (it only moves
 *       the Y axis), so a player standing in the fishing pool still counts as
 *       calm, while a player genuinely walking/looking around is active.</li>
 *   <li><b>Regular</b> — the intervals between the last few actions of the
 *       same type are almost identical (coefficient of variation below
 *       {@code regularity_max_cv}), which is what an autoclicker produces.</li>
 *   <li><b>Sustained</b> — at least {@code min_actions_while_calm} actions of
 *       the same type happened while calm. This catches loops whose intervals
 *       are not regular.</li>
 * </ul>
 *
 * <p>Suppression happens when {@code calm && (regular || sustained)}. A player
 * who is moving, rotating the camera or only acting a few times is never
 * affected. This class does not punish anyone; it only reports whether an
 * objective should award score.</p>
 *
 * <p>Fishing is special-cased in two ways, because catch intervals are random
 * (the bite delay is random) so neither regularity nor "many catches" can
 * separate a bot from a human — and "many catches while calm" wrongly flagged
 * legitimate players who stand still and click:</p>
 * <ul>
 *   <li>a catch with no recent right-click, or with bot-regular right-clicks,
 *       is treated as automation (rod input is the real signal);</li>
 *   <li>a player who rotates the yaw a lot while their pitch stays bit-for-bit
 *       constant for a long time is treated as an auto-fish client, which fakes
 *       camera movement with yaw only (a real mouse moves both axes).</li>
 * </ul>
 *
 * <p>Activity is updated from {@link PlayerMoveEvent}, which on Folia runs on
 * the player's region thread. Each player has an isolated, synchronised state,
 * so the tracker is safe to query from any scheduler thread.</p>
 */
public final class AntiAfkTracker implements Listener {

    private static final AntiAfkTracker INSTANCE = new AntiAfkTracker();

    private static final String FISH_OBJECTIVE = "PLAYER_FISH";
    private static final String FISH_INPUT_HISTORY = "PLAYER_FISH_INPUT";

    // Any change larger than this counts as genuine pitch movement. Auto-fish
    // clients rotate yaw but send a pitch delta of exactly 0, so pitch never
    // changes at all for them.
    private static final double PITCH_EPSILON = 0.001;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<String, Boolean> objectiveToggles = new ConcurrentHashMap<>();

    private boolean enabled;
    private long calmWindowMillis;
    private long fishInputTimeoutMillis;
    private boolean fishAimCheckEnabled;
    private long fishAimWindowMillis;
    private double fishMinYaw;
    private int fishMinCatchesWhilePitchStatic;
    private double minRotationDelta;
    private int minActionsWhileCalm;
    private int regularitySamples;
    private int regularityMinSamples;
    private double regularityMaxCv;
    private boolean initialized;

    private AntiAfkTracker() {
    }

    public static AntiAfkTracker getInstance() {
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
        enabled = config.getBoolean("anti_afk.enabled", true);
        calmWindowMillis = config.getLong("anti_afk.calm_window_seconds", 180) * 1000L;
        fishInputTimeoutMillis = Math.max(0L, config.getLong("anti_afk.fish_input_timeout_seconds", 2)) * 1000L;
        fishAimCheckEnabled = config.getBoolean("anti_afk.fish_aim_check", true);
        fishAimWindowMillis = Math.max(1000L, config.getLong("anti_afk.fish_aim_window_seconds", 300) * 1000L);
        fishMinYaw = config.getDouble("anti_afk.fish_min_yaw_degrees", 30.0);
        fishMinCatchesWhilePitchStatic = config.getInt("anti_afk.fish_min_catches_while_pitch_static", 3);
        minRotationDelta = config.getDouble("anti_afk.min_rotation_delta", 0.1);
        minActionsWhileCalm = config.getInt("anti_afk.min_actions_while_calm", 5);
        regularitySamples = Math.max(2, config.getInt("anti_afk.regularity_samples", 10));
        regularityMinSamples = Math.max(1, config.getInt("anti_afk.regularity_min_samples", 6));
        regularityMaxCv = config.getDouble("anti_afk.regularity_max_cv", 0.08);

        objectiveToggles.clear();
        for (String objective : new String[]{"PLAYER_FISH", "MOB_KILLS", "PLAYTIME", "ITEM_CRAFT", "POTION_BREW", "PLAYER_CONSUME"}) {
            objectiveToggles.put(objective, config.getBoolean("anti_afk.objectives." + objective, true));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether anti-AFK protection is enabled for the given objective identifier.
     */
    public boolean isObjectiveEnabled(String objective) {
        return objectiveToggles.getOrDefault(objective, true);
    }

    /**
     * Returns {@code true} when the player changed block on the X/Z plane or
     * rotated the camera within {@code windowMillis}. Vertical water bobbing is
     * ignored. Always returns {@code true} when the tracker is disabled.
     */
    public boolean hasRecentActivity(Player player, long windowMillis) {
        if (!enabled) return true;

        PlayerState state = states.get(player.getUniqueId());
        if (state == null) return false;

        synchronized (state) {
            return System.currentTimeMillis() - state.lastActivityMillis < windowMillis;
        }
    }

    /**
     * Records an action of the given type and returns whether it should award
     * score. Returns {@code true} for active players and during the grace
     * period; returns {@code false} only for calm players whose actions look
     * automated. Always returns {@code true} when the tracker or the objective
     * is disabled.
     *
     * @param player   the acting player
     * @param objective the objective identifier (matches the config toggle key)
     */
    public boolean shouldCount(Player player, String objective) {
        if (!enabled || !isObjectiveEnabled(objective)) return true;

        if (FISH_OBJECTIVE.equals(objective)) {
            return shouldCountFishing(player);
        }

        PlayerState state = states.computeIfAbsent(player.getUniqueId(), k -> new PlayerState(System.currentTimeMillis()));
        long now = System.currentTimeMillis();

        synchronized (state) {
            Deque<Long> history = state.actionHistory.computeIfAbsent(objective, k -> new ArrayDeque<>());
            history.addLast(now);
            int maxHistory = Math.max(regularitySamples, minActionsWhileCalm) + 1;
            while (history.size() > maxHistory) {
                history.removeFirst();
            }

            boolean calm = now - state.lastActivityMillis >= calmWindowMillis;
            if (!calm) return true;

            boolean regular = isRegularInterval(objective, history);
            boolean sustained = countRecent(history, now - calmWindowMillis) >= minActionsWhileCalm;
            return !(regular || sustained);
        }
    }

    /**
     * Fishing is judged on the player's actual rod input rather than on the
     * catches themselves. Catch intervals are random (the bite delay is
     * random), so they cannot separate a bot from a human, and "many catches
     * while calm" wrongly flags a legitimate player who simply stands still and
     * clicks. Instead, a legitimate catch is always preceded by a right-click
     * of the rod, while a "held mouse button" macro/scheme produces either no
     * per-reel input or bot-regular input. In addition, an auto-fish client that
     * fakes activity with yaw-only rotation (pitch never changes) is refused.
     */
    private boolean shouldCountFishing(Player player) {
        PlayerState state = states.computeIfAbsent(player.getUniqueId(), k -> new PlayerState(System.currentTimeMillis()));
        long now = System.currentTimeMillis();

        synchronized (state) {
            // Auto-fish clients rotate the yaw to fake activity but never change
            // pitch (a real mouse moves both axes). Catching fish while rotating
            // the yaw with a bit-exact constant pitch is not something a human
            // with a mouse does.
            if (fishAimCheckEnabled) {
                refreshAimWindow(state, now);
                state.aimCatches++;
                if (!state.aimPitchChanged
                        && state.aimYaw >= fishMinYaw
                        && state.aimCatches >= fishMinCatchesWhilePitchStatic) {
                    return false;
                }
            }

            // No calm/movement gate here: a fishing farm may push the player
            // around in water, and the rod input is a reliable signal on its own.
            boolean regularInput = isRegularInterval(FISH_OBJECTIVE, state.actionHistory.get(FISH_INPUT_HISTORY));
            boolean noInput = now - state.lastFishInputMillis > fishInputTimeoutMillis;
            return !(regularInput || noInput);
        }
    }

    private boolean isRegularInterval(String objective, Deque<Long> history) {
        // Brewing is inherently periodic, so its intervals are always "regular"
        // and carry no signal. Only judge click-driven actions.
        if ("POTION_BREW".equals(objective)) return false;
        if (history == null || history.size() < regularityMinSamples + 1) return false;

        Long[] times = history.toArray(new Long[0]);
        int intervals = times.length - 1;

        double sum = 0;
        for (int i = 1; i < times.length; i++) {
            sum += times[i] - times[i - 1];
        }
        double mean = sum / intervals;
        if (mean <= 0) return true;

        double variance = 0;
        for (int i = 1; i < times.length; i++) {
            double deviation = (times[i] - times[i - 1]) - mean;
            variance += deviation * deviation;
        }
        variance /= intervals;

        double coefficientOfVariation = Math.sqrt(variance) / mean;
        return coefficientOfVariation <= regularityMaxCv;
    }

    private int countRecent(Deque<Long> history, long since) {
        int count = 0;
        for (long time : history) {
            if (time >= since) count++;
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Only horizontal movement counts; standing in water only bobs the Y
        // axis and must not be mistaken for genuine activity.
        boolean changedBlock = from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ();

        double deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        if (deltaYaw > 180.0) deltaYaw = 360.0 - deltaYaw;
        double deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        boolean rotated = deltaYaw + deltaPitch >= minRotationDelta;
        boolean pitchMoved = deltaPitch > PITCH_EPSILON;

        if (!changedBlock && !rotated && !pitchMoved) return;

        PlayerState state = states.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new PlayerState(System.currentTimeMillis()));
        synchronized (state) {
            long now = System.currentTimeMillis();

            // Auto-fish clients rotate the yaw but send a pitch delta of exactly
            // zero, so any real pitch change proves a human (or a normal client).
            if (fishAimCheckEnabled) {
                refreshAimWindow(state, now);
                if (pitchMoved) {
                    state.aimPitchChanged = true;
                } else {
                    state.aimYaw += deltaYaw;
                }
            }

            if (changedBlock || rotated) {
                state.lastActivityMillis = now;
            }
        }
    }

    private void refreshAimWindow(PlayerState state, long now) {
        if (now - state.aimWindowStartMillis >= fishAimWindowMillis) {
            state.aimWindowStartMillis = now;
            state.aimYaw = 0;
            state.aimPitchChanged = false;
            state.aimCatches = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!enabled || !isObjectiveEnabled(FISH_OBJECTIVE)) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null || event.getItem().getType() != Material.FISHING_ROD) return;
        // Only the hand that actually performs the use should count.
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        long now = System.currentTimeMillis();
        PlayerState state = states.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new PlayerState(now));
        synchronized (state) {
            state.lastFishInputMillis = now;

            Deque<Long> history = state.actionHistory.computeIfAbsent(FISH_INPUT_HISTORY, k -> new ArrayDeque<>());
            history.addLast(now);
            int maxHistory = Math.max(regularitySamples, minActionsWhileCalm) + 1;
            while (history.size() > maxHistory) {
                history.removeFirst();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        states.put(event.getPlayer().getUniqueId(), new PlayerState(System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private static final class PlayerState {

        private final Map<String, Deque<Long>> actionHistory = new HashMap<>();
        private long lastActivityMillis;
        private long lastFishInputMillis;
        private long aimWindowStartMillis;
        private double aimYaw;
        private boolean aimPitchChanged;
        private int aimCatches;

        private PlayerState(long now) {
            this.lastActivityMillis = now;
            this.lastFishInputMillis = now;
            this.aimWindowStartMillis = now;
        }
    }
}
