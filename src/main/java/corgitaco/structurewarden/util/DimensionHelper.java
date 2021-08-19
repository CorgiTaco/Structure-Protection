/*
 * This class was taken and modified from: https://github.com/Commoble/hyperbox/blob/9165406709ae67741bd3f6b99cc23a8aff798be2/src/main/java/commoble/hyperbox/dimension/DimensionHelper.java
 *
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2020 Joseph Bettendorff aka "Commoble"
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package corgitaco.structurewarden.util;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Lifecycle;
import corgitaco.structurewarden.mixin.access.MinecraftServerAccess;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Dimension;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeManager;
import net.minecraft.world.border.IBorderListener;
import net.minecraft.world.chunk.listener.IChunkStatusListener;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.DerivedWorldInfo;
import net.minecraft.world.storage.IServerConfiguration;
import net.minecraft.world.storage.SaveFormat.LevelSave;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;


public class DimensionHelper {
    // helper for sending a given player to another dimension
    // for static dimensions (from datapacks, etc) use MinecraftServer::getWorld to get the world object
    // for dynamic dimensions (mystcrafty) use DimensionHelper.getOrCreateWorld to get the target world
    public static void sendPlayerToDimension(ServerPlayerEntity serverPlayer, ServerWorld targetWorld, Vector3d targetVec) {
        // ensure destination chunk is loaded before we put the player in it
        targetWorld.getChunk(new BlockPos(targetVec));
        serverPlayer.teleportTo(targetWorld, targetVec.x, targetVec.y, targetVec.z, 1, 1);
    }

    /**
     * Gets a world, dynamically creating and registering one if it doesn't exist.<br>
     * The dimension registry is stored in the server's level file, all previously registered dimensions are loaded
     * and recreated and reregistered whenever the server starts.<br>
     * Static, singular dimensions can be registered via this getOrCreateWorld method
     * in the FMLServerStartingEvent, which runs immediately after existing dimensions are loaded and registered.<br>
     * Dynamic dimensions (mystcraft, etc) seem to be able to be registered at runtime with no repercussions aside from
     * lagging the server for a couple seconds while the world initializes.
     *
     * @param server           a MinecraftServer instance (you can get this from a ServerPlayerEntity or ServerWorld)
     * @param worldKey         A RegistryKey for your world, you can make one via RegistryKey.getOrCreateKey(Registry.WORLD_KEY, yourWorldResourceLocation);
     * @param dimensionFactory A function that produces a new Dimension instance if necessary, given the server and dimension id<br>
     *                         (dimension ID will be the same as the world ID from worldKey)<br>
     *                         It should be assumed that intended dimension has not been created or registered yet,
     *                         so making the factory attempt to get this dimension from the server's dimension registry will fail
     * @return Returns a ServerWorld, creating and registering a world and dimension for it if the world does not already exist
     */
    public static ServerWorld getOrCreateWorld(MinecraftServer server, RegistryKey<World> worldKey, BiFunction<MinecraftServer, RegistryKey<Dimension>, Dimension> dimensionFactory) {

        // this is marked as deprecated but it's not called from anywhere and I'm not sure how old it is,
        // it's probably left over from forge's previous dimension api
        // in any case we need to get at the server's world field, and if we didn't use this getter,
        // then we'd just end up making a private-field-getter for it ourselves anyway
        Map<RegistryKey<World>, ServerWorld> map = ((MinecraftServerAccess) server).getLevels();

        // if the world already exists, return it
        if (map.containsKey(worldKey)) {
            return map.get(worldKey);
        } else {
            // for vanilla worlds, forge fires the world load event *after* the world is put into the map
            // we'll do the same for consistency
            // (this is why we're not just using map::computeIfAbsent)
            return createAndRegisterWorldAndDimension(server, map, worldKey, dimensionFactory);
        }
    }

    @SuppressWarnings("deprecation") // markWorldsDirty is deprecated, see below
    private static ServerWorld createAndRegisterWorldAndDimension(MinecraftServer server, Map<RegistryKey<World>, ServerWorld> map, RegistryKey<World> worldKey, BiFunction<MinecraftServer, RegistryKey<Dimension>, Dimension> dimensionFactory) {
        ServerWorld overworld = server.getLevel(World.OVERWORLD);
        RegistryKey<Dimension> dimensionKey = RegistryKey.create(Registry.LEVEL_STEM_REGISTRY, worldKey.location());
        Dimension dimension = dimensionFactory.apply(server, dimensionKey);

        // we need to get some private fields from MinecraftServer here
        // chunkStatusListenerFactory
        // backgroundExecutor
        // anvilConverterForAnvilFile
        // the int in create() here is radius of chunks to watch, 11 is what the server uses when it initializes worlds
        IChunkStatusListener chunkListener = ((MinecraftServerAccess) server).getProgressListenerFactory().create(11);
        Executor executor = ((MinecraftServerAccess) server).getExecutor();
        LevelSave anvilConverter = ((MinecraftServerAccess) server).getStorageSource();

        // this is the same order server init creates these worlds:
        // instantiate world, add border listener, add to map, fire world load event
        // (in server init, the dimension is already in the dimension registry,
        // that'll get registered here before the world is instantiated as well)

        IServerConfiguration serverConfig = server.getWorldData();
        DimensionGeneratorSettings dimensionGeneratorSettings = serverConfig.worldGenSettings();
        // this next line registers the Dimension
        dimensionGeneratorSettings.dimensions().register(dimensionKey, dimension, Lifecycle.experimental());
        DerivedWorldInfo derivedWorldInfo = new DerivedWorldInfo(serverConfig, serverConfig.overworldData());
        // now we have everything we need to create the world instance
        ServerWorld newWorld = new ServerWorld(
                server,
                executor,
                anvilConverter,
                derivedWorldInfo,
                worldKey,
                dimension.type(),
                chunkListener,
                dimension.generator(),
                dimensionGeneratorSettings.isDebug(), // boolean: is-debug-world
                BiomeManager.obfuscateSeed(dimensionGeneratorSettings.seed()),
                ImmutableList.of(), // "special spawn list"
                // phantoms, raiders, travelling traders, cats are overworld special spawns
                // the dimension loader is hardcoded to initialize preexisting non-overworld worlds with no special spawn lists
                // so this can probably be left empty for best results and spawns should be handled via other means
                false); // "tick time", true for overworld, always false for everything else

        // add world border listener
        overworld.getWorldBorder().addListener(new IBorderListener.Impl(newWorld.getWorldBorder()));

        // register world
        map.put(worldKey, newWorld);

        // update forge's world cache (very important, if we don't do this then the new world won't tick!)
        server.markWorldsDirty();

        // fire world load event
        MinecraftForge.EVENT_BUS.post(new WorldEvent.Load(newWorld)); // event isn't cancellable

        return newWorld;
    }
}