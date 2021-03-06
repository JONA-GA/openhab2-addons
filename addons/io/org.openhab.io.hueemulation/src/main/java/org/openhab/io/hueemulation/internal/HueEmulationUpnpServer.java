/**
 * Copyright (c) 2014-2015 openHAB UG (haftungsbeschraenkt) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.io.hueemulation.internal;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advertises a Hue UPNP compatible bridge
 *
 * @author Dan Cunningham
 *
 */
public class HueEmulationUpnpServer extends Thread {
    private Logger logger = LoggerFactory.getLogger(HueEmulationUpnpServer.class);

    // jUPNP shares port 1900, but since this is multicast, we can also bind to it
    static final private int UPNP_PORT_RECV = 1900;
    static final private String MULTI_ADDR = "239.255.255.250";
    private boolean running;
    private String discoPath;
    private String usn;
    private InetAddress address;

    private String discoString = "HTTP/1.1 200 OK\r\n" + "CACHE-CONTROL: max-age=100\r\n" + "EXT:\r\n"
            + "LOCATION: %s\r\n" + "SERVER: FreeRTOS/7.4.2 UPnP/1.0 IpBridge/1.10.0\r\n"
            + "ST: urn:schemas-upnp-org:device:basic:1\r\n" + "USN: uuid:%s::urn:Belkin:device:**\r\n\r\n";

    /**
     * Server to send UDP packets onto the network when requested by a Hue API compatible device.
     *
     * @param discoPath
     *            The URI path where the discovery xml document can be retrieved
     * @param usn
     *            The unique USN id for this server
     */
    public HueEmulationUpnpServer(String discoPath, String usn) {
        this.running = true;
        this.discoPath = discoPath;
        this.usn = usn;
    }

    /**
     * Stops the upnp server from running
     */
    public void shutdown() {
        this.running = false;
    }

    @Override
    public void run() {
        MulticastSocket recvSocket = null;
        // since jupnp shares port 1900, lets use a different port to send UDP packets on just to be safe.
        DatagramSocket sendSocket = null;
        byte[] buf = new byte[1000];
        DatagramPacket recv = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            address = addr;
                            break;
                        }

                    }
                }
                InetSocketAddress socketAddr = new InetSocketAddress(MULTI_ADDR, UPNP_PORT_RECV);
                recvSocket = new MulticastSocket(UPNP_PORT_RECV);
                recvSocket.joinGroup(socketAddr, NetworkInterface.getByInetAddress(address));
                sendSocket = new DatagramSocket();
                while (running) {
                    recvSocket.receive(recv);
                    if (recv.getLength() > 0) {
                        String data = new String(recv.getData());
                        logger.trace("Got SSDP Discovery packet from " + recv.getAddress().getHostAddress() + ":"
                                + recv.getPort());
                        if (data.startsWith("M-SEARCH")) {
                            String msg = String
                                    .format(discoString,
                                            "http://" + address.getHostAddress().toString() + ":"
                                                    + System.getProperty("org.osgi.service.http.port") + discoPath,
                                            usn);
                            DatagramPacket response = new DatagramPacket(msg.getBytes(), msg.length(),
                                    recv.getAddress(), recv.getPort());
                            try {
                                logger.trace("Sending to " + recv.getAddress().getHostAddress() + " : " + msg);
                                sendSocket.send(response);
                            } catch (IOException e) {
                                logger.error("Could not send UPNP response", e);
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                logger.error("Socket error with UPNP server", e);
            } catch (IOException e) {
                logger.error("IO Error with UPNP server", e);
            } finally {
                IOUtils.closeQuietly(recvSocket);
                IOUtils.closeQuietly(sendSocket);
                if (running) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    public InetAddress getAddress() {
        return address;
    }
}
