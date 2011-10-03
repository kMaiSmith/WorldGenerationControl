/*
   See README.markdown for more information
   
   World Generation Control - Bukkit chunk preloader
   Copyright (C) 2011 john@pointysoftware.net

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */

package net.pointysoftware.worldgenerationcontrol;

import java.util.logging.Logger;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import org.bukkit.ChatColor;

import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;

import org.bukkit.scheduler.BukkitScheduler;

public class WorldGenerationControl extends JavaPlugin implements Runnable
{
    private final static String VERSION = "2.0";
    public enum GenerationSpeed
    {
        // Do everything on the same tick, locking up
        // the server until the generation is complete.
        ALLATONCE,
        // Process whole regions per tick, extremely laggy.
        VERYFAST,
        // Split up region loading and lighting fixes
        // laggy, but playable.
        FAST,
        // like fast, but smaller regions, moderate
        // lag depending on conditions
        NORMAL,
        // even smaller regions, less lag
        SLOW,
        // tiny regions, very minimal lag, will
        // take forever.
        VERYSLOW
    }
    public enum GenerationLighting
    {
        // Force update every chunk without
        // fullbright lighting, completely
        // recalculating all lighting in the
        // area. This will take 3x longer
        // than the rest of the generation
        // combined...
        EXTREME,
        // Force update all lighting by toggling
        // skyblocks. This will easily double
        // generation times, but give you proper
        // lighting on generated chunks
        NORMAL,
        // Don't force lighting. Generated areas
        // will have invalid lighting until a
        // player wanders near them. This is only
        // a problem if you want to use a external
        // map that cares about lighting, or if
        // you want to get the CPU time involved
        // in lighting a chunk out of the way.
        NONE
    }
    public class GenerationRegion
    {
        GenerationRegion(World world, GenerationSpeed speed, GenerationLighting lighting)
        {
            this.world = world;
            this.speed = speed;
            this.fixlighting = lighting;
            this.pendinglighting = new ArrayDeque<GenerationChunk>();
            this.pendingcleanup = new ArrayDeque<GenerationChunk>();
            this.queuedregions = new ArrayDeque<QueuedRegion>();
        }
        
        // returns true if complete
        public boolean runStep()
        {
            boolean done = false;
            if (pendinglighting.size() > 0)
            {
                // Run lighting step
                // TODO print stuff
                while (pendinglighting.size() > 0)
                {
                    GenerationChunk x = pendinglighting.pop();
                    x.fixLighting();
                    pendingcleanup.push(x);
                }
            }
            else if (queuedregions.size() > 0)
            {
                QueuedRegion next = queuedregions.pop();
                // Load these chunks as our step
                GenerationChunk c;
                while ((c = next.getChunk(this.world)) != null)
                {
                    c.load();
                    if (this.fixlighting == GenerationLighting.NONE)
                        pendingcleanup.push(c);
                    else
                        pendinglighting.push(c);
                }
            }
            else
                done = true;
            
            // Handle pending-cleanup chunks
            Iterator<GenerationChunk> cleaner = pendingcleanup.iterator();
            while (cleaner.hasNext())
            {
                GenerationChunk x = cleaner.next();
                if (x.tryUnload()) cleaner.remove();
            }
            
            if (pendingcleanup.size() == 0 && done)
                return true;
            return false;
        }
        
        // Returns number of chunks queued
        public int addCircularRegion(World world, int xCenter, int zCenter, int radius)
        {
            xCenter = _toChunk(xCenter);
            zCenter = _toChunk(zCenter);
            radius = _toChunk(radius);
            return this._addRegion(xCenter - radius, zCenter - radius, xCenter + radius, zCenter + radius, xCenter, zCenter, radius);
        }
        // Returns number of chunks queued
        public int addSquareRegion(World world, int xStart, int zStart, int xEnd, int zEnd)
        {
            return this._addRegion(_toChunk(xStart), _toChunk(zStart), _toChunk(xEnd), _toChunk(zEnd), 0, 0, 0);
        }
        
        // Returns number of chunks queued
        // values are in *chunk coordinates* (see _toChunk)
        private int _addRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
        {
            if (xStart >= xEnd || zStart >= zEnd || radius < 0)
                return 0;
            
            // Break into regions
            int regionSize;
            if (this.speed == GenerationSpeed.NORMAL) regionSize = 16;
            else if (this.speed == GenerationSpeed.SLOW) regionSize = 13;
            else if (this.speed == GenerationSpeed.VERYSLOW) regionSize = 10;
            else regionSize = 24;
            
            // Regions need to overlap by 2 so block populators
            // and lighting can run. (edge chunks wont work in either)
            int overlap = 2;
            
            int zNext = zStart + overlap;
            int xNext = xStart + overlap;
            
            while (zNext <= zEnd)
            {
                int x1 = xNext - overlap;
                int x2 = Math.min(x1 + regionSize - 1, xEnd + overlap);
                int z1 = zNext - overlap;
                int z2 = Math.min(z1 + regionSize - 1, zEnd + overlap);
                
                queuedregions.add(new QueuedRegion(xStart, zStart, xEnd, zEnd, xCenter, zCenter, radius));
                
                xNext = x2 + 1;
                
                if (xNext > xEnd)
                {
                    xNext = xStart;
                    zNext = z2 + 1;
                }
            }
            return (xEnd - xStart + 1) * (zEnd - zStart + 1);
        }
        
        private int _toChunk(int worldCoordinate)
        {
            // floor/ceiling depending on which side of the origin we're on
            int ret = (int)((double)worldCoordinate / 16);
            if (worldCoordinate >= 0)
                return ret + 1;
            else
                return ret;
        }
        
        private class QueuedRegion
        {
            private int xStart, zStart, xEnd, zEnd, xCenter, zCenter, radius, x, z;
            QueuedRegion(int xStart, int zStart, int xEnd, int zEnd, int xCenter, int zCenter, int radius)
            {
                this.x = xStart;
                this.z = zStart;
                this.xCenter = xCenter;
                this.zCenter = zCenter;
                this.xStart = xStart;
                this.zStart = zStart;
                this.xEnd = xEnd;
                this.zEnd = zEnd;
                this.radius = radius;
            }
            
            // For iterating over chunks
            public void reset() { x = xStart; z = zStart; }
            public GenerationChunk getChunk(World world)
            {
                GenerationChunk ret = null;
                while (ret == null)
                {
                    if (x > xEnd && z > zEnd)
                        return null;
                    
                    // Skip chunks outside circle radius
                    if ((radius == 0) || (radius >= Math.sqrt((double)(Math.pow(Math.abs(x - xCenter),2) + Math.pow(Math.abs(z - zCenter),2)))))
                        ret = new GenerationChunk(x, z, world);
                    
                    x++;
                    if (x > xEnd)
                    {
                        x = xStart;
                        z++;
                    }
                }
                return ret;
            }
            
            // Chunks this represents
            public int getSize() { return (xEnd - xStart + 1) * (zEnd - zStart + 1); }
        }
        
        private ArrayDeque<GenerationChunk> pendinglighting, pendingcleanup;
        private ArrayDeque<QueuedRegion> queuedregions;
        private World world;
        private GenerationLighting fixlighting;
        private GenerationSpeed speed;
    }
    private class GenerationChunk
    {
        private int x, z;
        private World world;
        private Chunk chunk;
        GenerationChunk(int x, int z, World world) { this.x = x; this.z = z; this.world = world; }
        public int getX() { return x; }
        public int getZ() { return z; }
        // This references a lot of blocks, if calling this on a lot of chunks,
        // a System.gc() afterwards might be necessary to prevent overhead errors.
        public boolean fixLighting()
        {
            // Don't run this step on chunks without all adjacent chunks loaded, or it will
            // actually corrupt the lighting
            if (this.chunk != null
                && this.world.isChunkLoaded(this.x - 1, this.z)
                && this.world.isChunkLoaded(this.x, this.z - 1)
                && this.world.isChunkLoaded(this.x + 1, this.z)
                && this.world.isChunkLoaded(this.x, this.z + 1)
                && this.world.isChunkLoaded(this.x - 1, this.z + 1)
                && this.world.isChunkLoaded(this.x + 1, this.z + 1)
                && this.world.isChunkLoaded(this.x - 1, this.z - 1)
                && this.world.isChunkLoaded(this.x + 1, this.z - 1))
            {
                // Only fast lighting is done if no living entities are near.
                // The solution is to create a noble chicken, who will be destroyed
                // after the updates have been forced.
                // Note that if we *didnt* update lighting, it would be updated
                // the first time a player wanders near anyway, this is just
                // a hack to make it happen at generation time.
                int worldHeight = this.world.getMaxHeight();
                // Center of the chunk. ish. Don't think it matters as long as he's in the chunk.
                // Since he's removed at the end of this function he doesn't live for a single tick,
                // so it doesn't matter if this puts him in a solid object - he doesn't have time to suffocate.
                LivingEntity bobthechicken = this.world.spawnCreature(this.chunk.getBlock(8, worldHeight - 1, 8).getLocation(), CreatureType.CHICKEN);
                ArrayList<BlockState> touchedblocks = new ArrayList<BlockState>();
                for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++)
                {
                    Block bl = this.chunk.getBlock(bx, worldHeight - 1, bz);
                    // All touched blocks have their state saved and re-applied after the loop.
                    // TODO -
                    // I *think* this should be safe, but I need to do testing on various tile
                    // entities placed at max height to ensure it doesn't damage them.
                    touchedblocks.add(bl.getState());
                    // The way lighting works branches based on how far the skylight reaches down.
                    // Thus by toggling the top block between solid and not, we force a lighting update
                    // on this column of blocks.
                    if (bl.isEmpty())
                        bl.setType(Material.STONE);
                    else
                        bl.setType(Material.AIR);
                }
                // Tests show it's faster to set them all stone then back afterwards
                // than it is to toggle each one in order.
                for (BlockState s:touchedblocks)
                    s.update(true);
                
                bobthechicken.remove();
                return true;
            }
            else return false;
        }
        public void load()
        {
            this.chunk = this.world.getChunkAt(this.x, this.z);
            if (!this.chunk.isLoaded())
                this.chunk.load(true);
        }
        public boolean tryUnload()
        {
            if (this.world.isChunkLoaded(this.x, this.z))
            {
                // If this returns false, the chunk is loaded
                // due to a nearby player, which means the world
                // is managing it, which means we needn't worry
                // about it.
                return !this.world.unloadChunkRequest(x, z, true);
            }
            else
            {
                return true;
            }
        }
    }
    
    // *very* simple class the parse arguments with quoting
    private class NiceArgsParseIntException extends Throwable
    {
        private String argName, badValue;
        NiceArgsParseIntException(String argName, String badValue)
        {
            this.argName = argName;
            this.badValue = badValue;
        }
        public String getName() { return this.argName; }
        public String getBadValue() { return this.badValue; }
    }
    private class NiceArgsParseException extends Throwable {}
    private class NiceArgs
    {
        private ArrayList<String> cleanArgs;
        private int[] parsedInts;
        NiceArgs(String[] args) throws NiceArgsParseException
        {
            String allargs = "";
            for (int x = 0; x < args.length; x++)
                allargs += (allargs.length() > 0 ? " " : "") + args[x];

            cleanArgs = new ArrayList<String>();

            // Matches any list of items delimited by spaces. An item can have quotes around it to escape spaces
            // inside said quotes. Also honors escape sequences
            // E.g. arg1 "arg2 stillarg2" arg3 "arg4 \"bob\" stillarg4" arg5\ stillarg5
            Matcher m = Pattern.compile("\\s*(?:\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"|((?:[^\\s\\\\\\\"]|\\\\(?:.|$))+))(?:\\s|$)").matcher(allargs);
            while (m.regionStart() < m.regionEnd())
            {
                if (m.lookingAt())
                {
                    cleanArgs.add((m.group(1) == null ? m.group(2) : m.group(1)).replaceAll("\\\\(.|$)", "$1"));
                    m.region(m.end(), m.regionEnd());
                }
                else
                    throw new NiceArgsParseException();
            }
        }
        public int length() { return this.cleanArgs.size(); }
        public String get(int x) { return this.cleanArgs.get(x); }
        public int getInt(int i, String argName) throws NiceArgsParseIntException
        {
            try
                { return Integer.parseInt(this.cleanArgs.get(i)); }
            catch (NumberFormatException e)
                { throw new NiceArgsParseIntException(argName, this.cleanArgs.get(i)); }
        }
    }

    private Logger logger = Bukkit.getLogger();
    private GenerationRegion currentRegion;
    private ArrayDeque<GenerationRegion> pendingRegions = new ArrayDeque<GenerationRegion>();
    private ArrayList<GenerationChunk> ourChunks = new ArrayList<GenerationChunk>();
    private int taskId = 0;
    private int maxLoadedChunks;
    
    public void onEnable()
    {
        statusMsg("v"+VERSION+" Loaded");
    }
    
    // Send a status message to all players
    // with worldgenerationcontrol.statusmessage permissions,
    // as well as the console. If a player/console (target) is 
    // specified, include him regardless of his permissions.
    // if senderOnly is true, only send the message to him.
    private void statusMsg(String str, CommandSender target, boolean senderOnly)
    {
        //ChatColor.stripColor
        String msg = ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "WorldGenerationControl" + ChatColor.DARK_GRAY + "]" + ChatColor.WHITE + " " + str;
        
        // Message target if provided
        if (target instanceof Player)
            // commandsender.sendmessage doesn't support color, even if its a player :<
            ((Player)target).sendMessage(msg);
        else if (target != null)
            target.sendMessage(ChatColor.stripColor(msg));
        
        if (!senderOnly)
        {
            // Message all non-target players
            for (Player p:getServer().getOnlinePlayers())
            {
                if (p != target && p.hasPermission("worldgenerationcontrol.statusupdates"))
                    p.sendMessage(str);
            }
            
            // Message console/logger, unless its the target
            if (!(target instanceof ConsoleCommandSender))
                logger.info(ChatColor.stripColor(msg));
        }
    }
    private void statusMsg(String str)
    {
        this.statusMsg(str, null, false);
    }
    private void statusMsg(String str, CommandSender target)
    {
        this.statusMsg(str, target, true);
    }

    public void onDisable()
    {
        if (this.taskId != 0)
        {
            statusMsg("Plugin unloaded, aborting generation.");
            this.endTask();
        }
    }

    

    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] rawargs)
    {
        NiceArgs args;
        try
        {
            args = new NiceArgs(rawargs);
        }
        catch (NiceArgsParseException e)
        {
            statusMsg("Error - Mismatched/errant quotes in arguments. You can escape quotes in world names with backslashes, e.g. \\\"", sender);
            return true;
        }
        
        boolean bCircular = commandLabel.compareToIgnoreCase("forcegencircle") == 0;
        if (bCircular || commandLabel.compareToIgnoreCase("forcegenchunks") == 0 || commandLabel.compareToIgnoreCase("forcegen") == 0)
        {
            if (!sender.isOp())
            {
                statusMsg("Requires op status.", sender);
                return true;
            }
            if (this.taskId != 0)
            {
                statusMsg("Generation already in progress.", sender);
                return true;
            }
            if     ((bCircular && (args.length() != 1 && args.length() != 2 && args.length() != 4 && args.length() != 5))
                || (!bCircular && (args.length() != 5 && args.length() != 6)))
            {
                return false;
            }
            
            World world = null;
            int maxLoadedChunks = -1;
            int xCenter = 0, zCenter = 0, xStart, zStart, xEnd, zEnd, radius = 0;
            try
            {
                if (bCircular)
                {
                    radius = args.getInt(0, "radius");

                    if (radius < 1)
                    {
                        statusMsg("Radius must be > 1", sender);
                        return true;
                    }
                    
                    if (sender instanceof Player && args.length() < 4)
                    {
                        // Use player's location to center circle
                        Chunk c = ((Player)sender).getLocation().getBlock().getChunk();
                        world = c.getWorld();
                        xCenter = c.getX();
                        zCenter = c.getZ();
                    }
                    else
                    {
                        if (args.length() < 4)
                        {
                            statusMsg("You're not a player, so you need to specify a world name and location.", sender);
                            return true;
                        }
                        world = getServer().getWorld(args.get(1));
                        if (world == null)
                        {
                            statusMsg("World \"" + ChatColor.GOLD + args.get(1) + ChatColor.WHITE + "\" does not exist.", sender);
                            return true;
                        }
                        xCenter = args.getInt(2, "xCenter");
                        zCenter = args.getInt(3, "zCenter");
                    }
                    xStart = xCenter - radius;
                    xEnd = xCenter + radius;
                    zStart = zCenter - radius;
                    zEnd = zCenter + radius;
                    if (args.length() == 2) maxLoadedChunks = args.getInt(1, "maxLoadedChunks");
                    else if (args.length() == 5) maxLoadedChunks = args.getInt(4, "maxLoadedChunks");
                }
                else
                {
                    world = getServer().getWorld(args.get(0));
                    if (world == null)
                    {
                        statusMsg("World \"" + ChatColor.GOLD + args.get(0) + ChatColor.WHITE + "\" does not exist.", sender);
                        return true;
                    }
                    xStart = args.getInt(1, "xStart");
                    zStart = args.getInt(2, "zStart");
                    xEnd   = args.getInt(3, "xEnd");
                    zEnd   = args.getInt(4, "zEnd");
                    if (args.length() == 6) maxLoadedChunks = args.getInt(5, "maxLoadedChunks");
                }
            }
            catch (NiceArgsParseIntException e)
            {
                statusMsg("Error: " + e.getName() + " argument must be a number, not \"" + e.getBadValue() + "\"", sender);
                return true;
            }
            
            int loaded = world.getLoadedChunks().length;
            if (maxLoadedChunks < 0) maxLoadedChunks = loaded + 800;
            else if (maxLoadedChunks < loaded + 200)
            {
                statusMsg("maxLoadedChunks too low, there are already " + loaded + " chunks loaded - need a value of at least " + (loaded + 200), sender);
                return true;
            }
            
            if (xEnd - xStart < 1 || zEnd - zStart < 1)
            {
                statusMsg("xEnd and zEnd must be greater than xStart and zStart respectively.", sender);
                return true;
            }

            GenerationRegion gen = new GenerationRegion(world, GenerationSpeed.NORMAL, GenerationLighting.NORMAL);
            if (bCircular)
                gen.addCircularRegion(world, xCenter * 16, zCenter * 16, radius * 16);
            else
                gen.addSquareRegion(world, xStart * 16, xEnd * 16, zStart * 16, zEnd * 16);
            this.queueGeneration(gen);
        }
        else if (commandLabel.compareToIgnoreCase("cancelforcegenchunks") == 0 || commandLabel.compareToIgnoreCase("cancelforcegen") == 0)
        {
            if (this.taskId == 0)
            {
                statusMsg("There is no chunk generation in progress", sender);
                return true;
            }
            else
            {
                statusMsg("Generation canceled by " + (sender instanceof Player ? ("player " + ChatColor.GOLD + ((Player)sender).getName() + ChatColor.WHITE) : "the console") + ", waiting for remaining chunks to unload.");
                this.cancelGeneration();
            }
        }
        return true;
    }
    
    public void queueGeneration(GenerationRegion region)
    {
        if (this.currentRegion != null)
            this.pendingRegions.push(region);
        else
        {
            this.currentRegion = region;
            this.taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, this, 60, 60);
        }
    }

    public void cancelGeneration()
    {
        if (this.taskId != 0)
        {
            this.pendingRegions.clear();
        }
    }
    
    // use cancelGeneration to stop generation, this should only be used internally
    private void endTask()
    {
        if (this.taskId != 0)
            getServer().getScheduler().cancelTask(this.taskId);
        this.taskId = 0;
    }

    public void run()
    {
        if (this.taskId == 0) return; // Prevent inappropriate calls
        if (this.currentRegion.runStep())
        {
            if (this.pendingRegions.size() > 0)
                this.currentRegion = this.pendingRegions.pop();
            else
                this.endTask();
        }
    }
}
