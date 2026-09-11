package com.example.tailchase;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;

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
    }

    private final Map<UUID, UUID> masterMap = new HashMap<>(); // 노예 -> 주인
    private final Map<UUID, List<UUID>> slavesMap = new HashMap<>(); // 주인 -> 노예 목록
    private final Map<UUID, TailColor> colorMap = new HashMap<>(); // 플레이어 -> 색상

    private boolean gameStarted = false;
    private Scoreboard board;
    private Objective objective;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("꼬리")).setExecutor(this);

        getLogger().info("마이콜 꼬리잡기 플러그인이 활성화되었습니다.");
    }

    private void initScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            board = manager.getNewScoreboard();
            objective = board.registerNewObjective("michaeltail", "dummy", ChatColor.BOLD + "" + ChatColor.LIGHT_PURPLE + "[ 마이콜 꼬리잡기 ]");
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
        World overworld = Bukkit.getWorlds().get(0);

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            TailColor color = colors[i % colors.length];

            slavesMap.put(p.getUniqueId(), new ArrayList<>());
            colorMap.put(p.getUniqueId(), color);

            // 이름표 색상 세팅
            setupNameTagColor(p, color);

            // 1000x1000 (-450 ~ 450) 안의 안전한 랜덤 좌표로 텔레포트
            int x = random.nextInt(900) - 450;
            int z = random.nextInt(900) - 450;
            int y = overworld.getHighestBlockYAt(x, z) + 1;
            p.teleport(new Location(overworld, x + 0.5, y, z + 0.5));

            p.sendMessage(ChatColor.GRAY + "==============================");
            p.sendMessage(ChatColor.GOLD + " 당신의 색상: " + color.chatColor + color.name + ChatColor.GRAY + " (머리 위 이름표 색상 확인)");
            p.sendMessage(ChatColor.RED + " 타깃(공격대상) 색상: " + color.getNext().chatColor + color.getNext().name);
            p.sendMessage(ChatColor.AQUA + " [팁] 다이아몬드를 들고 우클릭하면 3초간 타깃 방향으로 파란 불꽃이 뻗어나갑니다!");
            p.sendMessage(ChatColor.GRAY + "==============================");
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "[꼬리잡기] 마이콜 꼬리잡기 게임이 시작되었습니다! (랜덤 텔레포트 완료)");
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

    private void setupNameTagColor(Player player, TailColor color) {
        String teamName = "tc_" + color.name();
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.setColor(color.chatColor);
        team.addEntry(player.getName());
    }

    // 다이아몬드 우클릭 시 파란 불꽃 파티클로 타깃 방향 표시 (4블록, 3초간)
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!gameStarted) return;
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.DIAMOND) {
                UUID myRoot = getRootMaster(player.getUniqueId());
                TailColor myColor = colorMap.get(myRoot);
                TailColor targetColor = myColor.getNext();

                Player targetPlayer = null;
                for (UUID uuid : colorMap.keySet()) {
                    if (getRootMaster(uuid).equals(uuid) && colorMap.get(uuid) == targetColor) {
                        targetPlayer = Bukkit.getPlayer(uuid);
                        break;
                    }
                }

                if (targetPlayer != null && targetPlayer.isOnline()) {
                    // 다이아몬드 1개 소모
                    item.setAmount(item.getAmount() - 1);

                    player.sendMessage(ChatColor.GREEN + "[타깃 추적] " + targetColor.chatColor + targetColor.name + ChatColor.GREEN + " 타깃 방향으로 3초간 파란 불꽃이 발사됩니다!");
                    player.playSound(player.getLocation(), Sound.BLOCK_SOUL_SAND_BREAK, 1.0f, 1.0f);

                    final Player finalTarget = targetPlayer;

                    // 3초간 파티클 지속 출력 (0.1초마다 총 30회 실행)
                    new BukkitRunnable() {
                        int ticks = 0;

                        @Override
                        public void run() {
                            if (!player.isOnline() || !finalTarget.isOnline() || !gameStarted || ticks >= 30) {
                                this.cancel();
                                return;
                            }

                            Location startLoc = player.getLocation().add(0, 1.0, 0); // 플레이어 눈/가슴 높이
                            Location targetLoc = finalTarget.getLocation().add(0, 1.0, 0);

                            // 타깃을 향하는 단위 벡터 계산
                            Vector direction = targetLoc.toVector().subtract(startLoc.toVector()).normalize();

                            // 4블록 길이로 파란 불꽃 파티클 생성
                            for (double d = 0.5; d <= 4.0; d += 0.3) {
                                Location particleLoc = startLoc.clone().add(direction.clone().multiply(d));
                                player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc, 1, 0, 0, 0, 0);
                            }

                            ticks++;
                        }
                    }.runTaskTimer(this, 0L, 2L); // 2틱(0.1초) 주기로 3초간 실행

                } else {
                    player.sendMessage(ChatColor.RED + "현재 추적 가능한 타깃 플레이어를 찾을 수 없거나 접속 중이 아닙니다.");
                }
            }
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!gameStarted) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;

        Location from = event.getFrom();
        World toWorld = event.getTo() != null ? event.getTo().getWorld() : null;

        if (toWorld != null) {
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
                attacker.sendMessage(ChatColor.RED + "지정된 타깃만 공격할 수 있습니다!");
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
                masterMap.put(victimRoot, killerRoot);
                slavesMap.computeIfAbsent(killerRoot, k -> new ArrayList<>()).add(victimRoot);

                TailColor masterColor = colorMap.get(killerRoot);

                // 노예로 전락한 플레이어는 주인의 색상 가죽갑옷 입히기
                setLeatherArmor(victim, masterColor.armorColor);

                String killerName = Bukkit.getPlayer(killerRoot) != null ? Bukkit.getPlayer(killerRoot).getName() : killer.getName();
                String victimName = Bukkit.getPlayer(victimRoot) != null ? Bukkit.getPlayer(victimRoot).getName() : victim.getName();

                Bukkit.broadcastMessage(ChatColor.GOLD + "[속보] " + ChatColor.GREEN + killerName +
                        ChatColor.YELLOW + " 님이 " + ChatColor.RED + victimName +
                        ChatColor.YELLOW + " 님을 제압하여 노예로 삼았습니다!");

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

        World overworld = Bukkit.getWorlds().get(0);
        int spawnY = overworld.getHighestBlockYAt(0, 0) + 1;
        Location spawnLoc = new Location(overworld, 0.5, spawnY, 0.5);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getInventory().setArmorContents(null);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            // 게임 종료 시 0, 0 위치로 이동
            p.teleport(spawnLoc);
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
