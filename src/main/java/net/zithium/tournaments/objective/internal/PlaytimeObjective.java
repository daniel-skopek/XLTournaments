package net.zithium.tournaments.objective.internal;

import net.zithium.tournaments.XLTournamentsPlugin;
import net.zithium.tournaments.objective.XLObjective;
import net.zithium.tournaments.tournament.Tournament;
import net.zithium.tournaments.utility.AntiAfkTracker;
import net.zithium.tournaments.utility.TaskScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class PlaytimeObjective extends XLObjective {

    private final JavaPlugin plugin = JavaPlugin.getProvidingPlugin(XLTournamentsPlugin.class);
    private ScheduledTask task;

    private long afkWindowMillis;

    public PlaytimeObjective() {
        super("PLAYTIME");
    }

    @Override
    public boolean loadTournament(Tournament tournament, FileConfiguration config) {
        afkWindowMillis = plugin.getConfig().getLong("anti_farm.playtime_afk_seconds", 300) * 1000L;

        if (task == null || task.isCancelled()) {
            int intervalTicks = plugin.getConfig().getInt("playtime_objective_task_update", 200);
            task = TaskScheduler.runSyncTimer(plugin, this::updatePlaytime, 20L, intervalTicks);
        }
        return true;
    }

    private void updatePlaytime() {
        AntiAfkTracker tracker = AntiAfkTracker.getInstance();
        boolean checkActivity = tracker.isEnabled() && tracker.isObjectiveEnabled("PLAYTIME");

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Ignore vertical bobbing from standing in water; only genuine
            // horizontal movement or camera rotation counts as activity.
            if (checkActivity && !tracker.hasRecentActivity(player, afkWindowMillis)) {
                continue;
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
}
