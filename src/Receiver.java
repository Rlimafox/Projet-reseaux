import java.net.*;

import java.util.*;



public class Receiver {



    static int bufferMax = 32;               // fenêtre dynamique initiale

    static final int SEQ_MOD = 65536;



    // Nombre d'éléments réellement "occupés"

    static volatile int bufferUsed = 0;



    static int seqNext(int seq) {

        return (seq + 1) % SEQ_MOD;

    }



    public static void main(String[] args) throws Exception {



        int port = Integer.parseInt(args[0]);

        DatagramSocket socket = new DatagramSocket(port);



        byte[] buffer = new byte[2048];



        System.out.println("Receiver en écoute...");



        /************************************************************

         *          THREAD DE CONSOMMATION (COMME UNE APP)

         ************************************************************/

        Thread consumer = new Thread(() -> {

            while (true) {

                try {

                    Thread.sleep(20);   // Consomme ~50 éléments/s

                } catch (Exception ignored) {}



                if (bufferUsed > 0)

                    bufferUsed--;



                // Ajustement dynamique de la taille du buffer

                if (bufferUsed < bufferMax / 4 && bufferMax < 255) {

                    bufferMax++;

                }

                else if (bufferUsed > 3 * bufferMax / 4 && bufferMax > 16) {

                    bufferMax--;

                }

            }

        });

        consumer.setDaemon(true);

        consumer.start();



        /************************************************************

         *                       HANDSHAKE

         ************************************************************/

        DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

        socket.receive(dp);



        Packet syn = PacketEncoder.decode(Arrays.copyOf(dp.getData(), dp.getLength()));



        Packet synAck = new Packet();

        synAck.seq = 0;

        synAck.ack = seqNext(syn.seq);

        synAck.flags = (byte)(Packet.FLAG_SYN | Packet.FLAG_ACK);

        synAck.data = new byte[]{ (byte) bufferMax };  // fenêtre annoncée



        socket.send(new DatagramPacket(

                PacketEncoder.encode(synAck),

                PacketEncoder.encode(synAck).length,

                dp.getAddress(),

                dp.getPort()));



        int expectedSeq = seqNext(syn.seq);



        System.out.println("Connexion établie");



        /************************************************************

         *                    BOUCLE PRINCIPALE

         ************************************************************/

        while (true) {



            DatagramPacket dpData = new DatagramPacket(buffer, buffer.length);

            socket.receive(dpData);



            Packet p = PacketEncoder.decode(Arrays.copyOf(dpData.getData(), dpData.getLength()));



            /************************************************************

             *                     TRAITEMENT

             ************************************************************/

            if (p.seq == expectedSeq) {

                expectedSeq = seqNext(expectedSeq);

                bufferUsed++;

            }



            // Toujours calculer la fenêtre restante

            int rwnd = Math.max(0, bufferMax - bufferUsed);



            /************************************************************

             *                       ENVOI DE L'ACK

             ************************************************************/

            Packet ack = new Packet();

            ack.flags = Packet.FLAG_ACK;

            ack.ack = expectedSeq;

            ack.data = new byte[]{ (byte) rwnd };



            socket.send(new DatagramPacket(

                    PacketEncoder.encode(ack),

                    PacketEncoder.encode(ack).length,

                    dpData.getAddress(),

                    dpData.getPort()));



            System.out.println(

                    "ACK envoyé | ack=" + ack.ack +

                            " | rwnd=" + rwnd +

                            " | bufUsed=" + bufferUsed +

                            " | bufMax=" + bufferMax);

        }

    }

}