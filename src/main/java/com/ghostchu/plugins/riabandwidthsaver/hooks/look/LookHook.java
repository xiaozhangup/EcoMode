package com.ghostchu.plugins.riabandwidthsaver.hooks.look;

import com.ghostchu.plugins.riabandwidthsaver.AFKHook;
import com.ghostchu.plugins.riabandwidthsaver.RIABandwidthSaver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.WeakHashMap;

public class LookHook extends AFKHook {
    private static final WeakHashMap<Player, Float> lastRotation = new WeakHashMap<>();

    public LookHook(RIABandwidthSaver plugin) {
        super(plugin);
        startChecker();
    }

    private void startChecker() {
        Bukkit.getScheduler().runTaskTimer(
                getPlugin(),
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        float rotation = player.getLocation().getYaw() + player.getLocation().getPitch();
                        if (lastRotation.containsKey(player)) {
                            if (lastRotation.get(player) == rotation && !getPlugin().playerIsEco(player)) {
                                getPlugin().playerEcoEnable(player);
                            }
                            else if (lastRotation.get(player) != rotation && getPlugin().playerIsEco(player)) {
                                getPlugin().playerEcoDisable(player);
                            }
                        }
                        lastRotation.put(player, rotation);
                    }
                },
                0L,
                800L
        );
    }
}
