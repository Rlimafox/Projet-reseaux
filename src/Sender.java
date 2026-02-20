import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;


public class Sender {


    static final int MAX_DATA = 1024;
    static final int DEFAULT_WINDOW = 256;

    static final int SEQ_MOD = 65536;
    static final int HANDSHAKE_TIMEOUT_MS = 500;
    static final int SYN_MAX_ATTEMPTS = 3;


    // --- comparaison modulo correcte ---

    static boolean seqLess(int a, int b) {
        int diff = (a - b) & 0xFFFF;
        return a != b && diff > 0x8000;
    }

    static boolean seqLessOrEqual(int a, int b) {
        return a == b || seqLess(a, b);
    }

    static int readU16(byte[] data) {
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }


    static void printWindow(String event, int cwnd, int ssthresh, int inFlight) {

        System.out.println("[WINDOW] " + event +

                " | cwnd=" + cwnd +

                " | ssthresh=" + ssthresh +

                " | rwnd=" + DEFAULT_WINDOW +

                " | effective=" + cwnd +

                " | inFlight=" + inFlight);

    }


    public static void main(String[] args) throws Exception {


        String ip = args[0];

        int port = Integer.parseInt(args[1]);

        String filename = args[2];


        byte[] fileData = Files.readAllBytes(Path.of(filename));


        InetAddress addr = InetAddress.getByName(ip);

        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);


        int cwnd = DEFAULT_WINDOW;
        int ssthresh = DEFAULT_WINDOW;


        int lastAck = -1;
        Set<Integer> dupAckSeqSet = new HashSet<>();
        boolean dupAckRetransmitted = false;


        // packets en vol : seq -> raw

        TreeMap<Integer, byte[]> inFlight = new TreeMap<>();


        byte[] buffer = new byte[2048];


        // ===== HANDSHAKE =====

        int baseSeq = new Random().nextInt(SEQ_MOD);


        Packet syn = new Packet();

        syn.seq = baseSeq;

        syn.flags = Packet.FLAG_SYN;

        syn.data = new byte[0];


        byte[] synRaw = PacketEncoder.encode(syn);

        Packet synAck = null;
        for (int attempt = 1; attempt <= SYN_MAX_ATTEMPTS; attempt++) {
            socket.send(new DatagramPacket(synRaw, synRaw.length, addr, port));
            try {
                DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
                socket.receive(dp);
                Packet candidate = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));
                if ((candidate.flags & Packet.FLAG_RST) != 0) {
                    System.err.println("Erreur: connexion interrompue par RST pendant l'ouverture.");
                    socket.close();
                    return;
                }
                if ((candidate.flags & Packet.FLAG_SYN) != 0 && (candidate.flags & Packet.FLAG_ACK) != 0) {
                    synAck = candidate;
                    break;
                }
            } catch (SocketTimeoutException ignored) {
                // tentative suivante
            }
        }
        if (synAck == null) {
            System.err.println("Erreur: echec d'ouverture de connexion apres 3 tentatives SYN.");
            socket.close();
            return;
        }

        Packet synFinal = new Packet();
        synFinal.seq = (baseSeq + 1) & 0xFFFF;
        synFinal.flags = Packet.FLAG_SYN;
        synFinal.data = new byte[0];
        byte[] synFinalRaw = PacketEncoder.encode(synFinal);
        socket.send(new DatagramPacket(synFinalRaw, synFinalRaw.length, addr, port));

        int nextSeq = (baseSeq + 2) & 0xFFFF;

        int offset = 0;


        System.out.println("Connexion établie");


        // ===== BOUCLE PRINCIPALE =====

        while (offset < fileData.length || !inFlight.isEmpty()) {


            // ENVOI des paquets dans la fenêtre

            while (offset < fileData.length && inFlight.size() < cwnd) {


                int size = Math.min(MAX_DATA, fileData.length - offset);

                byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + size);


                Packet p = new Packet();

                p.seq = nextSeq;

                p.data = chunk;


                byte[] raw = PacketEncoder.encode(p);

                socket.send(new DatagramPacket(raw, raw.length, addr, port));


                inFlight.put(nextSeq, raw);


                offset += size;

                nextSeq = (nextSeq + 1) & 0xFFFF;

            }


            // RÉCEPTION ACKS

            try {

                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);

                socket.receive(dpAck);


                Packet ack = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));


                if ((ack.flags & Packet.FLAG_RST) != 0) {
                    System.err.println("Erreur: connexion interrompue par RST pendant le transfert.");
                    socket.close();
                    return;
                }
                if ((ack.flags & Packet.FLAG_ACK) == 0)
                    continue;

                if (PacketEncoder.computeChecksum(ack) != ack.checksum)
                    continue;

                if (ack.data == null || ack.data.length < 2)
                    continue;

                int ackSeq = readU16(ack.data);
                int ackPacketSeq = ack.seq & 0xFFFF;

                boolean triggerFastRetransmit = false;
                if (ackSeq == lastAck) {
                    if (!dupAckRetransmitted && dupAckSeqSet.add(ackPacketSeq) && dupAckSeqSet.size() >= 3) {
                        triggerFastRetransmit = true;
                        dupAckRetransmitted = true;
                    }
                } else {
                    lastAck = ackSeq;
                    dupAckSeqSet.clear();
                    dupAckSeqSet.add(ackPacketSeq);
                    dupAckRetransmitted = false;
                }


                // --- suppression des paquets confirmés ---
                inFlight.keySet().removeIf(seq -> seqLessOrEqual(seq, ackSeq));


                // --- contrôle de congestion ---

                if (triggerFastRetransmit) {

                    // simple fast recovery

                    for (byte[] raw : inFlight.values()) {
                        socket.send(new DatagramPacket(
                                raw,
                                raw.length,
                                addr, port));
                    }

                }


                printWindow("ACK", cwnd, ssthresh, inFlight.size());


            } catch (SocketTimeoutException e) {


                // --- TIMEOUT ---

                dupAckSeqSet.clear();
                dupAckRetransmitted = false;


                printWindow("TIMEOUT", cwnd, ssthresh, inFlight.size());


                for (byte[] raw : inFlight.values()) {
                    socket.send(new DatagramPacket(
                            raw,
                            raw.length,
                            addr, port));
                }

            }

        }


        // ===== FIN =====
        Packet fin = new Packet();
        fin.seq = nextSeq;
        fin.flags = Packet.FLAG_FIN;
        fin.data = new byte[0];
        byte[] finRaw = PacketEncoder.encode(fin);

        int finAckNumExpected = fin.seq & 0xFFFF;
        while (true) {
            socket.send(new DatagramPacket(finRaw, finRaw.length, addr, port));
            try {
                DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                socket.receive(dpAck);
                Packet finAck = PacketEncoder.decode(Arrays.copyOf(dpAck.getData(), dpAck.getLength()));
                if ((finAck.flags & Packet.FLAG_RST) != 0) {
                    System.err.println("Erreur: connexion interrompue par RST pendant la fermeture.");
                    socket.close();
                    return;
                }
                if ((finAck.flags & Packet.FLAG_FIN) == 0 || (finAck.flags & Packet.FLAG_ACK) == 0)
                    continue;
                if (PacketEncoder.computeChecksum(finAck) != finAck.checksum)
                    continue;
                if (finAck.data != null && finAck.data.length >= 2 &&
                        readU16(finAck.data) == finAckNumExpected) {
                    Packet finalAck = new Packet();
                    finalAck.seq = (fin.seq + 1) & 0xFFFF;
                    finalAck.flags = Packet.FLAG_ACK;
                    finalAck.data = new byte[0];
                    byte[] finalAckRaw = PacketEncoder.encode(finalAck);
                    socket.send(new DatagramPacket(finalAckRaw, finalAckRaw.length, addr, port));
                    break;
                }
            } catch (SocketTimeoutException e) {
                // retransmit FIN
            }
        }

        socket.close();

        System.out.println("Transfert terminé");

    }

}





