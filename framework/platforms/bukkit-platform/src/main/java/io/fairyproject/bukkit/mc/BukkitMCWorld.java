package io.fairyproject.bukkit.mc;

import io.fairyproject.bukkit.metadata.Metadata;
import io.fairyproject.event.EventNode;
import io.fairyproject.event.GlobalEventNode;
import io.fairyproject.mc.MCEventFilter;
import io.fairyproject.mc.MCPlayer;
import io.fairyproject.mc.MCWorld;
import io.fairyproject.mc.event.trait.MCWorldEvent;
import io.fairyproject.metadata.MetadataMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.stream.Collectors;

public class BukkitMCWorld implements MCWorld {

    private final String worldName;
    private final EventNode<MCWorldEvent> eventNode;
    private final BukkitAudiences audiences;

    public BukkitMCWorld(String worldName, BukkitAudiences bukkitAudiences) {
        this.worldName = worldName;
        this.audiences = bukkitAudiences;
        this.eventNode = GlobalEventNode.get().map(this, MCEventFilter.WORLD);
    }

    public World getWorld() {
        return Bukkit.getWorld(this.worldName);
    }

    @Override
    public String getName() {
        return this.getWorld().getName();
    }

    @Override
    public EventNode<MCWorldEvent> getEventNode() {
        return this.eventNode;
    }

    @Override
    public List<MCPlayer> getPlayers() {
        return this.getWorld().getPlayers().stream()
                .map(MCPlayer::from)
                .collect(Collectors.toList());
    }

    @Override
    public MetadataMap getMetadata() {
        return Metadata.provideForWorld(this.getWorld());
    }

    @Override
    public <T> T as(Class<T> worldClass) {
        if (!worldClass.isInstance(this.getWorld())) {
            throw new ClassCastException();
        }
        return worldClass.cast(this.getWorld());
    }

    @Override
    public int getMaxY() {
        return this.getWorld().getMaxHeight();
    }

    @Override
    public int getMaxSectionY() {
        return (this.getMaxY() - 1) >> 4;
    }

    @Override
    public Audience audience() {
        return this.audiences.all();
    }
}
