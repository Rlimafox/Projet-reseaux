import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;


public class Receiver {


    static final int BUFFER_MAX = 32;

    static int seqNext(int x) {
        return (x + 1) & 0xFFFF;
    }

    static int seqPrev(int x) {
        return (x - 1) & 0xFFFF;
    }

    static byte[] ackPayload(int ackSeq) {
        int v = ackSeq & 0xFFFF;
        return new byte[]{
                (byte) ((v >>> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);

        byte[] buffer = new byte[2048];

        int expectedSeq;
        int localSeq = new Random().nextInt(65536);

        int bufferUsed = 0;
        int lastAckSent = -1;
        int lastRwndSent = -1;

        System.out.println("Receiver en écoute...");

        // HANDSHAKE SYN
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet syn = PacketEncoder.decode(
                Arrays.copyOf(dp.getData(), dp.getLength())
        );

        if ((syn.flags & Packet.FLAG_RST) != 0) {
            System.err.println("Erreur: connexion interrompue par RST pendant l'ouverture.");
            socket.close();
            return;
        }

        // SYN-ACK
        Packet synAck = new Packet();
        synAck.seq = localSeq;
        int synAckExpectedSeq = seqNext(syn.seq);
        synAck.flags = (Packet.FLAG_SYN | Packet.FLAG_ACK);
        synAck.data = ackPayload(seqPrev(synAckExpectedSeq));

        socket.send(new DatagramPacket(

                PacketEncoder.encode(synAck),

                PacketEncoder.encode(synAck).length,

                dp.getAddress(),

                dp.getPort()

        ));
        localSeq = seqNext(localSeq);


        expectedSeq = synAckExpectedSeq;


        // 3e paquet du handshake : ACK final de l'emetteur
        DatagramPacket dpFinalAck = new DatagramPacket(buffer, buffer.length);
        socket.receive(dpFinalAck);
        Packet ackfinal = PacketEncoder.decode(
                Arrays.copyOf(dpFinalAck.getData(), dpFinalAck.getLength())
        );
        if ((ackfinal.flags & Packet.FLAG_RST) != 0) {
            System.err.println("Erreur: connexion interrompue par RST pendant l'ouverture.");
            socket.close();
            return;
        }
        if ((ackfinal.flags & Packet.FLAG_ACK) == 0 || ackfinal.seq != expectedSeq) {
            System.out.println("flags = "+ackfinal.flags);
            throw new IllegalStateException("Handshake invalide: ACK final attendu");
        }
        expectedSeq = seqNext(expectedSeq);

        System.out.println("Connexion établie");

        FileOutputStream fos = new FileOutputStream("src/test.txt");

        // LOOP
        while (true) {

            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);

            Packet p = PacketEncoder.decode(
                    Arrays.copyOf(dpData.getData(), dpData.getLength())
            );
            if ((p.flags & Packet.FLAG_RST) != 0) {
                System.err.println("Erreur: connexion interrompue par RST pendant le transfert.");
                break;
            }

            fos.write(p.data, 0, p.data.length);

            if ((p.flags & Packet.FLAG_FIN) != 0) {
                if (p.seq == expectedSeq)
                    expectedSeq = seqNext(expectedSeq);
                Packet finAck = new Packet();
                finAck.seq = localSeq;
                finAck.flags = (byte) (Packet.FLAG_FIN | Packet.FLAG_ACK);
                finAck.data = ackPayload(seqPrev(expectedSeq));
                byte[] finAckRaw = PacketEncoder.encode(finAck);
                socket.send(new DatagramPacket(
                        finAckRaw,
                        finAckRaw.length,
                        dpData.getAddress(),
                        dpData.getPort()
                ));
                // Attend l'ACK final de fermeture de l'emetteur.
                socket.setSoTimeout(500);
                try {
                    DatagramPacket dpLast = new DatagramPacket(buffer, buffer.length);
                    socket.receive(dpLast);
                    Packet last = PacketEncoder.decode(Arrays.copyOf(dpLast.getData(), dpLast.getLength()));
                    if ((last.flags & Packet.FLAG_RST) != 0) {
                        System.err.println("Erreur: connexion interrompue par RST pendant la fermeture.");
                        break;
                    }
                    if ((last.flags & Packet.FLAG_ACK) == 0) {
                        System.err.println("Fermeture: ACK final manquant/invalide.");
                    }
                } catch (Exception e) {
                    System.err.println("Fermeture: timeout en attente de l'ACK final.");
                }
                System.out.println("FIN recu, fermeture.");
                fos.close();
                break;
            }
            if (p.seq == expectedSeq) {
                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;
            }
            if (bufferUsed > 0){
                bufferUsed--;
            }

            int rwnd = BUFFER_MAX - bufferUsed;
            int lastContiguousSeq = seqPrev(expectedSeq);

            Packet ack = new Packet();
            ack.seq = localSeq;
            ack.flags = Packet.FLAG_ACK;
            ack.data = ackPayload(lastContiguousSeq);

            socket.send(new DatagramPacket(
                    PacketEncoder.encode(ack),
                    PacketEncoder.encode(ack).length,
                    dpData.getAddress(),
                    dpData.getPort()));

            localSeq = seqNext(localSeq);

            if (expectedSeq != lastAckSent || rwnd != lastRwndSent) {
                int ackNum = ((ack.data[0] & 0xFF) << 8) | (ack.data[1] & 0xFF);
                System.out.println("ACK envoye | ack=" + ackNum + " | rwnd=" + rwnd);
                lastAckSent = lastContiguousSeq;
                lastRwndSent = rwnd;
            }
        }
        socket.close();

        Path fichier_recu = Paths.get("src/test.txt");
        Path fichier_de_controle = Paths.get("src/test_controle.txt");

        long mismatch = Files.mismatch(fichier_recu, fichier_de_controle);

        if (mismatch == -1) {
            System.out.println("Les fichiers sont identiques.");
        } else {
            System.out.println("Les fichiers sont différents à la position : " + mismatch);
        }
    }
}
