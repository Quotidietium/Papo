package org.bukkit.craftbukkit.util.permissions;

import org.bukkit.permissions.Permission;
import org.bukkit.util.permissions.DefaultPermissions;

public final class CraftDefaultPermissions {
    private static final String ROOT = "minecraft";

    private CraftDefaultPermissions() {}

    public static void registerCorePermissions() {
        Permission parent = DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT, "Gives the user the ability to use all vanilla utilities and commands");
        CommandPermissions.registerPermissions(parent);
        papoOverrideCommandVisibility(); // Papo - fingerprint hardening (commands.playerVisibleDefaults)
        DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT + ".nbt.place", "Gives the user the ability to place restricted blocks with NBT in creative", org.bukkit.permissions.PermissionDefault.OP, parent);
        DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT + ".nbt.copy", "Gives the user the ability to copy NBT in creative", org.bukkit.permissions.PermissionDefault.TRUE, parent);
        DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT + ".debugstick", "Gives the user the ability to use the debug stick in creative", org.bukkit.permissions.PermissionDefault.OP, parent);
        DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT + ".debugstick.always", "Gives the user the ability to use the debug stick in all game modes", org.bukkit.permissions.PermissionDefault.FALSE/* , parent */); // Paper - should not have this parent, as it's not a "vanilla" utility
        DefaultPermissions.registerPermission(CraftDefaultPermissions.ROOT + ".commandblock", "Gives the user the ability to use command blocks.", org.bukkit.permissions.PermissionDefault.OP, parent); // Paper
        parent.recalculatePermissibles();
    }

    // Papo start - fingerprint hardening: override the player-visible command permission defaults
    // (bukkit.command.plugins / version / help) per GlobalConfiguration.fingerprintHardening.commands.
    // These three default to TRUE (every player can run /plugins etc., leaking the plugin list).
    // Applied once at startup (registerCorePermissions, during CraftServer.enablePlugins): the plugin
    // manager is initialised and no players are online yet, so setDefault + recalculate is safe.
    // GlobalConfiguration.get() is a static field read (throw-free); null when config not yet loaded.
    private static void papoOverrideCommandVisibility() {
        final io.papermc.paper.configuration.GlobalConfiguration cfg = io.papermc.paper.configuration.GlobalConfiguration.get();
        if (cfg == null) return; // config not loaded → keep vanilla defaults (TRUE)
        final org.bukkit.permissions.PermissionDefault def = cfg.fingerprintHardening.commands.papoResolveDefault();
        if (def == org.bukkit.permissions.PermissionDefault.TRUE) return; // current behavior, nothing to change
        for (final String name : new String[]{"bukkit.command.plugins", "bukkit.command.version", "bukkit.command.help"}) {
            final org.bukkit.permissions.Permission perm = org.bukkit.Bukkit.getPluginManager().getPermission(name);
            if (perm != null) {
                perm.setDefault(def);
                perm.recalculatePermissibles();
            }
        }
    }
    // Papo end - fingerprint hardening
}
