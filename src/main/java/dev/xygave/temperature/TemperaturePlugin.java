package dev.xygave.temperature;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import com.sk89q.worldguard.LocalPlayer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TemperaturePlugin extends JavaPlugin implements Listener {
    private static final class State {
        String biome = "";
        int coldSeconds = 0, heatSeconds = 0, displaySeconds = 0, reminderSeconds = 0;
        long coldProtectedUntil = 0, heatProtectedUntil = 0;
        boolean ownsSlow = false, ownsWeak = false, ownsFrost = false; int slowAmplifier = -1, warningSeconds = 0;
    }
    private final Map<UUID, State> states = new HashMap<>();
    private int clock = 0;
    private boolean enabled = true;
    private StateFlag temperatureFlag;
    private boolean worldGuardAvailable = false;

    @Override public void onLoad() {
        // WorldGuard flags must be registered during plugin loading.
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
            StateFlag flag = new StateFlag("temperature-effects", true);
            try { registry.register(flag); temperatureFlag = flag; }
            catch (FlagConflictException ex) {
                com.sk89q.worldguard.protection.flags.Flag<?> existing = registry.get("temperature-effects");
                if (existing instanceof StateFlag sf) temperatureFlag = sf;
                else getLogger().warning("WorldGuard flag name conflict: temperature-effects");
            }
        } catch (Exception ex) { getLogger().warning("WorldGuard flag registration failed: " + ex.getMessage()); }
    }

    private boolean regionAllows(Player p) {
        if (!worldGuardAvailable || temperatureFlag == null) return true;
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(p);
            return query.testState(BukkitAdapter.adapt(p.getLocation()), local, temperatureFlag);
        } catch (Exception ex) { return true; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        enabled = getConfig().getBoolean("plugin-enabled", true);
        worldGuardAvailable = Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && temperatureFlag != null;
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("Temperature enabled");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null) clearOwnEffects(p, s);
        }
        states.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        State s = new State();
        s.biome = biome(p);
        if (getConfig().getBoolean("display.show-on-join", true)) s.displaySeconds = displayDuration();
        states.put(p.getUniqueId(), s);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        State s = states.remove(event.getPlayer().getUniqueId());
        if (s != null) clearOwnEffects(event.getPlayer(), s);
    }

    @EventHandler public void onConsume(PlayerItemConsumeEvent event) {
        Material m = event.getItem().getType();
        Player p = event.getPlayer();
        State s = states.computeIfAbsent(p.getUniqueId(), id -> new State());
        long now = System.currentTimeMillis();
        // Любое ванильное рагу: грибное, кроличье, свекольное, подозрительное.
        if (m == Material.MUSHROOM_STEW || m == Material.RABBIT_STEW
                || m == Material.BEETROOT_SOUP || m == Material.SUSPICIOUS_STEW) {
            s.coldProtectedUntil = now + seconds("cold.stew-immunity-seconds", 180) * 1000L;
            s.coldSeconds = 0;
            clearCold(p, s);
        }
        // Все обычные и необычные зелья, включая бутылку воды, имеют тип POTION.
        if (m == Material.POTION) {
            s.heatProtectedUntil = now + seconds("heat.drink-immunity-seconds", 180) * 1000L;
            s.heatSeconds = 0;
            clearHeat(p, s);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        State s = states.get(event.getEntity().getUniqueId());
        if (s != null) { s.coldSeconds = s.heatSeconds = 0; s.warningSeconds = 0; clearOwnEffects(event.getEntity(), s); }
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        State s = states.get(event.getPlayer().getUniqueId());
        if (s != null) { s.coldSeconds = s.heatSeconds = 0; s.warningSeconds = 0; }
    }

    private void tick() {
        clock++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            State s = states.computeIfAbsent(p.getUniqueId(), id -> new State());
            if (s.biome.isEmpty()) s.biome = biome(p);
            String current = biome(p);
            if (!current.equals(s.biome)) {
                s.biome = current;
                s.coldSeconds = s.heatSeconds = s.warningSeconds = 0;
                clearOwnEffects(p, s);
                if (getConfig().getBoolean("display.show-on-biome-change", true)) s.displaySeconds = displayDuration();
            }
            int reminder = seconds("display.reminder-seconds", 300);
            if (++s.reminderSeconds >= reminder) {
                s.reminderSeconds = 0;
                s.displaySeconds = displayDuration();
            }
            if (s.displaySeconds > 0) {
                String temp = String.valueOf(temperature(p, current));
                p.sendActionBar(Component.text("Температура: " + temp + "°C | " + pretty(current)));
                s.displaySeconds--;
            }
            if (!enabled || !regionAllows(p)) {
                s.coldSeconds = s.heatSeconds = s.warningSeconds = 0;
                clearOwnEffects(p, s);
                continue;
            }
            // Креатив, наблюдатель и мертвые игроки не испытывают воздействие погоды.
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                s.coldSeconds = s.heatSeconds = s.warningSeconds = 0;
                clearOwnEffects(p, s);
                continue;
            }
            boolean night = isNight(p.getWorld());
            String type = getConfig().getString("biomes." + current + ".type", "normal");
            boolean coldBiome = p.getWorld().getEnvironment() == World.Environment.NORMAL && type.equalsIgnoreCase("cold");
            boolean hotBiome = p.getWorld().getEnvironment() == World.Environment.NORMAL && type.equalsIgnoreCase("hot") && !night;
            boolean nether = p.getWorld().getEnvironment() == World.Environment.NETHER;
            // Проверяем окружающие блоки только раз в 2 секунды; результат нужен каждый тик.
            boolean coldSource = false, hotSource = false;
            if (clock % 2 == 0 || !cached.containsKey(p.getUniqueId())) {
                SourceResult result = scanSources(p);
                cached.put(p.getUniqueId(), result);
            }
            SourceResult nearby = cached.get(p.getUniqueId());
            if (nearby != null) { coldSource = nearby.warm; hotSource = nearby.hot; }
            if (p.isInWater()) {
                s.heatProtectedUntil = System.currentTimeMillis() + seconds("heat.bathing-immunity-seconds", 30) * 1000L;
                s.heatSeconds = 0;
            }
            int ambient = temperature(p, current);
            int effective = ambient + armorProtection(p);
            boolean cold = p.getWorld().getEnvironment() == World.Environment.NORMAL && effective < getConfig().getInt("cold.comfort-min-temperature", 15);
            boolean protectedCold = System.currentTimeMillis() < s.coldProtectedUntil || coldSource;
            if (cold && !protectedCold) {
                s.coldSeconds++;
                s.warningSeconds++;
                int warningInterval = seconds("cold.warning-message-every-seconds", 15);
                if (s.warningSeconds >= warningInterval) {
                    s.warningSeconds = 0;
                    p.sendActionBar(Component.text("§bВы мерзнете! §f" + ambient + "°C §7(с броней: " + effective + "°C)"));
                }
                int warn = seconds("cold.warning-" + (night ? "night" : "day") + "-seconds", night ? 30 : 60);
                int slow = seconds("cold.slow-" + (night ? "night" : "day") + "-seconds", night ? 60 : 120);
                int damage = seconds("cold.damage-" + (night ? "night" : "day") + "-seconds", night ? 90 : 180);
                if (s.coldSeconds >= warn) setFrost(p, s);
                else clearFrost(p, s);
                if (s.coldSeconds >= slow) {
                    int level = coldLevel(effective);
                    applySlow(p, s, level);
                    if (effective <= getConfig().getInt("cold.extreme-temperature", -31)) applyWeakness(p, s);
                    else clearWeakness(p, s);
                } else { clearSlow(p, s); clearWeakness(p, s); }
                if (s.coldSeconds >= damage) {
                    boolean extreme = effective <= getConfig().getInt("cold.extreme-temperature", -31);
                    int period = seconds("cold.damage-every-seconds", 5);
                    double amount = Math.max(0, getConfig().getDouble("cold.damage-amount", 1.0));
                    if (extreme) {
                        period = Math.max(1, (int) Math.round(period / Math.max(1.0, getConfig().getDouble("cold.extreme-damage-frequency-multiplier", 2.0))));
                        amount *= Math.max(1.0, getConfig().getDouble("cold.extreme-damage-multiplier", 2.0));
                    }
                    if (s.coldSeconds % period == 0 && amount > 0) p.damage(amount);
                }
            } else {
                s.coldSeconds = s.warningSeconds = 0;
                clearCold(p, s);
            }
            boolean hot = nether || hotBiome || (p.getWorld().getEnvironment() == World.Environment.NORMAL && hotSource);
            boolean protectedHeat = System.currentTimeMillis() < s.heatProtectedUntil;
            if (hot && !protectedHeat) {
                s.heatSeconds++;
                int delay = nether ? seconds("heat.nether-delay-seconds", 60)
                        : hotSource ? seconds("heat.source-delay-seconds", 180)
                        : seconds("heat.desert-delay-seconds", 180);
                if (s.heatSeconds >= delay && s.coldSeconds == 0) applySlow(p, s, 1);
            } else {
                s.heatSeconds = 0;
                if (s.coldSeconds == 0) clearSlow(p, s);
            }
        }
        cached.keySet().retainAll(states.keySet());
    }

    private record SourceResult(boolean warm, boolean hot) {}
    private final Map<UUID, SourceResult> cached = new HashMap<>();

    private SourceResult scanSources(Player p) {
        int coldRadius = Math.max(0, Math.min(8, getConfig().getInt("cold.heat-source-radius", 4)));
        int torchRadius = Math.max(0, Math.min(8, getConfig().getInt("cold.torch-radius", 2)));
        int hotRadius = Math.max(0, Math.min(8, getConfig().getInt("heat.source-radius", 3)));
        int radius = Math.max(Math.max(coldRadius, torchRadius), hotRadius);
        Block origin = p.getLocation().getBlock();
        boolean warm = false, hot = false;
        int r2cold = coldRadius * coldRadius, r2torch = torchRadius * torchRadius, r2hot = hotRadius * hotRadius;
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            int d2 = x*x + y*y + z*z;
            if (d2 > radius * radius) continue;
            Material m = origin.getRelative(x, y, z).getType();
            boolean fire = m == Material.FIRE || m == Material.SOUL_FIRE;
            boolean lava = m == Material.LAVA;
            boolean torch = m == Material.TORCH || m == Material.WALL_TORCH
                    || m == Material.SOUL_TORCH || m == Material.SOUL_WALL_TORCH;
            boolean campfire = m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE;
            if ((fire || lava || campfire) && d2 <= r2cold || torch && d2 <= r2torch) warm = true;
            if ((fire || lava) && d2 <= r2hot) hot = true;
            if (warm && hot) return new SourceResult(true, true);
        }
        return new SourceResult(warm, hot);
    }

    private void setFrost(Player p, State s) {
        if (p.getLocation().getBlock().getType() == Material.POWDER_SNOW) return;
        int max = p.getMaxFreezeTicks();
        int target = Math.max(1, Math.min(max - 25, (int) (max * Math.max(0.1, Math.min(0.9, getConfig().getDouble("cold.frost-screen-strength", 0.75))))));
        if (max > 30 && p.getFreezeTicks() < target) { p.setFreezeTicks(target); s.ownsFrost = true; }
    }
    private void clearFrost(Player p, State s) {
        if (s.ownsFrost && p.getLocation().getBlock().getType() != Material.POWDER_SNOW) p.setFreezeTicks(0);
        s.ownsFrost = false;
    }
    private int armorProtection(Player p) {
        int protection = 0;
        for (ItemStack item : p.getInventory().getArmorContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (name.startsWith("LEATHER_")) protection += getConfig().getInt("armor.leather-degrees-per-piece", 12);
            else if (name.startsWith("DIAMOND_")) protection += getConfig().getInt("armor.diamond-degrees-per-piece", 2);
            else if (name.startsWith("NETHERITE_")) protection += getConfig().getInt("armor.netherite-degrees-per-piece", 4);
        }
        return protection;
    }
    private int coldLevel(int temp) {
        int level = 1;
        for (Map<?, ?> range : getConfig().getMapList("cold.effect-ranges")) {
            try {
                int min = Integer.parseInt(String.valueOf(range.get("min")));
                int max = Integer.parseInt(String.valueOf(range.get("max")));
                if (temp >= min && temp <= max) {
                    level = Math.max(0, Math.min(10, Integer.parseInt(String.valueOf(range.get("slowness-level")))));
                    break;
                }
            } catch (Exception ignored) { }
        }
        return level;
    }
    private void applyWeakness(Player p, State s) {
        PotionEffect existing = p.getPotionEffect(PotionEffectType.WEAKNESS);
        if (existing != null && !s.ownsWeak && existing.getDuration() > 60) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 45, 0, true, false, false));
        s.ownsWeak = true;
    }
    private void clearWeakness(Player p, State s) {
        if (s.ownsWeak) {
            PotionEffect existing = p.getPotionEffect(PotionEffectType.WEAKNESS);
            if (existing != null && existing.getAmplifier() == 0 && existing.getDuration() <= 45) p.removePotionEffect(PotionEffectType.WEAKNESS);
        }
        s.ownsWeak = false;
    }
    private void applySlow(Player p, State s, int level) {
        if (level <= 0) { clearSlow(p, s); return; }
        int amplifier = level - 1;
        PotionEffect existing = p.getPotionEffect(PotionEffectType.SLOWNESS);
        // Не трогаем чужой более сильный/длительный эффект.
        if (existing != null && !s.ownsSlow && (existing.getAmplifier() >= amplifier || existing.getDuration() > 60)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, amplifier, true, false, false));
        s.ownsSlow = true; s.slowAmplifier = amplifier;
    }
    private void clearSlow(Player p, State s) {
        if (s.ownsSlow) {
            PotionEffect effect = p.getPotionEffect(PotionEffectType.SLOWNESS);
            if (effect != null && effect.getAmplifier() == s.slowAmplifier && effect.getDuration() <= 45) p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        s.ownsSlow = false; s.slowAmplifier = -1;
    }
    private void clearCold(Player p, State s) { clearFrost(p, s); clearWeakness(p, s); if (s.heatSeconds == 0) clearSlow(p, s); }
    private void clearHeat(Player p, State s) { if (s.coldSeconds == 0) clearSlow(p, s); }
    private void clearOwnEffects(Player p, State s) { clearFrost(p, s); clearSlow(p, s); clearWeakness(p, s); }

    private String biome(Player p) { return p.getLocation().getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT); }
    private boolean isNight(World w) { long time = w.getTime(); return time >= 12000 && time < 24000; }
    private int temperature(Player p, String biome) {
        World.Environment e = p.getWorld().getEnvironment();
        if (e == World.Environment.NETHER) return getConfig().getInt("biomes.nether-temperature", 55);
        if (e == World.Environment.THE_END) return getConfig().getInt("biomes.end-temperature", 15);
        String time = isNight(p.getWorld()) ? "night" : "day";
        return getConfig().getInt("biomes." + biome + "." + time + "", getConfig().getInt("biomes.default-" + time + "-temperature", 20));
    }
    private String pretty(String biome) { return biome.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private int seconds(String path, int fallback) { return Math.max(1, getConfig().getInt(path, fallback)); }
    private int displayDuration() { return seconds("display.duration-seconds", 5); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload") || sub.equals("on") || sub.equals("off") || sub.equals("onn")) {
                if (!sender.hasPermission("temperature.admin")) { sender.sendMessage("§cНет прав."); return true; }
                if (sub.equals("reload")) {
                    reloadConfig(); enabled = getConfig().getBoolean("plugin-enabled", true); cached.clear();
                    sender.sendMessage("§aTemperature: настройки перезагружены.");
                } else {
                    enabled = sub.equals("on") || sub.equals("onn");
                    getConfig().set("plugin-enabled", enabled);
                    saveConfig();
                    if (!enabled) for (Player p : Bukkit.getOnlinePlayers()) {
                        State state = states.get(p.getUniqueId());
                        if (state != null) { state.coldSeconds = state.heatSeconds = state.warningSeconds = 0; clearOwnEffects(p, state); }
                    }
                    sender.sendMessage(enabled ? "§aTemperature: эффекты включены." : "§eTemperature: эффекты выключены.");
                }
                return true;
            }
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Использование: /temperature [on|off|reload]"); return true; }
        int temp = temperature(p, biome(p));
        int protection = armorProtection(p);
        sender.sendMessage("§bБиом: §f" + biome(p) + " §bТемпература: §f" + temp + "°C");
        sender.sendMessage("§bЗащита брони: §f+" + protection + "°C §bЭффективная: §f" + (temp + protection) + "°C");
        sender.sendMessage(enabled ? "§aТемпературные эффекты включены" : "§eТемпературные эффекты выключены");
        return true;
    }
}
