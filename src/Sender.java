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

        /* ===== TCP-like variables ===== */
        int cwnd = 1;
        int ssthresh = 16;
        int rwnd = 1;

        long rto = 1000;
        socket.setSoTimeout((int) rto);

        long lastSendTime = System.currentTimeMillis();
        long bytesAcked = 0;

        Map<Integer, byte[]> inFlight = new TreeMap<>();
        int offset = 0;

        byte[] buffer = new byte[2048];

        /* ===== HANDSHAKE ===== */
        Packet syn = new Packet();
        syn.seq = baseSeq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        socket.send(new DatagramPacket(
                PacketEncoder.encode(syn),
                PacketEncoder.encode(syn).length,
                addr,
                port
        ));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp); // SYN+ACK

        System.out.println("Connexion établie");

        /* ===== TRANSFERT ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, rwnd);

            /* --- ENVOI --- */
            while (offset < fileData.length && inFlight.size() < win) {
                int size = Math.min(MAX_DATA, fileData.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);

                Packet p = new Packet();
                p.seq = nextSeq;
                p.data = chunk;

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, addr, port));

                inFlight.put(nextSeq, raw);
                System.out.println("[SEND] seq=" + nextSeq + " cwnd=" + cwnd + " rwnd=" + rwnd);

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

                System.out.println("[ACK] ack=" + ackSeq + " rwnd=" + rwnd);

                /* --- Calcul débit approx --- */
                bytesAcked += MAX_DATA;
                long now = System.currentTimeMillis();
                long delta = now - lastSendTime;
                if (delta > 0) {
                    long bandwidth = bytesAcked * 1000 / delta; // B/s
                    int cwndMaxDynamic = Math.max(2, (int)(bandwidth / MAX_DATA));
                    cwnd = Math.min(cwnd, cwndMaxDynamic);
                }

                /* --- Retirer ACK cumulatif --- */
                inFlight.keySet().removeIf(s -> (s - ackSeq + 65536) % 65536 <= 0);

                /* --- AIMD --- */
                if (cwnd < ssthresh) {
                    cwnd++;
                } else {
                    cwnd += 1;
                }

                lastSendTime = now;

            } catch (SocketTimeoutException e) {
                System.out.println("[TIMEOUT] cwnd=" + cwnd + " → " + Math.max(1, cwnd / 2));

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;

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

        socket.send(new DatagramPacket(
                PacketEncoder.encode(fin),
                PacketEncoder.encode(fin).length,
                addr,
                port
        ));

        socket.close();
        System.out.println("Transfert terminé");
    }
}
