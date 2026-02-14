import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress addr = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(500); // plus réactif

        int baseSeq = new Random().nextInt(65536);
        int nextSeq = (baseSeq + 1) % 65536;

        int cwnd = 1;
        int ssthresh = 32;
        int rwnd = 32;

        int lastAck = baseSeq;
        int dupAckCount = 0;

        Map<Integer, byte[]> inFlight = new TreeMap<>();
        int offset = 0;
        byte[] buffer = new byte[2048];

        /* ===== HANDSHAKE ===== */
        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        byte[] synRaw = PacketEncoder.encode(syn);
        socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet synAck = PacketEncoder.decode(dp.getData());

        System.out.println("Connexion établie");
        lastAck = synAck.ack;

        /* ===== ENVOI FICHIER ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            // fenêtre disponible
            int win = Math.min(cwnd, rwnd);

            // envoyer les paquets tant que possible
            while (offset < fileData.length && inFlight.size() < win) {

                int size = Math.min(MAX_DATA, fileData.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);

                Packet p = new Packet();
                p.seq = nextSeq;
                p.data = chunk;

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, addr, port));
                inFlight.put(nextSeq, raw);

                System.out.println("[SEND] seq=" + nextSeq + " cwnd=" + cwnd);

                offset += size;
                nextSeq = (nextSeq + 1) % 65536;
            }

            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                Packet ack = PacketEncoder.decode(dpAck.getData());
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                // duplicate ACK
                if (ackSeq == lastAck) {
                    dupAckCount++;
                } else {
                    dupAckCount = 0;
                }

                // FAST RETRANSMIT
                if (dupAckCount == 3) {
                    ssthresh = Math.max(2, cwnd / 2);
                    cwnd = ssthresh;

                    if (inFlight.containsKey(ackSeq)) {
                        System.out.println("[FAST RETRANSMIT] seq=" + ackSeq);
                        socket.send(new DatagramPacket(
                                inFlight.get(ackSeq),
                                inFlight.get(ackSeq).length,
                                addr,
                                port
                        ));
                    }
                }

                lastAck = ackSeq;

                /* ===== SUPPRESSION CUMULATIVE SÉCURISÉE ===== */
                List<Integer> toRemove = new ArrayList<>();
                for (int s : inFlight.keySet()) {
                    int diff = (ackSeq - s + 65536) % 65536;
                    if (diff < 32768) { // s <= ackSeq modulo 65536
                        toRemove.add(s);
                    }
                }
                for (int s : toRemove) {
                    inFlight.remove(s);
                }

                // ajustement cwnd
                if (dupAckCount < 3) {
                    if (cwnd < ssthresh) {
                        cwnd *= 2; // slow start
                    } else {
                        cwnd += 1; // congestion avoidance
                    }
                }

            } catch (SocketTimeoutException e) {
                // Timeout : retransmettre le plus ancien paquet
                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;
                dupAckCount = 0;

                if (!inFlight.isEmpty()) {
                    int firstSeq = inFlight.keySet().iterator().next();
                    System.out.println("[RETRANSMIT TIMEOUT] seq=" + firstSeq);
                    socket.send(new DatagramPacket(
                            inFlight.get(firstSeq),
                            inFlight.get(firstSeq).length,
                            addr,
                            port
                    ));
                }
            }
        }

        /* ===== FIN ===== */
        Packet fin = new Packet();
        fin.seq = nextSeq;
        fin.flags = Packet.FLAG_FIN;
        fin.data = new byte[0];

        byte[] finRaw = PacketEncoder.encode(fin);
        socket.send(new DatagramPacket(finRaw, finRaw.length, addr, port));

        socket.close();
        System.out.println("Transfert terminé");
    }
}
