package com.discordchatlogger;

import com.google.common.base.Strings;
import com.google.inject.Provides;

import java.io.IOException;

import net.runelite.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;

import static net.runelite.http.api.RuneLiteAPI.GSON;

import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;


@Slf4j
@PluginDescriptor(
        name = "Discord Chat Logger"
)
public class DiscordChatLoggerPlugin extends Plugin {
    @Inject
    private DiscordChatLoggerConfig config;
    @Inject
    private ItemManager itemManager;
    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Client client;


    @Override
    protected void startUp()
    {
    }

    @Override
    protected void shutDown()
    {
    }

    @Provides
    DiscordChatLoggerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DiscordChatLoggerConfig.class);
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        ChatMessageType messageType = chatMessage.getType();

        if (!isChatTypeSupported(messageType)) {
            return;
        }

        String sender = chatMessage.getName().replaceAll("\\<.*?>", "").replaceAll("[^0-9a-zA-Z ]+", " ");
        String sanitizedMessage = Text.removeTags(chatMessage.getMessage());

        sanitizedMessage = addDiscordIdsToMessage(sanitizedMessage);

        WebhookBody webhookBody = new WebhookBody();
        StringBuilder stringBuilder = new StringBuilder();
        String webhookUrl = null;

        if (config.usePrivate() && config.logSelf() && messageType == ChatMessageType.PRIVATECHATOUT ||
            config.usePrivate() && config.logOthers() && messageType == ChatMessageType.PRIVATECHAT) {
            String receiverName = messageType == ChatMessageType.PRIVATECHATOUT ? sender : getPlayerName();
            if (config.includeOtherUsername()) {
                if (sender.equals(getPlayerName())) {
                    stringBuilder.append("To **").append(receiverName).append("**").append(": ");
                }
                if (receiverName.equals(getPlayerName())) {
                    stringBuilder.append("From **").append(sender).append("**").append(": ");
                }
            }
            webhookUrl = config.webhookPrivate();
        }

        if (config.useGroup() && (config.logSelf() && sender.equals(getPlayerName())) && messageType == ChatMessageType.CLAN_GIM_CHAT ||
            config.useGroup() && (config.logOthers() && !sender.equals(getPlayerName())) && messageType == ChatMessageType.CLAN_GIM_CHAT) {
            String groupName = chatMessage.getSender();
            if (config.useGroupName()) {
                stringBuilder.append("**[").append(groupName.replaceAll("\\<.*?>", "").replaceAll("[^0-9a-zA-Z ]+", " ")).append("]** ");
            }
            if ((sender.equals(getPlayerName()) && config.includeUsername()) || (!sender.equals(getPlayerName()) && config.includeOtherUsername())) {
                stringBuilder.append("**").append(sender).append("**").append(": ");
            }
            webhookUrl = config.webhookGroup();
        }

        stringBuilder.append(sanitizedMessage);
        webhookBody.setContent(stringBuilder.toString());
        sendWebhook(webhookUrl, webhookBody);
    }

    private boolean isChatTypeSupported(ChatMessageType type) {
        return !(type != ChatMessageType.PRIVATECHAT &&
                 type != ChatMessageType.PRIVATECHATOUT &&
                 type != ChatMessageType.CLAN_GIM_CHAT);
    }

    private String getPlayerName() {
        return client.getLocalPlayer().getName();
    }

    private String addDiscordIdsToMessage(String message) {
        if (Strings.isNullOrEmpty(config.discordUsersAndIds())) {
            return message;
        }

        String newMessage = message;

        for (String userAndId : config.discordUsersAndIds().split(",")) {
            String[] split = userAndId.split(":");
            if (newMessage.toLowerCase().contains(split[0].toLowerCase())) {
                char[] c = newMessage.toCharArray();
                c[0] = Character.toLowerCase(c[0]);
                newMessage = new String(c).replace(split[0].toLowerCase(), "<@" + split[1] + ">");
            }
        }

        return newMessage;
    }

    private void sendWebhook(String configUrl, WebhookBody webhookBody)
    {
        if (Strings.isNullOrEmpty(configUrl)) {
            return;
        }

        HttpUrl url = HttpUrl.parse(configUrl);
        if (url == null) {
            return;
        }

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", GSON.toJson(webhookBody))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        sendRequest(request);
    }

    private void sendRequest(Request request)
    {
        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Error submitting webhook", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }
}