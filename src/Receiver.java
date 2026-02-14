import java.net.*;
import java.util.*;

public class Receiver {

    static final int BUFFER_MAX = 1024;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];
        int expectedSeq;
        int rwnd = BUFFER_MAX;

        // Buffer hors ordre
        TreeMap<Integer, byte[]> outOfOrder = new TreeMap<>();

        System.out.println("Receiver en écoute...");

        // ===== HANDSHAKE =====
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet syn = PacketEncoder.decode(dp.getData());

        // seq attendu = seq du SYN + 1
        expectedSeq = (syn.seq + 1) % 65536;

        Packet synAck = new Packet();
        synAck.seq = new Random().nextInt(65536); // sequence du receiver
        synAck.ack = expectedSeq; // ACK du SYN du sender
        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);
        synAck.data = new byte[]{ (byte) rwnd };

        byte[] synAckRaw = PacketEncoder.encode(synAck);
        socket.send(new DatagramPacket(synAckRaw, synAckRaw.length, dp.getAddress(), dp.getPort()));

        // Après handshake, expectedSeq = prochain seq que le sender enverra
        expectedSeq = synAck.ack;
        System.out.println("Connexion établie");

        // ===== RÉCEPTION =====
        while (true) {
            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);
            Packet p = PacketEncoder.decode(dpData.getData());

            if ((p.flags & Packet.FLAG_FIN) != 0) {
                System.out.println("FIN reçu, fermeture");
                break;
            }

            if (p.seq == expectedSeq) {
                expectedSeq = (expectedSeq + 1) % 65536;

                while (outOfOrder.containsKey(expectedSeq)) {
                    outOfOrder.remove(expectedSeq);
                    expectedSeq = (expectedSeq + 1) % 65536;
                }
                System.out.println("[IN ORDER] seq=" + p.seq);

            } else if (!outOfOrder.containsKey(p.seq) && outOfOrder.size() < BUFFER_MAX) {
                outOfOrder.put(p.seq, p.data);
                System.out.println("[BUFFERED] seq=" + p.seq);

            } else {
                System.out.println("[DROP] seq=" + p.seq);
            }

            rwnd = BUFFER_MAX - outOfOrder.size();

            // ACK cumulatif
            int ackSeq = (expectedSeq - 1 + 65536) % 65536;
            Packet ack = new Packet();
            ack.flags = Packet.FLAG_ACK;
            ack.ack = ackSeq;
            ack.data = new byte[]{ (byte) rwnd };

            byte[] ackRaw = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(ackRaw, ackRaw.length, dpData.getAddress(), dpData.getPort()));
            System.out.println("[ACK SENT] ack=" + ack.ack + " rwnd=" + rwnd);
        }

        socket.close();
        System.out.println("Receiver fermé");
    }
}
