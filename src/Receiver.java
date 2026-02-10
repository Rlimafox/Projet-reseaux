import java.net.*;
import java.util.*;

public class Receiver {

    static final byte FLAG_SYN = 0x01;
    static final byte FLAG_ACK = 0x02;
    static final byte FLAG_FIN = 0x04;

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);
        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];
        Random rand = new Random();

        System.out.println("Receiver en écoute...");

        // ---------- HANDSHAKE ----------
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);

        Packet syn = PacketEncoder.decode(dp.getData());
        if ((syn.flags & FLAG_SYN) == 0) {
            System.out.println("Paquet invalide (SYN attendu)");
            return;
        }

        System.out.println("SYN reçu seq=" + syn.seq);

        int seqReceiver = rand.nextInt();

        Packet synAck = new Packet();
        synAck.seq = seqReceiver;
        synAck.ack = syn.seq + 1;
        synAck.flags = (byte) (FLAG_SYN | FLAG_ACK);
        synAck.data = new byte[0];

        byte[] rawSynAck = PacketEncoder.encode(synAck);
        socket.send(new DatagramPacket(
                rawSynAck,
                rawSynAck.length,
                dp.getAddress(),
                dp.getPort()
        ));

        System.out.println("SYN+ACK envoyé seq=" + seqReceiver);

        // ---------- CONNEXION ÉTABLIE ----------
        int expectedSeq = syn.seq + 1;
        System.out.println("Connexion établie, attente des données...");

        // Buffer hors-ordre
        Map<Integer, byte[]> outOfOrder = new HashMap<>();

        boolean fin = false;
        while (!fin) {
            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);

            Packet p = PacketEncoder.decode(dpData.getData());

            // --- Paquet attendu ---
            if (p.seq == expectedSeq) {
                deliver(p.data);
                expectedSeq++;

                // vider le buffer hors-ordre
                while (outOfOrder.containsKey(expectedSeq)) {
                    deliver(outOfOrder.remove(expectedSeq));
                    expectedSeq++;
                }
            }
            // --- Paquet futur (hors-ordre) ---
            else if (p.seq > expectedSeq) {
                outOfOrder.putIfAbsent(p.seq, p.data);
                System.out.println("Paquet hors-ordre reçu seq=" + p.seq);
            }
            // --- Paquet déjà reçu ---
            else {
                System.out.println("Paquet dupliqué seq=" + p.seq);
            }

            // --- ACK cumulatif ---
            Packet ack = new Packet();
            ack.seq = 0;
            ack.ack = expectedSeq;   // prochain attendu
            ack.flags = FLAG_ACK;
            ack.data = new byte[0];

            byte[] rawAck = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(
                    rawAck,
                    rawAck.length,
                    dpData.getAddress(),
                    dpData.getPort()
            ));

            System.out.println("ACK envoyé ack=" + expectedSeq);

            if ((p.flags & FLAG_FIN) != 0) {
                System.out.println("FIN reçu seq=" + p.seq);

                // ACK du FIN
                Packet ackFin = new Packet();
                ackFin.seq = 0;
                ackFin.ack = p.seq + 1;
                ackFin.flags = FLAG_ACK;
                ackFin.data = new byte[0];

                byte[] rawAckFin = PacketEncoder.encode(ackFin);
                socket.send(new DatagramPacket(
                        rawAckFin,
                        rawAckFin.length,
                        dpData.getAddress(),
                        dpData.getPort()
                ));

                // envoi FIN retour
                Packet finBack = new Packet();
                finBack.seq = expectedSeq;
                finBack.ack = 0;
                finBack.flags = FLAG_FIN;
                finBack.data = new byte[0];

                byte[] rawFinBack = PacketEncoder.encode(finBack);
                socket.send(new DatagramPacket(
                        rawFinBack,
                        rawFinBack.length,
                        dpData.getAddress(),
                        dpData.getPort()
                ));

                System.out.println("FIN retour envoyé");
                fin = true;
            }
        }
    }

    private static void deliver(byte[] data) {
        System.out.println("Reçu data (" + data.length + " octets)");
        System.out.println(new String(data));
        // ici : écriture fichier, stdout, buffer, etc.
    }
}
