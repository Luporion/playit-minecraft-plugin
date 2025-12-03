package gg.playit.minecraft.command;

import gg.playit.minecraft.PlayitBukkit;
import gg.playit.minecraft.PlayitTunnelHelper;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Command handler for /playit createtunnels
 * Allows admins to manually create Java and Bedrock tunnels.
 */
public class CreateTunnelsCommand implements CommandExecutor, TabCompleter {
    private static final Logger log = Logger.getLogger(CreateTunnelsCommand.class.getName());

    private final PlayitBukkit plugin;

    public CreateTunnelsCommand(PlayitBukkit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playit.createtunnels")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        // Get the secret key and agent ID from the plugin
        String secretKey = plugin.getConfig().getString(PlayitBukkit.CFG_AGENT_SECRET_KEY);
        if (secretKey == null || secretKey.length() < 32) {
            sender.sendMessage(ChatColor.RED + "playit.gg is not configured yet. Please wait for the claim process to complete.");
            return true;
        }

        String agentId = plugin.getAgentId();
        if (agentId == null) {
            sender.sendMessage(ChatColor.RED + "playit.gg agent is not ready yet. Please wait for setup to complete.");
            return true;
        }

        int javaPort = plugin.getConfig().getInt("java_local_port", 25565);
        int bedrockPort = plugin.getConfig().getInt("bedrock_local_port", 19132);

        sender.sendMessage(ChatColor.AQUA + "Creating playit.gg tunnels...");
        log.info("Admin " + sender.getName() + " requested tunnel creation");

        // Run tunnel creation asynchronously to avoid blocking the main thread
        new Thread(() -> {
            try {
                PlayitTunnelHelper helper = new PlayitTunnelHelper(secretKey, agentId, javaPort, bedrockPort);

                // Check if Geyser is present to decide which tunnels to create
                boolean createBedrock = plugin.isGeyserPresent();

                PlayitTunnelHelper.TunnelCreationResult javaResult = helper.createJavaTunnel();
                sendTunnelResult(sender, "Java (TCP)", javaResult);

                if (createBedrock) {
                    PlayitTunnelHelper.TunnelCreationResult bedrockResult = helper.createBedrockTunnel();
                    sendTunnelResult(sender, "Bedrock (UDP)", bedrockResult);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Geyser not detected - skipping Bedrock tunnel creation.");
                    sender.sendMessage(ChatColor.GRAY + "Install Geyser-Spigot and restart to enable Bedrock support.");
                }

                sender.sendMessage(ChatColor.GREEN + "Tunnel creation complete!");
                sender.sendMessage(ChatColor.GRAY + "Visit https://playit.gg to view and manage your tunnels.");

            } catch (Exception e) {
                log.severe("Error during tunnel creation: " + e.getMessage());
                sender.sendMessage(ChatColor.RED + "Error creating tunnels: " + e.getMessage());
            }
        }).start();

        return true;
    }

    private void sendTunnelResult(CommandSender sender, String tunnelType, PlayitTunnelHelper.TunnelCreationResult result) {
        if (result.success) {
            if (result.alreadyExists) {
                sender.sendMessage(ChatColor.YELLOW + tunnelType + " tunnel: " + ChatColor.WHITE + "Already exists" +
                        (result.tunnelAddress != null ? " (" + result.tunnelAddress + ")" : ""));
            } else {
                sender.sendMessage(ChatColor.GREEN + tunnelType + " tunnel: " + ChatColor.WHITE + "Created" +
                        (result.tunnelAddress != null ? " (" + result.tunnelAddress + ")" : ""));
            }
        } else {
            sender.sendMessage(ChatColor.RED + tunnelType + " tunnel: " + ChatColor.WHITE + "Failed - " + result.message);
            if (result.accountLimitReached) {
                sender.sendMessage(ChatColor.YELLOW + "Tip: Delete unused tunnels at https://playit.gg or upgrade your account.");
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
