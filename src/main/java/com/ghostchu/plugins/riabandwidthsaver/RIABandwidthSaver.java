package com.ghostchu.plugins.riabandwidthsaver;

import com.ghostchu.plugins.riabandwidthsaver.hooks.cmi.CMIHook;
import com.ghostchu.plugins.riabandwidthsaver.hooks.essx.ESSXHook;
import com.ghostchu.plugins.riabandwidthsaver.hooks.look.LookHook;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.netty.buffer.ByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.*;

public final class RIABandwidthSaver extends JavaPlugin implements Listener {
    private final Set<UUID> AFK_PLAYERS = new HashSet<>();
    private final Map<PacketType.Play.Server, PacketInfo> PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final Map<PacketType.Play.Server, PacketInfo> UNFILTERED_PKT_TYPE_STATS = new ConcurrentHashMap<>();
    private final Map<UUID, PacketInfo> UNFILTERED_PLAYER_PKT_SAVED_STATS = new ConcurrentHashMap<>();
    private final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();
    private boolean calcAllPackets = false;
    private PacketCalcListener listenerCale = null;
    private PacketListener listener = null;
    private final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private final List<AFKHook> afkHooks = new ArrayList<>();

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        reloadConfig();
        scanHooks();
    }

    private void scanHooks() {
        if(Bukkit.getPluginManager().getPlugin("CMI") != null){
            afkHooks.add(new CMIHook(this));
            getLogger().info("CMI AFK状态钩子已注册！");
        }
        if(Bukkit.getPluginManager().getPlugin("Essentials")  != null){
            afkHooks.add(new ESSXHook(this));
            getLogger().info("Essentials AFK状态钩子已注册！");
        }
        if(afkHooks.size() > 1){
            getLogger().warning("存在多个 AFK 状态源钩子，这可能会导致问题。请只选择一个使用，并关闭其它插件的 AFK 功能以规避问题，如已关闭可忽略此提示");
        }
        if(afkHooks.isEmpty()){
            afkHooks.add(new LookHook(this));
        }
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.calcAllPackets = getConfig().getBoolean("calcAllPackets", true);
        if (listenerCale != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listenerCale);
        }
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        initProtocolLib();
    }

    private void initProtocolLib() {
        if (calcAllPackets) {
            listenerCale = new PacketCalcListener();
            PacketEvents.getAPI().getEventManager().registerListener(listenerCale);
        } else {
            UNFILTERED_PLAYER_PKT_SAVED_STATS.clear();
            UNFILTERED_PKT_TYPE_STATS.clear();
        }
        listener = new PacketListener();
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    private int calculatePacketSize(PacketSendEvent packet) {
        if (packet == null) return 0;
        try {
            ByteBuf byteBuf = (ByteBuf) packet.getByteBuf();
            return byteBuf.readableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    public void playerEcoEnable(Player player) {
        String message = getConfig().getString("message.playerEcoEnable", "");
        if(!message.isEmpty()){
            player.sendMessage(message);
        }
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
            player.setSendViewDistance(2);
        }
        AFK_PLAYERS.add(player.getUniqueId());
    }

    public void playerEcoDisable(Player player) {
        AFK_PLAYERS.remove(player.getUniqueId());
        if(getConfig().getBoolean("modifyPlayerViewDistance")) {
            player.setSendViewDistance(-1);
        }
        player.resetPlayerTime();
        String message = getConfig().getString("message.playerEcoDisable", "");
        if(!message.isEmpty()){
            player.sendMessage(message);
        }
    }

    public boolean playerIsEco(Player player) {
        return AFK_PLAYERS.contains(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerEcoDisable(event.getPlayer());
        PLAYER_PKT_SAVED_STATS.remove(event.getPlayer().getUniqueId());
        UNFILTERED_PLAYER_PKT_SAVED_STATS.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (listenerCale != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listenerCale);
        }
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NonNull [] args) {
        if (args.length == 1) {
            return  List.of(
                    "reload",
                    "unfiltered"
            );
        }
        return null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NonNull [] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GREEN + "🍃 ECO 节能模式 - 统计信息：");
            long pktCancelled = PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktCounter().longValue()).sum();
            long pktSizeSaved = PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktSize().longValue()).sum();
            sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + pktCancelled + " 个");
            sender.sendMessage(ChatColor.YELLOW + "共减少发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSizeSaved, false) + " （不包含视距优化的增益数据）");
            Map<PacketType.Play.Server, PacketInfo> sortedPktMap = new LinkedHashMap<>();
            Map<UUID, PacketInfo> sortedPlayerMap = new LinkedHashMap<>();
            PKT_TYPE_STATS.entrySet().stream().sorted(Map.Entry.<PacketType.Play.Server, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPktMap.put(e.getKey(), e.getValue()));
            PLAYER_PKT_SAVED_STATS.entrySet().stream().sorted(Map.Entry.<UUID, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPlayerMap.put(e.getKey(), e.getValue()));
            sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型节约 TOP 5 --");
            sortedPktMap.entrySet().stream().limit(5).forEach(entry -> sender.sendMessage(ChatColor.GRAY + entry.getKey().name() + " - " + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));
            sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量节约 TOP 5 --");
            sortedPlayerMap.entrySet().stream().limit(5).forEach(entry -> sender.sendMessage(ChatColor.GRAY + Bukkit.getOfflinePlayer(entry.getKey()).getName() + " - " + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("unfiltered")) {
            sender.sendMessage(ChatColor.GREEN + "🍃 UN-ECO - 数据总计 - 统计信息：");
            long pktSent = UNFILTERED_PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktCounter().longValue()).sum();
            long pktSize = UNFILTERED_PKT_TYPE_STATS.values().stream().mapToLong(r -> r.getPktSize().longValue()).sum();
            sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + pktSent + " 个");
            sender.sendMessage(ChatColor.YELLOW + "共发送数据包：" + ChatColor.AQUA + humanReadableByteCount(pktSize, false));
            Map<PacketType.Play.Server, PacketInfo> sortedPktMap = new LinkedHashMap<>();
            Map<UUID, PacketInfo> sortedPlayerMap = new LinkedHashMap<>();
            UNFILTERED_PKT_TYPE_STATS.entrySet().stream().sorted(Map.Entry.<PacketType.Play.Server, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPktMap.put(e.getKey(), e.getValue()));
            UNFILTERED_PLAYER_PKT_SAVED_STATS.entrySet().stream().sorted(Map.Entry.<UUID, PacketInfo>comparingByValue().reversed()).forEachOrdered(e -> sortedPlayerMap.put(e.getKey(), e.getValue()));
            sender.sendMessage(ChatColor.YELLOW + " -- 数据包类型 TOP 15 --");
            sortedPktMap.entrySet().stream().limit(15).forEach(entry -> sender.sendMessage(ChatColor.GRAY + entry.getKey().name() + " - " + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));
            sender.sendMessage(ChatColor.YELLOW + " -- 玩家流量 TOP 5 --");
            sortedPlayerMap.entrySet().stream().limit(5).forEach(entry -> sender.sendMessage(ChatColor.GRAY + Bukkit.getOfflinePlayer(entry.getKey()).getName() + " - " + humanReadableByteCount(entry.getValue().getPktSize().longValue(), false)));
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("riabandwidthsaver.reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "🍃 ECO - 配置文件已重载 - RIA.RED - Maintained by Ghost_chu");
        }
        return true;
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    private class PacketCalcListener extends PacketListenerAbstract {

        public PacketCalcListener() {
            super(PacketListenerPriority.MONITOR);
        }

        @Override
        public void onPacketSend(@NonNull PacketSendEvent event) {
            if (!(event.getPacketType() instanceof PacketType.Play.Server packetType)) {
                return;
            }
            User user = event.getUser();
            int calculatedSize = calculatePacketSize(event);

            CompletableFuture.runAsync(() -> {
                UNFILTERED_PKT_TYPE_STATS.compute(packetType, (k, v) -> {
                    if (v == null) {
                        v = new PacketInfo();
                    }
                    v.getPktCounter().increment();
                    v.getPktSize().add(calculatedSize);
                    return v;
                });

                user.getUUID();
                UNFILTERED_PLAYER_PKT_SAVED_STATS.compute(user.getUUID(), (k, v) -> {
                    if (v == null) {
                        v = new PacketInfo();
                    }
                    v.getPktCounter().increment();
                    v.getPktSize().add(calculatedSize);
                    return v;
                });
            }, EXECUTOR_SERVICE);
        }
    }

    private class PacketListener extends PacketListenerAbstract {

        private final List<PacketType.Play.Server> packets = List.of(
                PacketType.Play.Server.ENTITY_ANIMATION,
                PacketType.Play.Server.BLOCK_BREAK_ANIMATION,
                PacketType.Play.Server.ENTITY_SOUND_EFFECT,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.PARTICLE,
                PacketType.Play.Server.EXPLOSION,
                PacketType.Play.Server.TIME_UPDATE,
                PacketType.Play.Server.ENTITY_HEAD_LOOK,
                PacketType.Play.Server.HURT_ANIMATION,
                PacketType.Play.Server.DAMAGE_EVENT,
                PacketType.Play.Server.ENTITY_RELATIVE_MOVE,
                PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION,
                PacketType.Play.Server.SPAWN_EXPERIENCE_ORB,
                PacketType.Play.Server.VEHICLE_MOVE,
                PacketType.Play.Server.BLOCK_ACTION,
                PacketType.Play.Server.UPDATE_LIGHT,
                PacketType.Play.Server.PLAYER_LIST_HEADER_AND_FOOTER,
//                PacketType.Play.Server.WORLD_EVENT,
                PacketType.Play.Server.COLLECT_ITEM,
                PacketType.Play.Server.ENTITY_EFFECT
        );

        public PacketListener() {
            super(PacketListenerPriority.HIGHEST);
        }

        @Override
        public void onPacketSend(@NonNull PacketSendEvent event) {
            User user = event.getUser();
            UUID uuid = user.getUUID();

            if (!AFK_PLAYERS.contains(uuid)) {
                return;
            }

            if (!(event.getPacketType() instanceof PacketType.Play.Server type)) {
                return;
            }
            if (!packets.contains(type)) {
                return;
            }

            if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE ||
                    type == PacketType.Play.Server.VEHICLE_MOVE ||
                    type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION ||
                    type == PacketType.Play.Server.SPAWN_EXPERIENCE_ORB) {

                if (RANDOM.nextInt(3) > 0) {
                    return;
                }
            }

            if (type == PacketType.Play.Server.SET_SLOT) {
                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                int windowId = wrapper.getWindowId();

                if (windowId == 0) {
                    return;
                }
            }

            event.setCancelled(true);

            int packetSize = calculatePacketSize(event);
            CompletableFuture.runAsync(() -> {
                PKT_TYPE_STATS.compute(type, (k, v) -> {
                    if (v == null) {
                        v = new PacketInfo();
                    }
                    v.getPktCounter().increment();
                    v.getPktSize().add(packetSize);
                    return v;
                });

                PLAYER_PKT_SAVED_STATS.compute(uuid, (k, v) -> {
                    if (v == null) {
                        v = new PacketInfo();
                    }
                    v.getPktCounter().increment();
                    v.getPktSize().add(packetSize);
                    return v;
                });
            }, EXECUTOR_SERVICE);
        }
    }
}
