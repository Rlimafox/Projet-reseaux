import java.net.*;
import java.util.*;

public class Receiver {

    static final int BUFFER_MAX = 32;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);
        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];
        int expectedSeq;

        int rwnd = BUFFER_MAX;
        Queue<Integer> bufferQueue = new ArrayDeque<>();

        System.out.println("Receiver en écoute...");

        /* ===== HANDSHAKE ===== */
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet syn = PacketEncoder.decode(dp.getData());

        expectedSeq = (syn.seq + 1) % 65536;

        Packet synAck = new Packet();
        synAck.seq = new Random().nextInt(65536);
        synAck.ack = expectedSeq;
        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);
        synAck.data = new byte[] { (byte) rwnd };

        socket.send(new DatagramPacket(
                PacketEncoder.encode(synAck),
                PacketEncoder.encode(synAck).length,
                dp.getAddress(),
                dp.getPort()
        ));

        System.out.println("Connexion établie");

        /* ===== RÉCEPTION ===== */
        while (true) {
            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);

            Packet p = PacketEncoder.decode(dpData.getData());

            if ((p.flags & Packet.FLAG_FIN) != 0) {
                System.out.println("FIN reçu, fermeture");
                break;
            }

            if (p.seq == expectedSeq && bufferQueue.size() < BUFFER_MAX) {
                bufferQueue.add(p.seq);
                expectedSeq = (expectedSeq + 1) % 65536;
                System.out.println("[RECV] seq=" + p.seq);
            } else {
                System.out.println("[DROP] seq=" + p.seq + " attendu=" + expectedSeq);
            }

            /* --- Consommation simulée --- */
            if (bufferQueue.size() > BUFFER_MAX / 2) {
                bufferQueue.poll();
            }

            rwnd = BUFFER_MAX - bufferQueue.size();

            Packet ack = new Packet();
            ack.flags = Packet.FLAG_ACK;
            ack.ack = (expectedSeq - 1 + 65536) % 65536;
            ack.data = new byte[] { (byte) rwnd };

            socket.send(new DatagramPacket(
                    PacketEncoder.encode(ack),
                    PacketEncoder.encode(ack).length,
                    dpData.getAddress(),
                    dpData.getPort()
            ));

            System.out.println("[SEND ACK] ack=" + ack.ack + " rwnd=" + rwnd);
        }

        socket.close();
    }
}
