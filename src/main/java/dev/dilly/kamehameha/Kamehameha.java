package dev.dilly.kamehameha;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class Kamehameha extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] WORDS = {"Ka...", "Me...", "Ha...", "Me...", "HA!"};
    private static final Particle.DustOptions BLUE = new Particle.DustOptions(Color.fromRGB(70, 190, 255), 1.6f);
    private static final Particle.DustOptions DEEP = new Particle.DustOptions(Color.fromRGB(20, 90, 255), 2.2f);

    private static final class Charge {
        long ticks;
        long lastUse;
        int stage = -1;
    }

    private final Map<UUID, Charge> charging = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> firing = new HashSet<>();
    private NamespacedKey key;
    private long now = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "kamehameha");
        getServer().getPluginManager().registerEvents(this, this);
        java.util.Objects.requireNonNull(getCommand("kame")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ item + commands

    private ItemStack makeItem() {
        Material mat = Material.matchMaterial(getConfig().getString("item.material", "NETHER_STAR"));
        if (mat == null || !mat.isItem()) mat = Material.NETHER_STAR;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(getConfig().getString("item.name", "Kamehameha"))
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : getConfig().getStringList("item.lore")) {
            lore.add(MM.deserialize(l).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isKame(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "give" : args[0].toLowerCase();
        switch (sub) {
            case "give" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (t == null) {
                    sender.sendMessage(MM.deserialize("<red>Usage: /kame give <player>"));
                    return true;
                }
                for (ItemStack left : t.getInventory().addItem(makeItem()).values()) {
                    t.getWorld().dropItemNaturally(t.getLocation(), left);
                }
                sender.sendMessage(MM.deserialize("<aqua>Gave a Kamehameha to <white>" + t.getName() + "<aqua>."));
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(MM.deserialize("<green>Kamehameha config reloaded."));
            }
            default -> sender.sendMessage(MM.deserialize("<red>Usage: /kame <give [player]|reload>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("give");
            out.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String start = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(start));
        return out;
    }

    // ------------------------------------------------------------------ charging

    /** Holding right click makes the client send a use packet about every 4 ticks, that's how we know it's held. */
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isKame(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("kamehameha.use")) return;
        UUID id = p.getUniqueId();
        if (firing.contains(id)) return;

        Charge c = charging.get(id);
        if (c == null) {
            long wait = cooldownUntil.getOrDefault(id, 0L) - System.currentTimeMillis();
            if (wait > 0) {
                p.sendActionBar(MM.deserialize("<gray>Recharging... <white>" + String.format("%.1f", wait / 1000.0) + "s"));
                return;
            }
            c = new Charge();
            charging.put(id, c);
        }
        c.lastUse = now;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        charging.remove(e.getPlayer().getUniqueId());
    }

    private void tick() {
        now++;
        int max = Math.max(1, getConfig().getInt("charge.max-ticks", 60));
        Iterator<Map.Entry<UUID, Charge>> it = charging.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Charge> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Charge c = en.getValue();
            if (p == null || p.isDead() || !isKame(p.getInventory().getItemInMainHand())) {
                it.remove();
                continue;
            }
            if (now - c.lastUse > 6) { // let go
                it.remove();
                release(p, c);
                continue;
            }
            c.ticks = Math.min(max, c.ticks + 1);
            double level = c.ticks / (double) max;
            chargeEffects(p, c, level);
        }
    }

    private Location hand(Player p) {
        Location eye = p.getEyeLocation();
        return eye.clone().add(eye.getDirection().normalize().multiply(0.9)).subtract(0, 0.35, 0);
    }

    private void chargeEffects(Player p, Charge c, double level) {
        World w = p.getWorld();
        Location center = hand(p);
        double r = 0.15 + 0.55 * level;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < 10; i++) { // glowing ball
            Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
            if (v.lengthSquared() < 1e-6) continue;
            v.normalize().multiply(r);
            w.spawnParticle(Particle.DUST, center.clone().add(v), 1, 0, 0, 0, 0, i % 3 == 0 ? DEEP : BLUE);
        }
        w.spawnParticle(Particle.END_ROD, center, 1, 0.02, 0.02, 0.02, 0);
        for (int i = 0; i < 3; i++) { // energy flowing in
            Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
            if (v.lengthSquared() < 1e-6) continue;
            v.normalize().multiply(1.7);
            Location from = center.clone().add(v);
            Vector toward = v.clone().multiply(-1).normalize();
            w.spawnParticle(Particle.END_ROD, from, 0, toward.getX(), toward.getY(), toward.getZ(), 0.18);
        }
        if (level > 0.8) w.spawnParticle(Particle.ELECTRIC_SPARK, center, 2, r, r, r, 0.1);

        if (c.ticks % 10 == 0) {
            w.playSound(center, "block.beacon.ambient", SoundCategory.PLAYERS, 1.0f, (float) (0.5 + 1.5 * level));
        }
        int stage = Math.min(4, (int) (level * 5));
        if (stage != c.stage || c.ticks % 20 == 0) {
            c.stage = stage;
            StringBuilder sb = new StringBuilder("<aqua><bold>");
            for (int i = 0; i <= stage; i++) sb.append(WORDS[i]).append(' ');
            p.sendActionBar(MM.deserialize(sb.toString().trim()));
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 10, 3, false, false, false));
    }

    // ------------------------------------------------------------------ firing

    private void release(Player p, Charge c) {
        int min = getConfig().getInt("charge.min-ticks", 15);
        if (c.ticks < min) {
            p.sendActionBar(MM.deserialize("<gray>Not enough energy..."));
            return;
        }
        int max = Math.max(1, getConfig().getInt("charge.max-ticks", 60));
        double level = Math.min(1.0, c.ticks / (double) max);
        UUID id = p.getUniqueId();
        cooldownUntil.put(id, System.currentTimeMillis() + getConfig().getLong("charge.cooldown-seconds", 10) * 1000L);
        firing.add(id);

        World w = p.getWorld();
        Component shout = MM.deserialize(getConfig().getString("messages.shout", "<name> KAMEHAMEHA!"),
                Placeholder.unparsed("name", p.getName()));
        for (Player o : w.getNearbyPlayers(p.getLocation(), 40)) o.sendMessage(shout);
        p.sendActionBar(MM.deserialize("<aqua><bold>HAAAAAAAA!!!"));
        Location o = hand(p);
        w.playSound(o, "entity.warden.sonic_boom", SoundCategory.PLAYERS, 2.0f, 0.8f);
        w.playSound(o, "entity.lightning_bolt.thunder", SoundCategory.PLAYERS, 1.0f, 1.6f);
        w.spawnParticle(Particle.FLASH, o, 1);

        int duration = (int) (getConfig().getInt("beam.base-duration-ticks", 20)
                + getConfig().getInt("beam.extra-duration-ticks", 30) * level);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= duration) {
                    firing.remove(id);
                    cancel();
                    return;
                }
                beam(p, level, t);
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void beam(Player p, double level, int t) {
        World w = p.getWorld();
        Vector dir = p.getEyeLocation().getDirection().normalize();
        Location origin = hand(p);
        double range = getConfig().getDouble("beam.range", 40) * (0.5 + 0.5 * level);
        double radius = getConfig().getDouble("beam.width", 0.9) * (0.4 + 0.6 * level);

        RayTraceResult hit = w.rayTraceBlocks(origin, dir, range, FluidCollisionMode.NEVER, true);
        double len = hit == null ? range : hit.getHitPosition().distance(origin.toVector());
        Vector start = origin.toVector();
        Vector end = start.clone().add(dir.clone().multiply(len));
        Location endLoc = end.toLocation(w);

        for (double d = 0; d <= len; d += 0.7) { // the beam itself
            Location pt = origin.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.END_ROD, pt, 1, radius * 0.2, radius * 0.2, radius * 0.2, 0);
            w.spawnParticle(Particle.DUST, pt, 3, radius * 0.6, radius * 0.6, radius * 0.6, 0, BLUE);
            w.spawnParticle(Particle.DUST, pt, 2, radius, radius, radius, 0, DEEP);
        }
        w.spawnParticle(Particle.DUST, origin, 12, radius, radius, radius, 0, BLUE); // ball at the hands
        if (hit != null) {
            w.spawnParticle(Particle.EXPLOSION, endLoc, 2, radius, radius, radius, 0);
            w.spawnParticle(Particle.CLOUD, endLoc, 6, radius, radius, radius, 0.08);
            if (t % 5 == 0) w.playSound(endLoc, "entity.generic.explode", SoundCategory.PLAYERS, 0.6f, 1.5f);
            if (getConfig().getBoolean("beam.block-damage", false) && t % 10 == 0) {
                float power = (float) (getConfig().getDouble("beam.explosion-power", 3.0) * (0.4 + 0.6 * level));
                w.createExplosion(endLoc, power, false, true, p);
            }
        }
        if (t % 8 == 0) w.playSound(origin, "block.beacon.ambient", SoundCategory.PLAYERS, 1.5f, 1.8f);

        // damage + push everything touching the beam
        int interval = Math.max(1, getConfig().getInt("beam.damage-interval-ticks", 10));
        double dmg = getConfig().getDouble("beam.damage", 8.0) * (0.3 + 0.7 * level);
        double push = getConfig().getDouble("beam.push", 0.45);
        boolean hurtPlayers = getConfig().getBoolean("beam.damage-players", true);
        BoundingBox box = BoundingBox.of(start, end).expand(radius + 1.5);
        for (Entity en : w.getNearbyEntities(box)) {
            if (!(en instanceof LivingEntity le) || en.equals(p) || le instanceof ArmorStand) continue;
            if (!hurtPlayers && le instanceof Player) continue;
            double dist = le.getBoundingBox().getCenter().distance(closest(le.getBoundingBox().getCenter(), start, end));
            if (dist > radius + le.getWidth() / 2 + 0.3) continue;
            if (t % interval == 0) le.damage(dmg, p);
            Vector v = le.getVelocity().add(dir.clone().multiply(push));
            v.setY(Math.max(v.getY(), 0.12));
            le.setVelocity(v);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5, 5, false, false, false));
    }

    /** Closest point to P on the segment A-B. */
    private static Vector closest(Vector pt, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len2 = ab.lengthSquared();
        if (len2 < 1e-9) return a.clone();
        double t = Math.max(0, Math.min(1, pt.clone().subtract(a).dot(ab) / len2));
        return a.clone().add(ab.multiply(t));
    }
}
