package net.zithium.tournaments.objective.internal;

import net.zithium.tournaments.XLTournamentsPlugin;
import net.zithium.tournaments.objective.XLObjective;
import net.zithium.tournaments.tournament.Tournament;
import net.zithium.tournaments.utility.TaskScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlaytimeObjective extends XLObjective {

    private final JavaPlugin plugin = JavaPlugin.getProvidingPlugin(XLTournamentsPlugin.class);
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private ScheduledTask task;

    private boolean antiFarmEnabled;
    private long afkWindowMillis;

    public PlaytimeObjective() {
        super("PLAYTIME");
    }

    @Override
    public boolean loadTournament(Tournament tournament, FileConfiguration config) {
        antiFarmEnabled = plugin.getConfig().getBoolean("anti_farm.enabled", true);
        afkWindowMillis = plugin.getConfig().getLong("anti_farm.playtime_afk_seconds", 300) * 1000L;

        if (task == null || task.isCancelled()) {
            int intervalTicks = plugin.getConfig().getInt("playtime_objective_task_update", 200);
            task = TaskScheduler.runSyncTimer(plugin, this::updatePlaytime, 20L, intervalTicks);
        }
        return true;
    }

    private void updatePlaytime() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (antiFarmEnabled) {
                Long last = lastActivity.get(uuid);
                if (last == null) {
                    lastActivity.put(uuid, now);
                } else if (now - last > afkWindowMillis) {
                    continue;
                }
            }

            for (Tournament tournament : getTournaments()) {
                if (canExecute(tournament, player)) {
                    int intervalTicks = plugin.getConfig().getInt("playtime_objective_task_update", 200);
                    int seconds = intervalTicks / 20; // convert ticks → seconds

                    tournament.addScore(player.getUniqueId(), seconds);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!antiFarmEnabled) return;
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }
}
