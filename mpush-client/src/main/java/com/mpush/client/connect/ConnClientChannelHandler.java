package com.mpush.client.connect;


import com.google.common.collect.Maps;
import com.mpush.cache.redis.RedisKey;
import com.mpush.api.connection.Connection;
import com.mpush.api.protocol.Command;
import com.mpush.api.protocol.Packet;
import com.mpush.cache.redis.manager.RedisManager;
import com.mpush.common.message.*;
import com.mpush.common.security.AesCipher;
import com.mpush.common.security.CipherBox;
import com.mpush.netty.connection.NettyConnection;
import com.mpush.tools.thread.PoolThreadFactory;
import com.mpush.tools.thread.ThreadNames;
import io.netty.channel.*;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by ohun on 2015/12/19.
 *
 * @author ohun@live.cn
 */
@ChannelHandler.Sharable
public final class ConnClientChannelHandler extends ChannelHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnClientChannelHandler.class);
    private static final Timer HASHED_WHEEL_TIMER = new HashedWheelTimer(new PoolThreadFactory(ThreadNames.NETTY_TIMER));

    private Connection connection = new NettyConnection();
    private ClientConfig clientConfig;

    public ConnClientChannelHandler(ClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        connection.updateLastReadTime();
        //加密
        if (msg instanceof Packet) {
            Packet packet = (Packet) msg;
            Command command = Command.toCMD(packet.cmd);
            if (command == Command.HANDSHAKE) {
                connection.getSessionContext().changeCipher(new AesCipher(clientConfig.getClientKey(), clientConfig.getIv()));
                HandshakeOkMessage message = new HandshakeOkMessage(packet, connection);
                byte[] sessionKey = CipherBox.I.mixKey(clientConfig.getClientKey(), message.serverKey);
                connection.getSessionContext().changeCipher(new AesCipher(sessionKey, clientConfig.getIv()));
                startHeartBeat(message.heartbeat);
                LOGGER.warn("会话密钥：{}，message={}", sessionKey, message);
                bindUser(clientConfig);
                saveToRedisForFastConnection(clientConfig, message.sessionId, message.expireTime, sessionKey);
            } else if (command == Command.FAST_CONNECT) {
                String cipherStr = clientConfig.getCipher();
                String[] cs = cipherStr.split(",");
                byte[] key = AesCipher.toArray(cs[0]);
                byte[] iv = AesCipher.toArray(cs[1]);
                connection.getSessionContext().changeCipher(new AesCipher(key, iv));

                FastConnectOkMessage message = new FastConnectOkMessage(packet, connection);
                startHeartBeat(message.heartbeat);
                bindUser(clientConfig);
                LOGGER.warn("fast connect success, message=" + message);
            } else if (command == Command.KICK) {
                KickUserMessage message = new KickUserMessage(packet, connection);
                LOGGER.error("receive kick user userId={}, deviceId={}, message={},", clientConfig.getUserId(), clientConfig.getDeviceId(), message);
                ctx.close();
            } else if (command == Command.ERROR) {
                ErrorMessage errorMessage = new ErrorMessage(packet, connection);
                LOGGER.error("receive an error packet=" + errorMessage);
            } else if (command == Command.BIND) {
                OkMessage okMessage = new OkMessage(packet, connection);
                LOGGER.warn("receive an success packet=" + okMessage);
                HttpRequestMessage message = new HttpRequestMessage(connection);
                message.uri = "http://baidu.com";
                message.send();
            } else if (command == Command.PUSH) {
                PushMessage message = new PushMessage(packet, connection);
                LOGGER.warn("receive an push message, content=" + message.content);
            } else if (command == Command.HEARTBEAT) {
                LOGGER.warn("receive a heartbeat pong...");
            } else {
                LOGGER.warn("receive a message, type=" + command + "," + packet);
            }
        }


        LOGGER.warn("update currentTime:" + ctx.channel() + "," + ToStringBuilder.reflectionToString(msg));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        connection.close();
        LOGGER.error("caught an ex, channel={}", ctx.channel(), cause);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("client connect channel={}", ctx.channel());
        connection.init(ctx.channel(), true);
        tryFastConnect();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connection.close();
        LOGGER.info("client disconnect channel={}", ctx.channel());
    }

    private void tryFastConnect() {

        Map<String, String> sessionTickets = getFastConnectionInfo(clientConfig.getDeviceId());

        if (sessionTickets == null) {
            handshake(clientConfig);
            return;
        }
        String sessionId = sessionTickets.get("sessionId");
        if (sessionId == null) {
            handshake(clientConfig);
            return;
        }
        String expireTime = sessionTickets.get("expireTime");
        if (expireTime != null) {
            long exp = Long.parseLong(expireTime);
            if (exp < System.currentTimeMillis()) {
                handshake(clientConfig);
                return;
            }
        }

        final String cipher = sessionTickets.get("cipherStr");

        FastConnectMessage message = new FastConnectMessage(connection);
        message.deviceId = clientConfig.getDeviceId();
        message.sessionId = sessionId;

        message.sendRaw(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    clientConfig.setCipher(cipher);
                } else {
                    handshake(clientConfig);
                }
            }
        });
    }

    private void bindUser(ClientConfig client) {
        BindUserMessage message = new BindUserMessage(connection);
        message.userId = client.getUserId();
        message.send();
    }

    private void saveToRedisForFastConnection(ClientConfig client, String sessionId, Long expireTime, byte[] sessionKey) {
        Map<String, String> map = Maps.newHashMap();
        map.put("sessionId", sessionId);
        map.put("expireTime", expireTime + "");
        map.put("cipherStr", connection.getSessionContext().cipher.toString());
        String key = RedisKey.getDeviceIdKey(client.getDeviceId());
        RedisManager.I.set(key, map, 60 * 5); //5分钟
    }

    private Map<String, String> getFastConnectionInfo(String deviceId) {
        String key = RedisKey.getDeviceIdKey(deviceId);
        return RedisManager.I.get(key, Map.class);
    }

    private void handshake(ClientConfig client) {
        HandshakeMessage message = new HandshakeMessage(connection);
        message.clientKey = client.getClientKey();
        message.iv = client.getIv();
        message.clientVersion = client.getClientVersion();
        message.deviceId = client.getDeviceId();
        message.osName = client.getOsName();
        message.osVersion = client.getOsVersion();
        message.timestamp = System.currentTimeMillis();
        message.send();
    }


    public void startHeartBeat(final int heartbeat) throws Exception {
        HASHED_WHEEL_TIMER.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                final Channel channel = connection.getChannel();

                try {
                    ChannelFuture channelFuture = channel.writeAndFlush(Packet.getHBPacket());
                    channelFuture.addListener(new ChannelFutureListener() {

                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (!future.isSuccess()) {
                                if (!channel.isActive()) {
                                    LOGGER.warn("client send hb msg false:" + channel.remoteAddress().toString() + ",channel is not active");
                                }
                                LOGGER.warn("client send msg hb false:" + channel.remoteAddress().toString());
                            } else {
                                LOGGER.debug("client send msg hb success:" + channel.remoteAddress().toString());
                            }
                        }
                    });
                } finally {
                    if (channel.isActive()) {
                        HASHED_WHEEL_TIMER.newTimeout(this, heartbeat, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }, heartbeat, TimeUnit.MILLISECONDS);
    }
}