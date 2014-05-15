package robot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

class Upload {

    private byte[] data;
    private byte[] ID;
    private DatagramSocket soc;
    private DatagramPacket packet;
    private PriorityQueue<Integer> q;
    private List<Integer> window;
    private int control;
    private int lastSent;
    private int counter = 0;
    static final int tOut = 1000; //in miliseconds
    static final byte[] upFlag = {2};

    public Upload(String servername, int port, String firmware) {
        try {
            File thisFile = new File(firmware);
            InputStream is = new FileInputStream(firmware);
            data = new byte[(int) thisFile.length()];
            int offset = 0;
            int numRead = 0;
            while (offset < data.length && (numRead = is.read(data, offset, data.length - offset)) >= 0) {
                offset += numRead;
            }
            is.close();

        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }

        q = new PriorityQueue<Integer>();
        int last = 0;
        q.add(last);
        while (last < data.length) {
            last += 255;
            if (last > data.length) {
                last = data.length;
            }
            q.add(new Integer(last));
        }

        window = new LinkedList<Integer>();
        for (int i = 0; i < 9; i++) {
            window.add(q.poll());
        }

        try {
            soc = new DatagramSocket();
            soc.setSoTimeout(tOut);
        } catch (SocketException e) {
            System.out.println(e.toString());
            System.exit(1);
        }
        try {
            packet = new DatagramPacket(new byte[264], 10, InetAddress.getByName(servername), port);
        } catch (UnknownHostException e) {
            System.out.println(e.toString());
            System.exit(1);
        }
    }

    public void control() {
        while (true) {
            switch (control) {
                case 0:
                    init();
                    break;
                case 1:
                    first();
                    break;
                case 2:
                    send();
                    break;
                case 3:
                    ack();
                    break;
                case 4:
                    end();
                    break;
                case 5:
                    reset();
                    break;
            }
        }
    }

    public void init() {
        byte[] toSend = new byte[10];
        System.arraycopy(Download.nullID, 0, toSend, 0, 4);
        System.arraycopy(Download.nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(Download.nullCon, 0, toSend, 6, 2);
        System.arraycopy(Download.synFlag, 0, toSend, 8, 1);
        System.arraycopy(upFlag, 0, toSend, 9, 1);
        packet.setData(toSend);
        packet.setLength(toSend.length);
        try {
            soc.send(packet);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        control = 1;
    }

    public void first() {
        try {
            soc.receive(packet);
        } catch (SocketTimeoutException e) {
            System.out.println(e.getMessage());
            control = 0;
            return;
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }

        byte[] buf = packet.getData();

        if (buf[8] != Download.synFlag[0]) {
            System.out.println("Not a SYN packet");
            control = 0;
            return;
        }
        if (buf[9] != upFlag[0]) {
            System.out.println("Wrong flag.");
            control = 0;
            return;
        }

        ID = new byte[4];
        System.arraycopy(buf, 0, ID, 0, 4);
        System.out.println("Connection established with ID " + Integer.toHexString(parseID(ID)));       
        control = 2;
    }

    public int parseID(byte[] b){
        int i = 0;
        i |= b[0] & 0xFF;
        i <<= 8;
        i |= b[1] & 0xFF;
        i <<= 8;
        i |= b[2] & 0xFF;
        i <<= 8;
        i |= b[3] & 0xFF;
        return i;
    }
    
    public void send() {
        if (counter == 20) {
            System.out.println("Reached maximum sends of data datagram.");
            control = 5;
            return;
        }

        if (window.size() == 1) {
            System.out.println("Firmware sent.");
            control = 4;
            return;
        }

        byte[] toSend = new byte[264];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(Download.nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(Download.nullCon, 0, toSend, 6, 2);
        Iterator<Integer> iter = window.iterator();
        int first;
        int last;
        last = iter.next();
        first = last;
        while (iter.hasNext()) {
            int tmp = iter.next();
            System.arraycopy(Download.numToByte(last), 0, toSend, 4, 2);
            System.arraycopy(Arrays.copyOfRange(data, last, tmp), 0, toSend, 9, tmp - last);
            packet.setData(toSend);
            packet.setLength(tmp - last + 9);
            try {
                soc.send(packet);
            } catch (IOException e) {
                System.out.println(e.toString());
                System.exit(1);
            }
            last = tmp;
        }
        if (lastSent != last) {
            counter = 0;
        } else {
            counter++;
        }

        if (Robot.debug) {
            System.out.println("Sending data from " + first + " to " + last);
        }
        lastSent = last;
        control = 3;
    }

    private void ack() {
        packet.setData(new byte[264]);
        packet.setLength(264);
        try {
            soc.receive(packet);
        } catch (SocketTimeoutException e) {
            control = 2;
            return;
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        byte[] buf = packet.getData();

        if (buf[8] == Download.synFlag[0]) {
            System.out.println("SYN recieved");
            return;
        }

        if (buf[8] == Download.rstFlag[0]) {
            System.out.println("Game over. RST received.");
            System.exit(1);
        }

        int newn = Download.byteToInt(Arrays.copyOfRange(buf, 6, 8));
        int n = newn;
        int ref = 0;
        if (!q.isEmpty()) {
            ref = q.peek();
        } else {
            Iterator<Integer> iter = window.iterator();
            while (iter.hasNext()) {
                ref = iter.next();
            }
        }
        while (n < ref - 20000) {
            n += 65536;
        }
        if (Robot.debug) {
            System.out.println("Recieved ACK " + n + " / overflow " + newn);
        }
        Iterator<Integer> iter = window.iterator();

        iter = window.iterator();
        List<Integer> toRemove = new LinkedList<Integer>();
        while (iter.hasNext()) {
            int tmp = iter.next();
            if (n > tmp) {
                toRemove.add(tmp);
            }
        }

        iter = toRemove.iterator();
        while (iter.hasNext()) {
            window.remove(iter.next());
            if (!q.isEmpty()) {
                window.add(q.poll());
            }
        }
    }

    public void reset() {
        byte[] toSend = new byte[9];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(Download.nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(Download.nullCon, 0, toSend, 6, 2);
        System.arraycopy(Download.rstFlag, 0, toSend, 8, 1);
        packet.setData(toSend);
        try {
            soc.send(packet);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        System.out.println("Sending RST and shutting down.");
        System.exit(1);
    }

    public void end() {
        System.out.println("Sending FIN");
        byte[] toSend = new byte[9];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(Download.numToByte(data.length), 0, toSend, 4, 2);
        System.arraycopy(Download.nullCon, 0, toSend, 6, 2);
        System.arraycopy(Download.finFlag, 0, toSend, 8, 1);
        packet.setData(toSend);
        for (int i = 1; i <= 20; ++i) {
            try {
                soc.send(packet);
            } catch (IOException e) {
                System.out.println(e.toString());
                System.exit(1);
            }
            if (Robot.debug) {
                System.out.println("FIN send #" + i);
            }
        }
        System.out.println("Exit.");
        System.exit(0);
    }
}

class Download {

    private DatagramSocket socket;
    private DatagramPacket packet;
    private byte[] ID;
    private int last;
    private int control;
    static final byte[] nullID = {0, 0, 0, 0};
    static final byte[] nullSEQ = {0, 0};
    static final byte[] nullCon = {0, 0};
    static final byte[] synFlag = {4};
    static final byte[] finFlag = {2};
    static final byte[] rstFlag = {1};
    static final int tOut = 5000; //in miliseconds
    static final byte[] dwnFlag = {1};
    private LinkedList<dataPart> imgBuff;
    private PriorityQueue<dataPart> imgCache;
    private int imgBuffLast;

    /*  control:
     * 0: init
     * 1: first
     * 2: receive
     * 3: ack
     * 4: end
     * 5: reset
     */
    public Download(String servername, int port) {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(tOut);
        } catch (SocketException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        try {
            packet = new DatagramPacket(new byte[264], 10, InetAddress.getByName(servername), port);
        } catch (UnknownHostException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        control = 0;
        imgBuff = new LinkedList<dataPart>();
        imgCache = new PriorityQueue<dataPart>();
        imgBuffLast = 0;
        last = 0;
    }

    public void control() {
        while (true) {
            switch (control) {
                case 0:
                    init();
                    break;
                case 1:
                    first();
                    break;
                case 2:
                    receive();
                    break;
                case 3:
                    ack();
                    break;
                case 4:
                    end();
                    break;
                case 5:
                    reset();
                    break;
            }
        }
    }

    public void init() {
        byte[] toSend = new byte[10];
        System.arraycopy(nullID, 0, toSend, 0, 4);
        System.arraycopy(nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(nullCon, 0, toSend, 6, 2);
        System.arraycopy(synFlag, 0, toSend, 8, 1);
        System.arraycopy(dwnFlag, 0, toSend, 9, 1);
        packet.setData(toSend);
        packet.setLength(toSend.length);
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        control = 1;
    }

    public void first() {
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            System.out.println(e.getMessage());
            control = 0;
            return;
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        byte[] buf = packet.getData();
        if (buf[8] != synFlag[0] || buf[9] != 1) {
            System.out.println("Not SYN or WRONG flag");
            control = 0;
            return;
        }
        ID = new byte[4];
        System.arraycopy(buf, 0, ID, 0, 4);
        System.out.println("SYN received");
        control = 2;
        
        System.out.println("Connection established with ID " + Integer.toHexString(parseID(ID)));       
        }

    public int parseID(byte[] b){
        int i = 0;
        i |= b[0] & 0xFF;
        i <<= 8;
        i |= b[1] & 0xFF;
        i <<= 8;
        i |= b[2] & 0xFF;
        i <<= 8;
        i |= b[3] & 0xFF;
        return i;
    }
    
    public void receive() {
        packet.setData(new byte[264]);
        packet.setLength(264);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            System.out.println("Timeout");
            control = 3;
            return;
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        byte[] buf = packet.getData();

        if (buf[8] == synFlag[0]) {
            System.out.println("SYN received (inside session).");
            control = 5;
            return;
        }

        if (buf[8] == rstFlag[0]) {
            System.out.println("RST received.");
            System.exit(1);
        }

        if (!checkID(buf, ID)) {
            System.out.println("Datagram with wrong ID.");
            control = 2;
            return;
        }

        if (buf[8] == finFlag[0]) {
            System.out.println("FIN received.");
            control = 4;
            return;
        }
        last = add(buf, byteToInt(Arrays.copyOfRange(buf, 4, 6)), packet.getLength());
        control = 3;
    }

    public void ack() {
        byte[] toSend = new byte[9];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(numToByte(last), 0, toSend, 6, 2);
        packet.setData(toSend);
        packet.setLength(toSend.length);
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.out.println(e.toString());
            System.exit(1);
        }
        if (Robot.debug) {
            System.out.println("Waiting for: " + last + " / overflow: " + byteToInt(numToByte(last)));
        }
        control = 2;
    }

    public void reset() {
        byte[] toSend = new byte[9];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(nullCon, 0, toSend, 6, 2);
        System.arraycopy(rstFlag, 0, toSend, 8, 1);
        packet.setData(toSend);
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        System.out.println("Wrong datagram. Game over, sending RST and terminating session.");
        System.exit(1);
    }

    public void end() {
        byte[] toSend = new byte[9];
        System.arraycopy(ID, 0, toSend, 0, 4);
        System.arraycopy(nullSEQ, 0, toSend, 4, 2);
        System.arraycopy(numToByte(last), 0, toSend, 6, 2);
        System.arraycopy(finFlag, 0, toSend, 8, 1);
        packet.setData(toSend);
        for (int i = 0; i < 1; i++) {
            try {
                socket.send(packet);
            } catch (IOException ex) {
                System.out.println(ex.toString());
                System.exit(1);
            }
        }

        File f = new File("baryk.png");
        saveImg(f);
        System.out.println("Succesfully ending session and saving imgage to " + f.getAbsolutePath());
        System.exit(0);
    }

    public static byte[] numToByte(int l) {
        byte[] tmp = ByteBuffer.allocate(4).putInt(l).array();
        byte[] res = new byte[2];
        res[1] = tmp[3];
        res[0] = tmp[2];
        return res;
    }

    public static int byteToInt(byte[] b) {
        int i = 0;
        i |= b[0] & 0xFF;
        i <<= 8;
        i |= b[1] & 0xFF;
        return i;
    }

    public static boolean checkID(byte[] buf, byte[] ID) {
        for (int i = 0; i < ID.length; i++) {
            if (ID[i] != buf[i]) {
                return false;
            }
        }
        return true;
    }

    int add(byte[] buf, int offset, int length) {
        if (Robot.debug) {
            System.out.println("Saving: " + offset);
        }
        for (int i = 1; imgBuffLast + 2040 >= i * 65536; i++, offset += 65536) { //overflow
            if (offset >= imgBuffLast - 20000) {
                break;
            }
        }

        if (offset == imgBuffLast) {
            dataPart n = new dataPart();
            n.buf = Arrays.copyOf(buf, length);
            n.length = length - 9;
            imgBuff.add(n);
            imgBuffLast += n.length;
            while (!imgCache.isEmpty()) {
                if (imgCache.peek().offset != imgBuffLast) {
                    break;
                }
                n = imgCache.poll();
                imgBuff.add(n);
                imgBuffLast += n.length;
            }
        }

        if (offset > imgBuffLast) { // put data to part
            dataPart dataPart = new dataPart();
            dataPart.offset = offset;
            if (!imgCache.contains(dataPart)) {
                dataPart.buf = Arrays.copyOf(buf, length);
                dataPart.length = length - 9;
                if (!imgBuff.contains(dataPart)) {
                    imgCache.add(dataPart);
                }
            }
        }
        if (Robot.debug) {
        System.out.println("Received data: " + offset + "\n");
        }
        return imgBuffLast;
    }

    public void saveImg(File file) {
        Iterator<dataPart> iter = imgBuff.iterator();
        FileOutputStream out;
        try {
            out = new FileOutputStream(file);
            while (iter.hasNext()) {
                byte[] tmp = iter.next().buf;
                out.write(Arrays.copyOfRange(tmp, 9, tmp.length));
            }

        } catch (IOException e) {
            System.out.println(e.toString());
            System.exit(1);
        }
    }

    private class dataPart implements Comparable {

        private byte[] buf;
        private int offset;
        private int length;

        @Override
        public int compareTo(Object o) {
            dataPart n = (dataPart) o;
            if (offset > n.offset) {
                return 1;
            }
            if (offset < n.offset) {
                return -1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof dataPart) {
                dataPart dataPart = (dataPart) obj;
                return offset == dataPart.offset;
            }
            throw new IllegalArgumentException();
        }

        @Override
        public int hashCode() {
            return offset;
        }
    }
}

public class Robot {

    public static boolean debug = false;

    public static void main(String[] args) throws Exception {
        switch (args.length) {
            case 2:
                System.out.println("Download image...\n");
                Download d = new Download(args[0], Integer.parseInt(args[1]));
                d.control();
                break;
            case 3:
                System.out.println("Uploading firmware...\n");
                Upload u = new Upload(args[0], Integer.parseInt(args[1]), args[2]);
                u.control();
                break;
            default:
//                System.err.println("Download: java robot.Robot <hostname> <port>");
//                System.err.println("Upload: java robot.Robot <hostname> <port> <firmware>");
//                System.exit(1);
                System.out.println("Download image...\n");
//                Download down = new Download("baryk.fit.cvut.cz", 4000);
                Download down = new Download("localhost", 4000);
                down.control();
                break;
        }
    }
}
