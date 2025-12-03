package gg.playit.minecraft;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.api.actions.CreateTunnel;
import gg.playit.api.models.AccountTunnel;
import gg.playit.api.models.Notice;
import gg.playit.api.models.PortType;
import gg.playit.api.models.TunnelType;
import gg.playit.minecraft.utils.Hex;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class PlayitKeysSetup {
    private static final Logger log = Logger.getLogger(PlayitKeysSetup.class.getName());
    public final AtomicInteger state;
    public static final int STATE_INIT = 1;
    public static final int STATE_MISSING_SECRET = 2;
    public static final int STATE_CHECKING_SECRET = 3;
    public static final int STATE_CREATING_TUNNEL = 4;
    public static final int STATE_ERROR = 5;
    public static final int STATE_SHUTDOWN = 0;
    private final ApiClient openClient = new ApiClient(null);
    private final boolean isGeyserPresent;
    private final int geyserPort;
    private final int javaPort;
    private final boolean autoCreateBedrockTunnel;
    private final boolean promptAdminForBedrock;
    private boolean bedrockPromptShown = false;

    public PlayitKeysSetup(String secretKey, AtomicInteger state, boolean isGeyserPresent, int geyserPort,
                          int javaPort, boolean autoCreateBedrockTunnel, boolean promptAdminForBedrock) {
        keys.secretKey = secretKey;
        this.state = state;
        this.isGeyserPresent = isGeyserPresent;
        this.geyserPort = geyserPort;
        this.javaPort = javaPort;
        this.autoCreateBedrockTunnel = autoCreateBedrockTunnel;
        this.promptAdminForBedrock = promptAdminForBedrock;
    }

    private final PlayitKeys keys = new PlayitKeys();
    private String claimCode;

    public int getState() {
        return state.get();
    }

    public void shutdown() {
        this.state.set(STATE_SHUTDOWN);
    }

    public String getClaimCode() {
        return claimCode;
    }

    public PlayitKeys progress() throws IOException {
        switch (state.get()) {
            case STATE_INIT -> {
                if (keys.secretKey == null) {
                    state.compareAndSet(STATE_INIT, STATE_MISSING_SECRET);
                    return null;
                }

                state.compareAndSet(STATE_INIT, STATE_CHECKING_SECRET);
                log.info("secret key found, checking");
                return null;
            }
            case STATE_MISSING_SECRET -> {
                if (claimCode == null) {
                    byte[] array = new byte[8];
                    new Random().nextBytes(array);
                    claimCode = Hex.encodeHexString(array);
                    log.info("secret key not set, generate claim code: " + claimCode);
                }

                log.info("trying to exchange claim code for secret");
                keys.secretKey = openClient.exchangeClaimForSecret(claimCode);

                if (keys.secretKey == null) {
                    log.info("failed to exchange, to claim visit: https://playit.gg/mc/" + claimCode);
                } else {
                    state.compareAndSet(STATE_MISSING_SECRET, STATE_CHECKING_SECRET);
                }

                return null;
            }
            case STATE_CHECKING_SECRET -> {
                log.info("check secret");

                var api = new ApiClient(keys.secretKey);
                try {
                    var status = api.getStatus();

                    keys.isGuest = status.isGuest;
                    keys.isEmailVerified = status.emailVerified;
                    keys.agentId = status.agentId;
                    keys.notice = status.notice;

                    state.compareAndSet(STATE_CHECKING_SECRET, STATE_CREATING_TUNNEL);
                    return null;
                } catch (ApiError e) {
                    if (e.statusCode == 401 || e.statusCode == 400) {
                        if (claimCode == null) {
                            log.info("secret key invalid, starting over");
                            state.compareAndSet(STATE_CHECKING_SECRET, STATE_MISSING_SECRET);
                        } else {
                            state.compareAndSet(STATE_CHECKING_SECRET, STATE_ERROR);
                            log.info("secret failed verification after creating, moving to error state");
                        }

                        return null;
                    }

                    throw e;
                }
            }
            case STATE_CREATING_TUNNEL -> {
                var api = new ApiClient(keys.secretKey);

                var tunnels = api.listTunnels();
                keys.tunnelAddress = null;

                boolean haveJava = false;
                boolean haveBedrock = !isGeyserPresent;

                for (AccountTunnel tunnel : tunnels.tunnels) {
                    if (tunnel.tunnelType == TunnelType.MinecraftJava) {
                        keys.tunnelAddress = tunnel.displayAddress;
                        haveJava = true;
                        log.info("found minecraft java tunnel: " + keys.tunnelAddress);
                    }
                    if (isGeyserPresent && tunnel.tunnelType == TunnelType.MinecraftBedrock) {
                        haveBedrock = true;
                        keys.bedrockTunnelAddress = tunnel.displayAddress;
                        log.info("found minecraft bedrock tunnel: " + tunnel.displayAddress);
                    }
                }

                // Always create Java tunnel if not found
                if (!haveJava) {
                    log.info("create new minecraft java tunnel on port " + javaPort);

                    try {
                        var create = new CreateTunnel();
                        create.localIp = "127.0.0.1";
                        create.localPort = javaPort;
                        create.portCount = 1;
                        create.portType = PortType.TCP;
                        create.tunnelType = TunnelType.MinecraftJava;
                        create.agentId = keys.agentId;

                        api.createTunnel(create);
                    } catch (ApiError e) {
                        handleTunnelCreationError(e, "Java");
                    }
                    return null; // Wait for tunnel to appear
                }

                // If Geyser is present, handle Bedrock tunnel creation based on config
                if (isGeyserPresent && !haveBedrock) {
                    if (autoCreateBedrockTunnel) {
                        log.info("create new minecraft bedrock UDP tunnel on port " + geyserPort);
                        try {
                            var create = new CreateTunnel();
                            create.localIp = "127.0.0.1";
                            create.localPort = geyserPort;
                            create.portCount = 1;
                            create.portType = PortType.UDP;
                            create.tunnelType = TunnelType.MinecraftBedrock;
                            create.agentId = keys.agentId;
                            api.createTunnel(create);
                        } catch (ApiError e) {
                            handleTunnelCreationError(e, "Bedrock");
                        }
                        return null; // Wait for tunnel to appear
                    } else if (promptAdminForBedrock && !bedrockPromptShown) {
                        // Prompt admin to create bedrock tunnel manually
                        bedrockPromptShown = true;
                        log.info("================================================================================");
                        log.info("GEYSER DETECTED: Bedrock tunnel not configured.");
                        log.info("To allow Bedrock players to connect, run: /playit createtunnels");
                        log.info("Or set 'auto_create_bedrock_tunnel: true' in config.yml and restart.");
                        log.info("================================================================================");
                        // Continue without bedrock tunnel - don't block setup
                        haveBedrock = true; // Mark as handled so we can proceed
                    }
                }

                // Complete setup if:
                // 1. We have a Java tunnel AND
                // 2. Either we have/handled the bedrock tunnel, OR we're not auto-creating bedrock
                //    (meaning admin will create it manually via /playit createtunnels)
                if (haveJava && (haveBedrock || !autoCreateBedrockTunnel)) {
                    return keys;
                }

                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private void handleTunnelCreationError(ApiError e, String tunnelType) {
        String errorMsg = e.responseBody != null ? e.responseBody.toLowerCase() : "";
        
        if (e.statusCode == 400) {
            if (errorMsg.contains("already exists") || errorMsg.contains("tunnel already exists")) {
                log.info(tunnelType + " tunnel already exists for this agent - continuing");
                return;
            }
            if (errorMsg.contains("limit") || errorMsg.contains("maximum")) {
                log.warning("================================================================================");
                log.warning(tunnelType + " TUNNEL CREATION BLOCKED: Account tunnel limit reached.");
                log.warning("Please visit https://playit.gg to delete unused tunnels or upgrade your account.");
                log.warning("================================================================================");
                return;
            }
        }
        
        log.severe("Failed to create " + tunnelType + " tunnel: " + e.getMessage());
    }

    public static class PlayitKeys {
        public String secretKey;
        public String agentId;
        public String tunnelAddress;
        public String bedrockTunnelAddress;
        public boolean isGuest;
        public boolean isEmailVerified;
        public Notice notice;
    }
}
