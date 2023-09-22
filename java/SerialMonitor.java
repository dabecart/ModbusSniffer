import java.util.HashMap;
import java.util.Scanner;

import com.fazecast.jSerialComm.SerialPort;

class SerialMonitor {

    static SerialPort port;
    static byte[] buffer = new byte[256];
    static int bufferSize = 0;
    static HashMap<Byte, String> map = new HashMap<>();
    static int portsFound = 0;

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BLACK = "\u001B[30m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_PURPLE = "\u001B[35m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";

    public static final String COLORS[] = {ANSI_GREEN, ANSI_YELLOW, ANSI_BLUE, ANSI_PURPLE, ANSI_PURPLE, ANSI_CYAN, ANSI_WHITE};

    public static final boolean PRINT_SERIAL = true;
    public static final long DELAY_TIME = 0;

    public static void main(String[] args) {
        startModbusRoutine();
    }

    static void startModbusRoutine(){
        System.out.println("Available devices: ");

        SerialPort[] devicePorts = SerialPort.getCommPorts();
        if(devicePorts.length == 0){
            System.out.println("Connect a Serial Port!");
            return;
        }

        for (int i = 0; i < devicePorts.length; i++){
            SerialPort each_port = devicePorts[i];
            System.out.println("[" + i + "]: " +each_port.getSystemPortName());
        }
        System.out.print("Select a COM port: ");
        Scanner sc = new Scanner(System.in);
        int index = sc.nextInt();
        if(index > devicePorts.length){
            System.err.println("Index out of bounds");
            sc.close();
            return;
        }
        sc.nextLine(); // Clear the awaiting \n

        port = devicePorts[index];
        System.out.print("Baud rate (19200): ");
        String tempString = sc.nextLine();
        int baudRate = 19200;
        if(tempString != ""){
            baudRate = Integer.parseInt(tempString);
        }
        port.setBaudRate(baudRate);

        port.setParity(SerialPort.EVEN_PARITY);
        port.setNumDataBits(8);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
        port.openPort();

        for(;;){
            boolean readOk = readModbus();
            if(!readOk){
                printBuffer(ANSI_BLACK);
            }
        }
    }

    static void printBuffer(){
        printBuffer("");
    }

    static void printBuffer(final String color){
        printBuffer(color, 0, bufferSize);
    }

    static void printBuffer(final String color, int from, int to){
        if(!PRINT_SERIAL) return;

        StringBuilder out = new StringBuilder(color);
        for(int i = from; i < to; i++){
            int readData = buffer[i] & 0xFF;
            String parsedData = Integer.toHexString(readData);
            if(parsedData.length() < 2){
                parsedData = "0" + parsedData;
            }
            out.append(parsedData);
            out.append(" ");
        }
        out.append(ANSI_RESET);
        System.out.println(out.toString());
    }

    // Sends data with appended CRC.
    static void writeModbus(){
        short crc = calculate_crc();
        buffer[bufferSize++] = (byte)(crc&0x00FF);
        buffer[bufferSize++] = (byte)(crc>>8);
        printBuffer(ANSI_RED);
        port.writeBytes(buffer, bufferSize, 0);
        bufferSize = 0; // Clear buffer
    }

    static boolean readModbus(){
        long startTime = System.currentTimeMillis(), elapsedTime = 0;
        
        while(port.bytesAvailable() <= 0){}

        while(elapsedTime < 1000){
            if(port.bytesAvailable() > 0){
                int bytesAvailable = port.bytesAvailable();
                int freeSpace = buffer.length - bufferSize;
                if(bytesAvailable > freeSpace){
                    bytesAvailable = freeSpace;
                    port.readBytes(buffer, bytesAvailable, bufferSize);
                    bufferSize = buffer.length;
                    return findStartOfTransmission();
                }

                if(checkCRC()) return true;

                startTime = System.currentTimeMillis();
            }
            elapsedTime = System.currentTimeMillis() - startTime;
        }
        return false;
    }

    static boolean findStartOfTransmission(){
        for(int start = 0; start < buffer.length; start++){
            // Second byte must be an instruction code (read, write, mask write)
            byte opCode = (byte) (buffer[start+1]&0x7F); // Remove the first bit (means error)
            if(opCode!=3 && opCode!=6 && opCode!=22) continue;
            
            short crc = (short) 0xFFFF;
            int xorIndex = 0;

            int i = start;
            int length = buffer.length-start;
            while(length-- != 0){
                char bufRead = (char) (buffer[i++]&0x00FF);
                crc ^= bufRead;
                for(byte bit = 0; bit < 8; bit++){
                    xorIndex = crc & 1;
                    crc = (short) ((crc & 0xFFFF) >> 1);
                    crc ^= (xorIndex==0) ? 0 : 40961; /*0xA001 */
                }

                // Found the tail of the transmision!
                if(crc == 0){
                    //Discarded output
                    printBuffer(ANSI_BLACK, 0, start);
                    
                    byte port = buffer[start];
                    if(!map.containsKey(port)){
                        map.put(port, COLORS[portsFound++]);
                        portsFound %= COLORS.length;
                    }
                    printBuffer(map.get(port), start, i);
                    
                    for(int j = 0; j < buffer.length-i; j++){
                        buffer[j] = buffer[i+j];
                    }
                    bufferSize = buffer.length-i;
                    return true;
                }
            }
        }
        return false;
    }

    static boolean checkCRC(){
        if(bufferSize <= 2) return false;
        return calculate_crc()==0;
    }



    // Modified from https://github.com/LacobusVentura/MODBUS-CRC16/blob/master/MODBUS_CRC16.c
    public static short calculate_crc() {
        short crc = (short) 0xFFFF;
        int xorIndex = 0;

        int i = 0;
        int length = bufferSize;

        while(length-- != 0){
            char bufRead = (char) (buffer[i++]&0x00FF);
            crc ^= bufRead;
            for(byte bit = 0; bit < 8; bit++){
                xorIndex = crc & 1;
                crc = (short) ((crc & 0xFFFF) >> 1);
                crc ^= (xorIndex==0) ? 0 : 40961; /*0xA001 */
            }
        }
        return crc;
    }

}

