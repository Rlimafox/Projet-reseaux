import java.net.*;
import java.util.*;

public class Receiver {

    static final int RWND_MAX = 10;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);
        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];
        int expectedSeq;

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
        synAck.data = new byte[] { RWND_MAX };

        byte[] raw = PacketEncoder.encode(synAck);
        socket.send(new DatagramPacket(raw, raw.length, dp.getAddress(), dp.getPort()));

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

            if (p.seq == expectedSeq) {
                expectedSeq = (expectedSeq + 1) % 65536;
                System.out.println("Reçu OK seq=" + p.seq);
            } else {
                System.out.println("Hors ordre seq=" + p.seq + " attendu=" + expectedSeq);
            }

            /* --- ACK cumulatif strict --- */
            Packet ack = new Packet();
            ack.flags = Packet.FLAG_ACK;
            ack.ack = (expectedSeq - 1 + 65536) % 65536;
            ack.data = new byte[] { RWND_MAX };

            byte[] rawAck = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(
                    rawAck,
                    rawAck.length,
                    dpData.getAddress(),
                    dpData.getPort()
            ));
        }

        socket.close();
    }
}
