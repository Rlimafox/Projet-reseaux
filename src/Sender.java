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

        int baseSeq = new Random().nextInt(65536);
        int nextSeq = (baseSeq + 1) % 65536;

        /* ===== Congestion control dynamique ===== */
        double estimatedRTT = 100;      // ms
        double estimatedBandwidth = 0;  // bytes/ms
        double ALPHA = 0.125;
        double BETA = 0.25;

        int cwnd = 1;
        int rwnd = 1;

        long rto = 1000;
        socket.setSoTimeout((int) rto);

        Map<Integer, Long> sendTimes = new HashMap<>();
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

        System.out.println("Connexion établie");

        /* ===== TRANSFERT ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, Math.max(rwnd, 1));

            if (rwnd == 0) {
                System.out.println("[ZERO WINDOW] probing...");

                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    socket.send(new DatagramPacket(
                            inFlight.get(s),
                            inFlight.get(s).length,
                            addr,
                            port
                    ));
                }

                Thread.sleep(500);
                continue;
            }

            /* --- ENVOI --- */
            while (offset < fileData.length && inFlight.size() < win) {

                int size = Math.min(MAX_DATA, fileData.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);

                Packet p = new Packet();
                p.seq = nextSeq;
                p.data = chunk;

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, addr, port));

                sendTimes.put(nextSeq, System.currentTimeMillis());
                inFlight.put(nextSeq, raw);

                System.out.println("[SEND] seq=" + nextSeq + " cwnd=" + cwnd);

                offset += size;
                nextSeq = (nextSeq + 1) % 65536;
            }

            try {

                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);

                long now = System.currentTimeMillis();

                Packet ack = PacketEncoder.decode(dpAck.getData());
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                int ackSeq = ack.ack;
                rwnd = ack.data[0] & 0xFF;

                System.out.println("[ACK] ack=" + ackSeq + " rwnd=" + rwnd);

                /* === RTT estimation === */
                if (sendTimes.containsKey(ackSeq)) {
                    long sampleRTT = now - sendTimes.get(ackSeq);
                    estimatedRTT = (1 - ALPHA) * estimatedRTT + ALPHA * sampleRTT;

                    double sampleBandwidth = MAX_DATA / (double) sampleRTT;
                    estimatedBandwidth =
                            (1 - BETA) * estimatedBandwidth + BETA * sampleBandwidth;
                }

                /* === Retrait ACK cumulatif === */
                inFlight.keySet().removeIf(s ->
                        (s - ackSeq + 65536) % 65536 <= 0);

                sendTimes.keySet().removeIf(s ->
                        (s - ackSeq + 65536) % 65536 <= 0);

                /* === Nouvelle cwnd dynamique (BDP) === */
                double newCwnd = (estimatedBandwidth * estimatedRTT) / MAX_DATA;

                cwnd = Math.max(1, (int) newCwnd);

            } catch (SocketTimeoutException e) {

                System.out.println("[TIMEOUT] réduction cwnd");

                cwnd = Math.max(1, cwnd / 2);

                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    socket.send(new DatagramPacket(
                            inFlight.get(s),
                            inFlight.get(s).length,
                            addr,
                            port
                    ));
                    System.out.println("[RETX] seq=" + s);
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
