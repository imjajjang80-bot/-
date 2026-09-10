package com.example.tailchase;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;

public class ComaTailChase extends JavaPlugin implements Listener, CommandExecutor {

    public enum TailColor {
        PINK("분홍", ChatColor.LIGHT_PURPLE, Color.fromRGB(255, 105, 180)),
        RED("빨강", ChatColor.RED, Color.RED),
        ORANGE("주황", ChatColor.GOLD, Color.ORANGE),
        YELLOW("노랑", ChatColor.YELLOW, Color.YELLOW),
        GREEN("초록", ChatColor.GREEN, Color.GREEN),
        BLUE("파랑", ChatColor.BLUE, Color.BLUE),
        NAVY("남색", ChatColor.DARK_BLUE, Color.NAVY),
        PURPLE("보라", ChatColor.DARK_PURPLE, Color.PURPLE);

        final String name;
        final ChatColor chatColor;
        final Color armorColor;

        TailColor(String name, ChatColor chatColor, Color armorColor) {
            this.name = name;
            this.chatColor = chatColor;
            this.armorColor = armorColor;
        }

        public TailColor getNext() {
            TailColor[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        public TailColor getPrev() {
            TailColor[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    private final Map<UUID, UUID> masterMap = new HashMap<>(); // 노예 -> 주인
    private final Map<UUID, List<UUID>> slavesMap = new HashMap<>(); // 주인 -> 노예 목록
    private final Map<UUID, TailColor> colorMap = new HashMap<>(); // 플레이어 -> 색상

    private boolean gameStarted = false;
    private Scoreboard board;
    private Objective objective;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("꼬리")).setExecutor(this);

        getLogger().info("코마 꼬리잡기 플러그인이 활성화되었습니다.");
    }

    private void initScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            board = manager.getNewScoreboard();
            objective = board.registerNewObjective("comatail", "dummy", ChatColor.BOLD + "" + ChatColor.LIGHT_PURPLE + "[ 코마 꼬리잡기 ]");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("시작")) {
                if (gameStarted) {
                    sender.sendMessage(ChatColor.RED + "이미 게임이 진행 중입니다.");
                    return true;
                }
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (players.size() < 2) {
                    sender.sendMessage(ChatColor.RED + "최소 2명 이상의 플레이어가 필요합니다.");
                    return true;
                }
                startGame(players);
                return true;
            } else if (args[0].equalsIgnoreCase("종료")) {
                if (!gameStarted) {
                    sender.sendMessage(ChatColor.RED + "진행 중인 게임이 없습니다.");
                    return true;
                }
                stopGame();
                Bukkit.broadcastMessage(ChatColor.RED + "[꼬리잡기] 게임이 강제 종료되었습니다.");
                return true;
            }
        }
        sender.sendMessage(ChatColor.YELLOW + "사용법: /꼬리 [시작|종료]");
        return true;
    }

    private void startGame(List<Player> players) {
        gameStarted = true;
        masterMap.clear();
        slavesMap.clear();
        colorMap.clear();
        initScoreboard();

        Collections.shuffle(players);
        TailColor[] colors = TailColor.values();

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            TailColor color = colors[i % colors.length];

            slavesMap.put(p.getUniqueId(), new ArrayList<>());
            colorMap.put(p.getUniqueId(), color);

            setLeatherArmor(p, color.armorColor);

            p.sendMessage(ChatColor.GRAY + "==============================");
            p.sendMessage(ChatColor.GOLD + " 당신의 색상: " + color.chatColor + color.name);
            p.sendMessage(ChatColor.RED + " 타깃(공격대상) 색상: " + color.getNext().chatColor + color.getNext().name);
            p.sendMessage(ChatColor.GRAY + "==============================");
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "[꼬리잡기] 게임이 시작되었습니다! (월드보더: 1000x1000 설정 완료)");
        updateScoreboard();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(board);
        }

        // 월드 보더 설정
        for (World world : Bukkit.getWorlds()) {
            WorldBorder border = world.getWorldBorder();
            border.setCenter(0, 0);
            border.setSize(1000);
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!gameStarted) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;

        Location from = event.getFrom();
        World toWorld = event.getTo() != null ? event.getTo().getWorld() : null;

        if (toWorld != null) {
            // 지옥 포탈 좌표 1:1 대응 좌표로 정확하게 재설정
            Location targetLoc = new Location(
                toWorld,
                from.getX(),
                from.getY(),
                from.getZ(),
                from.getYaw(),
                from.getPitch()
            );
            event.setTo(targetLoc);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!gameStarted) return;

        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Player attacker) {
            UUID attackerRoot = getRootMaster(attacker.getUniqueId());
            UUID victimRoot = getRootMaster(victim.getUniqueId());

            if (attackerRoot.equals(victimRoot)) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "아군(또는 동일 체인)은 공격할 수 없습니다!");
                return;
            }

            TailColor attackerColor = colorMap.get(attackerRoot);
            TailColor victimColor = colorMap.get(victimRoot);

            if (attackerColor.getNext() != victimColor) {
                event.setCancelled(true);
                attacker.sendMessage(ChatColor.RED + "지정된 타깃(" + attackerColor.getNext().name + ")만 공격할 수 있습니다!");
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!gameStarted) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null) {
            UUID killerRoot = getRootMaster(killer.getUniqueId());
            UUID victimRoot = getRootMaster(victim.getUniqueId());

            if (!killerRoot.equals(victimRoot)) {
                // 노예화 처리
                masterMap.put(victimRoot, killerRoot);
                slavesMap.computeIfAbsent(killerRoot, k -> new ArrayList<>()).add(victimRoot);

                String killerName = Bukkit.getPlayer(killerRoot) != null ? Bukkit.getPlayer(killerRoot).getName() : killer.getName();
                String victimName = Bukkit.getPlayer(victimRoot) != null ? Bukkit.getPlayer(victimRoot).getName() : victim.getName();

                Bukkit.broadcastMessage(ChatColor.GOLD + "[속보] " + ChatColor.GREEN + killerName +
                        ChatColor.YELLOW + " 님이 " + ChatColor.RED + victimName +
                        ChatColor.YELLOW + " 님의 꼬리를 끊고 노예로 삼았습니다!");

                updateScoreboard();
                checkWinCondition();
            }
        }
    }

    private UUID getRootMaster(UUID uuid) {
        UUID current = uuid;
        while (masterMap.containsKey(current)) {
            current = masterMap.get(current);
        }
        return current;
    }

    private void checkWinCondition() {
        Set<UUID> activeMasters = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            activeMasters.add(getRootMaster(p.getUniqueId()));
        }

        if (activeMasters.size() == 1) {
            UUID winnerUUID = activeMasters.iterator().next();
            Player winner = Bukkit.getPlayer(winnerUUID);
            String winnerName = winner != null ? winner.getName() : "누군가";

            Bukkit.broadcastMessage(ChatColor.GREEN + "=================================");
            Bukkit.broadcastMessage(ChatColor.GOLD + " 최후의 승리자: " + winnerName);
            Bukkit.broadcastMessage(ChatColor.GREEN + "=================================");

            stopGame();
        }
    }

    private void stopGame() {
        gameStarted = false;
        masterMap.clear();
        slavesMap.clear();
        colorMap.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getInventory().setArmorContents(null);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void setLeatherArmor(Player p, Color color) {
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemStack legs = new ItemStack(Material.LEATHER_LEGGINGS);
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);

        LeatherArmorMeta meta;
        for (ItemStack item : new ItemStack[]{helmet, chest, legs, boots}) {
            meta = (LeatherArmorMeta) item.getItemMeta();
            if (meta != null) {
                meta.setColor(color);
                item.setItemMeta(meta);
            }
        }

        p.getInventory().setHelmet(helmet);
        p.getInventory().setChestplate(chest);
        p.getInventory().setLeggings(legs);
        p.getInventory().setBoots(boots);
    }

    private void updateScoreboard() {
        if (objective == null) return;

        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }

        int scoreIndex = 15;
        Set<UUID> rootMasters = new HashSet<>();
        for (UUID uuid : colorMap.keySet()) {
            rootMasters.add(getRootMaster(uuid));
        }

        for (UUID masterUUID : rootMasters) {
            Player master = Bukkit.getPlayer(masterUUID);
            if (master == null) continue;

            TailColor color = colorMap.get(masterUUID);
            int slaveCount = slavesMap.getOrDefault(masterUUID, new ArrayList<>()).size();

            String text = color.chatColor + master.getName() + ChatColor.WHITE + " (노예 " + slaveCount + "명)";
            if (text.length() > 40) text = text.substring(0, 40);

            objective.getScore(text).setScore(scoreIndex--);
        }
    }
}