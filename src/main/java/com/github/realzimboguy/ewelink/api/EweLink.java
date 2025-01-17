package com.github.realzimboguy.ewelink.api;

import com.github.realzimboguy.ewelink.api.errors.DeviceOfflineError;
import com.github.realzimboguy.ewelink.api.model.devices.DeviceItem;
import com.github.realzimboguy.ewelink.api.model.devices.Devices;
import com.github.realzimboguy.ewelink.api.model.Status;
import com.github.realzimboguy.ewelink.api.model.StatusChange;
import com.github.realzimboguy.ewelink.api.model.login.LoginRequest;
import com.github.realzimboguy.ewelink.api.model.login.LoginResponse;
import com.github.realzimboguy.ewelink.api.wss.WssLogin;
import com.github.realzimboguy.ewelink.api.wss.WssResponse;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;


public class EweLink {

    Logger logger = LoggerFactory.getLogger(EweLink.class);

    private static String region;
    private String email;
    private String password;
    private int activityTimer;
    private String baseUrl = "https://eu-api.coolkit.cc:8080/api/";
    public static final String APP_ID = "YzfeftUVcZ6twZw1OoVKPRFYTrGEg01Q";
    private static final String APP_SECRET = "4G91qSoboqYO4Y0XJ0LPPKIsq8reHdfa";
    private static boolean isLoggedIn = false;
    private static long lastActivity = 0L;
    private static final int TIMEOUT = 5000;


    private static String accessToken;
    private static String apiKey;
    private static WssResponse clientWssResponse;

    private static EweLinkWebSocketClient eweLinkWebSocketClient = null;
    private static Thread webSocketMonitorThread = null;
    Gson gson = new Gson();

    public EweLink(String region, String email, String password, int activityTimer) {
        this.region = region;
        this.email = email;
        this.password = password;
        if (region != null) {
            baseUrl = "https://" + region + "-api.coolkit.cc:8080/api/";
        }
        if (activityTimer < 30) {
            activityTimer = 30;
        }
        this.activityTimer = activityTimer;

        logger.info("EweLinkApi startup params : {} {}", region, email);

    }

    public void login() throws Exception {


        URL url = new URL(baseUrl + "user/login");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");

        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setAppid(APP_ID);
        loginRequest.setEmail(email);
        loginRequest.setPassword(password);
        loginRequest.setTs(new Date().getTime() + "");
        loginRequest.setVersion("8");
        loginRequest.setNonce(Util.getNonce());

        conn.setRequestProperty("Authorization", "Sign " + getAuthMac(gson.toJson(loginRequest)));

        logger.debug("Login Request:{}", loginRequest.toString());


        DataOutputStream wr = new DataOutputStream(conn.getOutputStream());

        wr.writeBytes(gson.toJson(loginRequest));

        wr.flush();
        wr.close();
        int responseCode = conn.getResponseCode();
        InputStream is;

        logger.debug("Login Response Code :{}", responseCode);

        if (responseCode >= 400) is = conn.getErrorStream();
        else is = conn.getInputStream();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            logger.debug("Login Response Raw:{}", response.toString());

            LoginResponse loginResponse = gson.fromJson(response.toString(), LoginResponse.class);

            logger.debug("Login Response:{}", loginResponse.toString());

            if (loginResponse.getError() > 0) {
                //something wrong with login, throw exception back up with msg
                throw new Exception(loginResponse.getMsg());

            } else {
                accessToken = loginResponse.getAt();
                apiKey = loginResponse.getUser().getApikey();
                logger.info("accessToken:{}", accessToken);
                logger.info("apiKey:{}", apiKey);

                isLoggedIn = true;
                lastActivity = new Date().getTime();


            }

        }

    }

    public void getWebSocket(WssResponse wssResponse) throws Exception {
        if (!isLoggedIn) {
            throw new Exception("Not Logged In, please call login Method");
        }

        eweLinkWebSocketClient = new EweLinkWebSocketClient(new URI("wss://" + region + "-pconnect3.coolkit.cc:8080/api/ws"));
        clientWssResponse = wssResponse;
        eweLinkWebSocketClient.setWssResponse(clientWssResponse);
        eweLinkWebSocketClient.setWssLogin(gson.toJson(new WssLogin(accessToken, apiKey, APP_ID, Util.getNonce())));
        eweLinkWebSocketClient.connect();

        if (webSocketMonitorThread == null) {
            webSocketMonitorThread = new Thread(new WebSocketMonitor());
            webSocketMonitorThread.start();
        }


    }

    public String getDevices() throws Exception {

        if (!isLoggedIn) {
            throw new Exception("Not Logged In, please call login Method");
        }
        if (lastActivity + (activityTimer * 60 * 1000) < new Date().getTime()) {
            logger.info("Longer than last Activity, perform login Again");
            login();
        }


        URL url = new URL(baseUrl + "/user/device?lang=en&appid=" + APP_ID + "&ts=" + new Date().getTime() + "&version=8&getTags=1");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);


        int responseCode = conn.getResponseCode();
        InputStream is;

        if (responseCode >= 400) is = conn.getErrorStream();
        else is = conn.getInputStream();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            String rawOutput = response.toString();
            logger.debug("GetDevices Response Raw:{}", rawOutput);

            return rawOutput;
        }

    }

    public DeviceItem getDevice(String deviceId) throws Exception {

        if (!isLoggedIn) {
            throw new Exception("Not Logged In, please call login Method");
        }
        if (lastActivity + (activityTimer * 60 * 1000) < new Date().getTime()) {
            logger.info("Longer than last Activity, perform login Again");
            login();
        }


        URL url = new URL(baseUrl + "/user/device/" + deviceId + "?deviceid=" + deviceId + "" +
                "&lang=en" +
                "&appid=" + APP_ID + "" +
                "&ts=" + new Date().getTime() + "&version=8");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);


        int responseCode = conn.getResponseCode();
        InputStream is;

        if (responseCode >= 400) is = conn.getErrorStream();
        else is = conn.getInputStream();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            logger.debug("GetDevice Response Raw:{}", response.toString());

            DeviceItem device = gson.fromJson(response.toString(), DeviceItem.class);

            logger.debug("GetDevice Response:{}", device.toString());

            if (device.getError() > 0) {
                //something wrong with login, throw exception back up with msg
                throw new Exception("Get Device Error:" + device.toString());

            } else {
                logger.info("getDevice:{}", device.toString());
                lastActivity = new Date().getTime();
                return device;
            }

        }

    }

    public Status getDeviceStatus(String deviceId) throws Exception {

        if (!isLoggedIn) {
            throw new Exception("Not Logged In, please call login Method");
        }
        if (lastActivity + (activityTimer * 60 * 1000) < new Date().getTime()) {
            logger.info("Longer than last Activity, perform login Again");
            login();
        }


        URL url = new URL(baseUrl + "/user/device/status?deviceid=" + deviceId + "" +
                "&lang=en" +
                "&appid=" + APP_ID + "" +
                "&ts=" + new Date().getTime() + "&version=8&params=switch|switches");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);


        int responseCode = conn.getResponseCode();
        InputStream is;

        if (responseCode >= 400) is = conn.getErrorStream();
        else is = conn.getInputStream();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            logger.debug("Status Response Raw:{}", response.toString());

            Status status = gson.fromJson(response.toString(), Status.class);

            logger.debug("Status Response:{}", status.toString());

            if (status.getError() > 0) {
                //something wrong with login, throw exception back up with msg

                if (status.getError() == 503) {
                    throw new DeviceOfflineError("Status Error:" + status.toString());
                }

                throw new Exception("Status Error:" + status.toString());

            } else {
                logger.info("Status:{}", status.toString());
                lastActivity = new Date().getTime();
                //this is not returned but to be nice we do
                status.setDeviceid(deviceId);
                return status;
            }

        }

    }

    public Status setDeviceStatus(String deviceId, String status) throws Exception {

        if (!isLoggedIn) {
            throw new Exception("Not Logged In, please call login Method");
        }
        if (lastActivity + (activityTimer * 60 * 1000) < new Date().getTime()) {
            logger.info("Longer than last Activity, perform login Again");
            login();
        }

        if (status.equalsIgnoreCase("on")) {
            status = "on";
        } else {
            status = "off";
        }
        logger.info("Setting device {} status to {}", deviceId, status);

        URL url = new URL(baseUrl + "user/device/status");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");

        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);

        StatusChange statusChange = new StatusChange();
        statusChange.setAppid(APP_ID);
        statusChange.setDeviceid(deviceId);
        statusChange.setTs(new Date().getTime() + "");
        statusChange.setVersion("8");
        statusChange.setParams("{\"switch\":\"" + status + "\"}");


        conn.setRequestProperty("Authorization", "Bearer " + accessToken);

        logger.debug("StatusChange Request:{}", statusChange.toString());


        DataOutputStream wr = new DataOutputStream(conn.getOutputStream());

        wr.writeBytes(gson.toJson(statusChange));

        wr.flush();
        wr.close();
        int responseCode = conn.getResponseCode();
        InputStream is;

        logger.debug("StatusChange Response Code :{}", responseCode);

        if (responseCode >= 400) is = conn.getErrorStream();
        else is = conn.getInputStream();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            logger.debug("StatusChange Response Raw:{}", response.toString());

            Status statusRsp = gson.fromJson(response.toString(), Status.class);

            logger.debug("StatusChange Response:{}", statusRsp.toString());

            if (statusRsp.getError() > 0) {
                //something wrong with login, throw exception back up with msg
                throw new Exception(statusRsp.getErrmsg());

            } else {


                isLoggedIn = true;
                lastActivity = new Date().getTime();
                return statusRsp;

            }

        }

    }

    private static String getAuthMac(String data) throws UnsupportedEncodingException, NoSuchAlgorithmException, InvalidKeyException {

        Mac sha256_HMAC = null;

        byte[] byteKey = APP_SECRET.getBytes("UTF-8");
        final String HMAC_SHA256 = "HmacSHA256";
        sha256_HMAC = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec keySpec = new SecretKeySpec(byteKey, HMAC_SHA256);
        sha256_HMAC.init(keySpec);
        byte[] mac_data = sha256_HMAC.
                doFinal(data.getBytes("UTF-8"));

        return Base64.getEncoder().encodeToString(mac_data);


    }


    public class WebSocketMonitor implements Runnable {

        Logger logger = LoggerFactory.getLogger(WebSocketMonitor.class);
        Gson gson = new Gson();


        @Override
        public void run() {

            logger.info("Websocket Monitor Thread start");

            while (true) {
                try {
                    Thread.sleep(30000);
                    logger.debug("send websocket ping");
                    eweLinkWebSocketClient.send("ping");

                } catch (Exception e) {
                    logger.error("Error in sening websocket ping:", e);
                    logger.info("Try reconnect to websocket");
                    try {
                        eweLinkWebSocketClient = new EweLinkWebSocketClient(new URI("wss://" + region + "-pconnect3.coolkit.cc:8080/api/ws"));
                        eweLinkWebSocketClient.setWssResponse(clientWssResponse);
                        eweLinkWebSocketClient.setWssLogin(gson.toJson(new WssLogin(accessToken, apiKey, APP_ID, Util.getNonce())));
                        eweLinkWebSocketClient.connect();

                    } catch (Exception c) {
                        logger.error("Error trying to reconnect:", c);
                    }
                }
            }
        }
    }


}


