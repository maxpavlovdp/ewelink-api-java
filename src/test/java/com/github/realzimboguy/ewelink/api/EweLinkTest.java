package com.github.realzimboguy.ewelink.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EweLinkTest {
    @Test
    public void testGetDevice() throws Exception {
        EweLink eweLink = new EweLink("eu", "maxpavlov.dp@gmail.com", "Nopassword1", 20);
        eweLink.login();
        String deviceStatus = eweLink.getDevices();

        System.out.println(deviceStatus);

        assertNotNull(deviceStatus);
    }



}