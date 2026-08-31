package net.zithium.tournaments.utility;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the Paper scheduler API, which works on both Paper and Folia.
 */
public final class TaskScheduler {

    private TaskScheduler() {
    }

    public static ScheduledTask runAsync(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    public static ScheduledTask runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delayTicks * 50L, periodTicks * 50L, TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask runSync(Plugin plugin, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
    }

    public static ScheduledTask runSyncLater(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
    }

    public static ScheduledTask runSyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delayTicks, periodTicks);
    }

    public static void cancelTasks(Plugin plugin) {
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
    }
}
