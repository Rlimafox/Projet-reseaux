import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;


public class Receiver {


    static final int BUFFER_MAX = 32;

    static int seqNext(int x) {
        return x + 1;
    }

    static byte[] ackPayload(int ackSeq, int rwnd) {
        int v = ackSeq & 0xFFFF;
        return new byte[]{
                (byte) ((v >>> 8) & 0xFF),
                (byte) (v & 0xFF),
                (byte) (rwnd & 0xFF)
        };
    }


    public static void main(String[] args) throws Exception {


        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);


        byte[] buffer = new byte[2048];


        int expectedSeq;

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


        // SYN-ACK

        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = seqNext(syn.seq);

        synAck.flags = (byte) (Packet.FLAG_SYN | Packet.FLAG_ACK);

        synAck.data = ackPayload(synAck.ack, BUFFER_MAX);


        socket.send(new DatagramPacket(

                PacketEncoder.encode(synAck),

                PacketEncoder.encode(synAck).length,

                dp.getAddress(),

                dp.getPort()

        ));


        expectedSeq = synAck.ack;


        System.out.println("Connexion établie");


        // LOOP

        while (true) {


            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);


            Packet p = PacketEncoder.decode(

                    Arrays.copyOf(dpData.getData(), dpData.getLength())

            );


            boolean valid = PacketEncoder.computeChecksum(p) == p.checksum;
            if (valid && (p.flags & Packet.FLAG_FIN) != 0) {
                if (p.seq == expectedSeq)
                    expectedSeq = seqNext(expectedSeq);
                int finRwnd = BUFFER_MAX - bufferUsed;
                Packet ack = new Packet();
                ack.flags = Packet.FLAG_ACK;
                ack.data = ackPayload(expectedSeq, finRwnd);
                socket.send(new DatagramPacket(
                        PacketEncoder.encode(ack),
                        PacketEncoder.encode(ack).length,
                        dpData.getAddress(),
                        dpData.getPort()
                ));
                System.out.println("FIN recu, fermeture.");
                break;
            }

            if (valid && p.seq == expectedSeq) {

                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;

            }


            if (bufferUsed > 0)

                bufferUsed--;


            int rwnd = BUFFER_MAX - bufferUsed;

            Packet ack = new Packet();

            ack.flags = Packet.FLAG_ACK;
            ack.data = ackPayload(expectedSeq, rwnd);


            socket.send(new DatagramPacket(

                    PacketEncoder.encode(ack),

                    PacketEncoder.encode(ack).length,

                    dpData.getAddress(),

                    dpData.getPort()

            ));

            if (expectedSeq != lastAckSent || rwnd != lastRwndSent) {
                System.out.println("ACK envoye | ack=" + ack.ack + " | rwnd=" + rwnd);
                lastAckSent = expectedSeq;
                lastRwndSent = rwnd;
            }

        }

        socket.close();
    }

}
