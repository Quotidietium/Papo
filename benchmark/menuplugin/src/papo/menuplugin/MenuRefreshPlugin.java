package papo.menuplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 批次116：菜单插件刷新模式复现插件——用户实例头号剩余假说的服内量化载体。
 *
 * 典型箱子菜单插件（ChestCommands 类）的每次交互/刷新动作：取消点击 + 重建全部
 * 54 格物品（带 name/lore/custom_model_data 组件）+ player.updateInventory()
 * （全量容器重同步）。批次115 已排除命令派发层；本插件把监听器工作层的标志性
 * 成本在服内复现，供 MenuPluginBench 量化（0/10/30 Hz 三档）。
 *
 * 命令：/menurefresh <hz>（0=停）——调度器按频率对每个打开菜单的玩家执行刷新；
 * /menurefresh heavy <0|1>——切换重 NBT 物品（附魔+属性）。
 */
public final class MenuRefreshPlugin extends JavaPlugin implements Listener {

    private int refreshRateHz;
    private int refreshTaskId = -1;
    private boolean heavyItems;
    private Inventory menu;

    @Override
    public void onEnable() {
        this.refreshRateHz = 0;
        Bukkit.getPluginManager().registerEvents(this, this);
        this.menu = Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.text("菜单"));
        rebuildMenu();
        this.getLogger().info("MenuRefresh ready: /menurefresh <hz> (heavy=" + this.heavyItems + ")");
    }

    /** 典型菜单重建：54 格 ×（name+lore+CMD 组件，heavy 时 +附魔+属性）。 */
    private void rebuildMenu() {
        for (int i = 0; i < 54; i++) {
            final ItemStack item = new ItemStack(org.bukkit.Material.DIAMOND_SWORD, 1);
            final ItemMeta meta = item.getItemMeta();
            meta.displayName(net.kyori.adventure.text.Component.text("菜单物品 #" + i));
            meta.lore(java.util.List.of(
                net.kyori.adventure.text.Component.text("第 " + (i / 9 + 1) + " 行"),
                net.kyori.adventure.text.Component.text("点击执行操作")));
            meta.setCustomModelData(1000 + i);
            if (this.heavyItems) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.LOOTING, 3, true);
            }
            item.setItemMeta(meta);
            this.menu.setItem(i, item);
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        // 进场 2 秒后自动打开菜单（真实菜单插件的常见行为）
        Bukkit.getScheduler().runTaskLater(this, () -> {
            event.getPlayer().openInventory(this.menu);
            Bukkit.getScheduler().runTask(this, () -> {
                final boolean top = event.getPlayer().getOpenInventory().getTopInventory() == this.menu;
                this.getLogger().info("opened for " + event.getPlayer().getName() + " viewTopIsMenu=" + top);
            });
        }, 40L);
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        // 菜单插件典型：取消一切点击并刷新
        if (event.getView().getTopInventory() == this.menu) {
            event.setCancelled(true);
            refreshFor(event.getView());
        }
    }

    /** 单次刷新：全量重设 54 格 + 全量重同步（updateInventory=sendAllDataToRemote）。 */
    private void refreshFor(final InventoryView view) {
        for (int i = 0; i < 54; i++) {
            view.getTopInventory().setItem(i, this.menu.getItem(i));
        }
        if (view.getPlayer() instanceof final Player p) {
            p.updateInventory();
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length >= 1 && "heavy".equals(args[0])) {
            this.heavyItems = args.length < 2 || "1".equals(args[1]);
            rebuildMenu();
            sender.sendMessage("heavy=" + this.heavyItems);
            return true;
        }
        final int hz = args.length >= 1 ? Integer.parseInt(args[0]) : 0;
        setRate(hz);
        sender.sendMessage("rate=" + this.refreshRateHz + "Hz");
        return true;
    }

    private void setRate(final int hz) {
        this.refreshRateHz = Math.max(0, hz);
        if (this.refreshTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(this.refreshTaskId);
            this.refreshTaskId = -1;
        }
        if (this.refreshRateHz > 0) {
            final long periodTicks = Math.max(1, 20 / this.refreshRateHz);
            final int[] exec = {0};
            this.refreshTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
                int viewers = 0;
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getOpenInventory().getTopInventory() == this.menu) {
                        viewers++;
                        refreshFor(p.getOpenInventory());
                    }
                }
                if (++exec[0] % 200 == 1) {
                    this.getLogger().info("refresh task alive: exec=" + exec[0] + " viewers=" + viewers
                        + " online=" + Bukkit.getOnlinePlayers().size());
                }
            }, 1L, periodTicks).getTaskId();
        }
    }
}
