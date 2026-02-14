import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;
    static final int TIMEOUT = 500; // ms
    static final int MAX_CWND = 1000;

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
        socket.setSoTimeout(50);

        Random rand = new Random();
        int seqBase = rand.nextInt();

        // ---------- HANDSHAKE ----------
        Packet syn = new Packet();
        syn.seq = seqBase;
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
        socket.receive(new DatagramPacket(buffer, buffer.length));
        seqBase++;

        System.out.println("Connexion établie");

        // ---------- DÉCOUPAGE ----------
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < fileData.length; i += MAX_DATA) {
            chunks.add(Arrays.copyOfRange(
                    fileData, i, Math.min(i + MAX_DATA, fileData.length)
            ));
        }

        int totalPackets = chunks.size();

        // ---------- CONGESTION CONTROL ----------
        int cwnd = 1;
        int ssthresh = 32;

        int base = 0;
        int nextSeq = 0;

        Map<Integer, Packet> window = new HashMap<>();
        Map<Integer, Long> sendTime = new HashMap<>();

        while (base < totalPackets) {

            // ---- ENVOI contrôlé par cwnd ----
            while (nextSeq < base + cwnd && nextSeq < totalPackets) {
                Packet p = new Packet();
                p.seq = seqBase + nextSeq;
                p.ack = 0;
                p.flags = 0;
                p.data = chunks.get(nextSeq);

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, address, port));

                window.put(nextSeq, p);
                sendTime.put(nextSeq, System.currentTimeMillis());

                nextSeq++;
            }

            boolean timeoutDetected = false;

            // ---- RÉCEPTION ACK ----
            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                Packet ack = PacketEncoder.decode(dpAck.getData());
                int ackIndex = ack.ack - seqBase;

                if (ackIndex > base) {
                    int newlyAcked = ackIndex - base;

                    for (int i = base; i < ackIndex; i++) {
                        window.remove(i);
                        sendTime.remove(i);
                    }
                    base = ackIndex;

                    // ---- ADAPTATION cwnd ----
                    if (cwnd < ssthresh) {
                        cwnd += newlyAcked;            // slow start
                    } else {
                        cwnd += Math.max(1, newlyAcked / cwnd); // avoidance
                    }

                    cwnd = Math.min(cwnd, MAX_CWND);

                    System.out.println("ACK reçu → cwnd=" + cwnd);
                }

            } catch (SocketTimeoutException e) {
                timeoutDetected = true;
            }

            // ---- TIMEOUT → CONGESTION ----
            if (timeoutDetected) {
                ssthresh = Math.max(1, cwnd / 2);
                cwnd = 1;

                System.out.println("Timeout → cwnd=1, ssthresh=" + ssthresh);
            }

            // ---- RETRANSMISSION ----
            long now = System.currentTimeMillis();
            for (int i : new ArrayList<>(window.keySet())) {
                if (now - sendTime.get(i) > TIMEOUT) {
                    Packet p = window.get(i);
                    byte[] raw = PacketEncoder.encode(p);
                    socket.send(new DatagramPacket(raw, raw.length, address, port));
                    sendTime.put(i, now);
                }
            }
        }

        // ---------- FIN ----------
        Packet fin = new Packet();
        fin.seq = seqBase + totalPackets;
        fin.ack = 0;
        fin.flags = FLAG_FIN;
        fin.data = new byte[0];

        byte[] rawFin = PacketEncoder.encode(fin);
        socket.send(new DatagramPacket(rawFin, rawFin.length, address, port));

        System.out.println("FIN envoyé");
        socket.close();
    }
}
