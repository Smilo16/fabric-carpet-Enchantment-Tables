package carpet.script;

import carpet.script.annotation.AnnotationParser;
import carpet.script.api.Auxiliary;
import carpet.script.api.BlockIterators;
import carpet.script.api.Entities;
import carpet.script.api.Inventories;
import carpet.script.api.Scoreboards;
import carpet.script.api.WorldAccess;
import carpet.script.exception.ExpressionException;
import carpet.script.exception.LoadException;
import carpet.script.external.Carpet;
import carpet.script.external.Vanilla;
import carpet.script.language.Arithmetic;
import carpet.script.language.ControlFlow;
import carpet.script.language.DataStructures;
import carpet.script.language.Functions;
import carpet.script.language.Loops;
import carpet.script.language.Sys;
import carpet.script.language.Threading;
import carpet.script.utils.AppStoreManager;
import carpet.script.value.FunctionValue;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static carpet.script.CarpetEventServer.Event.PLAYER_CONNECTS;
import static carpet.script.CarpetEventServer.Event.PLAYER_DISCONNECTS;

public class CarpetScriptServer extends ScriptServer
{
    //make static for now, but will change that later:
    public static final Logger LOG = LoggerFactory.getLogger("Scarpet");
    public final MinecraftServer server;
    public CarpetScriptHost globalHost;
    public Map<String, CarpetScriptHost> modules;
    public Set<String> unloadableModules;
    public long tickStart;
    public boolean stopAll;
    public int tickDepth;
    private Set<String> holyMoly;
    public CarpetEventServer events;

    private static final List<Module> bundledModuleData = new ArrayList<>();
    private static final List<Module> ruleModuleData = new ArrayList<>();

    /**
     * Registers a Scarpet App to be always available under the {@code /script load} list.
     *
     * @param app The {@link Module} of the app
     * @see Module#fromJarPath(String, String, boolean)
     */
    public static void registerBuiltInApp(Module app)
    {
        bundledModuleData.add(app);
    }

    /**
     * Registers a Scarpet App to be used as a Rule App (to be controlled with the value of a Carpet rule).
     * Libraries should be registered with {@link #registerBuiltInScript(BundledModule)} instead
     *
     * @param app The {@link Module} of the app.
     * @see Module#fromJarPath(String, String, boolean)
     */
    public static void registerSettingsApp(Module app)
    {
        ruleModuleData.add(app);
    }

    static
    {
        registerBuiltInApp(Module.carpetNative("camera", false));
        registerBuiltInApp(Module.carpetNative("overlay", false));
        registerBuiltInApp(Module.carpetNative("event_test", false));
        registerBuiltInApp(Module.carpetNative("stats_test", false));
        registerBuiltInApp(Module.carpetNative("math", true));
        registerBuiltInApp(Module.carpetNative("chunk_display", false));
        registerBuiltInApp(Module.carpetNative("ai_tracker", false));
        registerBuiltInApp(Module.carpetNative("draw_beta", false));
        registerBuiltInApp(Module.carpetNative("shapes", true));
        registerBuiltInApp(Module.carpetNative("distance_beta", false));
    }

    public CarpetScriptServer(MinecraftServer server)
    {
        this.server = server;
        init();
    }

    private void init()
    {
        events = new CarpetEventServer(this);
        modules = new HashMap<>();
        unloadableModules = new HashSet<>();
        tickStart = 0L;
        stopAll = false;
        holyMoly = server.getCommands().getDispatcher().getRoot().getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
        globalHost = CarpetScriptHost.create(this, null, false, null, p -> true, false, null, Expression.LoadOverride.DEFAULT);
    }

    public void initializeForWorld()
    {
        if (Vanilla.MinecraftServer_doScriptsAutoload(server))
        {
            for (String moduleName : listAvailableModules(false))
            {
                addScriptHost(server.createCommandSourceStack(), moduleName, null, true, true, false, null, Expression.LoadOverride.DEFAULT);
            }
        }
        CarpetEventServer.Event.START.onTick(server);
    }

    public Module getModule(String name, boolean allowLibraries)
    {
        try
        {
            Path folder = server.getWorldPath(LevelResource.ROOT).resolve("scripts");
            if (!Files.exists(folder))
            {
                Files.createDirectories(folder);
            }
            try (Stream<Path> folderLister = Files.list(folder))
            {
                Optional<Path> scriptPath = folderLister
                        .filter(script ->
                                script.getFileName().toString().equalsIgnoreCase(name + ".sc") ||
                                        (allowLibraries && script.getFileName().toString().equalsIgnoreCase(name + ".scl"))
                        ).findFirst();
                if (scriptPath.isPresent())
                {
                    return Module.fromPath(scriptPath.get());
                }
            }

            Module globalModule = Carpet.fetchGlobalModule(name, allowLibraries);
            if (globalModule != null)
            {
                return globalModule;
            }
        }
        catch (IOException e)
        {
            LOG.error("Exception while loading the app: ", e);
        }
        for (Module moduleData : bundledModuleData)
        {
            if (moduleData.name().equalsIgnoreCase(name) && (allowLibraries || !moduleData.library()))
            {
                return moduleData;
            }
        }
        return null;
    }

    public Module getRuleModule(String name)
    {
        for (Module moduleData : ruleModuleData)
        {
            if (moduleData.name().equalsIgnoreCase(name))
            {
                return moduleData;
            }
        }
        return null;
    }

    public List<String> listAvailableModules(boolean includeBuiltIns)
    {
        List<String> moduleNames = new ArrayList<>();
        if (includeBuiltIns)
        {
            for (Module mi : bundledModuleData)
            {
                if (!mi.library() && !mi.name().endsWith("_beta"))
                {
                    moduleNames.add(mi.name());
                }
            }
        }
        try
        {
            Path worldScripts = server.getWorldPath(LevelResource.ROOT).resolve("scripts");
            if (!Files.exists(worldScripts))
            {
                Files.createDirectories(worldScripts);
            }
            try (Stream<Path> folderLister = Files.list(worldScripts))
            {
                folderLister
                        .filter(f -> f.toString().endsWith(".sc"))
                        .forEach(f -> moduleNames.add(f.getFileName().toString().replaceFirst("\\.sc$", "").toLowerCase(Locale.ROOT)));
            }

            Carpet.addGlobalModules(moduleNames, includeBuiltIns);

        }
        catch (IOException e)
        {
            LOG.error("Exception while searching for apps: ", e);
        }
        return moduleNames;
    }

    public CarpetScriptHost getAppHostByName(String name)
    {
        return name == null ? globalHost : modules.get(name);
    }

    public int addScriptHost(CommandSourceStack source, String name, @Nullable Predicate<CommandSourceStack> commandValidator,
                                 boolean perPlayer, boolean autoload, boolean isRuleApp, AppStoreManager.StoreNode installer, Expression.LoadOverride override)
    {
        Runnable token = Carpet.startProfilerSection("Scarpet load");
        if (commandValidator == null)
        {
            commandValidator = p -> true;
        }
        long start = System.nanoTime();
        name = name.toLowerCase(Locale.ROOT);
        boolean reload = false;
        if (modules.containsKey(name))
        {
            if (isRuleApp)
            {
                return 0;
            }
            removeScriptHost(source, name, false, isRuleApp);
            reload = true;
        }
        Module module = isRuleApp ? getRuleModule(name) : getModule(name, false);
        if (module == null)
        {
            Carpet.Messenger_message(source, "r Failed to add " + name + " app: App not found");
            return 0;
        }
        CarpetScriptHost newHost;
        try
        {
            newHost = CarpetScriptHost.create(this, module, perPlayer, source, commandValidator, isRuleApp, installer, override);
        }
        catch (LoadException e)
        {
            Carpet.Messenger_message(source, "r Failed to add " + name + " app" + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return 0;
        }

        modules.put(name, newHost);
        if (!isRuleApp)
        {
            unloadableModules.add(name);
        }

        if (autoload && !newHost.persistenceRequired)
        {
            removeScriptHost(source, name, false, false);
            return 0;
        }
        String action = (installer != null) ? (reload ? "reinstalled" : "installed") : (reload ? "reloaded" : "loaded");

        String finalName = name;
        Boolean isCommandAdded = newHost.addAppCommands(s -> {
            if (!isRuleApp)
            {
                Carpet.Messenger_message(source, "r Failed to add app '" + finalName + "': ", s);
            }
        });
        if (isCommandAdded == null) // error should be dispatched
        {
            removeScriptHost(source, name, false, isRuleApp);
            return 0;
        }
        else if (isCommandAdded)
        {
            Vanilla.MinecraftServer_notifyPlayersCommandsChanged(server);
            if (!isRuleApp)
            {
                Carpet.Messenger_message(source, "gi " + name + " app " + action + " with /" + name + " command");
            }
        }
        else
        {
            if (!isRuleApp)
            {
                Carpet.Messenger_message(source, "gi " + name + " app " + action);
            }
        }

        if (newHost.isPerUser())
        {
            // that will provide player hosts right at the startup
            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers())
            {
                newHost.retrieveForExecution(player.createCommandSourceStack(), player);
            }
        }
        else
        {
            // global app - calling start now.
            FunctionValue onStart = newHost.getFunction("__on_start");
            if (onStart != null)
            {
                newHost.callNow(onStart, Collections.emptyList());
            }
        }
        token.run();
        long end = System.nanoTime();
        LOG.info("App " + name + " loaded in " + (end - start) / 1000000 + " ms");
        return 1;
    }

    public boolean isInvalidCommandRoot(String appName)
    {
        return holyMoly.contains(appName);
    }


    public boolean removeScriptHost(CommandSourceStack source, String name, boolean notifySource, boolean isRuleApp)
    {
        name = name.toLowerCase(Locale.ROOT);
        if (!modules.containsKey(name) || (!isRuleApp && !unloadableModules.contains(name)))
        {
            if (notifySource)
            {
                Carpet.Messenger_message(source, "r No such app found: ", "wb  " + name);
            }
            return false;
        }
        // stop all events associated with name
        CarpetScriptHost host = modules.remove(name);
        events.removeAllHostEvents(host);
        host.onClose();
        if (host.hasCommand)
        {
            Vanilla.CommandDispatcher_unregisterCommand(server.getCommands().getDispatcher(), name);
        }
        if (!isRuleApp)
        {
            unloadableModules.remove(name);
        }
        Vanilla.MinecraftServer_notifyPlayersCommandsChanged(server);
        if (notifySource)
        {
            Carpet.Messenger_message(source, "gi Removed " + name + " app");
        }
        return true;
    }

    public boolean uninstallApp(CommandSourceStack source, String name)
    {
        try
        {
            name = name.toLowerCase(Locale.ROOT);
            Path folder = server.getWorldPath(LevelResource.ROOT).resolve("scripts/trash");
            if (!Files.exists(folder))
            {
                Files.createDirectories(folder);
            }
            if (!Files.exists(folder.getParent().resolve(name + ".sc")))
            {
                Carpet.Messenger_message(source, "App doesn't exist in the world scripts folder, so can only be unloaded");
                return false;
            }
            removeScriptHost(source, name, false, false);
            Files.move(folder.getParent().resolve(name + ".sc"), folder.resolve(name + ".sc"), StandardCopyOption.REPLACE_EXISTING);
            Carpet.Messenger_message(source, "gi Removed " + name + " app");
            return true;
        }
        catch (IOException exc)
        {
            Carpet.Messenger_message(source, "rb Failed to uninstall the app");
        }
        return false;
    }

    public void tick()
    {
        Runnable token;
        token = Carpet.startProfilerSection("Scarpet schedule");
        events.handleEvents.getWhileDisabled(() -> {
            events.tick();
            return null;
        });
        token.run();
        token = Carpet.startProfilerSection("Scarpet app data");
        for (CarpetScriptHost host : modules.values())
        {
            host.tick();
        }
        token.run();
    }

    public void onClose()
    {
        CarpetEventServer.Event.SHUTDOWN.onTick(server);
        for (CarpetScriptHost host : modules.values())
        {
            host.onClose();
            events.removeAllHostEvents(host);
        }
        stopAll = true;
    }

    public void onPlayerJoin(ServerPlayer player)
    {
        PLAYER_CONNECTS.onPlayerEvent(player);
        modules.values().forEach(h ->
        {
            if (h.isPerUser())
            {
                try
                {
                    h.retrieveOwnForExecution(player.createCommandSourceStack());
                }
                catch (CommandSyntaxException ignored)
                {
                }
            }
        });
    }

    @Override
    public Path resolveResource(String suffix)
    {
        return server.getWorldPath(LevelResource.ROOT).resolve("scripts/" + suffix);
    }

    public void onPlayerLoggedOut(ServerPlayer player, Component reason)
    {
        if (PLAYER_DISCONNECTS.isNeeded())
        {
            PLAYER_DISCONNECTS.onPlayerMessage(player, reason.getContents().toString());
        }
    }

    private record TransferData(boolean perUser, Predicate<CommandSourceStack> commandValidator,
                                boolean isRuleApp, Expression.LoadOverride override)
    {
        private TransferData(CarpetScriptHost host)
        {
            this(host.perUser, host.commandValidator, host.isRuleApp, host.loadOverrides);
        }
    }

    public void reload(MinecraftServer server)
    {
        Map<String, TransferData> apps = new HashMap<>();
        modules.forEach((s, h) -> apps.put(s, new TransferData(h)));
        apps.keySet().forEach(s -> removeScriptHost(server.createCommandSourceStack(), s, false, false));
        CarpetEventServer.Event.clearAllBuiltinEvents();
        init();
        apps.forEach((s, data) -> addScriptHost(server.createCommandSourceStack(), s, data.commandValidator, data.perUser, false, data.isRuleApp, null, data.override));
    }

    public void reAddCommands()
    {
        modules.values().forEach(host -> host.addAppCommands(s -> {
        }));
    }

    private static boolean bootstrapDone = false;
    public static void parseFunctionClasses()
    {
        if (bootstrapDone) return;
        bootstrapDone = true;
        ExpressionException.prepareForDoom(); // see fc-#1172
        // Language
        AnnotationParser.parseFunctionClass(Arithmetic.class);
        AnnotationParser.parseFunctionClass(ControlFlow.class);
        AnnotationParser.parseFunctionClass(DataStructures.class);
        AnnotationParser.parseFunctionClass(Functions.class);
        AnnotationParser.parseFunctionClass(Loops.class);
        AnnotationParser.parseFunctionClass(Sys.class);
        AnnotationParser.parseFunctionClass(Threading.class);

        // API
        AnnotationParser.parseFunctionClass(Auxiliary.class);
        AnnotationParser.parseFunctionClass(BlockIterators.class);
        AnnotationParser.parseFunctionClass(Entities.class);
        AnnotationParser.parseFunctionClass(Inventories.class);
        AnnotationParser.parseFunctionClass(Scoreboards.class);
        AnnotationParser.parseFunctionClass(carpet.script.language.Threading.class);
        AnnotationParser.parseFunctionClass(WorldAccess.class);
    }
}
