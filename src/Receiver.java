import java.net.*;
import java.nio.*;
import java.util.Random;

public class Receiver {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);
        byte[] buffer = new byte[2048];
        Random rand = new Random();

        System.out.println("Receiver en écoute...");

        // --- HANDSHAKE : réception SYN ---
        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);
        socket.receive(dp);
        Packet syn = PacketEncoder.decode(dp.getData());
        System.out.println("SYN reçu seq=" + syn.seq);

        // --- HANDSHAKE : envoi SYN+ACK ---
        int seqReceiver = rand.nextInt(65536);
        Packet synAck = new Packet();
        synAck.seq = seqReceiver;
        synAck.syn = true;
        synAck.ack = true;
        synAck.data = new byte[0];
        socket.send(new DatagramPacket(
                PacketEncoder.encode(synAck),
                5,
                dp.getAddress(),
                dp.getPort()
        ));
        System.out.println("SYN+ACK envoyé seq=" + seqReceiver);

        // --- Connexion établie ---
        System.out.println("Connexion établie !");

        // --- Boucle réception données ---
        int lastSeqOk = syn.seq + 1;  // numéro attendu du premier paquet data

        while (true) {
            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);
            socket.receive(dpData);

            Packet p = PacketEncoder.decode(dpData.getData());

            if (p.seq == lastSeqOk) {
                System.out.println("Reçu OK seq=" + p.seq + " : " + new String(p.data));
                lastSeqOk++;
                break;
            } else {
                System.out.println("Paquet ignoré seq=" + p.seq + " (attendu seq=" + lastSeqOk + ")");
            }

            // --- Envoi ACK ---
            ByteBuffer ackBuf = ByteBuffer.allocate(2);
            ackBuf.order(ByteOrder.BIG_ENDIAN);
            ackBuf.putShort((short) lastSeqOk);

            Packet ack = new Packet();
            ack.ack = true;
            ack.data = ackBuf.array();

            byte[] rawAck = PacketEncoder.encode(ack);
            socket.send(new DatagramPacket(
                    rawAck,
                    rawAck.length,
                    dpData.getAddress(),
                    dpData.getPort()
            ));
            System.out.println("ACK envoyé seq=" + (lastSeqOk - 1));
        }
    }
}
