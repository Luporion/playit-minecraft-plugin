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
    private final int bedrockLocalPort;
    private final boolean autoCreateBedrockTunnel;
    private final boolean promptAdminForBedrock;
    private boolean adminPromptSent = false;

    public PlayitKeysSetup(String secretKey, AtomicInteger state, boolean isGeyserPresent, int bedrockLocalPort,
                           boolean autoCreateBedrockTunnel, boolean promptAdminForBedrock) {
        keys.secretKey = secretKey;
        this.state = state;
        this.isGeyserPresent = isGeyserPresent;
        this.bedrockLocalPort = bedrockLocalPort;
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
                keys.bedrockTunnelAddress = null;

                boolean haveJava = false;
                boolean haveBedrock = false;

                for (AccountTunnel tunnel : tunnels.tunnels) {
                    if (tunnel.tunnelType == TunnelType.MinecraftJava) {
                        keys.tunnelAddress = tunnel.displayAddress;
                        haveJava = true;
                        log.info("found minecraft java tunnel: " + keys.tunnelAddress);
                    }
                    if (tunnel.tunnelType == TunnelType.MinecraftBedrock) {
                        keys.bedrockTunnelAddress = tunnel.displayAddress;
                        haveBedrock = true;
                        log.info("found minecraft bedrock tunnel: " + tunnel.displayAddress);
                    }
                }

                // Always create Java tunnel if not found
                if (!haveJava) {
                    log.info("create new minecraft java tunnel");

                    var create = new CreateTunnel();
                    create.localIp = "127.0.0.1";
                    create.portCount = 1;
                    create.portType = PortType.TCP;
                    create.tunnelType = TunnelType.MinecraftJava;
                    create.agentId = keys.agentId;

                    api.createTunnel(create);
                    return null; // Wait for tunnel to appear
                }

                // Handle Bedrock tunnel based on configuration
                if (isGeyserPresent && !haveBedrock) {
                    if (autoCreateBedrockTunnel) {
                        // Auto-create Bedrock tunnel
                        log.info("auto_create_bedrock_tunnel is enabled, creating new minecraft bedrock UDP tunnel on port " + bedrockLocalPort);
                        var create = new CreateTunnel();
                        create.localIp = "127.0.0.1";
                        create.localPort = bedrockLocalPort;
                        create.portCount = 1;
                        create.portType = PortType.UDP;
                        create.tunnelType = TunnelType.MinecraftBedrock;
                        create.agentId = keys.agentId;
                        api.createTunnel(create);
                        return null; // Wait for tunnel to appear
                    } else if (promptAdminForBedrock && !adminPromptSent) {
                        // Prompt admin to create Bedrock tunnel manually
                        log.info("==============================================");
                        log.info("[playit.gg] Geyser-Spigot detected!");
                        log.info("[playit.gg] To create a Bedrock tunnel for Bedrock/mobile players,");
                        log.info("[playit.gg] run: /playit createtunnels");
                        log.info("[playit.gg] Or set 'auto_create_bedrock_tunnel: true' in config.yml");
                        log.info("==============================================");
                        PlayitTunnelHelper.notifyAdminAboutBedrockTunnel();
                        adminPromptSent = true;
                    }
                }

                // Java tunnel is ready (Bedrock is optional unless auto_create is enabled)
                if (haveJava) {
                    return keys;
                }

                return null;
            }
            default -> {
                return null;
            }
        }
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
