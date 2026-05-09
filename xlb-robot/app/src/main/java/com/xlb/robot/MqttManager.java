package com.xlb.robot;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MqttManager {
    private static final String TAG = "MqttManager";
    private static final String BROKER = "tcp://124.221.117.155:1883";
    private static final String CLIENT_ID_PREFIX = "xlb_robot_";

    private volatile MqttClient client;
    private final String deviceId;
    private final Callback callback;
    private final ScheduledExecutorService scheduler;
    private boolean connecting = false;

    public interface Callback {
        void onConnected();
        void onDisconnected();
        void onCommand(String topic, String payload);
    }

    public MqttManager(String deviceId, Callback callback) {
        this.deviceId = deviceId;
        this.callback = callback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void connect() {
        scheduler.execute(this::doConnect);
    }

    private void doConnect() {
        if (connecting) return;
        connecting = true;
        try {
            String clientId = CLIENT_ID_PREFIX + deviceId;
            client = new MqttClient(BROKER, clientId, new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT lost: " + cause);
                    connecting = false;
                    if (callback != null) callback.onDisconnected();
                    scheduleReconnect();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    Log.d(TAG, "MQTT RX [" + topic + "]: " + payload);
                    if (callback != null) callback.onCommand(topic, payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            opts.setAutomaticReconnect(false);

            client.connect(opts);
            Log.i(TAG, "MQTT connected");
            connecting = false;

            client.subscribe(getCmdTopic(), 1);
            client.subscribe(getOtaTopic(), 1);

            if (callback != null) callback.onConnected();
        } catch (Exception e) {
            Log.e(TAG, "MQTT connect failed: " + e);
            connecting = false;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::doConnect, 10, TimeUnit.SECONDS);
    }

    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "MQTT disconnect error: " + e);
        }
        scheduler.shutdownNow();
    }

    public void publish(String subtopic, String payload) {
        if (client == null) {
            Log.w(TAG, "Publish skipped: client is null");
            return;
        }
        if (!client.isConnected()) {
            Log.w(TAG, "Publish skipped: not connected");
            return;
        }
        try {
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(1);
            client.publish(getTopic(subtopic), msg);
            Log.d(TAG, "Published to " + getTopic(subtopic) + ": " + payload);
        } catch (Exception e) {
            Log.e(TAG, "Publish error: " + e);
        }
    }

    private String getTopic(String sub) {
        return "robot/" + deviceId + "/" + sub;
    }

    public String getCmdTopic() {
        return getTopic("cmd");
    }

    public String getOtaTopic() {
        return getTopic("ota");
    }
}
