/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge;

import com.google.inject.Inject;
import lombok.Getter;
import me.lucko.luckperms.ApiHandler;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.LuckPermsApi;
import me.lucko.luckperms.api.PlatformType;
import me.lucko.luckperms.common.LuckPermsPlugin;
import me.lucko.luckperms.common.api.ApiProvider;
import me.lucko.luckperms.common.calculators.CalculatorFactory;
import me.lucko.luckperms.common.commands.ConsecutiveExecutor;
import me.lucko.luckperms.common.commands.Sender;
import me.lucko.luckperms.common.config.LPConfiguration;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.contexts.ContextManager;
import me.lucko.luckperms.common.contexts.ServerCalculator;
import me.lucko.luckperms.common.core.UuidCache;
import me.lucko.luckperms.common.data.Importer;
import me.lucko.luckperms.common.groups.GroupManager;
import me.lucko.luckperms.common.runnables.ExpireTemporaryTask;
import me.lucko.luckperms.common.runnables.UpdateTask;
import me.lucko.luckperms.common.storage.Datastore;
import me.lucko.luckperms.common.storage.StorageFactory;
import me.lucko.luckperms.common.tracks.TrackManager;
import me.lucko.luckperms.common.users.UserManager;
import me.lucko.luckperms.common.utils.LocaleManager;
import me.lucko.luckperms.common.utils.LogFactory;
import me.lucko.luckperms.sponge.contexts.WorldCalculator;
import me.lucko.luckperms.sponge.service.LuckPermsService;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandManager;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GamePostInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Scheduler;
import org.spongepowered.api.service.permission.PermissionDescription;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.Subject;
import org.spongepowered.api.text.Text;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Getter
@Plugin(id = "luckperms", name = "LuckPerms", version = "null", authors = {"Luck"}, description = "A permissions plugin")
public class LPSpongePlugin implements LuckPermsPlugin {

    @Inject
    private Logger logger;

    @Inject
    private Game game;

    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;

    private Scheduler scheduler = Sponge.getScheduler();

    private final Set<UUID> ignoringLogs = ConcurrentHashMap.newKeySet();
    private LPConfiguration configuration;
    private UserManager userManager;
    private GroupManager groupManager;
    private TrackManager trackManager;
    private Datastore datastore;
    private UuidCache uuidCache;
    private ApiProvider apiProvider;
    private me.lucko.luckperms.api.Logger log;
    private Importer importer;
    private ConsecutiveExecutor consecutiveExecutor;
    private LuckPermsService service;
    private LocaleManager localeManager;
    private ContextManager<Subject> contextManager;
    private CalculatorFactory calculatorFactory;

    @Listener
    public void onEnable(GamePreInitializationEvent event) {
        log = LogFactory.wrap(logger);

        getLog().info("Loading configuration...");
        configuration = new SpongeConfig(this);

        localeManager = new LocaleManager();
        File locale = new File(getMainDir(), "lang.yml");
        if (locale.exists()) {
            getLog().info("Found locale file. Attempting to load from it.");
            try {
                localeManager.loadFromFile(locale);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // register events
        Sponge.getEventManager().registerListeners(this, new SpongeListener(this));

        // register commands
        getLog().info("Registering commands...");
        CommandManager cmdService = Sponge.getCommandManager();
        SpongeCommand commandManager = new SpongeCommand(this);
        cmdService.register(this, commandManager, "luckperms", "perms", "lp", "permissions", "p", "perm");

        datastore = StorageFactory.getDatastore(this, "h2");

        getLog().info("Loading internal permission managers...");
        uuidCache = new UuidCache(getConfiguration().isOnlineMode());
        userManager = new UserManager(this);
        groupManager = new GroupManager(this);
        trackManager = new TrackManager();
        importer = new Importer(commandManager);
        consecutiveExecutor = new ConsecutiveExecutor(commandManager);
        calculatorFactory = new SpongeCalculatorFactory(this);

        contextManager = new ContextManager<>();
        contextManager.registerCalculator(new ServerCalculator<>(getConfiguration().getServer()));
        contextManager.registerCalculator(new WorldCalculator(this));

        getLog().info("Registering PermissionService...");
        Sponge.getServiceManager().setProvider(this, PermissionService.class, (service = new LuckPermsService(this)));

        getLog().info("Registering API...");
        apiProvider = new ApiProvider(this);
        ApiHandler.registerProvider(apiProvider);
        Sponge.getServiceManager().setProvider(this, LuckPermsApi.class, apiProvider);

        int mins = getConfiguration().getSyncTime();
        if (mins > 0) {
            scheduler.createTaskBuilder().async().interval(mins, TimeUnit.MINUTES).execute(new UpdateTask(this))
                    .submit(LPSpongePlugin.this);
        } else {
            // Update online users
            runUpdateTask();
        }

        scheduler.createTaskBuilder().intervalTicks(1L).execute(SpongeSenderFactory.get(this)).submit(this);
        scheduler.createTaskBuilder().async().intervalTicks(60L).execute(new ExpireTemporaryTask(this)).submit(this);
        scheduler.createTaskBuilder().async().intervalTicks(20L).execute(consecutiveExecutor).submit(this);

        getLog().info("Successfully loaded.");
    }

    @Listener
    public void onDisable(GameStoppingServerEvent event) {
        getLog().info("Closing datastore...");
        datastore.shutdown();

        getLog().info("Unregistering API...");
        ApiHandler.unregisterProvider();
    }

    @Listener
    public void onPostInit(GamePostInitializationEvent event) {
        // register permissions
        Optional<PermissionService> ps = game.getServiceManager().provide(PermissionService.class);
        if (!ps.isPresent()) {
            getLog().warn("Unable to register all LuckPerms permissions. PermissionService not available.");
            return;
        }

        final PermissionService p = ps.get();

        Optional<PermissionDescription.Builder> builder = p.newDescriptionBuilder(this);
        if (!builder.isPresent()) {
            getLog().warn("Unable to register all LuckPerms permissions. Description Builder not available.");
            return;
        }

        for (Permission perm : Permission.values()) {
            registerPermission(p, perm.getNode());
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public File getMainDir() {
        File base = configDir.toFile().getParentFile().getParentFile();
        File luckPermsDir = new File(base, "luckperms");
        luckPermsDir.mkdirs();
        return luckPermsDir;
    }

    @Override
    public File getDataFolder() {
        return getMainDir();
    }

    @Override
    public String getVersion() {
        return "null";
    }

    @Override
    public PlatformType getType() {
        return PlatformType.SPONGE;
    }

    @Override
    public int getPlayerCount() {
        return game.getServer().getOnlinePlayers().size();
    }

    @Override
    public List<String> getPlayerList() {
        return game.getServer().getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    @Override
    public Set<UUID> getOnlinePlayers() {
        return game.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
    }

    @Override
    public boolean isOnline(UUID external) {
        return game.getServer().getPlayer(external).isPresent();
    }

    @Override
    public List<Sender> getNotifyListeners() {
        return game.getServer().getOnlinePlayers().stream()
                .map(s -> SpongeSenderFactory.get(this).wrap(s, Collections.singleton(Permission.LOG_NOTIFY)))
                .filter(Permission.LOG_NOTIFY::isAuthorized)
                .collect(Collectors.toList());
    }

    @Override
    public Sender getConsoleSender() {
        return SpongeSenderFactory.get(this).wrap(game.getServer().getConsole());
    }

    @Override
    public Set<Contexts> getPreProcessContexts(boolean op) {
        return Collections.emptySet();
    }

    @Override
    public Object getPlugin(String name) {
        return game.getPluginManager().getPlugin(name).get().getInstance().get();
    }

    @Override
    public Object getService(Class clazz) {
        return Sponge.getServiceManager().provideUnchecked(clazz);
    }

    @Override
    public UUID getUUID(String playerName) {
        try {
            return game.getServer().getPlayer(playerName).get().getUniqueId();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isPluginLoaded(String name) {
        return game.getPluginManager().isLoaded(name);
    }

    @Override
    public void runUpdateTask() {
        scheduler.createTaskBuilder().async().execute(new UpdateTask(this)).submit(this);
    }

    @Override
    public void doAsync(Runnable r) {
        scheduler.createTaskBuilder().async().execute(r).submit(this);
    }

    @Override
    public void doSync(Runnable r) {
        scheduler.createTaskBuilder().execute(r).submit(this);
    }

    private void registerPermission(PermissionService p, String node) {
        Optional<PermissionDescription.Builder> builder = p.newDescriptionBuilder(this);
        if (!builder.isPresent()) return;

        try {
            builder.get().assign(PermissionDescription.ROLE_ADMIN, true).description(Text.of(node)).id(node).register();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }
}
