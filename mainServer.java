import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
/*
################################################################################

il mainServer gestisce:
	- una struttura CurrentHashMap dedicata agli user dove ci saranno memorizzati le loro info
	- una struttura dedicata agli ordini, condivisa tra i threads, in cui memorizzaranno gli ordini in arrivo dai client:
		- ordinata in base al tipo dell'ordine, MarketOrderBook.Order saranno i primi
		- StopOrderBook.Order saranno evasi appena si verifica la loro condizione

################################################################################
*/
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class mainServer {
	
    // Struttura dati thread-safe per gestire gli utenti e i relativi ordini
    public static ConcurrentHashMap<UserManager.User, List<OrderBook.Order >> userDatabase = new ConcurrentHashMap<>();
    
    // Oggetto per gestire gli ordini
    static OrderBook orderbook = new OrderBook();

    public static void main(String[] args) throws IOException {
        
		// Decodifica il percorso
		String configPath = URLDecoder.decode(
        mainServer.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
        StandardCharsets.UTF_8
		) + "/server_config.properties";


    	// Caricamento delle configurazioni dal file
    	Properties config = utilities.ConfigLoader.loadConfig(configPath);
    	int port = Integer.parseInt(config.getProperty("server.port"));
        int maxConnections = Integer.parseInt(config.getProperty("server.maxConnections"));
        int udpPort = Integer.parseInt(config.getProperty("server.udpPort"));
        // Creazione di un thread pool con un numero fisso di thread
        ExecutorService threadPool = Executors.newFixedThreadPool(maxConnections);
        
        // Setup dei dati persistenti
        UserManager.setUpUserHistory(userDatabase);
        OrderBook.setUpOrderHistory();

        // Creazione del server socket
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server in ascolto sulla porta " + port);

            while (true) {
                // Accetta una nuova connessione dal client
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connessione accettata da: " + clientSocket.getInetAddress());

                // Invia la connessione al thread pool per gestirla
                threadPool.execute(new ClientHandler(clientSocket, udpPort));
            }
        } catch (IOException e) {
            System.err.println("Errore nel server: " + e.getMessage());
        } finally {
            // Arresta il pool di thread
            threadPool.shutdown();
        }
    }

    /**
     * Classe interna che gestisce le richieste di un singolo client.
     */
    static class ClientHandler implements Runnable {

    	private final Socket clientSocket;
    	private final int udpPort;
    	private UserManager.User user = new UserManager.User("", "");
    	//private final int timeout;

        public ClientHandler(Socket clientSocket, int udpPort) {
            this.clientSocket = clientSocket;
            this.udpPort = udpPort;

        }
        @Override
        public void run() {
            try (
			    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			    PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
			) {
			    Gson gson = new GsonBuilder().create();
			    String inputLine;
			    UserManager.User cliente = null;
			    
			    while ((inputLine = in.readLine()) != null) {
			    	                    	
			    	System.out.println("Ricevuto dal client: " + inputLine);
			        JsonObject requestJson = gson.fromJson(inputLine, JsonObject.class);
			        String command = requestJson.get("command").getAsString();
			        JsonObject data = requestJson.has("data") ? requestJson.getAsJsonObject("data") : null;
			        if (data == null) { break; }
			        
			        OrderBook.Order new_order = null;
			        Response risp = null;
			        ResponseId risp2 = new ResponseId();
			        String msg;
                    
			        // Gestione dei comandi
			        switch (command) {
			            case "logout":
			            	risp = UserManager.logout(data.get("username").getAsString(), userDatabase);
			                cliente = UserManager.findUserByUsername(data.get("username").getAsString(), userDatabase);
			                if (cliente != null) { if (cliente.isLogin()) { cliente.logout(); cliente = null; } } 

			                out.println(gson.toJson(risp));
			                out.flush();
			            	break;
			            
			            case "login":
			                risp = UserManager.login(data.get("username").getAsString(), data.get("password").getAsString(), userDatabase, clientSocket, out);
			                cliente = UserManager.findUserByUsername(data.get("username").getAsString(), userDatabase);
			                if (cliente != null) { if (!cliente.isLogin()) { cliente.login(); } }
			                out.println(gson.toJson(risp));
			                out.flush();
			                break;
			            
			            case "register":
			                risp = UserManager.register(data.get("username").getAsString(),data.get("password").getAsString(), clientSocket.getInetAddress().getHostAddress(),userDatabase, null, true);
			                out.println(gson.toJson(risp));
			                out.flush();
			                break;
			            
			            case "updateCredentials":
			                risp = UserManager.updateCredentials(
			                    data.get("username").getAsString(),
			                    data.get("old_password").getAsString(),
			                    data.get("new-password").getAsString(), 
			                    userDatabase
			                	);
			                out.println(gson.toJson(risp));
			                break;
			            
			            case "insertMarkerOrder":

			            	if ( cliente != null && cliente.isLogin() ) {
			            		
			            		new_order = orderbook.insertMarketOrder(data.get("type").getAsString(), data.get("size").getAsInt());
			            		risp2.setOrderId(addOrder(new_order, this.user.getUsername()));
			            		msg = gson.toJson(risp2);
			            		out.println(msg);
			            		out.flush();
			            		if (new_order != null) {
			            			String notifyJson1 = new_order.toJson();
			            			sendNotify(notifyJson1, new_order.getUsersToNotifyByOrderId(), this.udpPort);
			            			orderbook.checkStopOrders();
			            		}
			            	} else {
			            		msg = "E' necessario il login!";
			            		out.println(msg);
			            		out.flush();
			            	}
			            	
			            	
			            	break;
			            	
			                
			            case "insertLimitOrder":

			            	if ( cliente != null && cliente.isLogin() ) {

			                    new_order = orderbook.insertLimitOrder(data.get("type").getAsString(), data.get("size").getAsInt(), data.get("price").getAsInt());
			                    risp2.setOrderId(addOrder(new_order, this.user.getUsername()));
			                    msg = gson.toJson(risp2);
			                    out.println(msg);
			                    out.flush();
			                    //ThreadSafeFileWriter(fileO, msg);
			                    if (new_order.getUsersToNotifyByOrderId() != null) {
			                        String notifyJson2 = new_order.toJson();
			                        
			                        sendNotify(notifyJson2, new_order.getUsersToNotifyByOrderId(), udpPort);
			                    }
			                    //OrderBook.
			            	} else {
			            		msg = "E' necessario il login!";
			            		out.println(msg);
			            		out.flush();
			            	}
			                break;

			            case "insertStopOrder":

			            	if ( cliente != null && cliente.isLogin() ) {
			            		
			            		new_order = orderbook.insertStopOrder(data.get("type").getAsString(), data.get("size").getAsInt(), data.get("price").getAsInt());
			            		risp2.setOrderId(addOrder(new_order, this.user.getUsername()));
			            		msg = gson.toJson(risp2);
			            		out.println(msg);
			            		out.flush();
			            		//ThreadSafeFileWriter(fileO, msg);
			            	} else {
			            		msg = "E' necessario il login!";
			            		out.println(msg);
			            		out.flush();
			            	}
			               	break;
			                                        
			            case "cancelOrder":

			            	if ( cliente != null && cliente.isLogin() ) {
			            		
			            		if (deleteOrder(data.get("orderId").getAsInt(), this.user.getUsername()) == 1) {
			            			orderbook.cancelOrder(data.get("orderId").getAsInt());
			            		}
			            	} else {
			            		msg = "E' necessario il login!";
			            		out.println(msg);
			            		out.flush();
			            	}
			            	
			                break;
			                
			            case "getPriceHistory":
			            	Map<Integer, OrderBook.DayPriceData> monthPriceHistory = OrderBook.getPriceHistory(data.get("month").getAsInt()); // Dati per Settembre

			            	//for (Map.Entry<Integer, OrderBook.DayPriceData> entry : monthPriceHistory.entrySet()) {
			            	//    System.out.println("Day: " + entry.getKey() + ", " + entry.getValue());
			            	//}
			               	//msg = gson.toJson(OrderBook.getOrderHistory(data.get("month").getAsInt()));
			            	out.println(monthPriceHistory);
			            	out.flush();

			            default:
			                break;
			        }
			                           
			    }
			    
			} catch (SocketTimeoutException e) {
			    System.err.println("Timeout di lettura: il client non ha inviato dati entro il tempo limite.");
			} catch (IOException e) {
			    System.err.println("Errore nel gestire il client: " + e.getMessage());
			} finally {
			    try {
			        clientSocket.close();
			        System.out.println("Connessione chiusa con il client.");
			    } catch (IOException e) {
			        System.err.println("Errore nel chiudere il socket del client: " + e.getMessage());
			    }
			}
        }
    }

    // METODO THREAD SAFE
    public synchronized static int addOrder(OrderBook.Order order, String username) {
    	// REQUIRES: assumo che username esiste e che abbia associato un value, oltre che order sia diverso da null
    	
    	if (order == null) {return -1;}
       	for (UserManager.User user : userDatabase.keySet()) {
            if (user.getUsername().equals(username)) {

            	//userDatabase.get(user).add(order);
            	// senza fare controlli sul fatto che la lista ci sia gia'
            	userDatabase.computeIfAbsent(user, key -> Collections.synchronizedList(new ArrayList<>())).add(order);
            }
        }

    	return order.getOrderId();
    }

    public synchronized static int deleteOrder(Integer orderId, String username) {

    	for (UserManager.User user: userDatabase.keySet()) {
    		if (user.getUsername().equals(username)) {
    			List<OrderBook.Order> orders = userDatabase.get(user);
    			synchronized(orders) {
    				Iterator<OrderBook.Order> iterator = orders.iterator();
                    while (iterator.hasNext()) {
                        OrderBook.Order order = iterator.next();
                        if (order.getOrderId() == orderId) {
                            iterator.remove();
                            return 1; // Ritorna 1 se l'ordine è stato trovato e rimosso
                        }}}}}
    	return 0;
    }

 // Metodo per inviare notifiche via UDP
    private static void sendNotify(String message, List<Integer> orderIdsToNotify, int udpPort) {
        Set<Integer> orderIdsSet = new HashSet<>(orderIdsToNotify); // Usa un Set per ricerche veloci

        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] buffer = message.getBytes();

            for (UserManager.User user : userDatabase.keySet()) {
                List<OrderBook.Order> orders = userDatabase.get(user);

                for (OrderBook.Order order : orders) {
                    if (orderIdsSet.contains(order.getOrderId())) { // Controlla se l'ordine è nella lista di notifica
                        try {
                            if (user.isLogin()) {
                                sendUdpNotification(socket, user, buffer, udpPort);
                                System.out.println("Notifica inviata a: " + user.getUsername() + " per l'ordine: " + order.getOrderId());
                            } else {
                                UserManager.writeNotify(user.getUsername(), order);
                                System.out.println("Notifica scritta per: " + user.getUsername() + " per l'ordine: " + order.getOrderId());
                            }
                        } catch (IOException e) {
                            System.err.println("Errore nell'inviare la notifica a: " + user.getUsername() + " per l'ordine: " + order.getOrderId());
                            e.printStackTrace();
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Errore generale nell'inviare notifiche UDP: " + e.getMessage());
            e.printStackTrace();
        }
    }


    // Metodo helper per inviare una notifica UDP
    private static void sendUdpNotification(DatagramSocket socket, UserManager.User user, byte[] buffer, int udpPort) throws IOException {
        InetAddress address = InetAddress.getByName(user.getIpAddress());
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, udpPort);
        socket.send(packet);
    }
    
    // Strutture ausiliari per rispondere in Json
    public static class Response {
        @SuppressWarnings("unused")
		private int response;
        @SuppressWarnings("unused")
		private String errorMessage;
        
        public void setResponseErrormsg(int response, String errorMessage) {
            this.response = response;
            this.errorMessage = errorMessage;
        }
   }
    
   static class ResponseId {
	   @SuppressWarnings("unused")
	private int orderId;
	   public void setOrderId(int oi) {
		   this.orderId = oi;
	   }
   }
   
   
}


