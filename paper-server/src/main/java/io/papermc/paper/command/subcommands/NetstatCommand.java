package io.papermc.paper.command.subcommands;

import io.papermc.paper.command.PaperSubcommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

/**
 * Papo - /paper netstat: per-player and server-wide wire bandwidth accounting.
 *
 * Backed by the per-connection byte counters threaded through the frame codec factory (prepender
 * counts every outbound frame's exact wire size post-compression; splitter counts every inbound
 * frame), snapshotted once per second in Connection#tickSecond. Pure observation - the counters
 * are lock-free adds on the channel's event loop and cost nothing when nobody runs this command.
 *
 * Usage: /paper netstat [topN|all]  (default top 10 players by outbound bytes/s)
 */
@DefaultQualifier(NonNull.class)
public final class NetstatCommand implements PaperSubcommand {

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        int limit = 10;
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("all")) {
                limit = Integer.MAX_VALUE;
            } else {
                try {
                    limit = Math.max(1, Integer.parseInt(args[0]));
                } catch (final NumberFormatException ignored) {
                    // keep default
                }
            }
        }

        final List<net.minecraft.server.level.ServerPlayer> players = new ArrayList<>(Bukkit.getOnlinePlayers().stream()
            .map(p -> ((CraftPlayer) p).getHandle())
            .toList());
        players.sort(Comparator.comparingLong((final net.minecraft.server.level.ServerPlayer p) -> {
            final Connection c = this.connection(p);
            return c == null ? 0L : c.getPapoSentWireBytesPerSecond();
        }).reversed());

        long totalOutSec = 0L;
        long totalInSec = 0L;
        long totalOutAll = 0L;
        long totalInAll = 0L;
        for (final net.minecraft.server.level.ServerPlayer p : players) {
            final Connection c = this.connection(p);
            if (c == null) {
                continue;
            }
            totalOutSec += c.getPapoSentWireBytesPerSecond();
            totalInSec += c.getPapoReceivedWireBytesPerSecond();
            totalOutAll += c.getPapoSentWireBytesTotal();
            totalInAll += c.getPapoReceivedWireBytesTotal();
        }

        final TextComponent.Builder builder = Component.text();
        builder.append(Component.text("Server (last 1s): ", NamedTextColor.GOLD))
            .append(Component.text("out " + humanBytes(totalOutSec) + "/s", NamedTextColor.GREEN))
            .append(Component.text(", in " + humanBytes(totalInSec) + "/s", NamedTextColor.AQUA))
            .append(Component.text("  |  totals: out " + humanBytes(totalOutAll) + ", in " + humanBytes(totalInAll), NamedTextColor.GRAY))
            .append(Component.newline());
        builder.append(Component.text("Per player (out/s desc, wire bytes after compression):", NamedTextColor.GOLD))
            .append(Component.newline());
        int shown = 0;
        for (final net.minecraft.server.level.ServerPlayer p : players) {
            if (shown++ >= limit) {
                break;
            }
            final Connection c = this.connection(p);
            if (c == null) {
                continue;
            }
            builder.append(Component.join(JoinConfiguration.separator(Component.text("  ", NamedTextColor.DARK_GRAY)),
                    Component.text(p.getScoreboardName(), NamedTextColor.WHITE),
                    Component.text("out " + humanBytes(c.getPapoSentWireBytesPerSecond()) + "/s", NamedTextColor.GREEN),
                    Component.text("in " + humanBytes(c.getPapoReceivedWireBytesPerSecond()) + "/s", NamedTextColor.AQUA),
                    Component.text(String.format("%.1f pkt/s", c.getAverageSentPackets()), NamedTextColor.YELLOW),
                    Component.text("tot " + humanBytes(c.getPapoSentWireBytesTotal()), NamedTextColor.GRAY)
                ))
                .append(Component.newline());
        }
        if (players.isEmpty()) {
            builder.append(Component.text("No players online.", NamedTextColor.GRAY)).append(Component.newline());
        } else if (limit < players.size()) {
            builder.append(Component.text("... " + (players.size() - limit) + " more (use /paper netstat all)", NamedTextColor.GRAY))
                .append(Component.newline());
        }
        sender.sendMessage(builder.build());
        return true;
    }

    private Connection connection(final net.minecraft.server.level.ServerPlayer player) {
        final ServerGamePacketListenerImpl listener = player.connection;
        return listener == null ? null : listener.connection;
    }

    private static String humanBytes(final long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        final double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format("%.1f KiB", kib);
        }
        final double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format("%.1f MiB", mib);
        }
        return String.format("%.2f GiB", mib / 1024.0);
    }
}
