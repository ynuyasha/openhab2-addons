package org.openhab.binding.broadlink.internal.discovery;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.broadlink.BroadlinkBindingConstants;
import org.openhab.binding.broadlink.internal.BroadlinkProtocol;
import org.openhab.binding.broadlink.internal.socket.BroadlinkSocket;
import org.openhab.binding.broadlink.internal.socket.BroadlinkSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

// https://www.eclipse.org/smarthome/documentation/development/bindings/discovery-services.html
import org.osgi.service.component.annotations.Component;

/**
 * Broadlink discovery implementation.
 *
 * @author John Marshall/Cato Sognen - Initial contribution
 */
@Component(service = DiscoveryService.class, immediate = true, configurationPid = "discovery.broadlink")
public class BroadlinkDiscoveryService extends AbstractDiscoveryService
        implements BroadlinkSocketListener {

    private final Logger logger = LoggerFactory.getLogger(BroadlinkDiscoveryService.class);

    public BroadlinkDiscoveryService() {
        super(BroadlinkBindingConstants.SUPPORTED_THING_TYPES_UIDS_TO_NAME_MAP.keySet(), 10, true);
        logger.info("BroadlinkDiscoveryService - Constructed");
    }

    public void startScan() {
        BroadlinkSocket.registerListener(this);
        logger.warn("BroadlinkDiscoveryService - Beginning Broadlink device scan...");
        discoverDevices();
        waitUntilEnded();
        logger.warn("BroadlinkDiscoveryService - Ended Broadlink device scan...");

        BroadlinkSocket.unregisterListener(this);
    }

    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan());
    }

    private void waitUntilEnded() {
        // No idea what was going on here; JAD seems to have made quite a mess of it...
//        final Semaphore discoveryEndedLock = new Semaphore(0);
//        scheduler.schedule(new Runnable() {
//
//            public void run() {
//                discoveryEndedLock.release();
//            }
//
//            final BroadlinkDiscoveryService this$0;
//            private final Semaphore val$discoveryEndedLock;
//
//
//            {
//                this$0 = BroadlinkDiscoveryService.this;
//                discoveryEndedLock = semaphore;
//                super();
//            }
//        }
//                , 10L, TimeUnit.SECONDS);
//        try {
//            discoveryEndedLock.acquire();
//        } catch (InterruptedException e) {
//            logger.error("Discovery problem {}", e.getMessage());
//        }


        try {
            logger.warn("BroadlinkDiscoveryService - Broadlink device scan waiting for 10 seconds to complete ...");
            Thread.sleep(10000L);
            logger.warn("BroadlinkDiscoveryService - 10 second wait complete ...");

        } catch (InterruptedException e) {
            logger.error("Discovery problem {}", e.getMessage());
        }
    }

    public void onDataReceived(String remoteAddress, int remotePort, String remoteMAC, ThingTypeUID thingTypeUID) {
        logger.info("Data received during Broadlink device discovery: from " + remoteAddress + ":" + remotePort + "[" + remoteMAC + "]");
        discoveryResultSubmission(remoteAddress, remotePort, remoteMAC, thingTypeUID);
    }

    private void discoveryResultSubmission(String remoteAddress, int remotePort, String remoteMAC, ThingTypeUID thingTypeUID) {
        if (logger.isDebugEnabled()) {
            logger.debug("Adding new Broadlink device on {} with mac '{}' to Smarthome inbox", remoteAddress, remoteMAC);
	}
        Map properties = new HashMap(6);
        properties.put("ipAddress", remoteAddress);
        properties.put("port", Integer.valueOf(remotePort));
        properties.put("mac", remoteMAC);
        ThingUID thingUID = new ThingUID(thingTypeUID, remoteMAC.replace(":", "-"));
        if (logger.isDebugEnabled()) {
            logger.debug("Device '{}' discovered at '{}'.", thingUID, remoteAddress);
        }

	if (BroadlinkBindingConstants.SUPPORTED_THING_TYPES_UIDS_TO_NAME_MAP.containsKey(thingTypeUID)) {
		notifyThingDiscovered(thingTypeUID, thingUID, remoteAddress, properties);
	} else {
		logger.error("Discovered a " + thingTypeUID + " but do not know how to support it at this time :-(");
	}
    }

	private void notifyThingDiscovered(ThingTypeUID thingTypeUID, ThingUID thingUID, String remoteAddress, Map properties) {
		String deviceHumanName = BroadlinkBindingConstants.SUPPORTED_THING_TYPES_UIDS_TO_NAME_MAP.get(thingTypeUID);
		String label = deviceHumanName + " [" + remoteAddress + "]";
	    DiscoveryResult result = DiscoveryResultBuilder
		    .create(thingUID)
		    .withThingType(thingTypeUID)
		    .withProperties(properties)
		    .withLabel(label)
		    .build();

	    thingDiscovered(result);
	}

    private static InetAddress findNonLoopbackAddress() throws SocketException {
        Enumeration ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = (NetworkInterface) ifaces.nextElement();
            Enumeration inetAddrs = iface.getInetAddresses();
            while (inetAddrs.hasMoreElements()) {
                InetAddress inetAddr = (InetAddress) inetAddrs.nextElement();
                if (inetAddr.isLoopbackAddress()) {
                    continue; /* Loop/switch isn't completed */
                }

                if (inetAddr.isSiteLocalAddress()) {
                    return inetAddr;
                }
            }
        }

        return null;
    }

    private static InetAddress getLocalHostLANAddress()
            throws UnknownHostException {

        try {
            InetAddress candidateAddress = findNonLoopbackAddress();

            if (candidateAddress != null) return candidateAddress;

            InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
            if (jdkSuppliedAddress == null)
                throw new UnknownHostException("The JDK InetAddress.getLocalHost() method unexpectedly returned null.");
            else
                return jdkSuppliedAddress;
        } catch (Exception e) {
            UnknownHostException unknownHostException = new UnknownHostException((new StringBuilder("Failed to determine LAN address: ")).append(e).toString());
            unknownHostException.initCause(e);
            throw unknownHostException;
        }
    }

    private static void discoverDevices() {
        try {
            InetAddress localAddress = getLocalHostLANAddress();
            int localPort = nextFreePort(localAddress, 1024, 3000);
            byte message[] = BroadlinkProtocol.buildDiscoveryPacket(localAddress.getHostAddress(), localPort);
            BroadlinkSocket.sendMessage(message, "255.255.255.255", 80);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public static int nextFreePort(InetAddress host, int from, int to) {
        int port = randInt(from, to);
        do {
            if (isLocalPortFree(host, port))
                return port;
            port = ThreadLocalRandom.current().nextInt(from, to);
        } while (true);
    }

    private static boolean isLocalPortFree(InetAddress host, int port) {
        try {
            (new ServerSocket(port, 50, host)).close();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public static int randInt(int min, int max) {
        int randomNum = ThreadLocalRandom.current().nextInt((max - min) + 1) + min;
        return randomNum;
    }

}
