import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

import static java.nio.channels.SelectionKey.OP_READ;

public class UDPClient {

    private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);
    private static int seqNum = 0;
    private static ArrayList<Packet> segments = new ArrayList<Packet>();
    private static int WINDOW_SIZE = 1;

    private static void runClient(SocketAddress routerAddr, InetSocketAddress serverAddr) throws IOException {
        try(DatagramChannel channel = DatagramChannel.open()){
            String msg = "This is a new message.";
            Packet p = new Packet.Builder()
                    .setType(0)
                    .setSequenceNumber(1L)
                    .setPortNumber(serverAddr.getPort())
                    .setPeerAddress(serverAddr.getAddress())
                    .setPayload(msg.getBytes())
                    .create();

            logger.info("Sending \"{}\" to router at {}", msg, routerAddr);

            selectiveRepeat(p, channel, routerAddr);
            
            // Try to receive a packet within timeout.
            channel.configureBlocking(false);
            Selector selector = Selector.open();
            channel.register(selector, OP_READ);
            logger.info("Waiting for the response");
            selector.select(5000);

            Set<SelectionKey> keys = selector.selectedKeys();
            if(keys.isEmpty()){
                logger.error("No response after timeout");
                return;
            }
            
            ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
            SocketAddress router = channel.receive(buf);
            buf.flip();
            Packet resp = Packet.fromBuffer(buf);
            if(resp.getType() == 1) {
                System.out.println(("We received an ACK for packet ") + resp.getSequenceNumber() + (".") );
            }
            logger.info("Packet: {}", resp);
            logger.info("Router: {}", router);
            String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
            logger.info("Payload: {}",  payload);
            
            keys.clear();

        }
        
    }
    
    public static ArrayList<Packet> splitPacket(Packet p, ArrayList<Packet> segments) {
        // Splits payload into an array (just splitting it by word for now)
        String payload = new String(p.getPayload(), StandardCharsets.UTF_8);
        String[] split_payload = payload.split(" ");
        
        // Create a new packet for each word and add it to an array list of packets
        for(int i = 0; i < split_payload.length; i++) {
            Packet pckt = p.toBuilder()
                    .setSequenceNumber(seqNum)
                    .setPayload(split_payload[i].getBytes())
                    .create();
            seqNum++;
            segments.add(pckt);
        }
        return segments;
    }

    public static void selectiveRepeat(Packet p, DatagramChannel channel, SocketAddress routerAddr) throws IOException {
        splitPacket(p,segments);
        
        // Send out the segmented message 
        for (int i=0; i < WINDOW_SIZE; i++) {
            Packet packet = segments.get(i);
            channel.send(packet.toBuffer(), routerAddr);
            
            // Get server response
            ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN);
            SocketAddress router = channel.receive(buf);
            buf.flip();
            Packet resp = Packet.fromBuffer(buf);
            
            if(resp.getType() == 1) {
                System.out.println(("We received an ACK for packet ") + resp.getSequenceNumber() + (".") );
            }
            
            logger.info("Packet: {}", resp);
            logger.info("Router: {}", router);
            String payload = new String(resp.getPayload(), StandardCharsets.UTF_8);
            logger.info("Payload: {}",  payload);
            
            // Ensure that we got an ACK before we can increase window size and continue sending over packets
            if (resp.getType() == 1 && WINDOW_SIZE != segments.size()) {
                WINDOW_SIZE++;
            } 
        }
        System.out.println("All Packets sent.");
        channel.send(p.toBuffer(), routerAddr);
    }
    
    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.accepts("router-host", "Router hostname")
                .withOptionalArg()
                .defaultsTo("localhost");

        parser.accepts("router-port", "Router port number")
                .withOptionalArg()
                .defaultsTo("3000");

        parser.accepts("server-host", "EchoServer hostname")
                .withOptionalArg()
                .defaultsTo("localhost");

        parser.accepts("server-port", "EchoServer listening port")
                .withOptionalArg()
                .defaultsTo("8007");

        OptionSet opts = parser.parse(args);

        // Router address
        String routerHost = (String) opts.valueOf("router-host");
        int routerPort = Integer.parseInt((String) opts.valueOf("router-port"));

        // Server address
        String serverHost = (String) opts.valueOf("server-host");
        int serverPort = Integer.parseInt((String) opts.valueOf("server-port"));

        SocketAddress routerAddress = new InetSocketAddress(routerHost, routerPort);
        InetSocketAddress serverAddress = new InetSocketAddress(serverHost, serverPort);

        runClient(routerAddress, serverAddress);
    }
}

