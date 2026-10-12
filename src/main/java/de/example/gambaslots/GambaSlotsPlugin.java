package de.example.gambaslots;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class GambaSlotsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final int GUI_SIZE = 54;
    private static final int[] REEL_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    private static final int[] PAYLINE_SLOTS = {21, 22, 23};

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, Long> bets = new HashMap<>();
    private final Set<UUID> invalidBetInput = new HashSet<>();
    private final Set<UUID> spinning = new HashSet<>();
    private final Map<UUID, BukkitTask> spinTasks = new HashMap<>();
    private final Map<Material, SlotSymbol> symbols = new LinkedHashMap<>();

    private File balancesFile;
    private YamlConfiguration balancesYaml;
    private long startingBalance;
    private long maximumBet;
    private int minimumSpinTicks;
    private int maximumSpinTicks;
    private int spinUpdateTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readSettings();
        loadBalances();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("gamba") == null) {
            getLogger().severe("Der Befehl /gamba fehlt in plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("gamba").setExecutor(this);
        getLogger().info("GambaSlots für Paper 1.21.11 ist gestartet.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : spinTasks.values()) {
            task.cancel();
        }
        spinTasks.clear();
        spinning.clear();
        saveBalances();
    }

    private void readSettings() {
        reloadConfig();
        startingBalance = Math.max(0L, getConfig().getLong("starting-balance", 1000L));
        maximumBet = Math.max(1L, Math.min(1_000_000_000L, getConfig().getLong("maximum-bet", 1_000_000L)));
        minimumSpinTicks = Math.max(40, Math.min(80, getConfig().getInt("spin-min-ticks", 40)));
        maximumSpinTicks = Math.max(minimumSpinTicks, Math.min(80, getConfig().getInt("spin-max-ticks", 80)));
        spinUpdateTicks = Math.max(1, Math.min(2, getConfig().getInt("spin-update-ticks", 2)));
        symbols.clear();

        ConfigurationSection section = getConfig().getConfigurationSection("symbols");
        if (section != null) {
            for (String materialKey : section.getKeys(false)) {
                Material material = Material.matchMaterial(materialKey);
                ConfigurationSection symbolSection = section.getConfigurationSection(materialKey);
                if (material == null || symbolSection == null || !material.isItem()) {
                    getLogger().warning("Ungültiges Symbol in config.yml: " + materialKey);
                    continue;
                }
                int weight = symbolSection.getInt("weight", 0);
                int multiplier = symbolSection.getInt("multiplier", 1);
                if (weight <= 0 || multiplier < 1) {
                    getLogger().warning("Gewicht und Multiplikator müssen positiv sein: " + materialKey);
                    continue;
                }
                symbols.put(material, new SlotSymbol(
                        material,
                        symbolSection.getString("name", material.name()),
                        weight,
                        multiplier
                ));
            }
        }
        if (symbols.isEmpty()) {
            getLogger().severe("Keine gültigen Symbole in config.yml gefunden. Plugin wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void loadBalances() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Plugin-Ordner konnte nicht angelegt werden.");
        }
        balancesFile = new File(getDataFolder(), "balances.yml");
        balancesYaml = YamlConfiguration.loadConfiguration(balancesFile);
        for (String key : balancesYaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                balances.put(uuid, Math.max(0L, balancesYaml.getLong(key, startingBalance)));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Ungültige UUID in balances.yml: " + key);
            }
        }
    }

    private void saveBalances() {
        if (balancesFile == null || balancesYaml == null) {
            return;
        }
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            balancesYaml.set(entry.getKey().toString(), Math.max(0L, entry.getValue()));
        }
        try {
            balancesYaml.save(balancesFile);
        } catch (IOException exception) {
            getLogger().severe("Guthaben konnten nicht gespeichert werden: " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Diesen Befehl kann nur ein Spieler verwenden.", NamedTextColor.RED));
                return true;
            }
            openMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("bet")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Diesen Befehl kann nur ein Spieler verwenden.", NamedTextColor.RED));
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(Component.text("Benutzung: /gamba bet <betrag>", NamedTextColor.YELLOW));
                return true;
            }
            setBetFromCommand(player, args[1]);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("gamba.admin")) {
                sender.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(Component.text("Benutzung: /gamba give <spieler> <betrag>", NamedTextColor.YELLOW));
                return true;
            }
            giveCoins(sender, args[1], args[2]);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("gamba.admin")) {
                sender.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
                return true;
            }
            readSettings();
            sender.sendMessage(Component.text("GambaSlots-Konfiguration neu geladen.", NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Benutzung: /gamba [bet <betrag>|give <spieler> <betrag>|reload]", NamedTextColor.YELLOW));
        return true;
    }

    private void setBetFromCommand(Player player, String rawAmount) {
        long amount;
        try {
            amount = Long.parseLong(rawAmount);
        } catch (NumberFormatException exception) {
            invalidBetInput.add(player.getUniqueId());
            player.sendMessage(Component.text("Ungültiger Einsatz. Gib eine Zahl größer als 0 ein; Drehen ist bis dahin blockiert.", NamedTextColor.RED));
            refreshOpenMenu(player);
            return;
        }

        if (amount <= 0 || amount > maximumBet) {
            invalidBetInput.add(player.getUniqueId());
            player.sendMessage(Component.text("Der Einsatz muss mindestens 1 und höchstens " + format(maximumBet) + " Münzen betragen. Drehen ist blockiert, bis du einen gültigen Einsatz setzt.", NamedTextColor.RED));
            refreshOpenMenu(player);
            return;
        }

        bets.put(player.getUniqueId(), amount);
        invalidBetInput.remove(player.getUniqueId());
        player.sendMessage(Component.text("Einsatz auf " + format(amount) + " Münzen gesetzt.", NamedTextColor.GREEN));
        refreshOpenMenu(player);
    }

    private void giveCoins(CommandSender sender, String playerName, String rawAmount) {
        long amount;
        try {
            amount = Long.parseLong(rawAmount);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Der Betrag muss eine positive ganze Zahl sein.", NamedTextColor.RED));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(Component.text("Der Betrag muss größer als 0 sein.", NamedTextColor.RED));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        UUID uuid = target.getUniqueId();
        long oldBalance = balances.getOrDefault(uuid, startingBalance);
        if (Long.MAX_VALUE - oldBalance < amount) {
            sender.sendMessage(Component.text("Das Guthaben wäre zu groß.", NamedTextColor.RED));
            return;
        }
        balances.put(uuid, oldBalance + amount);
        saveBalances();
        sender.sendMessage(Component.text(format(amount) + " Münzen an " + playerName + " gegeben.", NamedTextColor.GREEN));
        Player onlineTarget = Bukkit.getPlayer(uuid);
        if (onlineTarget != null) {
            onlineTarget.sendMessage(Component.text("Du hast " + format(amount) + " Münzen erhalten. Guthaben: " + format(balanceOf(uuid)) + ".", NamedTextColor.GREEN));
            refreshOpenMenu(onlineTarget);
        }
    }

    private void openMenu(Player player) {
        player.openInventory(createMenu(player));
    }

    private Inventory createMenu(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text("✦ Slotsmaschine ✦", NamedTextColor.LIGHT_PURPLE));
        holder.inventory = inventory;
        fillBackground(inventory);
        setItem(inventory, 4, Material.GOLD_INGOT, "✦ " + format(balanceOf(player.getUniqueId())) + " Münzen ✦",
                List.of("Dein virtuelles Guthaben", "Einsatz mit den Knöpfen unten ändern."), NamedTextColor.GOLD);
        setItem(inventory, 46, Material.REDSTONE_TORCH, "Einsatz −", List.of("Linksklick: −1", "Rechtsklick: −10", "Minimum: 1 Münze"), NamedTextColor.RED);
        setItem(inventory, 48, Material.GOLD_NUGGET, "Einsatz: " + format(betOf(player.getUniqueId())) + " Münzen",
                List.of("Du kannst auch /gamba bet <betrag> schreiben.", "Einsätze von 0 oder weniger werden abgelehnt."), NamedTextColor.YELLOW);
        setItem(inventory, 50, Material.EMERALD, "Einsatz +", List.of("Linksklick: +1", "Rechtsklick: +10", "Maximum: " + format(maximumBet)), NamedTextColor.GREEN);
        setItem(inventory, 52, Material.LIME_CONCRETE, spinning.contains(player.getUniqueId()) ? "Dreht …" : "Drehen",
                List.of("Kostet " + format(betOf(player.getUniqueId())) + " Münzen", "Dauer: zufällig 2–4 Sekunden", "Drei gleiche Erze in der mittleren Reihe gewinnen."), NamedTextColor.GREEN);
        paintSymbols(inventory, randomReelSymbols());
        setPaylineMarkers(inventory);
        if (invalidBetInput.contains(player.getUniqueId())) {
            setItem(inventory, 40, Material.BARRIER, "Drehen blockiert", List.of("Setze zuerst einen gültigen Einsatz:", "/gamba bet <betrag>"), NamedTextColor.RED);
        } else {
            setItem(inventory, 40, Material.BOOK, "Gewinne", payoutLore(), NamedTextColor.AQUA);
        }
        return inventory;
    }

    private void fillBackground(Inventory inventory) {
        for (int slot = 0; slot < GUI_SIZE; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            Material pane = row == 0 || row == 5 || column == 0 || column == 8
                    ? Material.PURPLE_STAINED_GLASS_PANE
                    : Material.BLACK_STAINED_GLASS_PANE;
            setItem(inventory, slot, pane, " ", List.of(), NamedTextColor.DARK_PURPLE);
        }
        for (int slot : REEL_SLOTS) {
            setItem(inventory, slot, Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), NamedTextColor.GRAY);
        }
        for (int slot : new int[]{46, 48, 50, 52}) {
            setItem(inventory, slot, Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), NamedTextColor.DARK_PURPLE);
        }
    }

    private void setPaylineMarkers(Inventory inventory) {
        setItem(inventory, 20, Material.PURPLE_STAINED_GLASS_PANE, "Payline →", List.of("Die mittlere Reihe entscheidet."), NamedTextColor.LIGHT_PURPLE);
        setItem(inventory, 24, Material.PURPLE_STAINED_GLASS_PANE, "← Payline", List.of("Drei gleiche Symbole gewinnen."), NamedTextColor.LIGHT_PURPLE);
    }

    private List<Component> payoutLore() {
        List<Component> lore = new ArrayList<>();
        for (SlotSymbol symbol : symbols.values()) {
            lore.add(Component.text("3× " + symbol.name + ": Auszahlung ×" + symbol.multiplier, NamedTextColor.GRAY));
        }
        lore.add(Component.text("Die Auszahlung enthält den Einsatz.", NamedTextColor.DARK_GRAY));
        return lore;
    }

    private void setItem(Inventory inventory, int slot, Material material, String name, List<String> lore, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private void setSymbolItem(Inventory inventory, int slot, SlotSymbol symbol) {
        setItem(inventory, slot, symbol.material, symbol.name, List.of("Treffer-Multiplikator: ×" + symbol.multiplier), NamedTextColor.WHITE);
    }

    private void paintSymbols(Inventory inventory, SlotSymbol[] reelSymbols) {
        for (int i = 0; i < REEL_SLOTS.length; i++) {
            setSymbolItem(inventory, REEL_SLOTS[i], reelSymbols[i]);
        }
    }

    private SlotSymbol[] randomReelSymbols() {
        SlotSymbol[] result = new SlotSymbol[REEL_SLOTS.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = randomSymbol();
        }
        return result;
    }

    private SlotSymbol randomSymbol() {
        long totalWeight = symbols.values().stream().mapToLong(symbol -> symbol.weight).sum();
        long ticket = ThreadLocalRandom.current().nextLong(totalWeight);
        for (SlotSymbol symbol : symbols.values()) {
            ticket -= symbol.weight;
            if (ticket < 0) {
                return symbol;
            }
        }
        return symbols.values().iterator().next();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != holder.inventory) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (spinning.contains(uuid)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == 46) {
            adjustBet(player, -clickStep(event.getClick()));
        } else if (slot == 50) {
            adjustBet(player, clickStep(event.getClick()));
        } else if (slot == 52) {
            startSpin(player, holder.inventory);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private long clickStep(ClickType click) {
        return click == ClickType.RIGHT ? 10L : 1L;
    }

    private void adjustBet(Player player, long amount) {
        UUID uuid = player.getUniqueId();
        long current = betOf(uuid);
        long next;
        if (amount > 0) {
            next = Math.min(maximumBet, current > maximumBet - amount ? maximumBet : current + amount);
        } else {
            next = Math.max(1L, current + amount);
        }
        bets.put(uuid, next);
        invalidBetInput.remove(uuid);
        refreshOpenMenu(player);
    }

    private void startSpin(Player player, Inventory menu) {
        UUID uuid = player.getUniqueId();
        long stake = betOf(uuid);
        if (invalidBetInput.contains(uuid) || stake <= 0) {
            player.sendMessage(Component.text("Drehen blockiert: Der Einsatz muss mindestens 1 Münze betragen.", NamedTextColor.RED));
            return;
        }
        if (stake > maximumBet) {
            invalidBetInput.add(uuid);
            player.sendMessage(Component.text("Drehen blockiert: Der Einsatz ist höher als das erlaubte Maximum.", NamedTextColor.RED));
            refreshOpenMenu(player);
            return;
        }
        long balance = balanceOf(uuid);
        if (balance < stake) {
            player.sendMessage(Component.text("Du hast nicht genug Münzen für diesen Einsatz. Guthaben: " + format(balance) + ".", NamedTextColor.RED));
            return;
        }

        balances.put(uuid, balance - stake);
        saveBalances();
        spinning.add(uuid);
        SlotSymbol[] outcome = randomReelSymbols();
        int durationTicks = ThreadLocalRandom.current().nextInt(minimumSpinTicks, maximumSpinTicks + 1);
        player.sendMessage(Component.text("Die Walzen drehen sich …", NamedTextColor.LIGHT_PURPLE));
        refreshOpenMenu(player);

        BukkitTask task = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                elapsedTicks += spinUpdateTicks;
                if (onlinePlayer != null && onlinePlayer.isOnline() && onlinePlayer.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                    paintSymbols(onlinePlayer.getOpenInventory().getTopInventory(), randomReelSymbols());
                    setPaylineMarkers(onlinePlayer.getOpenInventory().getTopInventory());
                    setItem(onlinePlayer.getOpenInventory().getTopInventory(), 4, Material.GOLD_INGOT,
                            "✦ " + format(balanceOf(uuid)) + " Münzen ✦", List.of("Dein virtuelles Guthaben"), NamedTextColor.GOLD);
                }
                if (elapsedTicks >= durationTicks) {
                    finishSpin(uuid, stake, outcome);
                    cancel();
                }
            }
        }.runTaskTimer(this, spinUpdateTicks, spinUpdateTicks);
        spinTasks.put(uuid, task);
    }

    private void finishSpin(UUID uuid, long stake, SlotSymbol[] outcome) {
        spinTasks.remove(uuid);
        spinning.remove(uuid);
        long currentBalance = balanceOf(uuid);
        boolean win = outcome[3].material == outcome[4].material && outcome[4].material == outcome[5].material;
        long payout = 0L;
        if (win) {
            int multiplier = outcome[4].multiplier;
            try {
                payout = Math.multiplyExact(stake, multiplier);
            } catch (ArithmeticException exception) {
                getLogger().severe("Auszahlung überläuft den Long-Bereich für " + uuid);
            }
            if (Long.MAX_VALUE - currentBalance < payout) {
                payout = Long.MAX_VALUE - currentBalance;
            }
            balances.put(uuid, currentBalance + payout);
        }
        saveBalances();

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof MenuHolder) {
            paintSymbols(top, outcome);
            setPaylineMarkers(top);
            setItem(top, 4, Material.GOLD_INGOT, "✦ " + format(balanceOf(uuid)) + " Münzen ✦",
                    List.of("Dein virtuelles Guthaben"), NamedTextColor.GOLD);
            setItem(top, 52, Material.LIME_CONCRETE, "Drehen", List.of("Kostet " + format(betOf(uuid)) + " Münzen", "Dauer: zufällig 2–4 Sekunden"), NamedTextColor.GREEN);
        }
        if (win && payout > 0) {
            player.sendMessage(Component.text("GEWINN! Drei " + outcome[4].name + " — Auszahlung: " + format(payout)
                    + " Münzen (netto +" + format(Math.max(0L, payout - stake)) + ").", NamedTextColor.GOLD));
        } else {
            player.sendMessage(Component.text("Kein Treffer. In der mittleren Reihe müssen drei gleiche Erze liegen.", NamedTextColor.GRAY));
        }
    }

    private void refreshOpenMenu(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
            player.openInventory(createMenu(player));
        }
    }

    private long balanceOf(UUID uuid) {
        return balances.computeIfAbsent(uuid, ignored -> startingBalance);
    }

    private long betOf(UUID uuid) {
        return bets.getOrDefault(uuid, Math.min(10L, maximumBet));
    }

    private String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount);
    }

    private record SlotSymbol(Material material, String name, int weight, int multiplier) { }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
