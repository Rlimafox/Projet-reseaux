import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;
    static final int WINDOW_SIZE = 10;
    static final int TIMEOUT = 500; // ms
    static final byte FLAG_SYN = 0x01;
    static final byte FLAG_ACK = 0x02;
    static final byte FLAG_FIN = 0x04;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress address = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(50); // non bloquant

        Random rand = new Random();
        int seqSender = rand.nextInt(); // 32 bits

        // ---------- HANDSHAKE ----------
        Packet syn = new Packet();
        syn.seq = seqSender;
        syn.ack = 0;
        syn.flags = FLAG_SYN;
        syn.data = new byte[0];

        socket.send(new DatagramPacket(
                PacketEncoder.encode(syn),
                PacketEncoder.encode(syn).length,
                address,
                port
        ));

        byte[] buffer = new byte[2048];
        DatagramPacket dpRecv = new DatagramPacket(buffer, buffer.length);
        socket.receive(dpRecv); // SYN+ACK

        seqSender++; // première data

        System.out.println("Connexion établie, envoi du fichier...");

        // ---------- DÉCOUPAGE DU FICHIER ----------
        List<byte[]> chunks = new ArrayList<>();
        for (int offset = 0; offset < fileData.length; offset += MAX_DATA) {
            int size = Math.min(MAX_DATA, fileData.length - offset);
            chunks.add(Arrays.copyOfRange(fileData, offset, offset + size));
        }

        int totalPackets = chunks.size();

        // ---------- FENÊTRE GLISSANTE ----------
        int base = 0;
        int nextSeq = 0;

        Map<Integer, Packet> window = new HashMap<>();
        Map<Integer, Long> sendTime = new HashMap<>();

        while (base < totalPackets) {

            // --- Envoi tant que fenêtre non pleine ---
            while (nextSeq < base + WINDOW_SIZE && nextSeq < totalPackets) {
                Packet p = new Packet();
                p.seq = seqSender + nextSeq;
                p.data = chunks.get(nextSeq);

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, address, port));

                window.put(nextSeq, p);
                sendTime.put(nextSeq, System.currentTimeMillis());

                System.out.println("Envoyé seq=" + p.seq);
                nextSeq++;
            }

            // --- Réception ACK cumulatif ---
            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                Packet ack = PacketEncoder.decode(dpAck.getData());
                int ackSeq = ack.ack; // prochain attendu côté receiver
                int ackIndex = ackSeq - seqSender;

                if (ackIndex > base) {
                    for (int i = base; i < ackIndex; i++) {
                        window.remove(i);
                        sendTime.remove(i);
                    }
                    base = ackIndex;
                    System.out.println("ACK cumulatif reçu → base=" + base);
                }

            } catch (SocketTimeoutException ignored) {
            }

            // --- Timeout → retransmission ---
            long now = System.currentTimeMillis();
            for (int i : new ArrayList<>(window.keySet())) {
                if (now - sendTime.get(i) > TIMEOUT) {
                    Packet p = window.get(i);
                    byte[] raw = PacketEncoder.encode(p);
                    socket.send(new DatagramPacket(raw, raw.length, address, port));
                    sendTime.put(i, now);

                    System.out.println("Retransmission seq=" + p.seq);
                }
            }
        }

        // ---------- ENVOI FIN ----------
        Packet fin = new Packet();
        fin.seq = seqSender + totalPackets;
        fin.ack = 0;
        fin.flags = FLAG_FIN;
        fin.data = new byte[0];

        byte[] rawFin = PacketEncoder.encode(fin);
        socket.send(new DatagramPacket(rawFin, rawFin.length, address, port));

        System.out.println("FIN envoyé seq=" + fin.seq);

        boolean finAcked = false;

        while (!finAcked) {
            try {
                buffer = new byte[2048];
                DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
                socket.receive(dp);

                Packet p = PacketEncoder.decode(dp.getData());

                if ((p.flags & FLAG_ACK) != 0 && p.ack == fin.seq + 1) {
                    finAcked = true;
                    System.out.println("ACK du FIN reçu");
                }

            } catch (SocketTimeoutException e) {
                // retransmission FIN
                socket.send(new DatagramPacket(rawFin, rawFin.length, address, port));
                System.out.println("Retransmission FIN");
            }
        }

        System.out.println("Fichier envoyé avec succès.");
        socket.close();
    }
}