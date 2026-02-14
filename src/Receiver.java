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

        // Buffer pour paquets hors ordre
        TreeSet<Integer> outOfOrder = new TreeSet<>();

        System.out.println("Receiver en écoute...");

        /* ===== HANDSHAKE ===== */
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet syn = PacketEncoder.decode(dp.getData());

        expectedSeq = (syn.seq + 1) % 65536;

        Packet synAck = new Packet();
        synAck.seq = new Random().nextInt(65536);
        synAck.ack = expectedSeq;
        synAck.flags = (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK);
        synAck.data = new byte[] { (byte) rwnd };

        byte[] synAckRaw = PacketEncoder.encode(synAck);
        socket.send(new DatagramPacket(
                synAckRaw,
                synAckRaw.length,
                dp.getAddress(),
                dp.getPort()
        ));

        System.out.println("Connexion établie");

        /* ===== RÉCEPTION ===== */
        while (true) {

            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);
            Packet p = PacketEncoder.decode(dpData.getData());

            // FIN
            if ((p.flags & Packet.FLAG_FIN) != 0) {
                System.out.println("FIN reçu, fermeture");
                break;
            }

            // Paquet attendu
            if (p.seq == expectedSeq) {

                expectedSeq = (expectedSeq + 1) % 65536;

                // Drainer le buffer pour tous les paquets consécutifs déjà reçus
                while (outOfOrder.remove(expectedSeq)) {
                    expectedSeq = (expectedSeq + 1) % 65536;
                }

                System.out.println("[IN ORDER] seq=" + p.seq);

            }
            // Paquet hors ordre
            else if (!outOfOrder.contains(p.seq) && outOfOrder.size() < BUFFER_MAX) {

                outOfOrder.add(p.seq);
                System.out.println("[BUFFERED] seq=" + p.seq);

            }
            // Paquet doublon ou buffer plein
            else {
                System.out.println("[DROP] seq=" + p.seq);
            }

            // Mise à jour de rwnd
            rwnd = BUFFER_MAX - outOfOrder.size();

            // Envoi de l'ACK cumulatif correct
            int ackSeq = (expectedSeq - 1 + 65536) % 65536;
            Packet ack = new Packet();
            ack.flags = Packet.FLAG_ACK;
            ack.ack = ackSeq;
            ack.data = new byte[] { (byte) rwnd };

            byte[] ackRaw = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(
                    ackRaw,
                    ackRaw.length,
                    dpData.getAddress(),
                    dpData.getPort()
            ));

            System.out.println("[ACK SENT] ack=" + ack.ack + " rwnd=" + rwnd);
        }

        socket.close();
        System.out.println("Receiver fermé");
    }
}
