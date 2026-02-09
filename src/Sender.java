import java.net.*;
import java.nio.file.*;
import java.util.Random;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Sender {
    static final int MAX_DATA = 1024;

    public static void main(String[] args) throws Exception {

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];

        byte[] fileData = Files.readAllBytes(Path.of("src/test1.txt"));

        InetAddress address = InetAddress.getByName(ip);
        DatagramSocket socket = new DatagramSocket();

        Random rand = new Random();
        int seqSender = rand.nextInt(65536);

        // --- HANDSHAKE (comme avant) ---
        // --- HANDSHAKE PROPRE ---
        Packet syn = new Packet();
        syn.seq = seqSender;
        syn.syn = true;
        syn.data = new byte[0];
        socket.send(new DatagramPacket(
                PacketEncoder.encode(syn),
                5,
                address,
                port
        ));

        byte[] buffer = new byte[2048];
        DatagramPacket dpRecv = new DatagramPacket(buffer, buffer.length);
        socket.receive(dpRecv); // SYN+ACK

        seqSender++;  // PASSAGE À LA PREMIÈRE DATA


       /* Packet finalSyn = new Packet();
        finalSyn.seq = seqSender;
        finalSyn.syn = true;
        finalSyn.data = new byte[0];
        socket.send(new DatagramPacket(
                PacketEncoder.encode(finalSyn),
                5,
                address,
                port
        ));*/

        System.out.println("Connexion établie, envoi du fichier...");

        socket.setSoTimeout(1000); // 1 seconde

// --- ENVOI DU FICHIER AVEC ACK ---
        int offset = 0;
        while (offset < fileData.length) {
            int chunkSize = Math.min(MAX_DATA, fileData.length - offset);
            byte[] chunk = new byte[chunkSize];
            System.arraycopy(fileData, offset, chunk, 0, chunkSize);



            Packet p = new Packet();
            p.seq = seqSender;
            p.data = chunk;

            byte[] raw = PacketEncoder.encode(p);
            DatagramPacket dpData = new DatagramPacket(raw, raw.length, address, port);

            boolean acked = false;

            while (!acked) {
                socket.send(dpData);

                try {
                    DatagramPacket dpAck = new DatagramPacket(buffer, buffer.length);
                    socket.receive(dpAck);

                    Packet ack = PacketEncoder.decode(dpAck.getData());
                    ByteBuffer bb = ByteBuffer.wrap(ack.data);
                    bb.order(ByteOrder.BIG_ENDIAN);
                    int ackSeq = Short.toUnsignedInt(bb.getShort());

                    if (ackSeq == seqSender) {
                        acked = true;
                        System.out.println("ACK reçu pour seq=" + ackSeq);
                    }
                    else {
                        System.out.println("ACK non reçu pour seq=" + ackSeq);
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout, retransmission seq=" + seqSender);
                }
            }
            seqSender++;

            offset += chunkSize;
        }
    }
}