import java.net.*;

import java.util.*;



public class Receiver {



    static final int BUFFER_MAX = 32;

    static final int SEQ_MOD = 65536;



    public static int seqNext(int seq) {

        return (seq + 1) % SEQ_MOD;

    }



    public static void main(String[] args) throws Exception {



        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);



        byte[] buffer = new byte[2048];



        int expectedSeq;

        int bufferUsed = 0;



        System.out.println("Receiver en écoute...");



        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);



        Packet syn = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = seqNext(syn.seq);

        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);

        synAck.data = new byte[]{ (byte) BUFFER_MAX };



        socket.send(new DatagramPacket(

                PacketEncoder.encode(synAck),

                PacketEncoder.encode(synAck).length,

                dp.getAddress(),

                dp.getPort()));



        expectedSeq = seqNext(syn.seq);



        System.out.println("Connexion établie");



        while (true) {



            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);



            Packet p = PacketEncoder.decode(Arrays.copyOf(dpData.getData(), dpData.getLength()));



            if (p.seq == expectedSeq) {

                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;

            }



            if (bufferUsed > 0)

                bufferUsed--;



            int rwnd = BUFFER_MAX - bufferUsed;



            Packet ack = new Packet();

            ack.flags = Packet.FLAG_ACK;

            ack.ack = expectedSeq;

            ack.data = new byte[]{ (byte) rwnd };



            socket.send(new DatagramPacket(

                    PacketEncoder.encode(ack),

                    PacketEncoder.encode(ack).length,

                    dpData.getAddress(),

                    dpData.getPort()));



            System.out.println("ACK envoyé | ack=" + ack.ack + " | rwnd=" + rwnd);

        }
    }
}