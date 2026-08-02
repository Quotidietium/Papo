package com.destroystokyo.paper.network;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.CachedServerIcon;

import javax.annotation.Nullable;

class PaperServerListPingEventImpl extends PaperServerListPingEvent {

    private final MinecraftServer server;

    PaperServerListPingEventImpl(MinecraftServer server, StatusClient client, int protocolVersion, @Nullable CachedServerIcon icon) {
        super(client, server.motd(), server.getPlayerCount(), server.getMaxPlayers(),
                papoVersionName(server), protocolVersion, icon); // Papo - fingerprint hardening (status.version-string)
        this.server = server;
    }

    // Papo start - fingerprint hardening: resolve the ping version string per GlobalConfiguration.
    // GlobalConfiguration.get() is a static field read (throw-free); null when config not yet loaded.
    private static String papoVersionName(final MinecraftServer server) {
        final String realVersionName = server.getServerModName() + ' ' + server.getServerVersion();
        final io.papermc.paper.configuration.GlobalConfiguration cfg = io.papermc.paper.configuration.GlobalConfiguration.get();
        return (cfg != null) ? cfg.fingerprintHardening.status.resolve(realVersionName, server.getServerVersion()) : realVersionName;
    }
    // Papo end - fingerprint hardening

    @Override
    protected final Object[] getOnlinePlayers() {
        return this.server.getPlayerList().players.toArray();
    }

    @Override
    protected final Player getBukkitPlayer(Object player) {
        return ((ServerPlayer) player).getBukkitEntity();
    }

}
