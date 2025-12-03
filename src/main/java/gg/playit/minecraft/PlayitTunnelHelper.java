package gg.playit.minecraft;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.api.actions.CreateTunnel;
import gg.playit.api.models.AccountTunnel;
import gg.playit.api.models.AccountTunnels;
import gg.playit.api.models.PortType;
import gg.playit.api.models.TunnelType;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Helper class for managing playit.gg tunnels.
 * Provides methods to check existing tunnels and create new ones for Java and Bedrock.
 */
public class PlayitTunnelHelper {
    private static final Logger log = Logger.getLogger(PlayitTunnelHelper.class.getName());

    private final ApiClient api;
    private final String agentId;
    private final int javaLocalPort;
    private final int bedrockLocalPort;

    /**
     * Result of checking for existing tunnels.
     */
    public static class TunnelCheckResult {
        public boolean hasJavaTunnel = false;
        public boolean hasBedrockTunnel = false;
        public String javaTunnelAddress = null;
        public String bedrockTunnelAddress = null;

        public boolean hasBothTunnels() {
            return hasJavaTunnel && hasBedrockTunnel;
        }
    }

    /**
     * Result of a tunnel creation attempt.
     */
    public static class TunnelCreationResult {
        public boolean success;
        public String message;
        public String tunnelAddress;
        public boolean alreadyExists;
        public boolean accountLimitReached;

        public static TunnelCreationResult success(String address) {
            TunnelCreationResult result = new TunnelCreationResult();
            result.success = true;
            result.tunnelAddress = address;
            result.message = "Tunnel created successfully";
            return result;
        }

        public static TunnelCreationResult alreadyExists(String address) {
            TunnelCreationResult result = new TunnelCreationResult();
            result.success = true;
            result.alreadyExists = true;
            result.tunnelAddress = address;
            result.message = "Tunnel already exists";
            return result;
        }

        public static TunnelCreationResult failure(String message) {
            TunnelCreationResult result = new TunnelCreationResult();
            result.success = false;
            result.message = message;
            return result;
        }

        public static TunnelCreationResult accountLimit(String message) {
            TunnelCreationResult result = new TunnelCreationResult();
            result.success = false;
            result.accountLimitReached = true;
            result.message = message;
            return result;
        }
    }

    public PlayitTunnelHelper(String secretKey, String agentId, int javaLocalPort, int bedrockLocalPort) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("Secret key cannot be null or empty");
        }
        if (agentId == null || agentId.isEmpty()) {
            throw new IllegalArgumentException("Agent ID cannot be null or empty");
        }
        if (javaLocalPort < 1 || javaLocalPort > 65535) {
            throw new IllegalArgumentException("Java local port must be between 1 and 65535");
        }
        if (bedrockLocalPort < 1 || bedrockLocalPort > 65535) {
            throw new IllegalArgumentException("Bedrock local port must be between 1 and 65535");
        }
        
        this.api = new ApiClient(secretKey);
        this.agentId = agentId;
        this.javaLocalPort = javaLocalPort;
        this.bedrockLocalPort = bedrockLocalPort;
    }

    /**
     * Check what tunnels already exist for this agent.
     */
    public TunnelCheckResult checkExistingTunnels() throws IOException {
        TunnelCheckResult result = new TunnelCheckResult();

        AccountTunnels tunnels = api.listTunnels();
        for (AccountTunnel tunnel : tunnels.tunnels) {
            if (tunnel.tunnelType == TunnelType.MinecraftJava) {
                result.hasJavaTunnel = true;
                result.javaTunnelAddress = tunnel.displayAddress;
                log.info("Found existing Java tunnel: " + tunnel.displayAddress);
            }
            if (tunnel.tunnelType == TunnelType.MinecraftBedrock) {
                result.hasBedrockTunnel = true;
                result.bedrockTunnelAddress = tunnel.displayAddress;
                log.info("Found existing Bedrock tunnel: " + tunnel.displayAddress);
            }
        }

        return result;
    }

    /**
     * Create a Java (TCP) tunnel if one doesn't exist.
     */
    public TunnelCreationResult createJavaTunnel() {
        try {
            // First check if tunnel already exists
            TunnelCheckResult check = checkExistingTunnels();
            if (check.hasJavaTunnel) {
                log.info("Java tunnel already exists: " + check.javaTunnelAddress);
                return TunnelCreationResult.alreadyExists(check.javaTunnelAddress);
            }

            log.info("Creating new Minecraft Java tunnel on port " + javaLocalPort);

            CreateTunnel create = new CreateTunnel();
            create.localIp = "127.0.0.1";
            create.localPort = javaLocalPort;
            create.portCount = 1;
            create.portType = PortType.TCP;
            create.tunnelType = TunnelType.MinecraftJava;
            create.agentId = agentId;

            api.createTunnel(create);

            // Verify tunnel was created
            TunnelCheckResult verifyCheck = checkExistingTunnels();
            if (verifyCheck.hasJavaTunnel) {
                log.info("Java tunnel created successfully: " + verifyCheck.javaTunnelAddress);
                return TunnelCreationResult.success(verifyCheck.javaTunnelAddress);
            } else {
                return TunnelCreationResult.failure("Tunnel creation completed but tunnel not found");
            }

        } catch (ApiError e) {
            return handleApiError(e, "Java");
        } catch (IOException e) {
            log.severe("Failed to create Java tunnel: " + e.getMessage());
            return TunnelCreationResult.failure("I/O error: " + e.getMessage());
        }
    }

    /**
     * Create a Bedrock (UDP) tunnel if one doesn't exist.
     */
    public TunnelCreationResult createBedrockTunnel() {
        try {
            // First check if tunnel already exists
            TunnelCheckResult check = checkExistingTunnels();
            if (check.hasBedrockTunnel) {
                log.info("Bedrock tunnel already exists: " + check.bedrockTunnelAddress);
                return TunnelCreationResult.alreadyExists(check.bedrockTunnelAddress);
            }

            log.info("Creating new Minecraft Bedrock tunnel on port " + bedrockLocalPort);

            CreateTunnel create = new CreateTunnel();
            create.localIp = "127.0.0.1";
            create.localPort = bedrockLocalPort;
            create.portCount = 1;
            create.portType = PortType.UDP;
            create.tunnelType = TunnelType.MinecraftBedrock;
            create.agentId = agentId;

            api.createTunnel(create);

            // Verify tunnel was created
            TunnelCheckResult verifyCheck = checkExistingTunnels();
            if (verifyCheck.hasBedrockTunnel) {
                log.info("Bedrock tunnel created successfully: " + verifyCheck.bedrockTunnelAddress);
                return TunnelCreationResult.success(verifyCheck.bedrockTunnelAddress);
            } else {
                return TunnelCreationResult.failure("Tunnel creation completed but tunnel not found");
            }

        } catch (ApiError e) {
            return handleApiError(e, "Bedrock");
        } catch (IOException e) {
            log.severe("Failed to create Bedrock tunnel: " + e.getMessage());
            return TunnelCreationResult.failure("I/O error: " + e.getMessage());
        }
    }

    /**
     * Ensure both Java and Bedrock tunnels exist.
     * Returns a summary of what was created or already existed.
     */
    public String ensureBothTunnels() {
        StringBuilder summary = new StringBuilder();

        TunnelCreationResult javaResult = createJavaTunnel();
        summary.append("Java tunnel: ");
        if (javaResult.alreadyExists) {
            summary.append("already exists (").append(javaResult.tunnelAddress).append(")");
        } else if (javaResult.success) {
            summary.append("created (").append(javaResult.tunnelAddress).append(")");
        } else {
            summary.append("FAILED - ").append(javaResult.message);
        }

        summary.append("\n");

        TunnelCreationResult bedrockResult = createBedrockTunnel();
        summary.append("Bedrock tunnel: ");
        if (bedrockResult.alreadyExists) {
            summary.append("already exists (").append(bedrockResult.tunnelAddress).append(")");
        } else if (bedrockResult.success) {
            summary.append("created (").append(bedrockResult.tunnelAddress).append(")");
        } else {
            summary.append("FAILED - ").append(bedrockResult.message);
        }

        return summary.toString();
    }

    private TunnelCreationResult handleApiError(ApiError e, String tunnelType) {
        String errorMsg = e.responseBody != null ? e.responseBody.toLowerCase() : "";

        // Check for common error conditions
        if (e.statusCode == 400) {
            if (errorMsg.contains("already exists") || errorMsg.contains("tunnel already exists")) {
                log.info(tunnelType + " tunnel already exists for this agent");
                return TunnelCreationResult.alreadyExists(null);
            }
            if (errorMsg.contains("limit") || errorMsg.contains("maximum")) {
                log.warning(tunnelType + " tunnel creation blocked by account limit. " +
                        "Please visit https://playit.gg to manage your tunnels or upgrade your account.");
                return TunnelCreationResult.accountLimit(
                        "Account tunnel limit reached. Please delete unused tunnels at https://playit.gg or upgrade your account.");
            }
        }

        if (e.statusCode == 401 || e.statusCode == 403) {
            log.severe("Authentication failed when creating " + tunnelType + " tunnel");
            return TunnelCreationResult.failure("Authentication failed. Please reclaim the agent.");
        }

        log.severe("API error creating " + tunnelType + " tunnel: " + e.getMessage());
        return TunnelCreationResult.failure("API error (code " + e.statusCode + "): " + e.responseBody);
    }
}
