import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Sender {

    static final int MAX_DATA = 1024;
    static final int CWND_MAX = 64;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of(filename));

        InetAddress addr = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();

        int seq = new Random().nextInt(65536);
        int nextSeq = (seq + 1) % 65536;

        /* ===== TCP-like variables ===== */
        int cwnd = 1;
        int ssthresh = 16;
        int rwnd = 10;   // valeur initiale supposée

        long rto = 1000;
        socket.setSoTimeout((int) rto);

        Map<Integer, byte[]> inFlight = new TreeMap<>();
        int offset = 0;

        byte[] buffer = new byte[2048];

        /* ===== HANDSHAKE ===== */
        Packet syn = new Packet();
        syn.seq = seq;
        syn.flags = Packet.FLAG_SYN;
        syn.data = new byte[0];

        byte[] rawSyn = PacketEncoder.encode(syn);
        socket.send(new DatagramPacket(rawSyn, rawSyn.length, addr, port));

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp); // SYN+ACK

        System.out.println("Connexion établie");

        /* ===== TRANSFERT ===== */
        while (offset < fileData.length || !inFlight.isEmpty()) {

            int win = Math.min(cwnd, rwnd);

            /* --- Envoi --- */
            while (offset < fileData.length && inFlight.size() < win) {
                int size = Math.min(MAX_DATA, fileData.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);

                Packet p = new Packet();
                p.seq = nextSeq;
                p.data = chunk;

                byte[] raw = PacketEncoder.encode(p);
                socket.send(new DatagramPacket(raw, raw.length, addr, port));

                inFlight.put(nextSeq, raw);

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
                rwnd = ack.data[0] & 0xFF; // rwnd annoncée

                /* --- Retirer les paquets ACKés (ACK cumulatif strict) --- */
                inFlight.keySet().removeIf(s -> (s - ackSeq + 65536) % 65536 <= 0);

                /* --- Evolution cwnd --- */
                if (cwnd < ssthresh) {
                    cwnd = Math.min(cwnd * 2, CWND_MAX);
                } else {
                    cwnd = Math.min(cwnd + 1, CWND_MAX);
                }

            } catch (SocketTimeoutException e) {
                System.out.println("TIMEOUT");

                ssthresh = Math.max(2, cwnd / 2);
                cwnd = 1;

                if (!inFlight.isEmpty()) {
                    int s = inFlight.keySet().iterator().next();
                    byte[] raw = inFlight.get(s);
                    socket.send(new DatagramPacket(raw, raw.length, addr, port));
                }
            }
        }

        /* ===== FIN ===== */
        Packet fin = new Packet();
        fin.seq = nextSeq;
        fin.flags = Packet.FLAG_FIN;
        fin.data = new byte[0];

        byte[] rawFin = PacketEncoder.encode(fin);
        socket.send(new DatagramPacket(rawFin, rawFin.length, addr, port));

        socket.close();
        System.out.println("Transfert terminé");
    }
}
