package gg.playit.minecraft;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.api.models.Notice;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public final class PlayitBukkit extends JavaPlugin implements Listener {
    public static final String CFG_AGENT_SECRET_KEY = "agent-secret";
    public static final String CFG_CONNECTION_TIMEOUT_SECONDS = "mc-timeout-sec";
    public static final String CFG_AUTO_CREATE_BEDROCK_TUNNEL = "auto_create_bedrock_tunnel";
    public static final String CFG_PROMPT_ADMIN_FOR_BEDROCK = "prompt_admin_for_bedrock";
    public static final String CFG_JAVA_LOCAL_PORT = "java_local_port";
    public static final String CFG_BEDROCK_LOCAL_PORT = "bedrock_local_port";

    static Logger log = Logger.getLogger(PlayitBukkit.class.getName());
    final EventLoopGroup eventGroup = new NioEventLoopGroup();

    private final Object managerSync = new Object();
    private volatile PlayitManager playitManager;

    Server server;

    private boolean isGeyserPresent = false;
    private int geyserPort = 19132;
    private int javaLocalPort = 25565;
    private int bedrockLocalPort = 19132;
    private boolean autoCreateBedrockTunnel = false;
    private boolean promptAdminForBedrock = true;

    @Override
    public void onEnable() {
        server = Bukkit.getServer();

        // Detect Geyser plugin
        PluginManager pm = Bukkit.getServer().getPluginManager();
        Plugin geyser = pm.getPlugin("Geyser-Spigot");
        if (geyser != null && geyser.isEnabled()) {
            isGeyserPresent = true;
            // Try to read the port from Geyser config
            try {
                File geyserConfig = new File("plugins/Geyser-Spigot/config.yml");
                if (geyserConfig.exists()) {
                    Yaml yaml = new Yaml();
                    try (FileInputStream fis = new FileInputStream(geyserConfig)) {
                        Map<String, Object> config = yaml.load(fis);
                        if (config != null && config.containsKey("bedrock")) {
                            Object bedrockSection = config.get("bedrock");
                            if (bedrockSection instanceof Map) {
                                Object portObj = ((Map<?, ?>) bedrockSection).get("port");
                                if (portObj instanceof Number) {
                                    geyserPort = ((Number) portObj).intValue();
                                } else if (portObj instanceof String) {
                                    try {
                                        geyserPort = Integer.parseInt((String) portObj);
                                    } catch (NumberFormatException ignore) {}
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warning("Failed to read Geyser config: " + e.getMessage());
            }
            log.info("Geyser detected, Bedrock port: " + geyserPort);
        }

        var command = getCommand("playit");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            log.severe("failed to setup command /playit");
        }

        getConfig().addDefault("agent-secret", "");
        getConfig().addDefault(CFG_AUTO_CREATE_BEDROCK_TUNNEL, false);
        getConfig().addDefault(CFG_PROMPT_ADMIN_FOR_BEDROCK, true);
        getConfig().addDefault(CFG_JAVA_LOCAL_PORT, 25565);
        getConfig().addDefault(CFG_BEDROCK_LOCAL_PORT, 19132);
        saveDefaultConfig();

        // Read config values
        autoCreateBedrockTunnel = getConfig().getBoolean(CFG_AUTO_CREATE_BEDROCK_TUNNEL, false);
        promptAdminForBedrock = getConfig().getBoolean(CFG_PROMPT_ADMIN_FOR_BEDROCK, true);
        javaLocalPort = getConfig().getInt(CFG_JAVA_LOCAL_PORT, 25565);
        bedrockLocalPort = getConfig().getInt(CFG_BEDROCK_LOCAL_PORT, 19132);

        // If Geyser is present and config doesn't override, use Geyser's detected port
        if (isGeyserPresent && bedrockLocalPort == 19132) {
            bedrockLocalPort = geyserPort;
        }

        log.info("Configuration loaded - auto_create_bedrock_tunnel: " + autoCreateBedrockTunnel + 
                 ", prompt_admin_for_bedrock: " + promptAdminForBedrock +
                 ", java_local_port: " + javaLocalPort + 
                 ", bedrock_local_port: " + bedrockLocalPort);

        var secretKey = getConfig().getString("agent-secret");
        resetConnection(secretKey);

        try {
            pm.registerEvents(this, this);
        } catch (Exception e) {
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var manager = playitManager;

        if (player.isOp()) {
            if (manager.isGuest()) {
                player.sendMessage(ChatColor.RED + "WARNING:" + ChatColor.RESET + " playit.gg is running with a guest account");
            } else if (!manager.emailVerified()) {
                player.sendMessage(ChatColor.RED + "WARNING:" + ChatColor.RESET + " your email on playit.gg is not verified");
            }

            Notice notice = manager.getNotice();
            if (notice != null) {
                player.sendMessage(ChatColor.RED + "NOTICE:" + ChatColor.RESET + " " + notice.message);
                player.sendMessage(ChatColor.RED + "URL:" + ChatColor.RESET + " " + notice.url);
            }
        }
    }

    public void broadcast(String message) {
        Bukkit.broadcastMessage(ChatColor.BOLD + "" + ChatColor.UNDERLINE + "playit.gg:" + ChatColor.RESET + " " + message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("OP required");
            return true;
        }

        if (args.length > 0 && args[0].equals("agent")) {
            if (args.length > 1 && args[1].equals("status")) {
                var manager = playitManager;

                if (manager == null) {
                    String currentSecret = getConfig().getString(CFG_AGENT_SECRET_KEY);
                    if (currentSecret == null || currentSecret.length() == 0) {
                        sender.sendMessage(CFG_AGENT_SECRET_KEY + " is not set");
                    } else {
                        sender.sendMessage("playit status: offline (or shutting down)");
                    }
                } else {
                    String message = switch (manager.state()) {
                        case PlayitKeysSetup.STATE_INIT -> "preparing secret";
                        case PlayitKeysSetup.STATE_MISSING_SECRET -> "waiting for claim";
                        case PlayitKeysSetup.STATE_CHECKING_SECRET -> "checking secret";
                        case PlayitKeysSetup.STATE_CREATING_TUNNEL -> "preparing tunnel";
                        case PlayitKeysSetup.STATE_ERROR -> "error setting up key / tunnel";

                        case PlayitManager.STATE_CONNECTING -> "connecting";
                        case PlayitManager.STATE_ONLINE -> "connected";
                        case PlayitManager.STATE_OFFLINE -> "offline";
                        case PlayitManager.STATE_ERROR_WAITING -> "got error, retrying";
                        case PlayitManager.STATE_INVALID_AUTH -> "invalid secret key";

                        case PlayitManager.STATE_SHUTDOWN -> "shutdown";
                        default -> "unknown";
                    };

                    sender.sendMessage(ChatColor.BLUE + "" + ChatColor.UNDERLINE + "playit status:" + ChatColor.RESET + " " + message);
                }

                return true;
            }

            if (args.length > 1 && args[1].equals("restart")) {
                resetConnection(null);
                broadcast("restarting connection as requested by: " + sender.getName());
                return true;
            }

            if (args.length > 1 && args[1].equals("reset")) {
                getConfig().set(CFG_AGENT_SECRET_KEY, "");
                resetConnection(null);
                return true;
            }

            if (args.length > 1 && args[1].equals("shutdown")) {
                synchronized (managerSync) {
                    if (playitManager != null) {
                        playitManager.shutdown();
                        playitManager = null;
                    }
                }
                broadcast("shutting down connection as requested by: " + sender.getName());
                return true;
            }

            if (args.length > 2 && args[1].equals("set-secret")) {
                String secretKey = args[2];
                if (secretKey.length() < 32) {
                    sender.sendMessage("invalid secret key");
                    return true;
                }
                resetConnection(secretKey);
                sender.sendMessage("updated secret key, connecting to new tunnel server");
                return true;
            }

            return false;
        }

        if (args.length > 0 && args[0].equals("prop")) {
            if (args.length > 1 && args[1].equals("get")) {
                {
                    int current = 30;
                    var p = playitManager;
                    if (p != null) {
                        current = playitManager.connectionTimeoutSeconds;
                    }

                    int settings = 30;
                    try {
                        settings = getConfig().getInt(CFG_CONNECTION_TIMEOUT_SECONDS);
                    } catch (Exception ignore) {
                    }

                    sender.sendMessage("prop: " + CFG_CONNECTION_TIMEOUT_SECONDS + ", current: " + current + ", setting: " + settings);
                }

                return true;
            }

            if (args.length > 2 && args[1].equals("set")) {
                if (args[2].equals(CFG_CONNECTION_TIMEOUT_SECONDS)) {
                    try {
                        var value = Integer.parseInt(args[3]);
                        getConfig().set(CFG_CONNECTION_TIMEOUT_SECONDS, value);
                        saveConfig();

                        sender.sendMessage("configuration set, run \"/playit agent restart\" to apply");
                    } catch (Exception ignore) {
                        sender.sendMessage("invalid integer");
                    }

                    return true;
                }
            }

            return false;
        }

        if (args.length > 0 && args[0].equals("tunnel")) {
            if (args.length > 1 && args[1].equals("get-address")) {
                var m = playitManager;
                if (m != null) {
                    var a = m.getAddress();
                    if (a != null) {
                        broadcast(a);
                        return true;
                    }
                }

                sender.sendMessage("playit.gg is still setting up");
                return true;
            }
        }

        if (args.length > 0 && args[0].equals("account")) {
            if (args.length > 1 && args[1].equals("guest-login-link")) {
                var secret = getConfig().getString(CFG_AGENT_SECRET_KEY);
                if (secret == null) {
                    sender.sendMessage("ERROR: secret not set");
                    return true;
                }

                sender.sendMessage("preparing login link");

                new Thread(() -> {
                    try {
                        var api = new ApiClient(secret);
                        var session = api.createGuestWebSessionKey();

                        var url = "https://playit.gg/login/guest-account/" + session;
                        log.info("generated login url: " + url);

                        sender.sendMessage("generated login url");
                        sender.sendMessage("URL: " + url);
                    } catch (ApiError e) {
                        log.warning("failed to create guest secret: " + e);
                        sender.sendMessage("error: " + e.getMessage());
                    } catch (IOException e) {
                        log.severe("failed to create guest secret: " + e);
                    }
                }).start();

                return true;
            }
        }

        // Handle /playit createtunnels command
        if (args.length > 0 && args[0].equals("createtunnels")) {
            return handleCreateTunnelsCommand(sender);
        }

        return false;
    }

    /**
     * Handle the /playit createtunnels command.
     */
    private boolean handleCreateTunnelsCommand(CommandSender sender) {
        var manager = playitManager;
        String secretKey = getConfig().getString(CFG_AGENT_SECRET_KEY);
        String agentId = manager != null ? manager.getAgentId() : null;

        if (secretKey == null || secretKey.length() < 32) {
            sender.sendMessage(ChatColor.RED + "[playit.gg] Error: Agent not configured. Please complete the initial setup first.");
            sender.sendMessage(ChatColor.YELLOW + "Check server console for the claim URL.");
            return true;
        }

        if (agentId == null || agentId.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "[playit.gg] Error: Agent ID not available. Please wait for setup to complete.");
            return true;
        }

        sender.sendMessage(ChatColor.BLUE + "[playit.gg] " + ChatColor.RESET + "Creating tunnels...");
        sender.sendMessage("  Geyser detected: " + (isGeyserPresent ? ChatColor.GREEN + "Yes" : ChatColor.GRAY + "No"));

        // Run async to avoid blocking the main thread
        new Thread(() -> {
            try {
                PlayitTunnelHelper helper = new PlayitTunnelHelper(secretKey, agentId, javaLocalPort, bedrockLocalPort);
                PlayitTunnelHelper.TunnelStatus status = helper.createJavaAndBedrockTunnelsIfMissing(isGeyserPresent);

                // Send results back to sender (on main thread for safety)
                Bukkit.getScheduler().runTask(this, () -> sendTunnelResults(sender, status, isGeyserPresent));
            } catch (Exception e) {
                log.severe("[CreateTunnelsCommand] Error creating tunnels: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> 
                    sender.sendMessage(ChatColor.RED + "[playit.gg] Error creating tunnels: " + e.getMessage())
                );
            }
        }).start();

        return true;
    }

    /**
     * Send tunnel creation results to command sender.
     */
    private void sendTunnelResults(CommandSender sender, PlayitTunnelHelper.TunnelStatus status, boolean geyserPresent) {
        sender.sendMessage(ChatColor.BLUE + "[playit.gg] " + ChatColor.RESET + "Tunnel status:");

        // Java tunnel status
        if (status.javaCreated) {
            sender.sendMessage(ChatColor.GREEN + "  ✓ Java tunnel created");
        } else if (status.hasJavaTunnel) {
            sender.sendMessage(ChatColor.GREEN + "  ✓ Java tunnel exists");
        } else {
            sender.sendMessage(ChatColor.RED + "  ✗ Java tunnel not available");
        }

        if (status.javaTunnelAddress != null) {
            sender.sendMessage(ChatColor.WHITE + "    Address: " + ChatColor.AQUA + status.javaTunnelAddress);
        }

        // Bedrock tunnel status
        if (geyserPresent) {
            if (status.bedrockCreated) {
                sender.sendMessage(ChatColor.GREEN + "  ✓ Bedrock tunnel created");
            } else if (status.hasBedrockTunnel) {
                sender.sendMessage(ChatColor.GREEN + "  ✓ Bedrock tunnel exists");
            } else {
                sender.sendMessage(ChatColor.RED + "  ✗ Bedrock tunnel not available");
            }

            if (status.bedrockTunnelAddress != null) {
                sender.sendMessage(ChatColor.WHITE + "    Address: " + ChatColor.AQUA + status.bedrockTunnelAddress);
            }
        } else {
            sender.sendMessage(ChatColor.GRAY + "  - Bedrock tunnel skipped (Geyser not detected)");
        }

        // Error message if any
        if (status.errorMessage != null) {
            sender.sendMessage(ChatColor.RED + "  Error: " + status.errorMessage);
        }
    }

    private void resetConnection(String secretKey) {
        if (secretKey != null) {
            getConfig().set(CFG_AGENT_SECRET_KEY, secretKey);
            saveConfig();
        }

        synchronized (managerSync) {
            if (playitManager != null) {
                playitManager.shutdown();
            }

            playitManager = new PlayitManager(this, isGeyserPresent, javaLocalPort, bedrockLocalPort, autoCreateBedrockTunnel, promptAdminForBedrock);
            try {
                int waitSeconds = getConfig().getInt(CFG_CONNECTION_TIMEOUT_SECONDS);
                if (waitSeconds != 0) {
                    playitManager.connectionTimeoutSeconds = waitSeconds;
                }
            } catch (Exception ignore) {
            }

            new Thread(playitManager).start();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) {
            return null;
        }

        int argCount = args.length;
        if (argCount != 0 && args[argCount - 1].length() == 0) {
            argCount -= 1;
        }

        if (argCount == 0) {
            return List.of("agent", "tunnel", "prop", "account", "createtunnels");
        }

        if (args[0].equals("account")) {
            if (argCount == 1) {
                return List.of("guest-login-link");
            }
        }

        if (args[0].equals("agent")) {
            if (argCount == 1) {
                return List.of("set-secret", "shutdown", "status", "restart", "reset");
            }
        }

        if (args[0].equals("prop")) {
            if (argCount == 1) {
                return List.of("set", "get");
            }

            if (argCount == 2) {
                if (!args[1].equals("set") && !args[1].equals("get")) {
                    return null;
                }

                return List.of(CFG_CONNECTION_TIMEOUT_SECONDS);
            }
        }

        if (args[0].equals("tunnel")) {
            if (argCount == 1) {
                return List.of("get-address");
            }
        }

        return null;
    }

    @Override
    public void onDisable() {
        if (playitManager != null) {
            playitManager.shutdown();
            playitManager = null;
        }
    }
}
