import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class mainClient {

	// Variabile per monitorare lo stato del socket
    private static volatile boolean isSocketClosed = false;
    private static volatile long lastActivityTime = System.currentTimeMillis();

    public static void main(String[] args) throws IOException {
       		// Decodifica il percorso
		String configPath = URLDecoder.decode(
        mainServer.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
        StandardCharsets.UTF_8
		) + "/client_config.properties";
    	// Caricamento delle configurazioni dal file
    	Properties config = utilities.ConfigLoader.loadConfig(configPath);
        String serverAddress = config.getProperty("client.serverAddress");
        int serverPort = Integer.parseInt(config.getProperty("client.serverPort"));
        int udpPort = Integer.parseInt(config.getProperty("client.udpPort"));
        int timeout = Integer.parseInt(config.getProperty("client.timeout"));
        
        // Connessione al server
    	try (
    		 Socket socket = new Socket(serverAddress, serverPort);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             Scanner scanner = new Scanner(System.in)
        ) {
    		
    		startTimeoutThread(timeout, out);
            System.out.println("Connesso al server CROSS.");
            
            // Avvio del thread per la ricezione delle notifiche UDP
            Thread udpListenerThread = new Thread(() -> listenForNotifications(udpPort));
            udpListenerThread.start(); // Avvia il thread

            Gson gson = new GsonBuilder().create();
            
            // Gestione dei comandi utente
            while (!isSocketClosed) {
                // Gestisci l'input dell'utente (comandi)
                System.out.println("\nScegli un comando:");
                
                System.out.println("1. register\n2. login\n3. updateCredentials\n4. logout\n5. insertLimitOrder\n6. insertMarketOrder\n7. insertStopOrder\n8. cancelOrder\n9. getPriceHistory\n");
                System.out.print("Comando: ");

                String choice = scanner.nextLine();
                lastActivityTime = System.currentTimeMillis();
                JsonObject request = new JsonObject();

                switch (choice) {
                    case "1": // register
                        request.addProperty("command", "register");
                        System.out.print("Username: ");
                        String regUsername = scanner.nextLine();
                        System.out.print("Password: ");
                        String regPassword = scanner.nextLine();
                        JsonObject regData = new JsonObject();
                        regData.addProperty("username", regUsername);
                        regData.addProperty("password", regPassword);
                        request.add("data", regData);
                        break;

                    case "2": // login
                        request.addProperty("command", "login");
                        System.out.print("Username: ");
                        String logUsername = scanner.nextLine();
                        System.out.print("Password: ");
                        String logPassword = scanner.nextLine();
                        JsonObject logData = new JsonObject();
                        logData.addProperty("username", logUsername);
                        logData.addProperty("password", logPassword);
                        request.add("data", logData);
                        break;

                    case "3": // updateCredentials
                        request.addProperty("command", "updateCredentials");
                        System.out.print("Username: ");
                        String updUsername = scanner.nextLine();
                        System.out.print("Password attuale: ");
                        String oldPassword = scanner.nextLine();
                        System.out.print("Nuova password: ");
                        String newPassword = scanner.nextLine();
                        JsonObject updData = new JsonObject();
                        updData.addProperty("username", updUsername);
                        updData.addProperty("old_password", oldPassword);
                        updData.addProperty("new-password", newPassword);
                        request.add("data", updData);
                        break;

                    case "4": // logout
                        request.addProperty("command", "logout");
                        System.out.print("Username: ");
                        String logoutUsername = scanner.nextLine();
                        JsonObject logoutData = new JsonObject();
                        logoutData.addProperty("username", logoutUsername);
                        request.add("data", logoutData);
                        break;

                    case "5": // insertLimitOrder
                        request.addProperty("command", "insertLimitOrder");
                        System.out.print("Tipo (bid/ask): ");
                        String limitType = scanner.nextLine();
                        System.out.print("Dimensione: ");
                        int limitSize = Integer.parseInt(scanner.nextLine());
                        System.out.print("Prezzo limite: ");
                        int limitPrice = Integer.parseInt(scanner.nextLine());
                        JsonObject limitData = new JsonObject();
                        limitData.addProperty("type", limitType);
                        limitData.addProperty("size", limitSize);
                        limitData.addProperty("price", limitPrice);
                        request.add("data", limitData);
                        break;

                    case "6": // insertMarketOrder
                        request.addProperty("command", "insertMarkerOrder");
                        System.out.print("Tipo (bid/ask): ");
                        String marketType = scanner.nextLine();
                        System.out.print("Dimensione: ");
                        int marketSize = Integer.parseInt(scanner.nextLine());
                        JsonObject marketData = new JsonObject();
                        marketData.addProperty("type", marketType);
                        marketData.addProperty("size", marketSize);
                        request.add("data", marketData);
                        break;

                    case "7": // insertStopOrder
                        request.addProperty("command", "insertStopOrder");
                        System.out.print("Tipo (bid/ask): ");
                        String stopType = scanner.nextLine();
                        System.out.print("Dimensione: ");
                        int stopSize = Integer.parseInt(scanner.nextLine());
                        System.out.print("Prezzo stop: ");
                        int stopPrice = Integer.parseInt(scanner.nextLine());
                        JsonObject stopData = new JsonObject();
                        stopData.addProperty("type", stopType);
                        stopData.addProperty("size", stopSize);
                        stopData.addProperty("price", stopPrice);
                        request.add("data", stopData);
                        break;

                    case "8": // cancelOrder
                        request.addProperty("command", "cancelOrder");
                        System.out.print("ID ordine: ");
                        int orderId = Integer.parseInt(scanner.nextLine());
                        JsonObject cancelData = new JsonObject();
                        cancelData.addProperty("orderId", orderId);
                        request.add("data", cancelData);
                        break;

                    case "9": // getPriceHistory
                        request.addProperty("command", "getPriceHistory");
                        System.out.print("Mese (formato MMYYYY): ");
                        String month = scanner.nextLine();
                        JsonObject historyData = new JsonObject();
                        historyData.addProperty("month", month.substring(0, 2));
                        request.add("data", historyData);
                        break;

                    default:
                        System.out.println("Comando non valido.");
                        continue;
                }

                // Invia la richiesta al server
                String jsonRequest = gson.toJson(request);
                System.out.println("Invio richiesta al server: " + jsonRequest);
                out.println(jsonRequest);
                out.flush(); // Forza l'invio immediato dei dati

                // Leggi la risposta dal server
                String response = in.readLine();
                if (response == null) {
                    System.out.println("Il server ha chiuso la connessione.");
                    isSocketClosed = true; // Imposta la variabile su true quando il socket è chiuso
                    break;
                } else {
                    System.out.println("Risposta ricevuta dal server: " + response);
                }
            }
            
            // Chiusura del thread UDP una volta terminato il servizio
            udpListenerThread.interrupt();
            udpListenerThread.join();

        } catch (IOException | InterruptedException e) {
            System.err.println("Errore durante la connessione al server: " + e.getMessage());
        }
    }
    /**
     * Metodo per monitorare lo stato di inattivita' del thread.
     */
    private static void startTimeoutThread(int timeoutMillis, PrintWriter out) {
        Thread timeoutThread = new Thread(() -> {
            while (!isSocketClosed) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastActivityTime > timeoutMillis) {
                    System.out.println("Timeout di inattività raggiunto. Effettuo il logout e chiudo il programma...");
                    
                    // Effettua il logout
                    JsonObject request = new JsonObject();
                    request.addProperty("command", "logout");
                    out.println(request.toString());
                    out.flush();

                    isSocketClosed = true;
                    System.exit(0);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    System.err.println("Thread di timeout interrotto.");
                    return;
                }
            }
        });
        timeoutThread.start();
    }


    /**
     * Metodo per ricevere notifiche via UDP in un thread separato.
     * 
     * @param udpPort Porta su cui ricevere le notifiche.
     */
    private static void listenForNotifications(int udpPort) {
        try (DatagramSocket udpSocket = new DatagramSocket(udpPort)) {
            byte[] buffer = new byte[1024];
            Gson gson = new GsonBuilder().create();

            System.out.println("In ascolto per notifiche UDP sulla porta " + udpPort);

            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                JsonObject notification = gson.fromJson(message, JsonObject.class);
                System.out.println("Notifica ricevuta: " + notification.toString());
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.err.println("Errore nella ricezione delle notifiche UDP: " + e.getMessage());
            }
        }
    }
}
