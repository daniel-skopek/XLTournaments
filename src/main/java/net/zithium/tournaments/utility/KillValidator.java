/*
 * XLTournaments Plugin
 * Copyright (c) 2026 Zithium Studios. All rights reserved.
 */

package net.zithium.tournaments.utility;

import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Validates that a kill can really be credited to a player.
 *
 * <p>{@link org.bukkit.entity.LivingEntity#getKiller()} can be spoofed by any
 * plugin through {@link org.bukkit.entity.LivingEntity#setKiller(Player)}. A
 * common case is a minion/pet/trap plugin that damages a mob itself but sets the
 * owner as the killer so the kill is attributed to them (for XP, loot, death
 * messages, ...). A tournament that trusts {@code getKiller()} would then award
 * score for a kill the player never performed.</p>
 *
 * <p>When the final damage was dealt by another entity, this validator requires
 * that entity to actually belong to the player: the player themself, their
 * projectile, their primed TNT or their tamed pet. When the final damage was
 * environmental (fall, fire, drowning, ...) it keeps trusting
 * {@code getKiller()} so legitimate indirect kills are not lost.</p>
 */
public final class KillValidator {

    private KillValidator() {
    }

    /**
     * Returns whether the killing blow on {@code victim} can be attributed to
     * {@code player}.
     */
    public static boolean isPlayerKill(@Nullable Player player, @Nullable Entity victim) {
        if (player == null || victim == null) return false;

        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (!(lastDamage instanceof EntityDamageByEntityEvent)) {
            // Environmental or unknown cause — the player may have dealt an
            // earlier hit, so preserve the original getKiller() behaviour.
            return true;
        }

        return isOwnedBy(player, ((EntityDamageByEntityEvent) lastDamage).getDamager());
    }

    private static boolean isOwnedBy(@NotNull Player player, @Nullable Entity damager) {
        if (damager == null) return false;
        if (damager.getUniqueId().equals(player.getUniqueId())) return true;

        switch (damager) {
            case Projectile projectile -> {
                return projectile.getShooter() instanceof Player shooter
                        && shooter.getUniqueId().equals(player.getUniqueId());
            }
            case TNTPrimed tnt -> {
                Entity source = tnt.getSource();
                return source != null && source.getUniqueId().equals(player.getUniqueId());
            }
            case Tameable tameable -> {
                AnimalTamer owner = tameable.getOwner();
                return owner != null && owner.getUniqueId().equals(player.getUniqueId());
            }
            default -> {
            }
        }

        return false;
    }
}
