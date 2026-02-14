import java.net.*;
import java.util.*;

public class Receiver {

    static final int BUFFER_MAX = 1024;
    static final int SEQ_MOD = 65536;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);
        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];

        int expectedSeq;
        int rwnd = BUFFER_MAX;

        TreeMap<Integer, byte[]> outOfOrder = new TreeMap<>();

        System.out.println("Receiver en écoute...");

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);

        Packet syn = PacketEncoder.decode(
                Arrays.copyOf(dp.getData(), dp.getLength())
        );

        Packet synAck = new Packet();
        synAck.seq = new Random().nextInt(SEQ_MOD);
        synAck.ack = (syn.seq + 1) % SEQ_MOD;
        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);
        synAck.data = new byte[]{ (byte) rwnd };

        byte[] synAckRaw = PacketEncoder.encode(synAck);
        socket.send(new DatagramPacket(
                synAckRaw,
                synAckRaw.length,
                dp.getAddress(),
                dp.getPort()));

        expectedSeq = (syn.seq + 1) % SEQ_MOD;

        System.out.println("Connexion établie");

        while (true) {

            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);

            Packet p = PacketEncoder.decode(
                    Arrays.copyOf(dpData.getData(), dpData.getLength())
            );

            if (p.seq == expectedSeq) {

                expectedSeq = (expectedSeq + 1) % SEQ_MOD;

                while (outOfOrder.containsKey(expectedSeq)) {
                    outOfOrder.remove(expectedSeq);
                    expectedSeq = (expectedSeq + 1) % SEQ_MOD;
                }

            } else if (!outOfOrder.containsKey(p.seq)
                    && outOfOrder.size() < BUFFER_MAX) {

                outOfOrder.put(p.seq, p.data);
            }

            rwnd = BUFFER_MAX - outOfOrder.size();

            Packet ack = new Packet();
            ack.flags = Packet.FLAG_ACK;
            ack.ack = (expectedSeq - 1 + SEQ_MOD) % SEQ_MOD;
            ack.data = new byte[]{ (byte) rwnd };

            byte[] ackRaw = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(
                    ackRaw,
                    ackRaw.length,
                    dpData.getAddress(),
                    dpData.getPort()));
        }
    }
}
