package com.deadmandungeons.audioconnect.command.verify;

import com.deadmandungeons.audioconnect.AudioConnect;
import com.deadmandungeons.audioconnect.AudioConnectConfig;
import com.deadmandungeons.audioconnect.messages.AudioConnectUtils;
import com.deadmandungeons.connect.commons.ConnectUtils;
import com.deadmandungeons.deadmanplugin.command.ArgumentInfo;
import com.deadmandungeons.deadmanplugin.command.ArgumentInfo.ArgType;
import com.deadmandungeons.deadmanplugin.command.Arguments;
import com.deadmandungeons.deadmanplugin.command.Command;
import com.deadmandungeons.deadmanplugin.command.CommandInfo;
import com.deadmandungeons.deadmanplugin.command.SubCommandInfo;
import com.google.common.base.Supplier;
import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

//@formatter:off
@CommandInfo(
    name = "Verify",
    permissions = {"audioconnect.admin.verify"},
    subCommands = {
        @SubCommandInfo(
            arguments = {
                @ArgumentInfo(argName = "server-address", argType = ArgType.VARIABLE)
            },
            description = "Verify the public address of this server with your account at <connection.endpoint.host>"
        )
    }
)//@formatter:on
public class VerifyCommand implements Command {

    private static final BaseEncoding CREDENTIALS_ENCODING = BaseEncoding.base64().omitPadding();

    private final AudioConnect plugin = AudioConnect.getInstance();
    private final VerifyRequestListener verifyRequestListener;

    private VerifyTask activeVerifyTask;

    public VerifyCommand() {
        Supplier<String> verifyCodeSupplier = new Supplier<String>() {
            @Override
            public String get() {
                return activeVerifyTask != null ? activeVerifyTask.encodedVerifyCode : null;
            }
        };

        Plugin protocolLibPlugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        if (protocolLibPlugin != null && protocolLibPlugin.isEnabled()) {
            // Modify the status packet directly if ProtocolLib is enabled because
            // plugins like ServerListPlus do and overwrite the Bukkit ServerListPing event
            verifyRequestListener = new VerifyRequestPacketListener(verifyCodeSupplier);
        } else {
            verifyRequestListener = new VerifyRequestEventListener(verifyCodeSupplier);
        }
    }


    @Override
    public boolean execute(CommandSender sender, Arguments args) {
        Arguments.validateType(args, getClass());

        if (activeVerifyTask != null) {
            plugin.getMessenger().sendErrorMessage(sender, "failed.verify-in-progress");
            return false;
        }
        AudioConnectConfig config = plugin.getConfiguration();
        if (!config.validate()) {
            plugin.getMessenger().sendErrorMessage(sender, "failed.verify-invalid-config");
            return false;
        }
        String address = (String) args.getArgs()[0];
        if (!validateAddress(address)) {
            plugin.getMessenger().sendErrorMessage(sender, "failed.verify-invalid-address");
            return false;
        }

        plugin.getMessenger().sendMessage(sender, "misc.verify-request", config.getConnectionHost());

        verifyRequestListener.register();

        activeVerifyTask = new VerifyTask(sender, config, address);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, activeVerifyTask);
        return true;
    }


    private static boolean validateAddress(String address) {
        try {
            URI uri = new URI("mc://" + address);
            String host = uri.getHost();
            int port = uri.getPort();
            return host != null && port <= 65535 && address.equals(host + (port > 0 ? ":" + port : ""));
        } catch (URISyntaxException e) {
            return false;
        }
    }


    interface VerifyRequestListener {

        void register();

        void unregister();

    }

    private class VerifyTask implements Runnable {

        private static final String VERIFY_PATH = "/admin/servers/%s/verify";
        private static final long VERIFY_EXPIRE_MILLIS = 30000; // Verification should take at most 30 seconds
        private static final int VERIFY_DELAY_MILLIS = 1000; // Retry requests should be delayed at least 1 second

        private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
        private static final String USER_AGENT = "AudioConnect";
        private static final String CHARSET = "UTF-8";

        private final CookieManager cookieManager = new CookieManager();

        private final UUID playerId;
        private final String address;
        private final long startTime;

        private final String credentials;
        private final URL verifyUrl;
        private final URI verifyUri;

        private volatile String verifyCode;
        private volatile String encodedVerifyCode;

        private VerifyTask(CommandSender sender, AudioConnectConfig config, String address) {
            this.playerId = (sender instanceof Player ? ((Player) sender).getUniqueId() : null);
            this.address = address;
            this.startTime = System.currentTimeMillis();

            String rawCredentials = config.getConnectionUserId() + ":" + config.getConnectionUserPassword();
            credentials = CREDENTIALS_ENCODING.encode(rawCredentials.getBytes(StandardCharsets.UTF_8));

            try {
                URL baseUrl = config.getConnectionWebappUrl();
                UUID serverId = config.getConnectionServerId();

                verifyUrl = new URL(baseUrl + String.format(VERIFY_PATH, ConnectUtils.encodeUuidBase64(serverId)));
                verifyUri = verifyUrl.toURI();
            } catch (MalformedURLException | URISyntaxException e) {
                throw new IllegalStateException("Invalid server verify URL", e);
            }
        }

        @Override
        public void run() {
            try {
                long requestTime = System.currentTimeMillis();

                String requestParams = "address=" + URLEncoder.encode(address, CHARSET);
                if (verifyCode != null) {
                    requestParams += "&code=" + URLEncoder.encode(verifyCode, CHARSET);

                    if ((requestTime - startTime) / 1000 % 3 == 0) {
                        sendMessageSync("misc.verify-check");
                    }
                }

                byte[] data = requestParams.getBytes(CHARSET);

                HttpURLConnection connection = (HttpURLConnection) verifyUrl.openConnection();
                connection.setUseCaches(false);
                connection.setDoOutput(true);

                connection.setRequestMethod("POST");
                connection.setRequestProperty(HttpHeaders.USER_AGENT, USER_AGENT);
                connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
                connection.setRequestProperty(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length));
                // CookieManager requires this non-null requestHeaders parameter but never uses it...
                Map<String, List<String>> emptyRequestHeaders = Collections.emptyMap();
                for (Map.Entry<String, List<String>> cookieHeader : cookieManager.get(verifyUri, emptyRequestHeaders).entrySet()) {
                    connection.setRequestProperty(cookieHeader.getKey(), StringUtils.join(cookieHeader.getValue(), ";"));
                }

                connection.getOutputStream().write(data);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        // Read only the first line (server should only respond with the verify code in response body)
                        verifyCode = in.readLine();
                        // Encode the verify code using the Minecraft formatting codes (ChatColor)
                        // so that it is invisible when injected into the MOTD during server ping
                        encodedVerifyCode = AudioConnectUtils.encodeFormattingCodes(verifyCode);
                    }

                    cookieManager.put(verifyUri, connection.getHeaderFields());

                    long expireTimeDefault = startTime + VERIFY_EXPIRE_MILLIS;
                    long expireTime = connection.getHeaderFieldDate(HttpHeaders.EXPIRES, expireTimeDefault);
                    if (expireTime > expireTimeDefault) {
                        expireTime = expireTimeDefault;
                    }

                    long currentTime = System.currentTimeMillis();
                    if (currentTime < expireTime) {
                        long retryTimeDefault = requestTime + VERIFY_DELAY_MILLIS;
                        long retryTime = connection.getHeaderFieldDate(HttpHeaders.RETRY_AFTER, retryTimeDefault);
                        if (retryTime < retryTimeDefault) {
                            retryTime = retryTimeDefault;
                        }

                        long retryDelay = retryTime - currentTime;
                        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this, retryDelay / 50);
                    } else {
                        finish("failed.verify-invalid");
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    finish("succeeded.address-verified", address, verifyUrl.getHost());
                } else if (responseCode == HTTP_UNPROCESSABLE_ENTITY) {
                    finish("failed.verify-invalid");
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    finish("failed.verify-response-404");
                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    finish("failed.verify-response-401");
                } else if (responseCode == HttpURLConnection.HTTP_CONFLICT) {
                    finish("failed.verify-response-409");
                } else {
                    finish("failed.verify-response", responseCode);
                }
            } catch (Exception e) {
                finish("failed.verify-error");
                String errorMsg = "Failed to verify server at " + verifyUrl;
                plugin.getLogger().log(Level.SEVERE, errorMsg, e);
            }
        }

        private void finish(final String msgPath, final Object... msgVars) {
            verifyCode = encodedVerifyCode = null;

            // clear activeVerifyTask and send messages to the command sender on the main thread
            Bukkit.getScheduler().runTask(plugin, new Runnable() {

                @Override
                public void run() {
                    activeVerifyTask = null;
                    verifyRequestListener.unregister();
                    sendMessage(msgPath, msgVars);
                }
            });
        }

        private void sendMessageSync(final String msgPath, final Object... msgVars) {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {

                @Override
                public void run() {
                    sendMessage(msgPath, msgVars);
                }
            });
        }

        private void sendMessage(String msgPath, Object... msgVars) {
            CommandSender sender = Bukkit.getConsoleSender();
            if (playerId != null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    sender = player;
                }
            }
            if (msgPath.startsWith("failed")) {
                plugin.getMessenger().sendErrorMessage(sender, msgPath, msgVars);
            } else {
                plugin.getMessenger().sendMessage(sender, msgPath, msgVars);
            }
        }

    }

}
