package me.angel.starter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class StarterPlugin extends JavaPlugin implements Listener {

    private static final int HUB_X = 0;
    private static final int HUB_Y = 130;
    private static final int HUB_Z = 0;
    private static final int QUEUE_Z = 20;

    private static final String ARENA_WORLD_NAME = "starter_spleef";
    private static final int ARENA_FLOOR_Y = 100;
    private static final int ARENA_RADIUS = 15;
    private static final String MENU_TITLE = ChatColor.DARK_AQUA + "Minigames";

    private enum MatchState {
        WAITING,
        STARTING_GAME,
        ACTIVE
    }

    private World hubWorld;
    private World arenaWorld;
    private NamespacedKey compassKey;

    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> matchPlayers = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Set<UUID> restoreAfterRespawn = new LinkedHashSet<>();
    private final java.util.Map<UUID, PlayerState> savedStates = new java.util.HashMap<>();

    private MatchState matchState = MatchState.WAITING;
    private BukkitTask queueCountdownTask;
    private BukkitTask gameCountdownTask;
    private int queueSecondsRemaining;

    @Override
    public void onEnable() {
        compassKey = new NamespacedKey(this, "hub_compass");

        if (Bukkit.getWorlds().isEmpty()) {
            getLogger().severe("No Minecraft world is loaded; StarterPlugin cannot start.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        hubWorld = Bukkit.getWorlds().get(0);
        hubWorld.setSpawnLocation(HUB_X, HUB_Y, HUB_Z);
        buildHubPlatforms();

        arenaWorld = Bukkit.getWorld(ARENA_WORLD_NAME);
        if (arenaWorld == null) {
            WorldCreator creator = new WorldCreator(ARENA_WORLD_NAME);
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
            arenaWorld = creator.createWorld();
        }

        if (arenaWorld == null) {
            getLogger().severe("Could not create the Spleef arena world.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        arenaWorld.setSpawnLocation(0, ARENA_FLOOR_Y + 1, 0);
        prepareArena();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("StarterPlugin is enabled! Hub and Spleef queue are ready.");
    }

    @Override
    public void onDisable() {
        cancelTask(queueCountdownTask);
        cancelTask(gameCountdownTask);
        for (UUID id : new ArrayList<>(savedStates.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !player.isDead()) {
                teleportToHub(player, false);
                restorePlayerState(player);
                giveCompassIfMissing(player);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hello")) {
            sender.sendMessage(ChatColor.GREEN + "Hello from my first Minecraft plugin!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("hub")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only a player can use /hub.");
                return true;
            }

            UUID id = player.getUniqueId();
            if (queue.remove(id)) {
                player.sendMessage(ChatColor.YELLOW + "You left the Spleef queue.");
                cancelQueueCountdownIfTooSmall();
            }
            if (activePlayers.contains(id)) {
                eliminatePlayer(id, "You left the Spleef match.", false);
            } else {
                teleportToHub(player, true);
            }
            return true;
        }

        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online != null) {
                teleportToHub(online, true);
            }
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        event.setRespawnLocation(hubLocation());
        Bukkit.getScheduler().runTask(this, () -> {
            if (restoreAfterRespawn.remove(player.getUniqueId())) {
                restorePlayerState(player);
            }
            giveCompassIfMissing(player);
        });
    }

    @EventHandler
    public void onCompassUse(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (!isHubCompass(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        if (event.getAction().name().startsWith("RIGHT_CLICK")) {
            event.getPlayer().openInventory(createMenu(event.getPlayer()));
        }
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            if (event.getRawSlot() == 13 && event.getWhoClicked() instanceof Player player) {
                toggleQueue(player);
                player.openInventory(createMenu(player));
            }
            return;
        }

        if (event.getWhoClicked() instanceof Player player
                && (isHubCompass(event.getCurrentItem()) || isHubCompass(event.getCursor()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (activePlayers.contains(event.getPlayer().getUniqueId())
                || isHubCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!activePlayers.contains(id)) {
            return;
        }

        Block block = event.getBlock();
        boolean validSpleefBlock = matchState == MatchState.ACTIVE
                && block.getWorld().equals(arenaWorld)
                && block.getY() == ARENA_FLOOR_Y
                && Math.abs(block.getX()) <= ARENA_RADIUS
                && Math.abs(block.getZ()) <= ARENA_RADIUS
                && block.getType() == Material.SNOW_BLOCK;

        if (!validSpleefBlock) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (activePlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!activePlayers.contains(id) || matchState != MatchState.ACTIVE || event.getTo() == null) {
            return;
        }

        Location to = event.getTo();
        if (!arenaWorld.equals(to.getWorld())
                || to.getY() <= ARENA_FLOOR_Y - 4
                || Math.abs(to.getX()) > ARENA_RADIUS + 0.5
                || Math.abs(to.getZ()) > ARENA_RADIUS + 0.5) {
            eliminatePlayer(id, "You were eliminated from Spleef.", false);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        if (!activePlayers.contains(id)) {
            return;
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        eliminatePlayer(id, "You were eliminated from Spleef.", true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        queue.remove(id);
        if (activePlayers.remove(id)) {
            checkForWinner();
        }
        matchPlayers.remove(id);
        savedStates.remove(id);
        restoreAfterRespawn.remove(id);
        cancelQueueCountdownIfTooSmall();
    }

    private void toggleQueue(Player player) {
        UUID id = player.getUniqueId();
        if (activePlayers.contains(id) || matchPlayers.contains(id)) {
            player.sendMessage(ChatColor.RED + "You are already in a Spleef match.");
            return;
        }

        if (queue.remove(id)) {
            player.sendMessage(ChatColor.YELLOW + "You left the Spleef queue.");
            teleportToHub(player, true);
            cancelQueueCountdownIfTooSmall();
            return;
        }

        queue.add(id);
        player.sendMessage(ChatColor.GREEN + "You joined the Spleef queue. Players waiting: " + queue.size());
        player.teleport(queueLobbyLocation());
        startQueueCountdownIfReady();
    }

    private void startQueueCountdownIfReady() {
        if (matchState != MatchState.WAITING || queue.size() < 2 || queueCountdownTask != null) {
            return;
        }

        queueSecondsRemaining = 30;
        queueCountdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (matchState != MatchState.WAITING) {
                cancelTask(queueCountdownTask);
                queueCountdownTask = null;
                return;
            }

            removeOfflineQueueEntries();
            if (queue.size() < 2) {
                cancelTask(queueCountdownTask);
                queueCountdownTask = null;
                sendQueueMessage(ChatColor.RED + "The countdown stopped; at least 2 players are needed.");
                return;
            }

            if (queueSecondsRemaining <= 0) {
                cancelTask(queueCountdownTask);
                queueCountdownTask = null;
                beginMatch();
                return;
            }

            if (queueSecondsRemaining == 30 || queueSecondsRemaining % 10 == 0 || queueSecondsRemaining <= 5) {
                sendQueueMessage(ChatColor.GOLD + "Spleef starts in " + queueSecondsRemaining + " seconds.");
            }
            queueSecondsRemaining--;
        }, 0L, 20L);
    }

    private void cancelQueueCountdownIfTooSmall() {
        if (queueCountdownTask != null && queue.size() < 2) {
            cancelTask(queueCountdownTask);
            queueCountdownTask = null;
            sendQueueMessage(ChatColor.RED + "The countdown stopped; at least 2 players are needed.");
        }
    }

    private void removeOfflineQueueEntries() {
        Iterator<UUID> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (Bukkit.getPlayer(iterator.next()) == null) {
                iterator.remove();
            }
        }
    }

    private void sendQueueMessage(String message) {
        for (UUID id : queue) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void beginMatch() {
        removeOfflineQueueEntries();
        if (queue.size() < 2 || matchState != MatchState.WAITING) {
            return;
        }

        prepareArena();
        List<UUID> entrants = new ArrayList<>(queue);
        queue.clear();
        matchPlayers.clear();
        activePlayers.clear();
        restoreAfterRespawn.clear();
        matchState = MatchState.STARTING_GAME;

        int index = 0;
        for (UUID id : entrants) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }

            matchPlayers.add(id);
            activePlayers.add(id);
            savedStates.put(id, new PlayerState(player));

            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setFallDistance(0.0f);
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            player.getInventory().setItem(0, createSpleefShovel());
            player.teleport(arenaSpawn(index, entrants.size()));
            player.sendMessage(ChatColor.AQUA + "The Spleef match is starting!");
            index++;
        }

        if (activePlayers.size() < 2) {
            finishMatch(null);
            return;
        }

        startGameCountdown();
    }

    private void startGameCountdown() {
        final int[] seconds = {3};
        gameCountdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (matchState != MatchState.STARTING_GAME) {
                cancelTask(gameCountdownTask);
                gameCountdownTask = null;
                return;
            }

            if (seconds[0] > 0) {
                for (UUID id : activePlayers) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        player.sendTitle(ChatColor.YELLOW + String.valueOf(seconds[0]), ChatColor.GRAY + "Get ready!", 0, 20, 0);
                    }
                }
                seconds[0]--;
                return;
            }

            cancelTask(gameCountdownTask);
            gameCountdownTask = null;
            matchState = MatchState.ACTIVE;
            for (UUID id : activePlayers) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendTitle(ChatColor.GREEN + "GO!", ChatColor.WHITE + "Break the snow floor!", 0, 30, 5);
                }
            }
        }, 0L, 20L);
    }

    private Location arenaSpawn(int index, int total) {
        double angle = (Math.PI * 2.0 * index) / Math.max(total, 1);
        double radius = Math.min(9.0, ARENA_RADIUS - 4.0);
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        return new Location(arenaWorld, x + 0.5, ARENA_FLOOR_Y + 1.0, z + 0.5);
    }

    private void eliminatePlayer(UUID id, String message, boolean died) {
        if (!activePlayers.remove(id)) {
            return;
        }
        matchPlayers.remove(id);

        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            player.sendMessage(ChatColor.RED + message);
            if (died) {
                restoreAfterRespawn.add(id);
            } else {
                teleportToHub(player, false);
                restorePlayerState(player);
                giveCompassIfMissing(player);
            }
        }

        checkForWinner();
    }

    private void checkForWinner() {
        if (matchState == MatchState.WAITING) {
            return;
        }

        if (activePlayers.size() <= 1) {
            UUID winner = activePlayers.isEmpty() ? null : activePlayers.iterator().next();
            finishMatch(winner);
        }
    }

    private void finishMatch(UUID winnerId) {
        if (matchState == MatchState.WAITING) {
            return;
        }

        matchState = MatchState.WAITING;
        cancelTask(gameCountdownTask);
        gameCountdownTask = null;

        if (winnerId != null) {
            Player winner = Bukkit.getPlayer(winnerId);
            String winnerName = winner == null ? "A player" : winner.getName();
            Bukkit.broadcastMessage(ChatColor.GOLD + winnerName + " won Spleef!");
        } else {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "The Spleef match ended with no winner.");
        }

        for (UUID id : new ArrayList<>(matchPlayers)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || restoreAfterRespawn.contains(id)) {
                continue;
            }

            teleportToHub(player, false);
            restorePlayerState(player);
            giveCompassIfMissing(player);
            if (id.equals(winnerId)) {
                player.sendMessage(ChatColor.GOLD + "You are the last player standing!");
            } else {
                player.sendMessage(ChatColor.GRAY + "The match is over. You have returned to the hub.");
            }
        }

        matchPlayers.clear();
        activePlayers.clear();
        startQueueCountdownIfReady();
    }

    private Inventory createMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, MENU_TITLE);

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(ChatColor.YELLOW + "Spleef queue");
        infoMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Players waiting: " + queue.size(),
                ChatColor.GRAY + "The match begins when at least 2 players are ready."));
        info.setItemMeta(infoMeta);
        inventory.setItem(11, info);

        boolean alreadyQueued = queue.contains(player.getUniqueId());
        ItemStack button = new ItemStack(alreadyQueued ? Material.RED_DYE : Material.DIAMOND_SHOVEL);
        ItemMeta buttonMeta = button.getItemMeta();
        buttonMeta.setDisplayName(alreadyQueued
                ? ChatColor.RED + "Leave Spleef Queue"
                : ChatColor.GREEN + "Join Spleef Queue");
        buttonMeta.setLore(Arrays.asList(
                ChatColor.GRAY + (alreadyQueued ? "Click to leave the queue." : "Click to join the next match."),
                ChatColor.GRAY + "Last player standing wins."));
        button.setItemMeta(buttonMeta);
        inventory.setItem(13, button);

        return inventory;
    }

    private ItemStack createSpleefShovel() {
        ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
        ItemMeta meta = shovel.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Spleef Shovel");
        meta.setLore(List.of(ChatColor.GRAY + "Break the snow floor under other players."));
        shovel.setItemMeta(meta);
        return shovel;
    }

    private ItemStack createCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Minigames");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click to open the minigame menu."));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        return compass;
    }

    private boolean isHubCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    private void giveCompassIfMissing(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isHubCompass(item)) {
                return;
            }
        }
        player.getInventory().addItem(createCompass());
    }

    private Location hubLocation() {
        return new Location(hubWorld, HUB_X + 0.5, HUB_Y, HUB_Z + 0.5);
    }

    private Location queueLobbyLocation() {
        return new Location(hubWorld, 0.5, HUB_Y, QUEUE_Z + 0.5);
    }

    private void teleportToHub(Player player, boolean ensureCompass) {
        player.teleport(hubLocation());
        if (ensureCompass) {
            giveCompassIfMissing(player);
        }
    }

    private void buildHubPlatforms() {
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                placeIfAir(hubWorld.getBlockAt(x, HUB_Y - 1, z), Material.QUARTZ_BLOCK);
            }
        }

        for (int z = 7; z < QUEUE_Z - 5; z++) {
            for (int x = -2; x <= 2; x++) {
                placeIfAir(hubWorld.getBlockAt(x, HUB_Y - 1, z), Material.SMOOTH_QUARTZ);
            }
        }

        for (int x = -6; x <= 6; x++) {
            for (int z = QUEUE_Z - 6; z <= QUEUE_Z + 6; z++) {
                placeIfAir(hubWorld.getBlockAt(x, HUB_Y - 1, z), Material.LIGHT_BLUE_CONCRETE);
            }
        }
    }

    private void placeIfAir(Block block, Material material) {
        if (block.getType().isAir()) {
            block.setType(material, false);
        }
    }

    private void prepareArena() {
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                arenaWorld.getBlockAt(x, ARENA_FLOOR_Y, z).setType(Material.SNOW_BLOCK, false);
            }
        }

        for (int y = ARENA_FLOOR_Y + 1; y <= ARENA_FLOOR_Y + 5; y++) {
            for (int edge = -ARENA_RADIUS - 1; edge <= ARENA_RADIUS + 1; edge++) {
                arenaWorld.getBlockAt(-ARENA_RADIUS - 1, y, edge).setType(Material.GLASS, false);
                arenaWorld.getBlockAt(ARENA_RADIUS + 1, y, edge).setType(Material.GLASS, false);
                arenaWorld.getBlockAt(edge, y, -ARENA_RADIUS - 1).setType(Material.GLASS, false);
                arenaWorld.getBlockAt(edge, y, ARENA_RADIUS + 1).setType(Material.GLASS, false);
            }
        }
    }

    private void restorePlayerState(Player player) {
        PlayerState state = savedStates.remove(player.getUniqueId());
        if (state == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(copyItems(state.contents));
        inventory.setArmorContents(copyItems(state.armor));
        inventory.setItemInOffHand(state.offhand == null ? new ItemStack(Material.AIR) : state.offhand.clone());

        player.setGameMode(state.gameMode);
        player.setFoodLevel(state.foodLevel);
        player.setSaturation(state.saturation);
        player.setLevel(state.level);
        player.setExp(state.experience);
        player.setHealth(Math.max(1.0, Math.min(state.health, player.getMaxHealth())));
    }

    private ItemStack[] copyItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static final class PlayerState {
        private final ItemStack[] contents;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final GameMode gameMode;
        private final double health;
        private final int foodLevel;
        private final float saturation;
        private final int level;
        private final float experience;

        private PlayerState(Player player) {
            PlayerInventory inventory = player.getInventory();
            contents = copy(inventory.getStorageContents());
            armor = copy(inventory.getArmorContents());
            ItemStack heldOffhand = inventory.getItemInOffHand();
            offhand = heldOffhand == null ? null : heldOffhand.clone();
            gameMode = player.getGameMode();
            health = player.getHealth();
            foodLevel = player.getFoodLevel();
            saturation = player.getSaturation();
            level = player.getLevel();
            experience = player.getExp();
        }

        private static ItemStack[] copy(ItemStack[] items) {
            ItemStack[] copy = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                copy[i] = items[i] == null ? null : items[i].clone();
            }
            return copy;
        }
    }
}
